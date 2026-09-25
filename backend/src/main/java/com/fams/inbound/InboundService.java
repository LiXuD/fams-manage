package com.fams.inbound;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fams.auth.InboundActorContext.Actor;
import com.fams.masterdata.InboundNumberPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class InboundService {
    private final JdbcTemplate db;
    private final InboundNumberPort numbers;
    private final ObjectMapper json;
    private final Clock clock;

    InboundService(JdbcTemplate db, InboundNumberPort numbers, ObjectMapper json, Clock authClock) {
        this.db = db;
        this.numbers = numbers;
        this.json = json;
        this.clock = authClock;
    }

    Map<String, Object> list(Actor actor, int page, int size, String status, String number) {
        if (status != null && !List.of("DRAFT", "PENDING_ADMIN_DECISION", "REJECTED",
                "WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "COMPLETED", "CANCELLED")
                .contains(status)) { throw InboundFailure.invalid("INBOUND_FILTER_INVALID"); }
        String scope = "WAREHOUSE_OPERATOR".equals(actor.role())
                ? "(creator_account_id = ? OR receipt_status IN "
                  + "('WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING','COMPLETED'))"
                : "submitted_at IS NOT NULL";
        String sql = " FROM fams_inbound_receipt WHERE " + scope
                + " AND (? IS NULL OR receipt_status = ?) AND (? IS NULL OR receipt_no = ?)";
        List<Object> args = new ArrayList<>();
        if ("WAREHOUSE_OPERATOR".equals(actor.role())) { args.add(actor.id()); }
        args.add(status);
        args.add(status);
        args.add(number);
        args.add(number);
        long total = Objects.requireNonNull(db.queryForObject(
                "SELECT COUNT(*)" + sql, Long.class, args.toArray()));
        List<Object> paged = new ArrayList<>(args);
        paged.add(size);
        paged.add((page - 1) * size);
        List<Map<String, Object>> rows = db.queryForList("SELECT id" + sql
                + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?", paged.toArray());
        return Map.of("page", page, "pageSize", size, "total", total,
                "items", rows.stream().map(row -> receipt(id(row))).toList());
    }

    @Transactional
    Map<String, Object> create(Actor actor, String key, Map<String, Object> body) {
        String digest = digest("create", body);
        Map<String, Object> prior = replay(actor, "createInboundReceipt", key, digest);
        if (prior != null) { return prior; }
        LocalDate date = date(body.get("businessDate"));
        List<Map<String, Object>> inputs = items(body);
        Map<String, Object> parameter = one("SELECT v.id, v.snapshot_json FROM fams_business_parameter_state s "
                + "JOIN fams_business_parameter_version v ON v.id = s.current_version_id "
                + "WHERE s.singleton_id = 1");
        if (parameter == null) { throw InboundFailure.conflict("INBOUND_PARAMETERS_UNAVAILABLE"); }
        JsonNode snapshot = json.readTree(text(parameter, "snapshot_json"));
        String timezone = snapshot.path("timezone").asText();
        ZoneId.of(timezone);
        int adminDays = days(snapshot, "adminDecisionDeadlineWorkdays");
        int claimDays = days(snapshot, "warehouseClaimDeadlineWorkdays");
        int executeDays = days(snapshot, "warehouseFirstExecutionDeadlineWorkdays");
        List<Map<String, Object>> resolved = resolve(actor, inputs, body);
        InboundNumberPort.Reservation allocation = numbers.reserve();
        Instant now = now();
        db.update("INSERT INTO fams_inbound_receipt (receipt_no,creator_account_id,"
                + "creator_username_snapshot,receipt_status,business_date,parameter_version_id,"
                + "timezone_snapshot,admin_decision_deadline_workdays,warehouse_claim_deadline_workdays,"
                + "warehouse_first_execution_deadline_workdays,notes,version,created_at,updated_at,"
                + "created_by,updated_by) VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,0,?,?,?,?)",
                allocation.number(), actor.id(), actor.username(), date, id(parameter),
                timezone, adminDays, claimDays, executeDays, optional(body.get("notes"), 2000),
                now, now, actor.id(), actor.id());
        long receiptId = lastId();
        insertItems(receiptId, resolved, now);
        numbers.markUsed(allocation.id());
        audit(actor, "INBOUND_DRAFT_CREATED", receiptId, null, 0L, now);
        Map<String, Object> result = receipt(receiptId);
        record(actor, "createInboundReceipt", key, digest, 201, result, receiptId);
        return result;
    }

    @Transactional
    Map<String, Object> update(Actor actor, long receiptId, long version, String key,
            Map<String, Object> body) {
        String digest = digest("update:" + receiptId + ":" + version, body);
        Map<String, Object> prior = replay(actor, "updateInboundReceipt", key, digest);
        if (prior != null) { return prior; }
        Map<String, Object> header = lockedReceipt(receiptId);
        creatorDraft(actor, header, version);
        if (body.isEmpty()) { throw InboundFailure.invalid("INBOUND_UPDATE_EMPTY"); }
        Instant now = now();
        LocalDate date = body.containsKey("businessDate") ? date(body.get("businessDate"))
                : localDate(header.get("business_date"));
        String notes = body.containsKey("notes") ? optional(body.get("notes"), 2000)
                : text(header, "notes");
        if (body.containsKey("items") || body.containsKey("defaultTargetWarehouse")
                || body.containsKey("defaultTargetLocation")) {
            List<Map<String, Object>> inputs = items(body);
            List<Map<String, Object>> resolved = resolve(actor, inputs, body);
            db.update("DELETE FROM fams_inbound_receipt_item WHERE receipt_id = ?", receiptId);
            insertItems(receiptId, resolved, now);
        }
        db.update("UPDATE fams_inbound_receipt SET business_date=?,notes=?,version=version+1,"
                + "updated_at=?,updated_by=? WHERE id=?", date, notes, now, actor.id(), receiptId);
        audit(actor, "INBOUND_DRAFT_UPDATED", receiptId, version, version + 1, now);
        Map<String, Object> result = receipt(receiptId);
        record(actor, "updateInboundReceipt", key, digest, 200, result, receiptId);
        return result;
    }

    @Transactional
    Map<String, Object> submit(Actor actor, long receiptId, long version, String key) {
        String digest = digest("submit:" + receiptId + ":" + version, Map.of());
        Map<String, Object> prior = replay(actor, "submitInboundReceipt", key, digest);
        if (prior != null) { return prior; }
        Map<String, Object> header = lockedReceipt(receiptId);
        creatorDraft(actor, header, version);
        List<Map<String, Object>> lines = lines(receiptId);
        if (lines.isEmpty()) { throw InboundFailure.invalid("INBOUND_ASSET_INCOMPLETE"); }
        Instant now = now();
        for (Map<String, Object> line : lines.stream()
                .sorted(Comparator.comparingLong(item -> number(item, "asset_id"))).toList()) {
            Map<String, Object> asset = asset(number(line, "asset_id"), true);
            eligible(actor, asset, number(line, "asset_version_snapshot"));
            if (asset.get("category_id") == null || asset.get("model") == null
                    || asset.get("unit") == null || asset.get("owning_organization_id") == null) {
                throw InboundFailure.conflict("INBOUND_ASSET_INCOMPLETE");
            }
            activeReference("fams_asset_category", number(asset, "category_id"));
            activeReference("fams_organization", number(asset, "owning_organization_id"));
            target(number(line, "target_warehouse_id"), number(line, "warehouse_version_snapshot"),
                    number(line, "target_location_id"), number(line, "location_version_snapshot"));
            db.update("INSERT INTO fams_asset_business_lock (asset_id,business_type,business_id,"
                    + "business_item_id,lock_version,acquired_at) VALUES (?,'INBOUND',?,?,0,?)",
                    number(line, "asset_id"), receiptId, id(line), now);
            if (db.update("UPDATE fams_asset SET card_stage='PENDING_INBOUND',version=version+1,"
                    + "updated_at=?,updated_by=? WHERE id=? AND version=? AND card_stage='DRAFT'",
                    now, actor.id(), number(line, "asset_id"), number(asset, "version")) != 1) {
                throw InboundFailure.conflict("INBOUND_ASSET_VERSION_CONFLICT");
            }
        }
        Instant due = due(now, number(header, "parameter_version_id"), text(header, "timezone_snapshot"),
                number(header, "admin_decision_deadline_workdays"));
        transition(receiptId, version, "PENDING_ADMIN_DECISION", now, actor,
                "submitted_at=?,admin_decision_due_at=?", now, due);
        todo(receiptId, "ASSET_ADMIN", "ADMIN_DECISION", now);
        audit(actor, "INBOUND_SUBMITTED", receiptId, version, version + 1, now);
        Map<String, Object> result = receipt(receiptId);
        record(actor, "submitInboundReceipt", key, digest, 200, result, receiptId);
        return result;
    }

    @Transactional
    Map<String, Object> cancel(Actor actor, long receiptId, long version, String key,
            Map<String, Object> body) {
        String digest = digest("cancel:" + receiptId + ":" + version, body);
        Map<String, Object> prior = replay(actor, "cancelInboundReceipt", key, digest);
        if (prior != null) { return prior; }
        Map<String, Object> header = lockedReceipt(receiptId);
        if (number(header, "creator_account_id") != actor.id()) {
            throw InboundFailure.conflict("INBOUND_NOT_CREATOR"); }
        checkVersion(header, version);
        String status = text(header, "receipt_status");
        if (!"DRAFT".equals(status) && !"PENDING_ADMIN_DECISION".equals(status)) {
            throw InboundFailure.conflict("INBOUND_CANCELLATION_NOT_ALLOWED"); }
        String reason = required(body.get("reason"), 500, "INBOUND_CANCEL_REASON_REQUIRED");
        Instant now = now();
        if ("PENDING_ADMIN_DECISION".equals(status)) {
            releaseAll(receiptId, actor, now, "CANCELLED");
            closeTodo(receiptId, "ADMIN_DECISION", now);
        }
        transition(receiptId, version, "CANCELLED", now, actor,
                "cancelled_at=?,cancellation_reason=?", now, reason);
        audit(actor, "INBOUND_CANCELLED_BEFORE_DECISION", receiptId, version, version + 1, now);
        Map<String, Object> result = receipt(receiptId);
        record(actor, "cancelInboundReceipt", key, digest, 200, result, receiptId);
        return result;
    }

    @Transactional
    Map<String, Object> decide(Actor actor, long receiptId, long version, String key,
            Map<String, Object> body) {
        String digest = digest("decision:" + receiptId + ":" + version, body);
        Map<String, Object> prior = replay(actor, "decideInboundReceipt", key, digest);
        if (prior != null) { return prior; }
        Map<String, Object> header = lockedReceipt(receiptId);
        checkVersion(header, version);
        if (!"PENDING_ADMIN_DECISION".equals(text(header, "receipt_status"))) {
            throw InboundFailure.conflict("INBOUND_NOT_PENDING_DECISION"); }
        String decision = required(body.get("decision"), 20, "INBOUND_DECISION_INVALID");
        if (!List.of("APPROVED", "REJECTED").contains(decision)) {
            throw InboundFailure.invalid("INBOUND_DECISION_INVALID"); }
        String comment = "REJECTED".equals(decision)
                ? required(body.get("reason"), 1000, "INBOUND_DECISION_REASON_REQUIRED")
                : optional(body.get("comment"), 1000);
        Instant now = now();
        db.update("INSERT INTO fams_inbound_decision (receipt_id,decision_result,comment_text,"
                + "decided_by_account_id,decided_by_username_snapshot,decided_role,"
                + "receipt_version_before,decided_at) VALUES (?,?,?,?,?,'ASSET_ADMIN',?,?)",
                receiptId, decision, comment, actor.id(), actor.username(), version, now);
        long decisionId = lastId();
        for (Map<String, Object> line : lines(receiptId)) {
            db.update("INSERT INTO fams_inbound_approval_scope_item (decision_id,receipt_item_id,"
                    + "approved_asset_id,approved_asset_version,approved_warehouse_id,"
                    + "approved_warehouse_version,approved_location_id,approved_location_version) "
                    + "VALUES (?,?,?,?,?,?,?,?)", decisionId, id(line), number(line, "asset_id"),
                    number(line, "asset_version_snapshot"), number(line, "target_warehouse_id"),
                    number(line, "warehouse_version_snapshot"), number(line, "target_location_id"),
                    number(line, "location_version_snapshot"));
        }
        closeTodo(receiptId, "ADMIN_DECISION", now);
        if ("APPROVED".equals(decision)) {
            Instant due = due(now, number(header, "parameter_version_id"), text(header, "timezone_snapshot"),
                    number(header, "warehouse_claim_deadline_workdays"));
            transition(receiptId, version, "WAITING_WAREHOUSE_CLAIM", now, actor,
                    "warehouse_claim_due_at=?", due);
            todo(receiptId, "WAREHOUSE_OPERATOR", "WAREHOUSE_CLAIM", now);
        } else {
            releaseAll(receiptId, actor, now, "REJECTED");
            transition(receiptId, version, "REJECTED", now, actor, null);
        }
        audit(actor, "INBOUND_ADMIN_DECIDED", receiptId, version, version + 1, now);
        Map<String, Object> result = receipt(receiptId);
        record(actor, "decideInboundReceipt", key, digest, 200, result, receiptId);
        return result;
    }

    @Transactional
    Map<String, Object> claim(Actor actor, long receiptId, long version, String key) {
        String digest = digest("claim:" + receiptId + ":" + version, Map.of());
        Map<String, Object> prior = replay(actor, "claimInboundReceipt", key, digest);
        if (prior != null) { return prior; }
        Map<String, Object> header = lockedReceipt(receiptId);
        checkVersion(header, version);
        if (!"WAITING_WAREHOUSE_CLAIM".equals(text(header, "receipt_status"))) {
            throw InboundFailure.conflict("INBOUND_NOT_APPROVED"); }
        Instant now = now();
        db.update("INSERT INTO fams_inbound_processing_ownership (receipt_id,processor_account_id,"
                + "processor_username_snapshot,ownership_version,acquired_at) VALUES (?,?,?,0,?)",
                receiptId, actor.id(), actor.username(), now);
        closeTodo(receiptId, "WAREHOUSE_CLAIM", now);
        transition(receiptId, version, "WAREHOUSE_PROCESSING", now, actor,
                "warehouse_first_execution_due_at=?", due(now, number(header, "parameter_version_id"),
                        text(header, "timezone_snapshot"),
                        number(header, "warehouse_first_execution_deadline_workdays")));
        todo(receiptId, "WAREHOUSE_OPERATOR", "WAREHOUSE_EXECUTION", now);
        audit(actor, "INBOUND_WAREHOUSE_CLAIMED", receiptId, version, version + 1, now);
        Map<String, Object> result = receipt(receiptId);
        record(actor, "claimInboundReceipt", key, digest, 200, result, receiptId);
        return result;
    }

    @Transactional
    Map<String, Object> accept(Actor actor, long receiptId, long itemId, long version,
            String key, Map<String, Object> body) {
        String digest = digest("accept:" + receiptId + ":" + itemId + ":" + version, body);
        Map<String, Object> prior = replay(actor, "acceptInboundReceiptItem", key, digest);
        if (prior != null) { return prior; }
        Map<String, Object> header = lockedReceipt(receiptId);
        checkVersion(header, version);
        if (!"WAREHOUSE_PROCESSING".equals(text(header, "receipt_status"))) {
            throw InboundFailure.conflict("INBOUND_NOT_PROCESSING"); }
        Map<String, Object> owner = one("SELECT * FROM fams_inbound_processing_ownership "
                + "WHERE receipt_id=? AND archived_at IS NULL FOR UPDATE", receiptId);
        if (owner == null || number(owner, "processor_account_id") != actor.id()) {
            throw InboundFailure.conflict("INBOUND_NOT_CURRENT_PROCESSOR"); }
        Map<String, Object> line = one("SELECT * FROM fams_inbound_receipt_item WHERE id=? "
                + "AND receipt_id=? FOR UPDATE", itemId, receiptId);
        if (line == null) { throw InboundFailure.missing(); }
        if (!"PENDING".equals(text(line, "execution_status"))) {
            throw InboundFailure.conflict("INBOUND_ITEM_ALREADY_ACCEPTED"); }
        Map<String, Object> scope = one("SELECT s.* FROM fams_inbound_approval_scope_item s "
                + "JOIN fams_inbound_decision d ON d.id=s.decision_id "
                + "WHERE d.receipt_id=? AND d.decision_result='APPROVED' AND s.receipt_item_id=?",
                receiptId, itemId);
        if (scope == null || number(scope, "approved_asset_id") != number(line, "asset_id")
                || number(scope, "approved_warehouse_id") != number(line, "target_warehouse_id")
                || number(scope, "approved_location_id") != number(line, "target_location_id")) {
            throw InboundFailure.conflict("INBOUND_APPROVED_SCOPE_MISMATCH"); }
        Map<String, Object> asset = asset(number(line, "asset_id"), true);
        long assetVersion = longValue(body.get("assetVersion"));
        if (number(asset, "version") != assetVersion
                || !"PENDING_INBOUND".equals(text(asset, "card_stage"))
                || !"WAITING_INBOUND".equals(text(asset, "lifecycle_status"))) {
            throw InboundFailure.conflict("INBOUND_ASSET_STATE_CONFLICT"); }
        String observed = required(body.get("observedAssetCode"), 128,
                "INBOUND_OBSERVED_ASSET_CODE_MISMATCH");
        if (!observed.equals(text(asset, "asset_code"))) {
            throw InboundFailure.conflict("INBOUND_OBSERVED_ASSET_CODE_MISMATCH"); }
        Map<String, Object> lock = one("SELECT * FROM fams_asset_business_lock WHERE asset_id=? "
                + "AND business_type='INBOUND' AND business_id=? AND business_item_id=? "
                + "AND released_at IS NULL FOR UPDATE", number(line, "asset_id"), receiptId, itemId);
        if (lock == null || number(lock, "lock_version") != longValue(body.get("lockVersion"))) {
            throw InboundFailure.conflict("INBOUND_ASSET_LOCK_CONFLICT"); }
        Map<String, Object> targets = target(number(line, "target_warehouse_id"),
                number(scope, "approved_warehouse_version"), number(line, "target_location_id"),
                number(scope, "approved_location_version"));
        Instant now = now();
        if (db.update("UPDATE fams_asset SET lifecycle_status='IN_STOCK',card_stage='ACTIVE',"
                + "custody_type='STORAGE',storage_warehouse_id=?,current_location_id=?,"
                + "inbound_business_date=?,version=version+1,updated_at=?,updated_by=? "
                + "WHERE id=? AND version=? AND card_stage='PENDING_INBOUND'",
                number(line, "target_warehouse_id"), number(line, "target_location_id"),
                header.get("business_date"), now, actor.id(), number(line, "asset_id"), assetVersion) != 1) {
            throw InboundFailure.conflict("INBOUND_ASSET_STATE_CONFLICT"); }
        db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='ACCEPTED' "
                + "WHERE id=? AND released_at IS NULL", now, id(lock));
        db.update("UPDATE fams_inbound_receipt_item SET execution_status='ACCEPTED',"
                + "version=version+1,updated_at=? WHERE id=?", now, itemId);
        db.update("UPDATE fams_inbound_processing_ownership SET ownership_version=ownership_version+1 "
                + "WHERE id=?", id(owner));
        long auditId = audit(actor, "INBOUND_ITEM_ACCEPTED", receiptId, version, version + 1, now);
        db.update("INSERT INTO fams_inbound_acceptance (receipt_item_id,asset_id,ownership_id,"
                + "accepted_by_account_id,accepted_by_username_snapshot,accepted_at,business_date,"
                + "receipt_version_before,asset_version_before,asset_version_after,lock_version,"
                + "observed_asset_code,storage_warehouse_id,storage_location_id,"
                + "warehouse_version_at_acceptance,location_version_at_acceptance,notes,audit_event_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", itemId, number(line, "asset_id"),
                id(owner), actor.id(), actor.username(), now, header.get("business_date"), version,
                assetVersion, assetVersion + 1, number(lock, "lock_version"), observed,
                number(line, "target_warehouse_id"), number(line, "target_location_id"),
                number(targets, "warehouse_version"), number(targets, "location_version"),
                optional(body.get("notes"), 1000), auditId);
        long pending = Objects.requireNonNull(db.queryForObject(
                "SELECT COUNT(*) FROM fams_inbound_receipt_item "
                + "WHERE receipt_id=? AND execution_status='PENDING'", Long.class, receiptId));
        boolean complete = pending == 0;
        if (complete) {
            transition(receiptId, version, "COMPLETED", now, actor, "completed_at=?", now);
            db.update("UPDATE fams_inbound_processing_ownership SET archived_at=?,"
                    + "archive_reason='COMPLETED' WHERE id=?", now, id(owner));
            closeTodo(receiptId, "WAREHOUSE_EXECUTION", now);
        } else { transition(receiptId, version, "WAREHOUSE_PROCESSING", now, actor, null); }
        Map<String, Object> result = new HashMap<>();
        result.put("receipt", receipt(receiptId));
        result.put("item", item(itemId));
        result.put("asset", acceptedAsset(number(line, "asset_id")));
        record(actor, "acceptInboundReceiptItem", key, digest, 200, result, receiptId);
        return result;
    }

    Map<String, Object> get(Actor actor, long receiptId) {
        Map<String, Object> header = one("SELECT * FROM fams_inbound_receipt WHERE id=?", receiptId);
        if (header == null) { throw InboundFailure.missing(); }
        String status = text(header, "receipt_status");
        if ("WAREHOUSE_OPERATOR".equals(actor.role())) {
            if (number(header, "creator_account_id") != actor.id()
                    && !List.of("WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "COMPLETED")
                            .contains(status)) { throw InboundFailure.missing(); }
        } else if (header.get("submitted_at") == null) { throw InboundFailure.missing(); }
        return receipt(receiptId);
    }

    private Map<String, Object> receipt(long receiptId) {
        Map<String, Object> row = one("SELECT * FROM fams_inbound_receipt WHERE id=?", receiptId);
        if (row == null) { throw InboundFailure.missing(); }
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.toString(id(row)));
        result.put("receiptNumber", text(row, "receipt_no"));
        result.put("status", text(row, "receipt_status"));
        result.put("businessDate", localDate(row.get("business_date")).toString());
        result.put("createdBy", identity(number(row, "creator_account_id"),
                text(row, "creator_username_snapshot")));
        result.put("createdAt", instant(row.get("created_at")).toString());
        result.put("version", number(row, "version"));
        result.put("parameterVersionId", Long.toString(number(row, "parameter_version_id")));
        result.put("snapshot", Map.of("parameterVersionId", result.get("parameterVersionId"),
                "businessDate", result.get("businessDate"), "timezone", text(row, "timezone_snapshot"),
                "adminDecisionDeadlineWorkdays", number(row, "admin_decision_deadline_workdays"),
                "warehouseClaimDeadlineWorkdays", number(row, "warehouse_claim_deadline_workdays"),
                "warehouseFirstExecutionDeadlineWorkdays",
                number(row, "warehouse_first_execution_deadline_workdays")));
        result.put("items", lines(receiptId).stream().map(this::item).toList());
        putTime(result, row, "submittedAt", "submitted_at");
        putTime(result, row, "adminDecisionDueAt", "admin_decision_due_at");
        putTime(result, row, "warehouseClaimDueAt", "warehouse_claim_due_at");
        putTime(result, row, "warehouseFirstExecutionDueAt", "warehouse_first_execution_due_at");
        putTime(result, row, "cancelledAt", "cancelled_at");
        putTime(result, row, "completedAt", "completed_at");
        putOptional(result, row, "notes", "notes");
        putOptional(result, row, "cancellationReason", "cancellation_reason");
        Map<String, Object> decision = one("SELECT * FROM fams_inbound_decision WHERE receipt_id=?", receiptId);
        if (decision != null) {
            List<Map<String, Object>> scope = db.queryForList("SELECT * FROM fams_inbound_approval_scope_item "
                    + "WHERE decision_id=? ORDER BY receipt_item_id", id(decision));
            Map<String, Object> decisionView = new HashMap<>();
            decisionView.put("id", Long.toString(id(decision)));
            decisionView.put("result", text(decision, "decision_result"));
            decisionView.put("decidedReceiptVersion", number(decision, "receipt_version_before"));
            decisionView.put("decidedBy", identity(number(decision, "decided_by_account_id"),
                    text(decision, "decided_by_username_snapshot")));
            decisionView.put("decidedAt", instant(decision.get("decided_at")).toString());
            putOptional(decisionView, decision, "comment", "comment_text");
            decisionView.put("scope", Map.of("receiptVersion", number(decision, "receipt_version_before"),
                    "items", scope.stream().map(this::scope).toList()));
            result.put("decision", decisionView);
        }
        Map<String, Object> owner = one("SELECT * FROM fams_inbound_processing_ownership "
                + "WHERE receipt_id=? ORDER BY id DESC LIMIT 1", receiptId);
        if (owner != null) {
            Map<String, Object> view = new HashMap<>();
            view.put("processor", identity(number(owner, "processor_account_id"),
                    text(owner, "processor_username_snapshot")));
            view.put("claimedAt", instant(owner.get("acquired_at")).toString());
            view.put("ownershipVersion", number(owner, "ownership_version"));
            putTime(view, owner, "archivedAt", "archived_at");
            result.put("currentProcessor", view);
        }
        return result;
    }

    private Map<String, Object> item(long itemId) {
        Map<String, Object> row = one("SELECT * FROM fams_inbound_receipt_item WHERE id=?", itemId);
        return item(row);
    }

    private Map<String, Object> item(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.toString(id(row)));
        result.put("executionStatus", text(row, "execution_status"));
        result.put("asset", ref(number(row, "asset_id"), number(row, "asset_version_snapshot")));
        result.put("assetSnapshot", Map.of("assetCode", text(row, "asset_code_snapshot"),
                "assetName", text(row, "asset_name_snapshot")));
        result.put("targetWarehouse", ref(number(row, "target_warehouse_id"),
                number(row, "warehouse_version_snapshot")));
        result.put("targetLocation", ref(number(row, "target_location_id"),
                number(row, "location_version_snapshot")));
        result.put("warehouseSnapshot", Map.of("code", text(row, "warehouse_code_snapshot"),
                "name", text(row, "warehouse_name_snapshot")));
        result.put("locationSnapshot", Map.of("code", text(row, "location_code_snapshot"),
                "name", text(row, "location_name_snapshot")));
        Map<String, Object> lock = one("SELECT * FROM fams_asset_business_lock "
                + "WHERE business_type='INBOUND' AND business_item_id=? ORDER BY id DESC LIMIT 1", id(row));
        if (lock != null) {
            result.put("businessLock", Map.of("assetId", Long.toString(number(lock, "asset_id")),
                    "businessType", "INBOUND", "businessId", Long.toString(number(lock, "business_id")),
                    "businessItemId", Long.toString(number(lock, "business_item_id")),
                    "lockVersion", number(lock, "lock_version"),
                    "acquiredAt", instant(lock.get("acquired_at")).toString()));
        }
        Map<String, Object> acceptance = one("SELECT * FROM fams_inbound_acceptance "
                + "WHERE receipt_item_id=?", id(row));
        if (acceptance != null) { result.put("acceptance", acceptance(acceptance)); }
        return result;
    }

    private Map<String, Object> scope(Map<String, Object> row) {
        return Map.of("receiptItemId", Long.toString(number(row, "receipt_item_id")),
                "asset", ref(number(row, "approved_asset_id"), number(row, "approved_asset_version")),
                "targetWarehouse", ref(number(row, "approved_warehouse_id"),
                        number(row, "approved_warehouse_version")),
                "targetLocation", ref(number(row, "approved_location_id"),
                        number(row, "approved_location_version")));
    }

    private Map<String, Object> acceptance(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.toString(id(row)));
        result.put("receiptItemId", Long.toString(number(row, "receipt_item_id")));
        result.put("assetId", Long.toString(number(row, "asset_id")));
        result.put("acceptedBy", identity(number(row, "accepted_by_account_id"),
                text(row, "accepted_by_username_snapshot")));
        result.put("acceptedAt", instant(row.get("accepted_at")).toString());
        result.put("businessDate", localDate(row.get("business_date")).toString());
        for (String field : List.of("receiptVersionBefore", "assetVersionBefore",
                "assetVersionAfter", "lockVersion")) {
            result.put(field, number(row, field.replaceAll("([A-Z])", "_$1").toLowerCase()));
        }
        result.put("observedAssetCode", text(row, "observed_asset_code"));
        result.put("storageCustody", storage(number(row, "storage_warehouse_id"),
                number(row, "warehouse_version_at_acceptance"),
                number(row, "storage_location_id"), number(row, "location_version_at_acceptance")));
        putOptional(result, row, "notes", "notes");
        return result;
    }

    private Map<String, Object> acceptedAsset(long assetId) {
        Map<String, Object> asset = asset(assetId, false);
        Map<String, Object> warehouse = one("SELECT version FROM fams_warehouse WHERE id=?",
                number(asset, "storage_warehouse_id"));
        Map<String, Object> location = one("SELECT version FROM fams_location WHERE id=?",
                number(asset, "current_location_id"));
        return Map.of("id", Long.toString(assetId), "assetCode", text(asset, "asset_code"),
                "lifecycle", text(asset, "lifecycle_status"),
                "exception", text(asset, "exception_status"),
                "cardStage", text(asset, "card_stage"), "version", number(asset, "version"),
                "currentCustody", storage(number(asset, "storage_warehouse_id"),
                        number(warehouse, "version"), number(asset, "current_location_id"),
                        number(location, "version")),
                "inboundBusinessDate", localDate(asset.get("inbound_business_date")).toString());
    }

    private Map<String, Object> storage(long warehouse, long warehouseVersion,
            long location, long locationVersion) {
        return Map.of("custodyType", "STORAGE", "warehouse", ref(warehouse, warehouseVersion),
                "location", ref(location, locationVersion));
    }

    private List<Map<String, Object>> resolve(Actor actor, List<Map<String, Object>> inputs,
            Map<String, Object> body) {
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> input : inputs) {
            Map<String, Object> selected = object(input.get("asset"));
            long assetId = longValue(selected.get("id"));
            if (!seen.add(assetId)) { throw InboundFailure.conflict("INBOUND_DUPLICATE_ASSET"); }
            Map<String, Object> asset = asset(assetId, true);
            eligible(actor, asset, longValue(selected.get("version")));
            Map<String, Object> warehouse = object(input.getOrDefault("targetWarehouse",
                    body.get("defaultTargetWarehouse")));
            Map<String, Object> location = object(input.getOrDefault("targetLocation",
                    body.get("defaultTargetLocation")));
            Map<String, Object> target = target(longValue(warehouse.get("id")),
                    longValue(warehouse.get("version")), longValue(location.get("id")),
                    longValue(location.get("version")));
            Map<String, Object> row = new HashMap<>();
            row.put("asset", asset);
            row.putAll(target);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> target(long warehouseId, long warehouseVersion,
            long locationId, long locationVersion) {
        Map<String, Object> warehouse = one("SELECT id,version,status,code,name FROM fams_warehouse "
                + "WHERE id=? FOR UPDATE", warehouseId);
        Map<String, Object> location = one("SELECT id,version,status,warehouse_id,code,name "
                + "FROM fams_location WHERE id=? FOR UPDATE", locationId);
        if (warehouse == null || location == null || !"ACTIVE".equals(text(warehouse, "status"))
                || !"ACTIVE".equals(text(location, "status"))) {
            throw InboundFailure.conflict("INBOUND_MASTER_DATA_DISABLED"); }
        if (number(warehouse, "version") != warehouseVersion
                || number(location, "version") != locationVersion) {
            throw InboundFailure.conflict("INBOUND_REFERENCE_VERSION_CONFLICT"); }
        if (number(location, "warehouse_id") != warehouseId) {
            throw InboundFailure.conflict("INBOUND_WAREHOUSE_LOCATION_MISMATCH"); }
        return Map.of("warehouse", warehouse, "location", location,
                "warehouse_version", warehouseVersion, "location_version", locationVersion);
    }

    private void eligible(Actor actor, Map<String, Object> asset, long version) {
        if (asset == null) { throw InboundFailure.conflict("INBOUND_ASSET_NOT_FOUND"); }
        if (number(asset, "creator_account_id") != actor.id()) {
            throw InboundFailure.conflict("INBOUND_ASSET_NOT_CREATOR"); }
        if (number(asset, "version") != version) {
            throw InboundFailure.conflict("INBOUND_ASSET_VERSION_CONFLICT"); }
        if (!"DRAFT".equals(text(asset, "card_stage"))
                || !"WAITING_INBOUND".equals(text(asset, "lifecycle_status"))
                || !"NORMAL".equals(text(asset, "exception_status"))) {
            throw InboundFailure.conflict("INBOUND_ASSET_NOT_DRAFT"); }
        if (one("SELECT id FROM fams_asset_business_lock WHERE asset_id=? "
                + "AND released_at IS NULL", id(asset)) != null) {
            throw InboundFailure.conflict("INBOUND_ASSET_LOCKED"); }
    }

    private void insertItems(long receiptId, List<Map<String, Object>> resolved, Instant now) {
        for (Map<String, Object> row : resolved) {
            Map<String, Object> asset = object(row.get("asset"));
            Map<String, Object> warehouse = object(row.get("warehouse"));
            Map<String, Object> location = object(row.get("location"));
            db.update("INSERT INTO fams_inbound_receipt_item (receipt_id,asset_id,"
                    + "asset_version_snapshot,asset_code_snapshot,asset_name_snapshot,"
                    + "target_warehouse_id,warehouse_version_snapshot,warehouse_code_snapshot,"
                    + "warehouse_name_snapshot,target_location_id,location_version_snapshot,"
                    + "location_code_snapshot,location_name_snapshot,execution_status,version,"
                    + "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)",
                    receiptId, id(asset), number(asset, "version"), text(asset, "asset_code"),
                    text(asset, "name"), id(warehouse), number(warehouse, "version"),
                    text(warehouse, "code"), text(warehouse, "name"), id(location),
                    number(location, "version"), text(location, "code"), text(location, "name"),
                    now, now);
        }
    }

    private void releaseAll(long receiptId, Actor actor, Instant now, String reason) {
        for (Map<String, Object> line : lines(receiptId).stream()
                .sorted(Comparator.comparingLong(item -> number(item, "asset_id"))).toList()) {
            long assetId = number(line, "asset_id");
            if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason=? "
                    + "WHERE asset_id=? AND business_type='INBOUND' AND business_id=? "
                    + "AND business_item_id=? AND released_at IS NULL",
                    now, reason, assetId, receiptId, id(line)) != 1) {
                throw InboundFailure.conflict("INBOUND_LOCK_RELEASE_CONFLICT"); }
            if (db.update("UPDATE fams_asset SET card_stage='DRAFT',version=version+1,"
                    + "updated_at=?,updated_by=? WHERE id=? AND card_stage='PENDING_INBOUND'",
                    now, actor.id(), assetId) != 1) {
                throw InboundFailure.conflict("INBOUND_ASSET_STATE_CONFLICT"); }
        }
    }

    private void transition(long receiptId, long version, String status, Instant now,
            Actor actor, String extra, Object... values) {
        String update = "UPDATE fams_inbound_receipt SET receipt_status=?,version=version+1,"
                + "updated_at=?,updated_by=?" + (extra == null ? "" : "," + extra)
                + " WHERE id=? AND version=?";
        List<Object> args = new ArrayList<>(List.of(status, now, actor.id()));
        for (Object value : values) { args.add(value); }
        args.add(receiptId);
        args.add(version);
        if (db.update(update, args.toArray()) != 1) {
            throw InboundFailure.conflict("INBOUND_VERSION_CONFLICT"); }
    }

    private void todo(long receiptId, String pool, String type, Instant now) {
        db.update("INSERT INTO fams_todo (pool_type,assignee_account_id,business_type,"
                + "business_id,todo_type,todo_status,opened_at) "
                + "VALUES (?,NULL,'INBOUND',?,?,'OPEN',?)", pool, receiptId, type, now);
    }

    private void closeTodo(long receiptId, String type, Instant now) {
        if (db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? "
                + "WHERE business_type='INBOUND' AND business_id=? AND todo_type=? "
                + "AND closed_at IS NULL", now, receiptId, type) != 1) {
            throw InboundFailure.conflict("INBOUND_TODO_CONFLICT"); }
    }

    private long audit(Actor actor, String action, long receiptId, Long before,
            Long after, Instant now) {
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,"
                + "action,resource_type,resource_id,before_version,after_version,occurred_at,"
                + "detail_json) VALUES (?,?,?,?, 'INBOUND',?,?,?,?,?)", actor.requestId(),
                actor.id(), actor.role(), action, receiptId, before, after, now, "{}");
        return lastId();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replay(Actor actor, String operation, String key, String digest) {
        Map<String, Object> prior = one("SELECT payload_sha256,response_body_json "
                + "FROM fams_idempotency_record WHERE actor_account_id=? AND operation_id=? "
                + "AND idempotency_key=?", actor.id(), operation, key);
        if (prior == null) { return null; }
        if (!digest.equals(text(prior, "payload_sha256"))) {
            throw InboundFailure.conflict("INBOUND_IDEMPOTENCY_CONFLICT"); }
        return json.readValue(text(prior, "response_body_json"), Map.class);
    }

    private void record(Actor actor, String operation, String key, String digest,
            int status, Map<String, Object> response, long receiptId) {
        Instant now = now();
        db.update("INSERT INTO fams_idempotency_record (actor_account_id,operation_id,"
                + "idempotency_key,payload_sha256,result_status,http_status,response_body_json,"
                + "resource_type,resource_id,resource_version,created_at,completed_at,expires_at) "
                + "VALUES (?,?,?,?,'SUCCEEDED',?,?,'INBOUND',?,?,?,?,?)", actor.id(),
                operation, key, digest, status, json.writeValueAsString(response), receiptId,
                number(one("SELECT version FROM fams_inbound_receipt WHERE id=?", receiptId), "version"),
                now, now, now.plus(24, ChronoUnit.HOURS));
    }

    private String digest(String operation, Object payload) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    (operation + ":" + json.writeValueAsString(payload)).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private Map<String, Object> lockedReceipt(long receiptId) {
        Map<String, Object> row = one("SELECT * FROM fams_inbound_receipt WHERE id=? FOR UPDATE", receiptId);
        if (row == null) { throw InboundFailure.missing(); }
        return row;
    }

    private void creatorDraft(Actor actor, Map<String, Object> row, long version) {
        if (number(row, "creator_account_id") != actor.id()) {
            throw InboundFailure.conflict("INBOUND_NOT_CREATOR"); }
        checkVersion(row, version);
        if (!"DRAFT".equals(text(row, "receipt_status"))) {
            throw InboundFailure.conflict("INBOUND_NOT_DRAFT"); }
    }

    private void checkVersion(Map<String, Object> row, long version) {
        if (number(row, "version") != version) {
            throw InboundFailure.conflict("INBOUND_VERSION_CONFLICT"); }
    }

    private Map<String, Object> asset(long assetId, boolean lock) {
        return one("SELECT * FROM fams_asset WHERE id=?" + (lock ? " FOR UPDATE" : ""), assetId);
    }

    private void activeReference(String table, long id) {
        Map<String, Object> row = one("SELECT status FROM " + table + " WHERE id=? FOR UPDATE", id);
        if (row == null || !"ACTIVE".equals(text(row, "status"))) {
            throw InboundFailure.conflict("INBOUND_MASTER_DATA_DISABLED"); }
    }

    private List<Map<String, Object>> lines(long receiptId) {
        return db.queryForList("SELECT * FROM fams_inbound_receipt_item WHERE receipt_id=? "
                + "ORDER BY id", receiptId);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long lastId() {
        return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    private Instant due(Instant now, long parameterVersionId, String timezone, long workdays) {
        Map<String, Object> version = one("SELECT snapshot_json FROM fams_business_parameter_version "
                + "WHERE id=?", parameterVersionId);
        if (version == null) { throw InboundFailure.conflict("INBOUND_PARAMETERS_UNAVAILABLE"); }
        JsonNode snapshot = json.readTree(text(version, "snapshot_json"));
        LocalDate date = LocalDate.ofInstant(now, ZoneId.of(timezone));
        for (long index = 0; index < workdays; ) {
            date = date.plusDays(1);
            boolean adjusted = containsDate(snapshot.path("adjustedWorkdays"), date);
            boolean holiday = containsDate(snapshot.path("holidays"), date);
            boolean weekday = containsDay(snapshot.path("workWeek"), date);
            if (adjusted || (weekday && !holiday)) { index++; }
        }
        return date.plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant();
    }

    private boolean containsDate(JsonNode values, LocalDate date) {
        if (!values.isArray()) { return false; }
        for (JsonNode value : values) {
            if (date.toString().equals(value.asText())) { return true; }
        }
        return false;
    }

    private boolean containsDay(JsonNode values, LocalDate date) {
        if (!values.isArray()) { return false; }
        for (JsonNode value : values) {
            if (date.getDayOfWeek().name().equals(value.asText())) { return true; }
        }
        return false;
    }

    private int days(JsonNode node, String name) {
        int value = node.path(name).asInt();
        if (value < 1 || value > 30) { throw InboundFailure.conflict("INBOUND_PARAMETERS_UNAVAILABLE"); }
        return value;
    }

    private List<Map<String, Object>> items(Map<String, Object> body) {
        Object value = body.get("items");
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 500) {
            throw InboundFailure.invalid("INBOUND_ITEMS_INVALID"); }
        return list.stream().map(this::object).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw InboundFailure.invalid("INBOUND_REQUEST_INVALID"); }
        return (Map<String, Object>) map;
    }

    private LocalDate date(Object value) {
        try { return LocalDate.parse(value.toString()); }
        catch (RuntimeException error) { throw InboundFailure.invalid("INBOUND_DATE_INVALID"); }
    }

    private LocalDate localDate(Object value) {
        if (value instanceof Date date) { return date.toLocalDate(); }
        if (value instanceof LocalDate date) { return date; }
        return LocalDate.parse(value.toString());
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp stamp) { return stamp.toInstant(); }
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime.toInstant(java.time.ZoneOffset.UTC); }
        return Instant.parse(value.toString());
    }

    private long longValue(Object value) {
        try {
            long result = Long.parseLong(value.toString());
            if (result >= 0) { return result; }
        } catch (RuntimeException ignored) { }
        throw InboundFailure.invalid("INBOUND_REFERENCE_INVALID");
    }

    private long id(Map<String, Object> row) { return number(row, "id"); }
    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private String required(Object value, int max, String code) {
        if (!(value instanceof String text) || text.isBlank() || text.strip().length() > max) {
            throw InboundFailure.invalid(code); }
        return text.strip();
    }

    private String optional(Object value, int max) {
        if (value == null) { return null; }
        if (!(value instanceof String text) || text.length() > max) {
            throw InboundFailure.invalid("INBOUND_FIELD_INVALID"); }
        return text.strip();
    }

    private Map<String, Object> identity(long accountId, String username) {
        return Map.of("id", Long.toString(accountId), "username", username);
    }

    private Map<String, Object> ref(long id, long version) {
        return Map.of("id", Long.toString(id), "version", version);
    }

    private void putTime(Map<String, Object> target, Map<String, Object> row,
            String jsonKey, String column) {
        if (row.get(column) != null) { target.put(jsonKey, instant(row.get(column)).toString()); }
    }

    private void putOptional(Map<String, Object> target, Map<String, Object> row,
            String jsonKey, String column) {
        if (row.get(column) != null) { target.put(jsonKey, text(row, column)); }
    }
}
