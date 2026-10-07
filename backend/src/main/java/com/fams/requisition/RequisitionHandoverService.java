package com.fams.requisition;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.masterdata.RequisitionMasterDataPort;

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
@Transactional(isolation = Isolation.READ_COMMITTED)
class RequisitionHandoverService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final RequisitionMasterDataPort references;
    private final AssetHandoverPort assets;
    private final RequisitionService requisitions;
    private final SecureRandom random = new SecureRandom();

    RequisitionHandoverService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            RequisitionMasterDataPort references,
            AssetHandoverPort assets,
            RequisitionService requisitions) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.references = references;
        this.assets = assets;
        this.requisitions = requisitions;
    }

    Map<String, Object> pending(Actor actor, int page, int size) {
        Current current = actors.revalidateLocked(actor);
        String from =
                " FROM fams_requisition_allocation a JOIN fams_requisition_demand d ON"
                        + " d.id=a.demand_id JOIN fams_requisition r ON r.id=a.request_id JOIN"
                        + " fams_requisition_handover h ON h.allocation_id=a.id WHERE"
                        + " COALESCE(d.target_employee_id,d.target_department_employee_id)=? AND"
                        + " a.allocation_status='DELIVERED_WAITING_RECEIPT' AND"
                        + " r.request_status='WAITING_HANDOVER'";
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*)" + from, Long.class, current.employeeId()));
        var rows =
                db.queryForList(
                        "SELECT a.id,a.request_id,r.request_no,a.asset_snapshot_json,a.version,"
                                + "h.delivered_at,h.receipt_deadline_at,h.destination_text"
                                + from
                                + " ORDER BY a.id LIMIT ? OFFSET ?",
                        current.employeeId(),
                        size,
                        (page - 1) * size);
        return Map.of(
                "items",
                rows.stream().map(this::clean).toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    Map<String, Object> grant(Actor actor, long request, long allocation) {
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
        return Map.of(
                "grant",
                token,
                "expiresAt",
                clock.instant().plusSeconds(300).toString(),
                "requestVersion",
                n(header, "request_version"),
                "allocationVersion",
                n(item, "version"));
    }

    Map<String, Object> deliver(
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
                replay(actor, current, "deliver", key, request, allocation, version, input, false);
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
        var deadline = clock.instant().atZone(zone).plusDays(days).toInstant();
        db.update(
                "INSERT INTO fams_requisition_handover"
                    + " (allocation_id,delivered_by_account_id,delivered_at,receipt_deadline_at,destination_text,"
                    + "temporary_employee_id,delivery_evidence,target_snapshot_json,formal_custody_snapshot_json)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                allocation,
                current.id(),
                now(),
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
                now(),
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
        return finish(
                current,
                "deliver",
                key,
                request,
                allocation,
                version,
                input,
                requisitions.get(actor, request));
    }

    Map<String, Object> warehouseAction(
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
                replay(actor, current, action, key, request, allocation, version, input, false);
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
        return finish(
                current,
                action,
                key,
                request,
                allocation,
                version,
                input,
                requisitions.get(actor, request));
    }

    Map<String, Object> receipt(
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
        var replay = replay(actor, current, action, key, request, allocation, version, input, true);
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
        var result = new LinkedHashMap<String, Object>();
        result.put("allocationId", Long.toString(allocation));
        result.put("requestId", Long.toString(request));
        result.put("result", refuse ? "REFUSED" : "RECEIVED");
        result.put("version", n(header, "version") + 1);
        return finish(current, action, key, request, allocation, version, input, result);
    }

    Map<String, Object> receiptDetail(Actor actor, long request, long allocation) {
        Current current = actors.revalidateLocked(actor);
        var header = header(request);
        var item = item(request, allocation);
        var demand = demand(item);
        receiver(current, demand);
        Map<String, Object> result = new LinkedHashMap<>();
        var handover =
                one(
                        "SELECT delivered_at,receipt_deadline_at,destination_text"
                                + " FROM fams_requisition_handover WHERE allocation_id=?",
                        allocation);
        if (handover == null
                || !"DELIVERED_WAITING_RECEIPT".equals(item.get("allocation_status"))) {
            throw RequisitionFailure.unavailable();
        }
        result.put("requestId", Long.toString(request));
        result.put("requestNo", header.get("request_no"));
        result.put("requestVersion", n(header, "version"));
        result.put("allocationId", Long.toString(allocation));
        result.put("allocationStatus", item.get("allocation_status"));
        result.put("allocationVersion", n(item, "version"));
        result.put("asset", json.readTree(item.get("asset_snapshot_json").toString()));
        result.put("handover", clean(handover));
        return result;
    }

    private void targets(Map<String, Object> demand) {
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
            if (current.isEmpty()
                    || !"ACTIVE".equals(current.get("status"))
                    || n(current, "version") != n(demand, spec[2])
                    || "employee".equals(spec[0])
                            && n(current, "organization_id")
                                    != n(demand, "target_organization_id")) {
                throw RequisitionFailure.conflict("HANDOVER_TARGET_CHANGED");
            }
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
        return Objects.requireNonNull(
                one(
                        "SELECT * FROM fams_requisition_demand WHERE id=? FOR UPDATE",
                        n(item, "demand_id")));
    }

    private Map<String, Object> replay(
            Actor actor,
            Current current,
            String action,
            String key,
            long request,
            long allocation,
            long version,
            Object input,
            boolean receiver) {
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
            requisitions.get(actor, request);
        }
        return json.readValue(
                row.get("response_body_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(
            Current current,
            String action,
            String key,
            long request,
            long allocation,
            long version,
            Object input,
            Map<String, Object> result) {
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
                result.get("version"),
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

    private Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach(
                (key, value) -> {
                    StringBuilder name = new StringBuilder();
                    boolean upper = false;
                    for (char c : key.toCharArray()) {
                        if (c == '_') {
                            upper = true;
                        } else {
                            name.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                    }
                    result.put(
                            name.toString(),
                            value == null
                                    ? null
                                    : key.endsWith("_json")
                                            ? json.readTree(value.toString())
                                            : key.equals("id") || key.endsWith("_id")
                                                    ? value.toString()
                                                    : value instanceof java.time.LocalDateTime time
                                                            ? time.toInstant(
                                                                            java.time.ZoneOffset
                                                                                    .UTC)
                                                                    .toString()
                                                            : value);
                });
        return result;
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
