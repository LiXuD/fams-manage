package com.fams.requisition;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.masterdata.RequisitionMasterDataPort;
import com.fams.proxy.ProxyViews;
import com.fams.targetchange.TargetChangeViews;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Account, request, demand/target, asset and business lock are held in one short transaction. */
@Service
@Profile("fams-product")
class RequisitionHandoverService {
    private static final Logger LOG = LoggerFactory.getLogger(RequisitionHandoverService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final RequisitionMasterDataPort references;
    private final AssetHandoverPort assets;
    private final RequisitionService requisitions;
    private final com.fams.loan.LoanReceiptPort loans;
    private final SecureRandom random = new SecureRandom();

    RequisitionHandoverService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            RequisitionMasterDataPort references,
            AssetHandoverPort assets,
            RequisitionService requisitions,
            com.fams.loan.LoanReceiptPort loans) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.references = references;
        this.assets = assets;
        this.requisitions = requisitions;
        this.loans = loans;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RequisitionViews.Page<RequisitionViews.PendingReceiptItem> pending(
            Actor actor, int page, int size, Long allocationId) {
        LOG.debug("Listing pending receipt items actor={} page={} size={} allocationId={}",
                actor.id(), page, size, allocationId);
        Current current = actors.revalidateLocked(actor);
        String from =
                " FROM fams_requisition_allocation a JOIN fams_requisition_demand d ON"
                        + " d.id=a.demand_id JOIN fams_requisition r ON r.id=a.request_id JOIN"
                        + " fams_requisition_handover h ON h.allocation_id=a.id WHERE"
                        + " COALESCE(a.receipt_target_employee_id,d.target_employee_id,d.target_department_employee_id)=? AND"
                        + " a.allocation_status='DELIVERED_WAITING_RECEIPT' AND"
                        + " r.request_status='WAITING_HANDOVER' AND (? IS NULL OR a.id=?)";
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*)" + from, Long.class, current.employeeId(), allocationId, allocationId));
        var rows =
                db.queryForList(
                        "SELECT a.id,a.request_id,r.request_no,a.asset_snapshot_json,a.version,"
                                + "h.delivered_at,h.receipt_deadline_at,h.destination_text"
                                + from
                                + " ORDER BY a.id LIMIT ? OFFSET ?",
                        current.employeeId(),
                        allocationId,
                        allocationId,
                        size,
                        ((long) page - 1) * size);
        return new RequisitionViews.Page<>(
                rows.stream().map(row -> RequisitionViews.pendingReceiptItem(json, row)).toList(),
                total,
                page,
                size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RequisitionViews.Grant grant(Actor actor, long request, long allocation) {
        Current current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        var demand = demand(item);
        receiver(current, demand);
        approved(header);
        if (!"DELIVERED_WAITING_RECEIPT".equals(item.get("allocation_status"))) {
            throw RequisitionFailure.conflict("HANDOVER_NOT_DELIVERED");
        }
        targets(demand);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update(
                "UPDATE fams_requisition_receipt_grant SET consumed_at=? WHERE allocation_id=?"
                        + " AND receiver_account_id=? AND consumed_at IS NULL",
                now(),
                allocation,
                current.id());
        db.update(
                "INSERT INTO fams_requisition_receipt_grant"
                    + " (token_hash,allocation_id,receiver_account_id,receiver_employee_id,auth_epoch,authorization_version,request_version,allocation_version,issued_at,expires_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                hash(token),
                allocation,
                current.id(),
                current.employeeId(),
                actor.authEpoch(),
                actor.authorizationVersion(),
                n(header, "request_version"),
                n(item, "version"),
                now(),
                Timestamp.from(clock.instant().plusSeconds(300)));
        LOG.info("Issued receipt grant actor={} request={} allocation={}", current.id(), request, allocation);
        return new RequisitionViews.Grant(
                token,
                clock.instant().plusSeconds(300).toString(),
                n(header, "request_version"),
                n(item, "version"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RequisitionViews.Detail deliver(
            Actor actor,
            long request,
            long allocation,
            long version,
            String key,
            HandoverInput.Delivery input) {
        Current current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        if (!current.has(Role.WAREHOUSE_OPERATOR)) {
            throw RequisitionFailure.forbidden();
        }
        var replay =
                replay(
                        actor,
                        current,
                        "deliver",
                        key,
                        request,
                        allocation,
                        version,
                        input,
                        false,
                        RequisitionViews.Detail.class);
        if (replay != null) {
            return replay;
        }
        warehouse(current, header);
        approved(header);
        version(header, version);
        allocationVersion(item, input.allocationVersion());
        if (!"WAITING_DELIVERY".equals(item.get("allocation_status"))) {
            throw RequisitionFailure.conflict("HANDOVER_ALREADY_DELIVERED");
        }
        var demand = demand(item);
        targets(demand);
        var stock = assets.stock(n(item, "asset_id"), input.assetVersion());
        if (input.assetVersion() != n(item, "asset_version_snapshot")) {
            throw RequisitionFailure.conflict("HANDOVER_ASSET_CHANGED");
        }
        locked(item, input.lockVersion());
        String observed = assets.observedCode(n(item, "asset_id"), input.observedAssetCode());
        var parameters = json.readTree(header.get("parameter_snapshot_json").toString());
        var zone = ZoneId.of(parameters.get("timezone").asText());
        long days = parameters.get("handoverAcceptanceDeadlineNaturalDays").asLong();
        var deliveredAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var deadline = deliveredAt.atZone(zone).plusDays(days).toInstant();
        db.update(
                "INSERT INTO fams_requisition_handover"
                    + " (allocation_id,delivered_by_account_id,delivered_at,receipt_deadline_at,destination_text,"
                    + "temporary_employee_id,delivery_evidence,target_snapshot_json,formal_custody_snapshot_json)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                allocation,
                current.id(),
                Timestamp.from(deliveredAt),
                Timestamp.from(deadline),
                input.destination(),
                target(demand),
                input.evidence(),
                demand.get("target_snapshot_json"),
                json.writeValueAsString(stock));
        db.update(
                "UPDATE fams_requisition_allocation SET"
                    + " allocation_status='DELIVERED_WAITING_RECEIPT',delivered_at=?,version=version+1"
                    + " WHERE id=?",
                Timestamp.from(deliveredAt),
                allocation);
        event(
                actor,
                current,
                "DELIVERED",
                item,
                observed,
                input.evidence(),
                Role.WAREHOUSE_OPERATOR);
        todo(allocation, "RECEIPT", "RECEIVER", receiverAccount(target(demand)));
        progress(request);
        LOG.info("Delivered requisition allocation actor={} request={} allocation={}", current.id(), request, allocation);
        return finish(
                current,
                "deliver",
                key,
                request,
                allocation,
                version,
                input,
                requisitions.getDetail(actor, request));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RequisitionViews.Detail warehouseAction(
            Actor actor,
            long request,
            long allocation,
            long version,
            String key,
            String action,
            HandoverInput.WarehouseAction input) {
        Current current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        if (!current.has(Role.WAREHOUSE_OPERATOR)) {
            throw RequisitionFailure.forbidden();
        }
        var replay =
                replay(
                        actor,
                        current,
                        action,
                        key,
                        request,
                        allocation,
                        version,
                        input,
                        false,
                        RequisitionViews.Detail.class);
        if (replay != null) {
            return replay;
        }
        warehouse(current, header);
        approved(header);
        version(header, version);
        allocationVersion(item, input.allocationVersion());
        assets.stock(n(item, "asset_id"), input.assetVersion());
        locked(item, input.lockVersion());
        if (input.assetVersion() != n(item, "asset_version_snapshot")) {
            throw RequisitionFailure.conflict("HANDOVER_ASSET_CHANGED");
        }
        String state = item.get("allocation_status").toString();
        String observed = null;
        boolean cancel = "cancel".equals(action), recover = "recover".equals(action);
        if (cancel && !"WAITING_DELIVERY".equals(state)
                || "recovery".equals(action) && !"DELIVERED_WAITING_RECEIPT".equals(state)
                || recover && !"RECOVERY_PENDING_CANCEL".equals(state)) {
            throw RequisitionFailure.conflict("HANDOVER_CANCEL_STATE_INVALID");
        }
        if (recover) {
            if (!input.physicalReturned()
                    || input.inspectionEvidence() == null
                    || input.inspectionEvidence().isBlank()
                    || input.observedAssetCode() == null) {
                throw RequisitionFailure.invalid("HANDOVER_RECOVERY_EVIDENCE_REQUIRED");
            }
            observed = assets.observedCode(n(item, "asset_id"), input.observedAssetCode());
        }
        db.update(
                "UPDATE fams_requisition_allocation SET allocation_status=?,version=version+1,"
                        + "cancelled_at=?,cancellation_reason=? WHERE id=?",
                cancel || recover ? "CANCELLED" : "RECOVERY_PENDING_CANCEL",
                cancel || recover ? now() : null,
                input.reason(),
                allocation);
        if (cancel || recover) {
            demand(item);
            db.update(
                    "UPDATE fams_requisition_demand SET"
                        + " pending_handover_quantity=pending_handover_quantity-1,cancelled_quantity=cancelled_quantity+1,version=version+1"
                        + " WHERE id=?",
                    n(item, "demand_id"));
            release(item);
            close(allocation, "RECEIPT");
            close(allocation, "HANDOVER_EXCEPTION");
        }
        invalidate(allocation);
        event(
                actor,
                current,
                cancel ? "CANCELLED" : recover ? "RECOVERED" : "RECOVERY_REQUESTED",
                item,
                observed,
                recover ? input.inspectionEvidence() : input.reason(),
                Role.WAREHOUSE_OPERATOR);
        progress(request);
        LOG.info("Applied warehouse handover action actor={} request={} allocation={} action={}",
                current.id(), request, allocation, action);
        return finish(
                current,
                action,
                key,
                request,
                allocation,
                version,
                input,
                requisitions.getDetail(actor, request));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RequisitionViews.ReceiptResult receipt(
            Actor actor,
            long request,
            long allocation,
            long version,
            String key,
            HandoverInput.Receipt input,
            boolean refuse) {
        Current current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        var demand = demand(item);
        receiver(current, demand);
        String action = refuse ? "refuse" : "receive";
        var replay =
                replay(
                        actor,
                        current,
                        action,
                        key,
                        request,
                        allocation,
                        version,
                        input,
                        true,
                        RequisitionViews.ReceiptResult.class);
        if (replay != null) {
            return replay;
        }
        approved(header);
        version(header, version);
        targets(demand);
        if (!input.physicalChecked()
                || !"DELIVERED_WAITING_RECEIPT".equals(item.get("allocation_status"))) {
            throw RequisitionFailure.conflict("HANDOVER_NOT_DELIVERED");
        }
        var grant =
                one(
                        "SELECT * FROM fams_requisition_receipt_grant WHERE token_hash=? FOR"
                                + " UPDATE",
                        hash(input.grant()));
        if (grant == null
                || grant.get("consumed_at") != null
                || n(grant, "allocation_id") != allocation
                || n(grant, "receiver_account_id") != current.id()
                || n(grant, "receiver_employee_id") != current.employeeId()
                || n(grant, "auth_epoch") != actor.authEpoch()
                || n(grant, "authorization_version") != actor.authorizationVersion()
                || n(grant, "request_version") != n(header, "request_version")
                || n(grant, "allocation_version") != n(item, "version")
                || !Objects.requireNonNull(
                                db.queryForObject(
                                        "SELECT expires_at FROM fams_requisition_receipt_grant"
                                                + " WHERE token_hash=?",
                                        Timestamp.class,
                                        hash(input.grant())))
                        .toInstant()
                        .isAfter(clock.instant())) {
            throw RequisitionFailure.conflict("HANDOVER_GRANT_INVALID");
        }
        assets.stock(n(item, "asset_id"), n(item, "asset_version_snapshot"));
        locked(item, null);
        String observed =
                refuse ? null : assets.observedCode(n(item, "asset_id"), input.observedAssetCode());
        if (refuse && (input.reason() == null || input.reason().isBlank())) {
            throw RequisitionFailure.invalid("HANDOVER_REFUSAL_REASON_REQUIRED");
        }
        if (!refuse) {
            completeReceipt(current, header, item, demand, request, allocation);
        } else {
            db.update(
                    "UPDATE fams_requisition_allocation SET version=version+1 WHERE id=?",
                    allocation);
            todo(allocation, "HANDOVER_EXCEPTION", "WAREHOUSE", processor(request));
        }
        invalidate(allocation);
        event(
                actor,
                current,
                refuse ? "REFUSED" : "RECEIVED",
                item,
                observed,
                refuse ? input.reason() : "接收人核对实物与资产编码，确认收到",
                Role.EMPLOYEE);
        progress(request);
        LOG.info("Handover receipt decision actor={} request={} allocation={} refused={}",
                current.id(), request, allocation, refuse);
        return finish(
                current,
                action,
                key,
                request,
                allocation,
                version,
                input,
                new RequisitionViews.ReceiptResult(
                        Long.toString(allocation),
                        Long.toString(request),
                        refuse ? "REFUSED" : "RECEIVED",
                        n(header, "version") + 1));
    }

    private void completeReceipt(Current current, Map<String, Object> header,
            Map<String, Object> item, Map<String, Object> demand, long request, long allocation) {
        if ("BORROW".equals(header.get("request_type"))) {
            var parameters = header.get("parameter_snapshot_json").toString();
            var zone = ZoneId.of(json.readTree(parameters).get("timezone").asText());
            var businessDate = clock.instant().atZone(zone).toLocalDate();
            var dueDate = java.time.LocalDate.parse(header.get("expected_return_date").toString());
            if (!dueDate.isAfter(businessDate)) {
                throw RequisitionFailure.conflict("LOAN_RETURN_DATE_EXPIRED");
            }
            assets.borrow(n(item, "asset_id"), n(item, "asset_version_snapshot"),
                    n(demand, "target_organization_id"), target(demand),
                    n(demand, "target_location_id"), current.id(), request, clock.instant());
            loans.open(n(item, "asset_id"), target(demand), n(demand, "target_organization_id"),
                    request, allocation, businessDate, dueDate, parameters, clock.instant());
        } else {
            assets.receive(
                n(item, "asset_id"),
                n(item, "asset_version_snapshot"),
                demand.get("custody_type").toString(),
                n(demand, "target_organization_id"),
                target(demand),
                n(demand, "target_location_id"),
                current.id(),
                request,
                clock.instant());
        }
        db.update(
                "UPDATE fams_requisition_demand SET"
                    + " pending_handover_quantity=pending_handover_quantity-1,completed_quantity=completed_quantity+1,version=version+1"
                    + " WHERE id=?",
                n(item, "demand_id"));
        db.update(
                "UPDATE fams_requisition_allocation SET"
                        + " allocation_status='RECEIVED',received_at=?,version=version+1 WHERE"
                        + " id=?",
                now(),
                allocation);
        release(item);
        close(allocation, "RECEIPT");
        close(allocation, "HANDOVER_EXCEPTION");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProxyViews.RequisitionSnapshot proxySnapshot(Actor actor, long request, long allocation, boolean executing) {
        LOG.debug("Building proxy receipt snapshot actor={} request={} allocation={} executing={}",
                actor.id(), request, allocation, executing);
        var current = actors.revalidateLocked(actor);
        var header = header(request);
        if (executing) { warehouse(current, header); }
        else if (!current.has(Role.ASSET_ADMIN)) { throw RequisitionFailure.forbidden(); }
        approved(header);
        var item = item(request, allocation);
        if (!"DELIVERED_WAITING_RECEIPT".equals(item.get("allocation_status"))) {
            throw RequisitionFailure.conflict("HANDOVER_NOT_DELIVERED");
        }
        if (!db.queryForList("SELECT id FROM fams_todo WHERE business_type='REQUISITION_ALLOCATION'"
                + " AND business_id=? AND todo_type IN ('HANDOVER_EXCEPTION','RECOVERY') AND closed_at IS NULL FOR UPDATE",
                allocation).isEmpty()) { throw RequisitionFailure.conflict("PROXY_RECEIPT_DISPUTED"); }
        var demand = demand(item);
        targets(demand);
        var asset = assets.stock(n(item, "asset_id"), n(item, "asset_version_snapshot"));
        locked(item, null);
        var lock = one("SELECT lock_version FROM fams_asset_business_lock WHERE id=? FOR UPDATE",
                n(item, "business_lock_id"));
        var owner = one("SELECT processor_account_id,ownership_version FROM fams_requisition_ownership"
                + " WHERE request_id=? AND archived_at IS NULL FOR UPDATE", request);
        return new ProxyViews.RequisitionSnapshot(
                "REQUISITION",
                Long.toString(request),
                Long.toString(allocation),
                (String) header.get("request_no"),
                n(header, "version"),
                n(header, "request_version"),
                n(item, "version"),
                item.get("asset_id").toString(),
                n(item, "asset_version_snapshot"),
                asset.assetCode(),
                Long.toString(target(demand)),
                (String) demand.get("custody_type"),
                n(lock, "lock_version"),
                owner.get("processor_account_id").toString(),
                n(owner, "ownership_version"),
                n(demand, "version"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProxyViews.RequisitionReceiptResult proxyReceipt(Actor actor, long request, long allocation,
            ProxyViews.ProxySnapshot expected, String observedCode, long confirmationId) {
        var snapshot = proxySnapshot(actor, request, allocation, true);
        // Text round-trip keeps the pre-JAVA-04 comparison semantics verbatim (integral width
        // normalization between the stored/HTTP expected snapshot and the typed live snapshot).
        if (!json.readTree(json.writeValueAsString(snapshot)).equals(json.readTree(json.writeValueAsString(expected)))) {
            throw RequisitionFailure.conflict("PROXY_RECEIPT_SCOPE_CHANGED");
        }
        var current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        var demand = demand(item);
        String observed = assets.observedCode(n(item, "asset_id"), observedCode);
        completeReceipt(current, header, item, demand, request, allocation);
        invalidate(allocation);
        event(actor, current, "PROXY_RECEIVED", item, observed,
                "异常代确认许可 " + confirmationId, Role.WAREHOUSE_OPERATOR);
        progress(request);
        LOG.info("Proxy receipt executed actor={} request={} allocation={} confirmationId={}",
                current.id(), request, allocation, confirmationId);
        return new ProxyViews.RequisitionReceiptResult(
                Long.toString(confirmationId), Long.toString(request), Long.toString(allocation),
                "RECEIVED", Long.toString(target(demand)), observed);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RequisitionViews.ReceiptDetail receiptDetail(Actor actor, long request, long allocation) {
        LOG.debug("Reading handover receipt detail actor={} request={} allocation={}", actor.id(), request, allocation);
        Current current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        var demand = demand(item);
        receiver(current, demand);
        var handover =
                one(
                        "SELECT delivered_at,receipt_deadline_at,destination_text"
                                + " FROM fams_requisition_handover WHERE allocation_id=?",
                        allocation);
        if (handover == null
                || !"DELIVERED_WAITING_RECEIPT".equals(item.get("allocation_status"))) {
            throw RequisitionFailure.unavailable();
        }
        return new RequisitionViews.ReceiptDetail(
                Long.toString(request),
                (String) header.get("request_no"),
                (String) header.get("request_type"),
                header.get("expected_return_date") == null
                        ? null
                        : header.get("expected_return_date").toString(),
                n(header, "version"),
                Long.toString(allocation),
                (String) item.get("allocation_status"),
                n(item, "version"),
                json.readTree(item.get("asset_snapshot_json").toString()),
                RequisitionViews.receiptHandover(handover));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TargetChangeViews.RequisitionContext targetChangeContext(Actor actor, long request, long allocation) {
        LOG.debug("Reading target-change context actor={} request={} allocation={}", actor.id(), request, allocation);
        var current = actors.revalidateLocked(actor);
        var header = header(request);
        if (!current.has(Role.ASSET_ADMIN)) { warehouse(current, header); }
        if (!List.of("WAITING_HANDOVER", "WAREHOUSE_PROCESSING").contains(header.get("request_status"))
                || one("SELECT id FROM fams_requisition_decision WHERE request_id=? AND request_version=?"
                        + " AND decision_result='APPROVED'", request, n(header, "request_version")) == null) {
            throw RequisitionFailure.conflict("REQUISITION_NOT_APPROVED");
        }
        var item = item(request, allocation);
        if (!List.of("WAITING_DELIVERY", "DELIVERED_WAITING_RECEIPT").contains(item.get("allocation_status"))
                || !db.queryForList("SELECT id FROM fams_todo WHERE business_type='REQUISITION_ALLOCATION'"
                        + " AND business_id=? AND todo_type='RECOVERY' AND closed_at IS NULL FOR UPDATE", allocation).isEmpty()) {
            throw RequisitionFailure.conflict("TARGET_CHANGE_PHYSICAL_STAGE_INVALID");
        }
        var asset = assets.stock(n(item, "asset_id"), n(item, "asset_version_snapshot"));
        locked(item, null);
        var owner = one("SELECT processor_account_id,ownership_version FROM fams_requisition_ownership"
                + " WHERE request_id=? AND archived_at IS NULL FOR UPDATE", request);
        if (owner == null) { throw RequisitionFailure.conflict("REQUISITION_ALREADY_FINISHED"); }
        var demand = demand(item);
        var target = new TargetChangeViews.RequisitionTarget(
                (String) demand.get("custody_type"),
                demand.get("target_organization_id").toString(),
                n(demand, "organization_version_snapshot"),
                Long.toString(target(demand)),
                n(demand, "employee_version_snapshot"),
                demand.get("target_location_id").toString(),
                n(demand, "location_version_snapshot"));
        return new TargetChangeViews.RequisitionContext(
                "REQUISITION",
                Long.toString(request),
                Long.toString(allocation),
                n(header, "request_version"),
                n(header, "version"),
                n(item, "version"),
                (String) item.get("allocation_status"),
                n(demand, "version"),
                item.get("asset_id").toString(),
                n(item, "asset_version_snapshot"),
                asset.assetCode(),
                target,
                n(owner, "ownership_version"),
                owner.get("processor_account_id").toString(),
                n(Objects.requireNonNull(one("SELECT lock_version FROM fams_asset_business_lock WHERE id=? FOR UPDATE",
                        n(item, "business_lock_id"))), "lock_version"),
                db.queryForList("SELECT delivered_at,receipt_deadline_at,destination_text"
                        + " FROM fams_requisition_handover WHERE allocation_id=?", allocation).stream()
                        .map(row -> TargetChangeViews.handover(json, row)).toList());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void validateChangedTarget(long request, long allocation, ItemTargetChangeInput.Target target) {
        LOG.debug("Validating changed target request={} allocation={} custodyType={}",
                request, allocation, target.custodyType());
        var header = header(request);
        if (!List.of("PERSONAL", "SHARED").contains(target.custodyType())
                || "BORROW".equals(header.get("request_type")) && !"PERSONAL".equals(target.custodyType())) {
            throw RequisitionFailure.invalid("TARGET_CHANGE_CUSTODY_INVALID");
        }
        var next = new LinkedHashMap<>(demand(item(request, allocation)));
        next.put("custody_type", target.custodyType());
        next.put("target_organization_id", Long.parseLong(target.organizationId()));
        next.put("organization_version_snapshot", target.organizationVersion());
        next.put("target_employee_id", "PERSONAL".equals(target.custodyType()) ? Long.parseLong(target.employeeId()) : null);
        next.put("target_department_employee_id", "SHARED".equals(target.custodyType()) ? Long.parseLong(target.employeeId()) : null);
        next.put("employee_version_snapshot", target.employeeVersion());
        next.put("target_location_id", Long.parseLong(target.locationId()));
        next.put("location_version_snapshot", target.locationVersion());
        targets(next);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void applyChangedTarget(Actor actor, long request, long allocation, ItemTargetChangeInput.Target target,
            String observedCode, long proposal) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) { throw RequisitionFailure.forbidden(); }
        targetChangeContext(actor, request, allocation);
        validateChangedTarget(request, allocation, target);
        var item = item(request, allocation);
        String observed = assets.observedCode(n(item, "asset_id"), observedCode);
        var approved = json.readValue(json.writeValueAsString(target),
                new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        approved.put("referenceSnapshot", targetReferenceSnapshot(target));
        db.update("UPDATE fams_requisition_allocation SET approved_target_json=?,receipt_target_employee_id=?,"
                + "version=version+1 WHERE id=?", json.writeValueAsString(approved), Long.parseLong(target.employeeId()), allocation);
        invalidate(allocation);
        if ("DELIVERED_WAITING_RECEIPT".equals(item.get("allocation_status"))) {
            close(allocation, "RECEIPT");
            close(allocation, "HANDOVER_EXCEPTION");
            todo(allocation, "RECEIPT", "RECEIVER", receiverAccount(Long.parseLong(target.employeeId())));
        }
        db.update("UPDATE fams_requisition SET version=version+1,updated_at=? WHERE id=?", now(), request);
        event(actor, current, "TARGET_CHANGED", item, observed, "批准目标变更 " + proposal, Role.ASSET_ADMIN);
        LOG.info("Applied target change actor={} request={} allocation={} proposal={}",
                current.id(), request, allocation, proposal);
    }

    private Map<String, Object> targetReferenceSnapshot(ItemTargetChangeInput.Target target) {
        var result = new LinkedHashMap<String, Object>();
        for (String[] spec : new String[][] {{"organization", target.organizationId()},
                {"employee", target.employeeId()}, {"location", target.locationId()}}) {
            // validateChangedTarget -> targets() has already guaranteed the row exists.
            result.put(spec[0], ReferenceSnapshot.of(Objects.requireNonNull(
                    references.current(spec[0], Long.parseLong(spec[1]), true),
                    "TARGET_REFERENCE_SNAPSHOT_MISSING")));
        }
        return result;
    }

    /**
     * Reference row persisted verbatim into approved_target_json.referenceSnapshot. The legacy
     * map copy stringified id and *_id values and kept the snake_case keys and declaration order;
     * this record reproduces that stored shape (JAVA-09, frozen by SmallPortsWireShapeTest).
     */
    record ReferenceSnapshot(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("version") long version,
            @JsonProperty("organization_id")
                    @JsonInclude(JsonInclude.Include.NON_NULL)
                    String organizationId) {

        static ReferenceSnapshot of(RequisitionMasterDataPort.MasterReference row) {
            return new ReferenceSnapshot(
                    Long.toString(row.id()),
                    row.name(),
                    row.status(),
                    row.version(),
                    row.organizationId() == null ? null : Long.toString(row.organizationId()));
        }
    }

    private Map<String, Object> legacyTargetSnapshot(tools.jackson.databind.JsonNode target) {
        // Old opt-in overrides have exact frozen IDs/versions but no frozen display names.
        return Map.of("organization", Map.of("id", target.get("organizationId").asText(),
                "version", target.get("organizationVersion").asLong()),
                "employee", Map.of("id", target.get("employeeId").asText(),
                "version", target.get("employeeVersion").asLong()),
                "location", Map.of("id", target.get("locationId").asText(),
                "version", target.get("locationVersion").asLong()));
    }

    private void targets(Map<String, Object> demand) {
        sharedReceiver(demand);
        for (String[] spec :
                new String[][] {
                    {"category", "category_id", "category_version_snapshot"},
                    {"organization", "target_organization_id", "organization_version_snapshot"},
                    {
                        "employee",
                        demand.get("target_employee_id") != null
                                ? "target_employee_id"
                                : "target_department_employee_id",
                        "employee_version_snapshot"
                    },
                    {"location", "target_location_id", "location_version_snapshot"}
                }) {
            var current = references.current(spec[0], n(demand, spec[1]), true);
            if (current == null
                    || !"ACTIVE".equals(current.status())
                    || current.version() != n(demand, spec[2])
                    || "employee".equals(spec[0])
                            && current.organizationId()
                                    != n(demand, "target_organization_id")) {
                throw RequisitionFailure.conflict("HANDOVER_TARGET_CHANGED");
            }
        }
    }

    private void sharedReceiver(Map<String, Object> demand) {
        if ("SHARED".equals(demand.get("custody_type"))
                && db.queryForList("SELECT a.id FROM fams_system_account a JOIN fams_account_role r ON r.account_id=a.id"
                        + " WHERE a.employee_id=? AND a.status='ENABLED' AND r.role_code='DEPARTMENT_ASSET_MANAGER' FOR UPDATE",
                        target(demand)).isEmpty()) {
            throw RequisitionFailure.conflict("HANDOVER_SHARED_RECEIVER_INVALID");
        }
    }

    private void warehouse(Current current, Map<String, Object> header) {
        if (!current.has(Role.WAREHOUSE_OPERATOR)) {
            throw RequisitionFailure.forbidden();
        }
        if (processor(n(header, "id")) != current.id()) {
            throw RequisitionFailure.conflict("REQUISITION_OWNED_BY_ANOTHER");
        }
    }

    private long processor(long request) {
        var row =
                one(
                        "SELECT processor_account_id FROM fams_requisition_ownership WHERE"
                                + " request_id=? AND archived_at IS NULL FOR UPDATE",
                        request);
        if (row == null) {
            throw RequisitionFailure.conflict("REQUISITION_ALREADY_FINISHED");
        }
        return n(row, "processor_account_id");
    }

    private void receiver(Current current, Map<String, Object> demand) {
        if (current.employeeId() != target(demand)) {
            throw RequisitionFailure.unavailable();
        }
    }

    private long target(Map<String, Object> demand) {
        return n(
                demand,
                demand.get("target_employee_id") != null
                        ? "target_employee_id"
                        : "target_department_employee_id");
    }

    private void approved(Map<String, Object> header) {
        if (!"WAITING_HANDOVER".equals(header.get("request_status"))
                || one(
                                "SELECT id FROM fams_requisition_decision WHERE request_id=? AND"
                                        + " request_version=? AND decision_result='APPROVED'",
                                n(header, "id"),
                                n(header, "request_version"))
                        == null) {
            throw RequisitionFailure.conflict("REQUISITION_NOT_APPROVED");
        }
    }

    private void version(Map<String, Object> header, long version) {
        if (n(header, "version") != version) {
            throw RequisitionFailure.conflict("REQUISITION_VERSION_CONFLICT");
        }
    }

    private void allocationVersion(Map<String, Object> item, long version) {
        if (n(item, "version") != version) {
            throw RequisitionFailure.conflict("HANDOVER_ALLOCATION_CHANGED");
        }
    }

    private void locked(Map<String, Object> item, Long expected) {
        var lock =
                one(
                        "SELECT * FROM fams_asset_business_lock WHERE id=? FOR UPDATE",
                        n(item, "business_lock_id"));
        if (lock == null
                || lock.get("released_at") != null
                || n(lock, "asset_id") != n(item, "asset_id")
                || n(lock, "business_id") != n(item, "request_id")
                || n(lock, "business_item_id") != n(item, "demand_id")
                || !"REQUISITION".equals(lock.get("business_type"))
                || expected != null && n(lock, "lock_version") != expected) {
            throw RequisitionFailure.conflict("HANDOVER_LOCK_CHANGED");
        }
    }

    private void release(Map<String, Object> item) {
        db.update(
                "UPDATE fams_asset_business_lock SET"
                    + " released_at=?,release_reason='HANDOVER_FINISHED',lock_version=lock_version+1"
                    + " WHERE id=? AND released_at IS NULL",
                now(),
                n(item, "business_lock_id"));
    }

    private void invalidate(long allocation) {
        db.update(
                "UPDATE fams_requisition_receipt_grant SET consumed_at=? WHERE allocation_id=? AND"
                        + " consumed_at IS NULL",
                now(),
                allocation);
    }

    private void progress(long request) {
        var totals =
                Objects.requireNonNull(
                        one(
                                "SELECT SUM(requested_quantity)"
                                        + " requested,SUM(pending_allocation_quantity)"
                                        + " pending,SUM(pending_handover_quantity)"
                                        + " handover,SUM(completed_quantity)"
                                        + " completed,SUM(cancelled_quantity) cancelled FROM"
                                        + " fams_requisition_demand WHERE request_id=?",
                                request));
        String status =
                n(totals, "handover") > 0
                        ? "WAITING_HANDOVER"
                        : n(totals, "pending") > 0
                                ? "WAREHOUSE_PROCESSING"
                                : n(totals, "completed") == n(totals, "requested")
                                        ? "COMPLETED"
                                        : n(totals, "completed") > 0
                                                ? "PARTIALLY_COMPLETED"
                                                : n(totals, "cancelled") == n(totals, "requested")
                                                        ? "CANCELLED"
                                                        : "UNFULFILLABLE";
        boolean terminal = !List.of("WAITING_HANDOVER", "WAREHOUSE_PROCESSING").contains(status);
        db.update(
                "UPDATE fams_requisition SET"
                    + " request_status=?,version=version+1,updated_at=?,completed_at=? WHERE id=?",
                status,
                now(),
                terminal ? now() : null,
                request);
        if (terminal) {
            db.update(
                    "UPDATE fams_requisition_ownership SET"
                        + " archived_at=?,archive_reason='EXECUTION_FINISHED' WHERE request_id=?"
                        + " AND archived_at IS NULL",
                    now(),
                    request);
            db.update(
                    "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='REQUISITION' AND business_id=? AND closed_at IS NULL",
                    now(),
                    request);
        }
    }

    private void event(
            Actor actor,
            Current current,
            String type,
            Map<String, Object> item,
            String code,
            String evidence,
            Role role) {
        if (evidence.length() > 1000) {
            throw RequisitionFailure.invalid("HANDOVER_EVIDENCE_TOO_LONG");
        }
        db.update(
                "INSERT INTO fams_audit_event"
                    + " (request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'REQUISITION_ALLOCATION',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role.name(),
                "HANDOVER_" + type,
                n(item, "id"),
                n(item, "version"),
                n(item, "version") + 1,
                now(),
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                Long.toString(n(item, "request_id")),
                                "evidence",
                                evidence)));
        long audit =
                Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO fams_requisition_handover_event"
                    + " (allocation_id,event_type,actor_account_id,occurred_at,"
                    + "observed_asset_code,evidence_text,allocation_version_before,allocation_version_after,audit_event_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                n(item, "id"),
                type,
                current.id(),
                now(),
                code,
                evidence,
                n(item, "version"),
                n(item, "version") + 1,
                audit);
    }

    private void todo(long allocation, String type, String pool, Long assignee) {
        db.update(
                "INSERT INTO fams_todo"
                    + " (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'REQUISITION_ALLOCATION',?,?,'OPEN',?) ON DUPLICATE KEY UPDATE"
                    + " id=id",
                pool,
                assignee,
                allocation,
                type,
                now());
    }

    private void close(long allocation, String type) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                    + " business_type='REQUISITION_ALLOCATION' AND business_id=? AND todo_type=?"
                    + " AND closed_at IS NULL",
                now(),
                allocation,
                type);
    }

    private Long receiverAccount(long employee) {
        var row =
                one(
                        "SELECT id FROM fams_system_account WHERE employee_id=? AND"
                                + " status='ENABLED'",
                        employee);
        return row == null ? null : n(row, "id");
    }

    private Map<String, Object> header(long request) {
        var row = one("SELECT * FROM fams_requisition WHERE id=? FOR UPDATE", request);
        if (row == null) {
            throw RequisitionFailure.unavailable();
        }
        return row;
    }

    private Map<String, Object> item(long request, long allocation) {
        var row =
                one(
                        "SELECT * FROM fams_requisition_allocation WHERE request_id=? AND id=? FOR"
                                + " UPDATE",
                        request,
                        allocation);
        if (row == null) {
            throw RequisitionFailure.unavailable();
        }
        return row;
    }

    private Map<String, Object> demand(Map<String, Object> item) {
        var result = new LinkedHashMap<>(Objects.requireNonNull(
                one("SELECT * FROM fams_requisition_demand WHERE id=? FOR UPDATE", n(item, "demand_id"))));
        if (item.get("approved_target_json") != null) {
            var target = json.readTree(item.get("approved_target_json").toString());
            result.put("custody_type", target.get("custodyType").asText());
            result.put("target_organization_id", target.get("organizationId").asLong());
            result.put("organization_version_snapshot", target.get("organizationVersion").asLong());
            result.put("target_employee_id", "PERSONAL".equals(target.get("custodyType").asText())
                    ? target.get("employeeId").asLong() : null);
            result.put("target_department_employee_id", "SHARED".equals(target.get("custodyType").asText())
                    ? target.get("employeeId").asLong() : null);
            result.put("employee_version_snapshot", target.get("employeeVersion").asLong());
            result.put("target_location_id", target.get("locationId").asLong());
            result.put("location_version_snapshot", target.get("locationVersion").asLong());
            var frozen = target.get("referenceSnapshot");
            result.put("target_snapshot_json", json.writeValueAsString(frozen != null && frozen.isObject()
                    ? frozen : legacyTargetSnapshot(target)));
        }
        return result;
    }

    private <T extends RequisitionViews.Reply> T replay(
            Actor actor,
            Current current,
            String action,
            String key,
            long request,
            long allocation,
            long version,
            Object input,
            boolean receiver,
            Class<T> type) {
        var row =
                one(
                        "SELECT * FROM fams_idempotency_record WHERE actor_account_id=? AND"
                                + " operation_id=? AND idempotency_key=? FOR UPDATE",
                        current.id(),
                        "handover:" + action,
                        key);
        if (row == null) {
            return null;
        }
        if (!Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT expires_at FROM fams_idempotency_record WHERE id=?",
                                Timestamp.class,
                                n(row, "id")))
                .toInstant()
                .isAfter(clock.instant())) {
            db.update("DELETE FROM fams_idempotency_record WHERE id=?", n(row, "id"));
            return null;
        }
        if (!row.get("payload_sha256")
                .equals(fingerprint(action, request, allocation, version, input))) {
            throw RequisitionFailure.conflict("REQUISITION_IDEMPOTENCY_CONFLICT");
        }
        // Current identity was checked; receiver relation is checked before replay, warehouse
        // history remains readable.
        if (!receiver) {
            requisitions.getDetail(actor, request);
        }
        return json.readValue(row.get("response_body_json").toString(), type);
    }

    private <T extends RequisitionViews.Reply> T finish(
            Current current,
            String action,
            String key,
            long request,
            long allocation,
            long version,
            Object input,
            T result) {
        db.update(
                "INSERT INTO fams_idempotency_record"
                    + " (actor_account_id,operation_id,idempotency_key,payload_sha256,"
                    + "result_status,http_status,response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                    + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'REQUISITION',?,?,?,?,?)",
                current.id(),
                "handover:" + action,
                key,
                fingerprint(action, request, allocation, version, input),
                json.writeValueAsString(result),
                request,
                result.version(),
                now(),
                now(),
                Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private String fingerprint(
            String action, long request, long allocation, long version, Object input) {
        return hash(json.writeValueAsString(List.of(action, request, allocation, version, input)));
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
