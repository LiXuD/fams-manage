package com.fams.transfer;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetTransferPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** All facts, custody changes, locks, audit and replay records share one short transaction. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class TransferService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetTransferPort assets;
    private final AssetHandoverPort handover;

    TransferService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetTransferPort assets,
            AssetHandoverPort handover) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.handover = handover;
    }

    Map<String, Object> eligible(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String predicate = sourceScope(current, "a");
        String from =
                " FROM fams_asset a WHERE a.card_stage='ACTIVE' AND a.exception_status='NORMAL'"
                        + " AND a.lifecycle_status IN ('IN_STOCK','IN_USE') AND ("
                        + predicate
                        + ") AND NOT EXISTS (SELECT 1 FROM fams_asset_business_lock b WHERE"
                        + " b.asset_id=a.id AND b.released_at IS NULL)";
        var rows =
                db.queryForList(
                        "SELECT"
                            + " a.id,a.asset_code,a.name,a.version,a.lifecycle_status,a.custody_type,"
                            + "a.owning_organization_id,a.using_organization_id,a.responsible_employee_id,a.department_responsible_employee_id,"
                            + "a.storage_warehouse_id,a.current_location_id"
                                + from
                                + " ORDER BY a.id LIMIT ? OFFSET ?",
                        size,
                        (page - 1) * size);
        long count =
                Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + from, Long.class));
        return Map.of(
                "items",
                rows.stream().map(this::wire).toList(),
                "total",
                count,
                "page",
                page,
                "pageSize",
                size);
    }

    Map<String, Object> catalog(Actor actor, String kind, Long organization, int page, int size) {
        var current = actors.revalidateLocked(actor);
        if ("personnel".equals(kind)) {
            role(current, Role.ASSET_ADMIN);
        }
        String sql =
                switch (kind) {
                    case "personnel" ->
                            "SELECT id,name,version,status,organization_id FROM fams_employee";
                    case "organization" ->
                            "SELECT id,name,version FROM fams_organization WHERE status='ACTIVE'";
                    case "warehouse" ->
                            "SELECT id,name,version FROM fams_warehouse WHERE status='ACTIVE'";
                    case "location" ->
                            "SELECT id,name,version,warehouse_id,location_type FROM fams_location"
                                    + " WHERE status='ACTIVE'";
                    case "employee" ->
                            "SELECT e.id,e.name,e.version,e.organization_id,EXISTS (SELECT 1 FROM"
                                + " fams_system_account a JOIN fams_account_role r ON"
                                + " r.account_id=a.id WHERE a.employee_id=e.id AND"
                                + " a.status='ENABLED' AND r.role_code='DEPARTMENT_ASSET_MANAGER')"
                                + " AS shared_eligible FROM fams_employee e JOIN fams_organization"
                                + " o ON o.id=e.organization_id WHERE e.status='ACTIVE' AND"
                                + " o.status='ACTIVE' AND e.organization_id="
                                    + requireOrganization(organization);
                    default -> throw new TransferFailure("TRANSFER_CATALOG_NOT_FOUND", 404);
                };
        return Map.of(
                "items",
                db
                        .queryForList(
                                sql + " ORDER BY id LIMIT ? OFFSET ?", size, (page - 1) * size)
                        .stream()
                        .map(this::wire)
                        .toList(),
                "page",
                page,
                "pageSize",
                size);
    }

    private long requireOrganization(Long id) {
        if (id == null || id < 1) {
            throw new TransferFailure("TRANSFER_ORGANIZATION_REQUIRED", 400);
        }
        return id;
    }

    Map<String, Object> personnel(Actor actor, long employee) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.ASSET_ADMIN);
        var person =
                one(
                        "SELECT id,name,status,organization_id FROM fams_employee WHERE id=?",
                        employee);
        var rows =
                db.queryForList(
                        "SELECT"
                            + " a.id,a.asset_code,a.name,a.lifecycle_status,a.exception_status,a.custody_type,"
                            + "a.version,a.current_location_id,a.using_organization_id,b.business_type,b.business_id"
                            + " FROM fams_asset a LEFT JOIN fams_asset_business_lock b ON"
                            + " b.asset_id=a.id AND b.released_at IS NULL WHERE"
                            + " a.responsible_employee_id=? OR"
                            + " a.department_responsible_employee_id=? ORDER BY a.id",
                        employee,
                        employee);
        var receipts =
                db.queryForList(
                        "SELECT id,request_id,item_status FROM fams_transfer_item WHERE"
                                + " receiver_employee_id=? AND item_status IN"
                                + " ('DELIVERED','REFUSED','RECOVERY_PENDING')",
                        employee);
        var processing =
                db.queryForList(
                        "SELECT t.id,t.request_status FROM fams_transfer t JOIN fams_system_account"
                                + " a ON a.id=t.processor_account_id WHERE a.employee_id=? AND"
                                + " t.request_status IN ('PROCESSING','WAITING_HANDOVER')",
                        employee);
        var loans =
                db.queryForList(
                        "SELECT id,asset_id,expected_return_date,relation_status FROM fams_loan"
                            + " WHERE borrower_employee_id=? AND relation_status='OPEN' ORDER BY"
                            + " id",
                        employee);
        var returns =
                db.queryForList(
                        "SELECT r.id,r.asset_id,r.return_status FROM fams_asset_return r LEFT JOIN"
                            + " fams_system_account a ON a.id=r.processor_account_id WHERE"
                            + " (r.responsible_employee_id=? OR a.employee_id=?) AND"
                            + " r.return_status IN"
                            + " ('PENDING_APPROVAL','WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING')"
                            + " ORDER BY r.id",
                        employee,
                        employee);
        var requisitions =
                db.queryForList(
                        "SELECT DISTINCT r.id,r.request_no,r.request_status FROM fams_requisition r"
                            + " LEFT JOIN fams_requisition_demand d ON d.request_id=r.id LEFT JOIN"
                            + " fams_requisition_ownership o ON o.request_id=r.id AND o.archived_at"
                            + " IS NULL LEFT JOIN fams_system_account a ON"
                            + " a.id=o.processor_account_id WHERE (d.target_employee_id=? OR"
                            + " d.target_department_employee_id=? OR a.employee_id=?) AND"
                            + " r.request_status IN"
                            + " ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING','WAITING_HANDOVER')"
                            + " ORDER BY r.id",
                        employee,
                        employee,
                        employee);
        return Map.of(
                "employee",
                wire(person),
                "assets",
                rows.stream().map(this::wire).toList(),
                "pendingReceipts",
                receipts.stream().map(this::wire).toList(),
                "processingTransfers",
                processing.stream().map(this::wire).toList(),
                "openLoans",
                loans.stream().map(this::wire).toList(),
                "openReturns",
                returns.stream().map(this::wire).toList(),
                "openRequisitions",
                requisitions.stream().map(this::wire).toList());
    }

    Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String predicate = current.globalApplicant() ? "1=1" : "creator_account_id=" + current.id();
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_transfer WHERE " + predicate,
                                Long.class));
        return Map.of(
                "items",
                db
                        .queryForList(
                                "SELECT * FROM fams_transfer WHERE "
                                        + predicate
                                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                size,
                                (page - 1) * size)
                        .stream()
                        .map(this::wire)
                        .toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var request = request(id);
        visible(current, request);
        return detail(request);
    }

    Map<String, Object> create(Actor actor, String key, TransferController.Draft input) {
        var current = actors.revalidateLocked(actor);
        String hash = digest("create", input);
        var replay = replay(current, "create", key, hash);
        if (replay != null) {
            return replay;
        }
        if (input.items().stream().map(TransferController.Item::assetId).distinct().count()
                != input.items().size()) {
            throw new TransferFailure("TRANSFER_DUPLICATE_ASSET", 400);
        }
        db.update(
                "INSERT INTO fams_transfer (creator_account_id,reason,created_at,updated_at) VALUES"
                        + " (?,?,?,?)",
                current.id(),
                input.reason(),
                now(),
                now());
        long id = lastId();
        for (var inputItem :
                input.items().stream()
                        .sorted(Comparator.comparingLong(TransferController.Item::assetId))
                        .toList()) {
            var source = assets.snapshot(inputItem.assetId(), inputItem.assetVersion());
            sourceAllowed(current, source);
            var target = assets.validateTarget(source, inputItem.target());
            boolean receipt = receiptRequired(source, target);
            Object parameter =
                    one(
                                    "SELECT snapshot_json FROM fams_business_parameter_version"
                                            + " WHERE id=?",
                                    source.get("parameter_version_id"))
                            .get("snapshot_json");
            db.update(
                    "INSERT INTO fams_transfer_item"
                        + " (request_id,asset_id,asset_version,source_snapshot_json,target_snapshot_json,parameter_snapshot_json,receiver_employee_id,receipt_required)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                    id,
                    inputItem.assetId(),
                    inputItem.assetVersion(),
                    json.writeValueAsString(source),
                    json.writeValueAsString(inputItem.target()),
                    parameter.toString(),
                    receipt ? inputItem.target().employeeId() : null,
                    receipt);
        }
        event(
                actor,
                current,
                id,
                null,
                "TRANSFER_DRAFT_CREATED",
                Map.of("count", input.items().size()));
        return finish(current, "create", key, hash, detail(request(id)));
    }

    Map<String, Object> action(Actor actor, long id, long version, String key, String action) {
        var current = actors.revalidateLocked(actor);
        var request = request(id);
        visible(current, request);
        String hash = digest(action + ":" + id + ":" + version, Map.of());
        var replay = replay(current, action, key, hash);
        if (replay != null) {
            return replay;
        }
        version(request, version);
        switch (action) {
            case "submit" -> {
                creator(current, request);
                state(request, "DRAFT");
                for (var item : items(id)) {
                    var source = source(item);
                    sourceAllowed(current, source);
                    assets.validateTarget(source, target(item));
                    db.update(
                            "INSERT INTO fams_asset_business_lock"
                                + " (asset_id,business_type,business_id,business_item_id,acquired_at)"
                                + " VALUES (?,'TRANSFER',?,?,?)",
                            item.get("asset_id"),
                            id,
                            item.get("id"),
                            now());
                }
                updateState(id, "PENDING_APPROVAL");
                todo(id, "APPROVAL", "ASSET_ADMIN", null);
            }
            case "cancel" -> {
                creator(current, request);
                if (!List.of("DRAFT", "PENDING_APPROVAL").contains(request.get("request_status"))) {
                    throw new TransferFailure("TRANSFER_PHYSICAL_RECOVERY_REQUIRED", 409);
                }
                if ("PENDING_APPROVAL".equals(request.get("request_status"))) {
                    for (var item : items(id)) {
                        release(item);
                    }
                }
                db.update(
                        "UPDATE fams_transfer_item SET item_status='CANCELLED',version=version+1"
                                + " WHERE request_id=?",
                        id);
                updateState(id, "CANCELLED");
                closeTodos(id);
            }
            case "claim" -> {
                role(current, Role.WAREHOUSE_OPERATOR);
                state(request, "WAITING_WAREHOUSE_CLAIM");
                for (var item : items(id)) {
                    active(item);
                }
                db.update(
                        "UPDATE fams_transfer SET processor_account_id=? WHERE id=?",
                        current.id(),
                        id);
                updateState(id, "PROCESSING");
                closeTodos(id);
                todo(id, "EXECUTION", "WAREHOUSE_OPERATOR", current.id());
            }
            default -> throw new TransferFailure("TRANSFER_ACTION_INVALID", 400);
        }
        event(
                actor,
                current,
                id,
                null,
                "TRANSFER_" + action.toUpperCase(java.util.Locale.ROOT),
                Map.of());
        return finish(current, action, key, hash, detail(request(id)));
    }

    Map<String, Object> decide(
            Actor actor, long id, long version, String key, TransferController.Decision input) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.ASSET_ADMIN);
        var request = request(id);
        String hash = digest("decision:" + id + ":" + version, input);
        var replay = replay(current, "decision", key, hash);
        if (replay != null) {
            return replay;
        }
        version(request, version);
        state(request, "PENDING_APPROVAL");
        for (var item : items(id)) {
            if (input.approved()) {
                active(item);
            } else {
                release(item);
            }
        }
        db.update(
                "UPDATE fams_transfer SET"
                    + " decided_by=?,decision_reason=?,decided_at=?,approved_version=? WHERE id=?",
                current.id(),
                input.reason(),
                now(),
                input.approved() ? version : null,
                id);
        updateState(id, input.approved() ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED");
        closeTodos(id);
        if (input.approved()) {
            todo(id, "EXECUTION", "WAREHOUSE_OPERATOR", null);
        }
        event(
                actor,
                current,
                id,
                null,
                input.approved() ? "TRANSFER_APPROVED" : "TRANSFER_REJECTED",
                input);
        return finish(current, "decision", key, hash, detail(request(id)));
    }

    Map<String, Object> physical(
            Actor actor,
            long id,
            long itemId,
            long version,
            String key,
            String action,
            TransferController.Physical input) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        var request = request(id);
        processor(current, request);
        String hash = digest(action + ":" + id + ":" + itemId + ":" + version, input);
        var replay = replay(current, action, key, hash);
        if (replay != null) {
            return replay;
        }
        version(request, version);
        executable(request);
        var item = item(itemId);
        belongs(item, id);
        version(item, input.itemVersion());
        var original = source(item);
        lock(item);
        handover.observedCode(number(item, "asset_id"), input.observedCode());
        switch (action) {
            case "deliver" -> {
                itemState(item, "READY");
                active(item);
                if (!receiptRequired(item)) {
                    throw new TransferFailure("TRANSFER_SITE_VERIFICATION_REQUIRED", 409);
                }
                long days =
                        json.readTree(item.get("parameter_snapshot_json").toString())
                                .path("handoverAcceptanceDeadlineNaturalDays")
                                .asLong(3);
                if (days < 1 || days > 365) {
                    throw new TransferFailure("TRANSFER_PARAMETER_INVALID", 409);
                }
                db.update(
                        "UPDATE fams_transfer_item SET"
                            + " item_status='DELIVERED',destination=?,delivery_evidence=?,delivered_at=?,receipt_deadline_at=?,version=version+1"
                            + " WHERE id=?",
                        input.destination(),
                        input.evidence(),
                        now(),
                        Timestamp.from(
                                clock.instant().plusSeconds(Math.multiplyExact(days, 86400))),
                        itemId);
            }
            case "verify" -> {
                itemState(item, "READY");
                active(item);
                if (receiptRequired(item)) {
                    throw new TransferFailure("TRANSFER_ACTUAL_RECEIVER_REQUIRED", 409);
                }
                var target = target(item);
                if (!Objects.equals(original.get("storage_warehouse_id"), target.warehouseId())
                        && (input.actualReceiver() == null
                                || input.actualReceiver().isBlank()
                                || input.receivedAt() == null
                                || input.receivedAt().isAfter(clock.instant())
                                || input.receivedAt()
                                        .isBefore(instant(request.get("created_at"))))) {
                    throw new TransferFailure("TRANSFER_DESTINATION_RECEIPT_REQUIRED", 400);
                }
                complete(current, item);
            }
            case "cancel" -> {
                itemState(item, "READY");
                cancelItem(item);
            }
            case "recovery" -> {
                if (!List.of("DELIVERED", "REFUSED").contains(item.get("item_status"))) {
                    throw new TransferFailure("TRANSFER_ITEM_CHANGED", 409);
                }
                db.update(
                        "UPDATE fams_transfer_item SET"
                                + " item_status='RECOVERY_PENDING',version=version+1 WHERE id=?",
                        itemId);
            }
            case "recover" -> {
                itemState(item, "RECOVERY_PENDING");
                if (!Boolean.TRUE.equals(input.physicalReturned())) {
                    throw new TransferFailure("TRANSFER_PHYSICAL_RECOVERY_REQUIRED", 400);
                }
                cancelItem(item);
            }
            default -> throw new TransferFailure("TRANSFER_ACTION_INVALID", 400);
        }
        db.update(
                "UPDATE fams_transfer_receipt_grant SET consumed_at=? WHERE item_id=? AND"
                        + " consumed_at IS NULL",
                now(),
                itemId);
        event(
                actor,
                current,
                id,
                itemId,
                "TRANSFER_" + action.toUpperCase(java.util.Locale.ROOT),
                input);
        aggregate(id);
        return finish(current, action, key, hash, detail(request(id)));
    }

    Map<String, Object> pending(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        var rows =
                db.queryForList(
                        "SELECT"
                            + " i.id,i.request_id,i.item_status,i.version,i.destination,i.delivered_at,i.receipt_deadline_at,a.asset_code,a.name,t.version"
                            + " AS request_version FROM fams_transfer_item i JOIN fams_transfer t"
                            + " ON t.id=i.request_id JOIN fams_asset a ON a.id=i.asset_id WHERE"
                            + " i.receiver_employee_id=? AND i.item_status='DELIVERED' ORDER BY"
                            + " i.id LIMIT ? OFFSET ?",
                        current.employeeId(),
                        size,
                        (page - 1) * size);
        long total =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_transfer_item WHERE"
                                        + " receiver_employee_id=? AND item_status='DELIVERED'",
                                Long.class,
                                current.employeeId()));
        return Map.of(
                "items",
                rows.stream().map(this::wire).toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    Map<String, Object> grant(Actor actor, long itemId) {
        var current = actors.revalidateLocked(actor);
        var located = one("SELECT request_id FROM fams_transfer_item WHERE id=?", itemId);
        var request = request(number(located, "request_id"));
        executable(request);
        var item = item(itemId);
        receiver(current, item);
        itemState(item, "DELIVERED");
        active(item);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.update(
                "INSERT INTO fams_transfer_receipt_grant"
                    + " (item_id,item_version,account_id,auth_epoch,authorization_version,token_sha256,issued_at,expires_at)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                itemId,
                item.get("version"),
                current.id(),
                actor.authEpoch(),
                actor.authorizationVersion(),
                sha(token),
                now(),
                Timestamp.from(clock.instant().plusSeconds(300)));
        return Map.of(
                "grant",
                token,
                "itemId",
                Long.toString(itemId),
                "itemVersion",
                item.get("version"),
                "requestVersion",
                request.get("version"),
                "expiresAt",
                clock.instant().plusSeconds(300).toString());
    }

    Map<String, Object> receipt(
            Actor actor,
            long itemId,
            long version,
            String key,
            String action,
            TransferController.Receipt input) {
        var current = actors.revalidateLocked(actor);
        var located = one("SELECT request_id FROM fams_transfer_item WHERE id=?", itemId);
        long id = number(located, "request_id");
        var request = request(id);
        var item = item(itemId);
        receiver(current, item);
        String hash = digest(action + ":" + itemId + ":" + version, input);
        var replay = replay(current, action, key, hash);
        if (replay != null) {
            return replay;
        }
        version(request, version);
        executable(request);
        itemState(item, "DELIVERED");
        active(item);
        var grants =
                db.queryForList(
                        "SELECT * FROM fams_transfer_receipt_grant WHERE token_sha256=? AND"
                            + " item_id=? AND account_id=? AND item_version=? AND auth_epoch=? AND"
                            + " authorization_version=? AND consumed_at IS NULL AND expires_at>?"
                            + " FOR UPDATE",
                        sha(input.grant()),
                        itemId,
                        current.id(),
                        item.get("version"),
                        actor.authEpoch(),
                        actor.authorizationVersion(),
                        now());
        if (grants.size() != 1) {
            throw new TransferFailure("TRANSFER_GRANT_INVALID", 409);
        }
        if (!Boolean.TRUE.equals(input.physicalChecked())) {
            throw new TransferFailure("TRANSFER_PHYSICAL_CHECK_REQUIRED", 400);
        }
        if ("receive".equals(action)) {
            handover.observedCode(number(item, "asset_id"), input.observedCode());
            complete(current, item);
        } else {
            if (input.reason() == null || input.reason().isBlank()) {
                throw new TransferFailure("TRANSFER_REFUSAL_REASON_REQUIRED", 400);
            }
            db.update(
                    "UPDATE fams_transfer_item SET item_status='REFUSED',version=version+1 WHERE"
                            + " id=?",
                    itemId);
        }
        db.update(
                "UPDATE fams_transfer_receipt_grant SET consumed_at=? WHERE id=?",
                now(),
                grants.getFirst().get("id"));
        event(
                actor,
                current,
                id,
                itemId,
                "TRANSFER_" + action.toUpperCase(java.util.Locale.ROOT),
                Map.of(
                        "physicalChecked",
                        true,
                        "reason",
                        input.reason() == null ? "" : input.reason()));
        aggregate(id);
        return finish(current, action, key, hash, wire(item(itemId)));
    }

    private void complete(Current current, Map<String, Object> item) {
        var after =
                assets.complete(
                        number(item, "asset_id"),
                        number(item, "asset_version"),
                        target(item),
                        current.id(),
                        number(item, "request_id"),
                        clock.instant());
        db.update(
                "UPDATE fams_transfer_item SET"
                    + " item_status='COMPLETED',completed_at=?,completed_by=?,after_snapshot_json=?,version=version+1"
                    + " WHERE id=?",
                now(),
                current.id(),
                json.writeValueAsString(after),
                item.get("id"));
        release(item);
    }

    private void cancelItem(Map<String, Object> item) {
        db.update(
                "UPDATE fams_transfer_item SET item_status='CANCELLED',version=version+1 WHERE"
                        + " id=?",
                item.get("id"));
        release(item);
    }

    private void aggregate(long id) {
        var items = items(id);
        long done =
                items.stream().filter(row -> "COMPLETED".equals(row.get("item_status"))).count();
        long cancelled =
                items.stream().filter(row -> "CANCELLED".equals(row.get("item_status"))).count();
        long pending =
                items.stream()
                        .filter(
                                row ->
                                        List.of("DELIVERED", "REFUSED", "RECOVERY_PENDING")
                                                .contains(row.get("item_status")))
                        .count();
        String state =
                done + cancelled == items.size()
                        ? (done == 0
                                ? "CANCELLED"
                                : cancelled == 0 ? "COMPLETED" : "PARTIALLY_COMPLETED")
                        : pending > 0 ? "WAITING_HANDOVER" : "PROCESSING";
        updateState(id, state);
        if (done + cancelled == items.size()) {
            closeTodos(id);
        }
    }

    private Map<String, Object> detail(Map<String, Object> request) {
        long id = number(request, "id");
        var result = new LinkedHashMap<>(wire(request));
        var items = items(id);
        result.put("items", items.stream().map(this::wire).toList());
        result.put(
                "events",
                db
                        .queryForList(
                                "SELECT"
                                    + " id,item_id,event_type,actor_account_id,occurred_at,detail_json"
                                    + " FROM fams_transfer_event WHERE request_id=? ORDER BY id",
                                id)
                        .stream()
                        .map(this::wire)
                        .toList());
        result.put("totalQuantity", items.size());
        result.put(
                "completedQuantity",
                items.stream().filter(i -> "COMPLETED".equals(i.get("item_status"))).count());
        result.put(
                "cancelledQuantity",
                items.stream().filter(i -> "CANCELLED".equals(i.get("item_status"))).count());
        result.put(
                "pendingQuantity",
                items.stream()
                        .filter(
                                i ->
                                        !List.of("COMPLETED", "CANCELLED")
                                                .contains(i.get("item_status")))
                        .count());
        result.put(
                "sourceGroups",
                items.stream()
                        .map(
                                i ->
                                        json.readTree(i.get("source_snapshot_json").toString())
                                                .path("current_location_id")
                                                .asText())
                        .distinct()
                        .count());
        result.put(
                "targetGroups",
                items.stream()
                        .map(i -> target(i).warehouseId() + ":" + target(i).locationId())
                        .distinct()
                        .count());
        return result;
    }

    private Map<String, Object> source(Map<String, Object> item) {
        return assets.snapshot(number(item, "asset_id"), number(item, "asset_version"));
    }

    private AssetTransferPort.Target target(Map<String, Object> item) {
        return json.readValue(
                item.get("target_snapshot_json").toString(), AssetTransferPort.Target.class);
    }

    private void active(Map<String, Object> item) {
        var source = source(item);
        lock(item);
        assets.validateTarget(source, target(item));
    }

    private void lock(Map<String, Object> item) {
        var rows =
                db.queryForList(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='TRANSFER' AND business_id=? AND business_item_id=?"
                            + " AND released_at IS NULL FOR UPDATE",
                        item.get("asset_id"),
                        item.get("request_id"),
                        item.get("id"));
        if (rows.size() != 1) {
            throw new TransferFailure("TRANSFER_LOCK_CHANGED", 409);
        }
    }

    private void release(Map<String, Object> item) {
        if (db.update(
                        "UPDATE fams_asset_business_lock SET"
                            + " released_at=?,release_reason='TRANSFER_TERMINAL',lock_version=lock_version+1"
                            + " WHERE asset_id=? AND business_type='TRANSFER' AND business_id=? AND"
                            + " business_item_id=? AND released_at IS NULL",
                        now(),
                        item.get("asset_id"),
                        item.get("request_id"),
                        item.get("id"))
                != 1) {
            throw new TransferFailure("TRANSFER_LOCK_CHANGED", 409);
        }
    }

    private boolean receiptRequired(Map<String, Object> source, Map<String, Object> target) {
        return !"STORAGE".equals(source.get("custody_type"))
                && List.of(
                                "owning_organization_id",
                                "using_organization_id",
                                "custody_type",
                                "responsible_employee_id",
                                "department_responsible_employee_id")
                        .stream()
                        .anyMatch(k -> !Objects.equals(source.get(k), target.get(k)));
    }

    private boolean receiptRequired(Map<String, Object> item) {
        Object value = item.get("receipt_required");
        return Boolean.TRUE.equals(value)
                || value instanceof Number number && number.longValue() == 1;
    }

    private String sourceScope(Current current, String alias) {
        if (current.globalApplicant()) {
            return "1=1";
        }
        return "COALESCE("
                + alias
                + ".responsible_employee_id,"
                + alias
                + ".department_responsible_employee_id)="
                + current.employeeId()
                + (current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        ? " OR " + alias + ".using_organization_id=" + current.organizationId()
                        : "");
    }

    private void sourceAllowed(Current current, Map<String, Object> source) {
        if (current.globalApplicant()) {
            return;
        }
        Object owner = source.get("responsible_employee_id");
        if (owner == null) {
            owner = source.get("department_responsible_employee_id");
        }
        if (!(owner instanceof Number value && value.longValue() == current.employeeId())
                && !(current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && source.get("using_organization_id") instanceof Number org
                        && org.longValue() == current.organizationId())) {
            throw new TransferFailure("TRANSFER_SOURCE_FORBIDDEN", 403);
        }
    }

    private void receiver(Current current, Map<String, Object> item) {
        if (!(item.get("receiver_employee_id") instanceof Number employee)
                || employee.longValue() != current.employeeId()) {
            throw new TransferFailure("TRANSFER_RECEIPT_NOT_FOUND", 404);
        }
    }

    private void visible(Current current, Map<String, Object> request) {
        if (!current.globalApplicant() && number(request, "creator_account_id") != current.id()) {
            throw new TransferFailure("TRANSFER_NOT_FOUND", 404);
        }
    }

    private void creator(Current current, Map<String, Object> request) {
        if (number(request, "creator_account_id") != current.id()) {
            throw new TransferFailure("TRANSFER_FORBIDDEN", 403);
        }
    }

    private void processor(Current current, Map<String, Object> request) {
        if (request.get("processor_account_id") == null
                || number(request, "processor_account_id") != current.id()) {
            throw new TransferFailure("TRANSFER_NOT_PROCESSOR", 409);
        }
    }

    private void role(Current current, Role role) {
        if (!current.has(role)) {
            throw new TransferFailure("TRANSFER_FORBIDDEN", 403);
        }
    }

    private void version(Map<String, Object> row, long version) {
        if (number(row, "version") != version) {
            throw new TransferFailure("TRANSFER_VERSION_CONFLICT", 409);
        }
    }

    private void state(Map<String, Object> request, String state) {
        if (!state.equals(request.get("request_status"))) {
            throw new TransferFailure("TRANSFER_STATE_CHANGED", 409);
        }
    }

    private void itemState(Map<String, Object> item, String state) {
        if (!state.equals(item.get("item_status"))) {
            throw new TransferFailure("TRANSFER_ITEM_CHANGED", 409);
        }
    }

    private void executable(Map<String, Object> request) {
        if (request.get("approved_version") == null
                || !List.of("PROCESSING", "WAITING_HANDOVER")
                        .contains(request.get("request_status"))) {
            throw new TransferFailure("TRANSFER_NOT_APPROVED", 409);
        }
    }

    private void updateState(long id, String state) {
        db.update(
                "UPDATE fams_transfer SET request_status=?,version=version+1,updated_at=? WHERE"
                        + " id=?",
                state,
                now(),
                id);
    }

    private Map<String, Object> request(long id) {
        return one("SELECT * FROM fams_transfer WHERE id=? FOR UPDATE", id);
    }

    private Map<String, Object> item(long id) {
        return one("SELECT * FROM fams_transfer_item WHERE id=? FOR UPDATE", id);
    }

    private List<Map<String, Object>> items(long id) {
        return db.queryForList(
                "SELECT * FROM fams_transfer_item WHERE request_id=? ORDER BY asset_id FOR UPDATE",
                id);
    }

    private void belongs(Map<String, Object> item, long id) {
        if (number(item, "request_id") != id) {
            throw new TransferFailure("TRANSFER_ITEM_NOT_FOUND", 404);
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new TransferFailure("TRANSFER_NOT_FOUND", 404);
        }
        return rows.getFirst();
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private long lastId() {
        return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private void todo(long id, String type, String pool, Long owner) {
        db.update(
                "INSERT INTO fams_todo"
                    + " (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'TRANSFER',?,?,'OPEN',?)",
                pool,
                owner,
                id,
                type,
                now());
    }

    private void closeTodos(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='TRANSFER' AND business_id=? AND closed_at IS NULL",
                now(),
                id);
    }

    private void event(
            Actor actor, Current current, long id, Long item, String type, Object detail) {
        db.update(
                "INSERT INTO fams_transfer_event"
                    + " (request_id,item_id,event_type,actor_account_id,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,?,?)",
                id,
                item,
                type,
                current.id(),
                now(),
                json.writeValueAsString(detail));
        db.update(
                "INSERT INTO fams_audit_event"
                    + " (request_id,actor_account_id,actor_role,action,resource_type,resource_id,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'TRANSFER',?,?,?)",
                actor.requestId().toString(),
                current.id(),
                current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow(),
                type,
                id,
                now(),
                json.writeValueAsString(detail));
    }

    private Map<String, Object> replay(Current current, String action, String key, String hash) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_idempotency_record WHERE actor_account_id=? AND"
                                + " operation_id=? AND idempotency_key=? FOR UPDATE",
                        current.id(),
                        "transfer:" + action,
                        key);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        if (!instant(row.get("expires_at")).isAfter(clock.instant())) {
            db.update("DELETE FROM fams_idempotency_record WHERE id=?", row.get("id"));
            return null;
        }
        if (!hash.equals(row.get("payload_sha256"))) {
            throw new TransferFailure("TRANSFER_IDEMPOTENCY_CONFLICT", 409);
        }
        return json.readValue(
                row.get("response_body_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(
            Current current, String action, String key, String hash, Map<String, Object> result) {
        db.update(
                "INSERT INTO fams_idempotency_record"
                    + " (actor_account_id,operation_id,idempotency_key,payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                    + " VALUES (?,?,?,?,'SUCCEEDED',200,?,?,?,?,?,?,?)",
                current.id(),
                "transfer:" + action,
                key,
                hash,
                json.writeValueAsString(result),
                result.containsKey("requestId") ? "TRANSFER_ITEM" : "TRANSFER",
                result.get("id"),
                result.get("version"),
                now(),
                now(),
                Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private String digest(String prefix, Object value) {
        return sha(prefix + "\n" + json.writeValueAsString(value));
    }

    private String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Map<String, Object> wire(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach(
                (key, value) -> {
                    if ("parameter_snapshot_json".equals(key)) {
                        return;
                    }
                    String field = key.endsWith("_json") ? key.substring(0, key.length() - 5) : key;
                    String[] words = field.split("_");
                    StringBuilder name = new StringBuilder(words[0]);
                    for (int i = 1; i < words.length; i++) {
                        name.append(Character.toUpperCase(words[i].charAt(0)))
                                .append(words[i].substring(1));
                    }
                    Object output = value;
                    if (value != null && key.endsWith("_json")) {
                        output = json.readTree(value.toString());
                    } else if (value instanceof Timestamp
                            || value instanceof java.time.LocalDateTime) {
                        output = instant(value).toString();
                    } else if (value instanceof Number
                            && (key.equals("id") || key.endsWith("_id") || key.endsWith("_by"))) {
                        output = value.toString();
                    }
                    result.put(name.toString(), output);
                });
        return result;
    }
}
