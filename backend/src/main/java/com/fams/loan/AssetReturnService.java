package com.fams.loan;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetReturnPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.business.ReturnLossOpeningPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class AssetReturnService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetReturnPort assets;
    private final AssetHandoverPort handover;
    private final LoanReminderService reminders;
    private final ObjectProvider<ReturnLossOpeningPort> lossReports;

    AssetReturnService(JdbcTemplate db, ObjectMapper json, Clock clock, RequisitionActorContext actors,
            AssetReturnPort assets, AssetHandoverPort handover, LoanReminderService reminders,
            ObjectProvider<ReturnLossOpeningPort> lossReports) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.handover = handover;
        this.reminders = reminders;
        this.lossReports = lossReports;
    }

    Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String scope = current.globalApplicant() ? "1=1" : "responsible_employee_id=" + current.employeeId()
                + (current.has(Role.DEPARTMENT_ASSET_MANAGER) ? " OR using_organization_id=" + current.organizationId() : "");
        var items = db.queryForList("SELECT * FROM fams_asset_return WHERE " + scope
                + " ORDER BY id DESC LIMIT ? OFFSET ?", size, (page - 1) * size);
        long total = java.util.Objects.requireNonNull(
                db.queryForObject("SELECT COUNT(*) FROM fams_asset_return WHERE " + scope, Long.class), "RETURN_COUNT_MISSING");
        var returnIds = items.stream().filter(item -> "TRANSFERRED".equals(item.get("return_status")))
                .map(item -> number(item, "id")).toList();
        var summaries = new java.util.HashMap<Long, ReturnLossOpeningPort.Summary>();
        if (!returnIds.isEmpty()) {
            var followups = new NamedParameterJdbcTemplate(db).queryForList(
                    "SELECT id,return_id FROM fams_return_followup WHERE business_type='LOST_REPORT' AND return_id IN (:ids)",
                    Map.of("ids", returnIds));
            var progress = lossReports.getObject().latest(followups.stream().map(row -> number(row, "id")).toList());
            for (var followup : followups) {
                summaries.put(number(followup, "return_id"), progress.get(number(followup, "id")));
            }
        }
        var results = items.stream().map(item -> {
            var result = view(item);
            var summary = summaries.get(number(item, "id"));
            if (summary != null) {
                result.put("returnLossReport", summary);
            }
            return result;
        }).toList();
        return Map.of("items", results, "total", total, "page", page, "pageSize", size);
    }

    Map<String, Object> catalog(Actor actor, String kind, int page, int size) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        String sql;
        if ("warehouse".equals(kind)) {
            sql = "SELECT id,name,version FROM fams_warehouse WHERE status='ACTIVE'";
        } else if ("location".equals(kind)) {
            sql = "SELECT l.id,l.name,l.version,l.warehouse_id FROM fams_location l JOIN fams_warehouse w"
                    + " ON w.id=l.warehouse_id WHERE l.status='ACTIVE' AND w.status='ACTIVE' AND l.location_type='BIN'";
        } else { throw new LoanFailure("RETURN_CATALOG_NOT_FOUND", 404); }
        var rows = db.queryForList(sql + " ORDER BY id LIMIT ? OFFSET ?", size, (page - 1) * size);
        return Map.of("items", rows.stream().map(this::view).toList(), "page", page, "pageSize", size);
    }

    Map<String, Object> eligible(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String scope = current.has(Role.ASSET_ADMIN) ? "1=1" :
                "COALESCE(a.responsible_employee_id,a.department_responsible_employee_id)=" + current.employeeId()
                + (current.has(Role.DEPARTMENT_ASSET_MANAGER) ? " OR a.using_organization_id=" + current.organizationId() : "");
        String from = " FROM fams_asset a WHERE a.card_stage='ACTIVE' AND a.exception_status='NORMAL'"
                + " AND a.lifecycle_status IN ('IN_USE','BORROWED') AND (" + scope + ")"
                + " AND NOT EXISTS (SELECT 1 FROM fams_asset_business_lock b WHERE b.asset_id=a.id AND b.released_at IS NULL)";
        var rows = db.queryForList("SELECT a.id,a.asset_code,a.name,a.version,a.lifecycle_status" + from
                + " ORDER BY a.id DESC LIMIT ? OFFSET ?", size, (page - 1) * size);
        long total = java.util.Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + from, Long.class), "RETURN_COUNT_MISSING");
        return Map.of("items", rows.stream().map(this::view).toList(), "total", total, "page", page, "pageSize", size);
    }

    Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = request(id);
        visible(current, row);
        var result = new LinkedHashMap<>(view(row));
        result.put("asset", view(one("SELECT id,asset_code,name,version,lifecycle_status,exception_status FROM fams_asset WHERE id=?",
                number(row, "asset_id"))));
        var locks = db.queryForList("SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=?"
                + " AND business_type='ASSET_RETURN' AND business_id=? AND released_at IS NULL", row.get("asset_id"), id);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        result.put("canReportMissing", !locks.isEmpty() && "WAREHOUSE_PROCESSING".equals(row.get("return_status"))
                && current.has(Role.WAREHOUSE_OPERATOR)
                && java.util.Objects.equals(current.id(), row.get("processor_account_id")));
        result.put("followups", db.queryForList("SELECT * FROM fams_return_followup WHERE return_id=?", id)
                .stream().map(followup -> followupView(current, followup)).toList());
        return result;
    }

    private Map<String, Object> followupView(Current current, Map<String, Object> row) {
        var result = view(row);
        if ("LOST_REPORT".equals(row.get("business_type"))) {
            long id = number(row, "id");
            var report = lossReports.getObject().latest(id);
            result.put("lossReport", report);
            if (report != null && report.recoveryReturnId() != null) {
                var acceptance = db.queryForList("SELECT return_status FROM fams_asset_return WHERE id=?",
                        String.class, Long.parseLong(report.recoveryReturnId()));
                if (!acceptance.isEmpty()) {
                    result.put("recoveryReturnStatus", acceptance.getFirst());
                }
            }
            if ("PENDING".equals(row.get("followup_status")) && current.has(Role.WAREHOUSE_OPERATOR)
                    && current.id() == number(row, "processor_account_id")) {
                var lockVersions = db.queryForList("SELECT lock_version FROM fams_asset_business_lock"
                        + " WHERE business_type='RETURN_LOST_REPORT' AND business_id=? AND released_at IS NULL",
                        Long.class, id);
                if (!lockVersions.isEmpty()) {
                    result.put("missingLossLockVersion", lockVersions.getFirst());
                    result.put("canRetryMissingLoss", true);
                }
            }
        }
        return result;
    }

    Map<String, Object> propose(Actor actor, String key, AssetReturnController.Proposal input) {
        var current = actors.revalidateLocked(actor);
        String hash = digest("propose", input);
        var replay = replay(current, "propose", key, hash);
        if (replay != null) { return replay; }
        var asset = one("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", input.assetId());
        if (asset.get("using_organization_id") == null) { throw new LoanFailure("RETURN_NOT_AVAILABLE", 409); }
        long owner = number(asset, "PERSONAL".equals(asset.get("custody_type"))
                ? "responsible_employee_id" : "department_responsible_employee_id");
        if (current.employeeId() != owner && !current.has(Role.ASSET_ADMIN)
                && !(current.has(Role.DEPARTMENT_ASSET_MANAGER)
                    && current.organizationId() == number(asset, "using_organization_id"))) {
            throw new LoanFailure("RETURN_FORBIDDEN", 403);
        }
        checkVersion(asset, input.assetVersion());
        if (!List.of("BORROWED", "IN_USE").contains(asset.get("lifecycle_status"))
                || !"NORMAL".equals(asset.get("exception_status")) || !"ACTIVE".equals(asset.get("card_stage"))) {
            throw new LoanFailure("RETURN_NOT_AVAILABLE", 409);
        }
        var loans = db.queryForList("SELECT * FROM fams_loan WHERE asset_id=? AND relation_status='OPEN' FOR UPDATE", input.assetId());
        if ("BORROWED".equals(asset.get("lifecycle_status")) && loans.size() != 1) {
            throw new LoanFailure("RETURN_LOAN_CHANGED", 409);
        }
        Long loan = loans.isEmpty() ? null : number(loans.getFirst(), "id");
        Object snapshot = one("SELECT snapshot_json FROM fams_business_parameter_version WHERE id=?",
                asset.get("parameter_version_id")).get("snapshot_json");
        db.update("INSERT INTO fams_asset_return (asset_id,loan_id,asset_version,responsible_employee_id,"
                + "using_organization_id,proposed_by,reason,parameter_snapshot_json,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                input.assetId(), loan, input.assetVersion(), owner, asset.get("using_organization_id"), current.id(),
                input.reason(), snapshot.toString(), now());
        long id = lastId();
        db.update("INSERT INTO fams_asset_business_lock (asset_id,business_type,business_id,business_item_id,acquired_at)"
                + " VALUES (?,'ASSET_RETURN',?,?,?)", input.assetId(), id, id, now());
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, id, "RETURN_PROPOSED", null, 0L, input);
        return finish(current, "propose", key, hash, id);
    }

    Map<String, Object> proposeLossRecovery(Actor actor, String key, long assetId, long version,
            String reason, LossReturnPort.Source source) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        var asset = one("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        var loans = db.queryForList("SELECT * FROM fams_loan WHERE asset_id=? AND relation_status='OPEN' FOR UPDATE", assetId);
        Long owner = (Long) asset.get("PERSONAL".equals(asset.get("custody_type"))
                ? "responsible_employee_id" : "department_responsible_employee_id");
        if (owner == null || asset.get("using_organization_id") == null
                || ("BORROWED".equals(asset.get("lifecycle_status")) ? loans.size() != 1 : !loans.isEmpty())) {
            throw new LoanFailure("RETURN_LOAN_CHANGED", 409);
        }
        var parameters = json.readTree(one("SELECT snapshot_json FROM fams_business_parameter_version WHERE id=?",
                asset.get("parameter_version_id")).get("snapshot_json").toString());
        if (!(parameters instanceof tools.jackson.databind.node.ObjectNode snapshot)) {
            throw new LoanFailure("RETURN_PARAMETER_INVALID", 409);
        }
        snapshot.set("lossRecovery", json.valueToTree(source));
        db.update("INSERT INTO fams_asset_return (asset_id,loan_id,asset_version,responsible_employee_id,"
                + "using_organization_id,proposed_by,reason,parameter_snapshot_json,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                assetId, loans.isEmpty() ? null : loans.getFirst().get("id"), version, owner,
                asset.get("using_organization_id"), current.id(), reason, json.writeValueAsString(snapshot), now());
        long id = lastId();
        assets.adoptLossRecovery(assetId, version, Long.parseLong(source.reportId()), Long.parseLong(source.lossItemId()), source.lockVersion(), id);
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, id, "RETURN_FROM_LOSS_RECOVERY", null, 0L, source);
        return view(request(id));
    }

    Map<String, Object> resubmitLossRecovery(Actor actor, long id, long version, String key,
            String reason) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.ASSET_ADMIN);
        String hash = digest("resubmit-loss:" + id + ":" + version, reason);
        var prior = replay(current, "resubmit-loss", key, hash);
        if (prior != null) { return prior; }
        var row = request(id);
        state(row, version, "REJECTED");
        if (!linkedLoss(row)) { throw new LoanFailure("RETURN_LOSS_SOURCE_REQUIRED", 409); }
        currentAsset(row);
        requireRetainedLock(row);
        db.update("UPDATE fams_asset_return SET return_status='PENDING_APPROVAL',version=version+1 WHERE id=?", id);
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, id, "RETURN_LOSS_RESUBMITTED", version, version + 1, Map.of("reason", reason));
        return finish(current, "resubmit-loss", key, hash, id);
    }

    private boolean linkedLoss(Map<String, Object> row) {
        return json.readTree(row.get("parameter_snapshot_json").toString()).has("lossRecovery");
    }

    private void requireRetainedLock(Map<String, Object> row) {
        if (db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND business_type='ASSET_RETURN'"
                + " AND business_id=? AND business_item_id=? AND released_at IS NULL FOR UPDATE",
                row.get("asset_id"), row.get("id"), row.get("id")).size() != 1) {
            throw new LoanFailure("RETURN_LOCK_CHANGED", 409);
        }
    }

    Map<String, Object> decide(Actor actor, long id, long version, String key, AssetReturnController.Decision input) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.ASSET_ADMIN);
        String hash = digest("decision:" + id + ":" + version, input);
        var replay = replay(current, "decision", key, hash);
        if (replay != null) { return replay; }
        var row = request(id);
        state(row, version, "PENDING_APPROVAL");
        if (linkedLoss(row)) { requireRetainedLock(row); }
        if (input.approved()) { currentAsset(row); }
        db.update("UPDATE fams_asset_return SET return_status=?,decided_by=?,decision_reason=?,decided_at=?,"
                + "version=version+1 WHERE id=?", input.approved() ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                current.id(), input.reason(), now(), id);
        closeTodos(id);
        if (input.approved()) { todo(id, "EXECUTION", "WAREHOUSE_OPERATOR", null); }
        else if (!linkedLoss(row)) { release(id, "RETURN_REJECTED"); }
        else { requireRetainedLock(row); }
        audit(actor, current, id, input.approved() ? "RETURN_APPROVED" : "RETURN_REJECTED", version, version + 1, input);
        return finish(current, "decision", key, hash, id);
    }

    Map<String, Object> claim(Actor actor, long id, long version, String key) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        String hash = digest("claim:" + id + ":" + version, Map.of());
        var replay = replay(current, "claim", key, hash);
        if (replay != null) { return replay; }
        var row = request(id);
        state(row, version, "WAITING_WAREHOUSE_CLAIM");
        currentAsset(row);
        db.update("UPDATE fams_asset_return SET return_status='WAREHOUSE_PROCESSING',processor_account_id=?,"
                + "version=version+1 WHERE id=?", current.id(), id);
        closeTodos(id);
        todo(id, "INSPECTION", "WAREHOUSE_OPERATOR", current.id());
        audit(actor, current, id, "RETURN_CLAIMED", version, version + 1, Map.of());
        return finish(current, "claim", key, hash, id);
    }

    Map<String, Object> inspect(Actor actor, long id, long version, String key, AssetReturnController.Inspection input) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        var row = request(id);
        if (row.get("processor_account_id") == null || number(row, "processor_account_id") != current.id()) {
            throw new LoanFailure("RETURN_NOT_PROCESSOR", 403);
        }
        String hash = digest("inspect:" + id + ":" + version, input);
        var replay = replay(current, "inspect", key, hash);
        if (replay != null) { return replay; }
        state(row, version, "WAREHOUSE_PROCESSING");
        currentAsset(row);
        if (input.assetVersion() != number(row, "asset_version") || !List.of("NORMAL", "DAMAGED", "MISSING").contains(input.result())) {
            throw new LoanFailure("RETURN_INSPECTION_INVALID", 409);
        }
        var lock = one("SELECT * FROM fams_asset_business_lock WHERE asset_id=? AND business_type='ASSET_RETURN'"
                + " AND business_id=? AND released_at IS NULL FOR UPDATE", row.get("asset_id"), id);
        if (number(lock, "lock_version") != input.lockVersion()) { throw new LoanFailure("RETURN_LOCK_CHANGED", 409); }
        boolean missing = "MISSING".equals(input.result());
        if (!missing) {
            if (input.observedCode() == null || input.observedCode().isBlank()) { throw new LoanFailure("RETURN_PHYSICAL_REQUIRED", 400); }
            handover.observedCode(number(row, "asset_id"), input.observedCode());
            target(input);
        } else {
            if (input.warehouseId() != null || input.locationId() != null) {
                throw new LoanFailure("RETURN_MISSING_TARGET_INVALID", 400);
            }
            if (input.missingLoss() == null) {
                throw new LoanFailure("RETURN_MISSING_LOSS_FACTS_REQUIRED", 400);
            }
        }
        assets.inspect(number(row, "asset_id"), input.assetVersion(), input.result(), input.warehouseId(),
                input.locationId(), current.id(), id, clock.instant());
        if (!missing && row.get("loan_id") != null) {
            if (db.update("UPDATE fams_loan SET relation_status='RETURNED',closed_at=?,updated_at=?,version=version+1"
                    + " WHERE id=? AND relation_status='OPEN'", now(), now(), row.get("loan_id")) != 1) {
                throw new LoanFailure("RETURN_LOAN_CHANGED", 409);
            }
        }
        boolean damaged = "DAMAGED".equals(input.result());
        if (!missing) {
            release(id, damaged ? "TRANSFERRED_TO_FOLLOWUP" : "RETURN_ACCEPTED");
        }
        if (missing || damaged) {
            String type = missing ? "LOST_REPORT" : "REPAIR";
            db.update("INSERT INTO fams_return_followup (return_id,asset_id,loan_id,business_type,processor_account_id,reason,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", id, row.get("asset_id"), row.get("loan_id"), type, current.id(), input.reason(), now());
            long next = lastId();
            if (missing) {
                openMissing(actor, current, id, next, number(row, "asset_id"), input.assetVersion() + 1,
                        "ASSET_RETURN", id, input.lockVersion(), input.missingLoss());
            } else {
                db.update("INSERT INTO fams_asset_business_lock (asset_id,business_type,business_id,business_item_id,acquired_at)"
                        + " VALUES (?,'RETURN_REPAIR',?,?,?)", row.get("asset_id"), next, next, now());
                db.update("INSERT INTO fams_todo (pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES ('ASSET_ADMIN','RETURN_REPAIR',?,'FOLLOWUP','OPEN',?)", next, now());
            }
        }
        db.update("UPDATE fams_asset_return SET return_status=?,inspection_result=?,inspection_reason=?,warehouse_id=?,"
                + "location_id=?,completed_at=?,version=version+1 WHERE id=?", missing ? "TRANSFERRED" : "COMPLETED",
                input.result(), input.reason(), input.warehouseId(), input.locationId(), now(), id);
        closeTodos(id);
        if (!missing && row.get("loan_id") != null && linkedLoss(row)) {
            reminders.refresh(number(row, "loan_id"));
        }
        audit(actor, current, id, "RETURN_INSPECTED", version, version + 1, input);
        return finish(current, "inspect", key, hash, id);
    }

    Map<String, Object> retryMissingLoss(Actor actor, long id, long version, String key,
            AssetReturnController.MissingRetry input) {
        var current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        String hash = digest("missing-loss:" + id + ":" + version, input);
        var prior = replay(current, "missing-loss", key, hash);
        if (prior != null) {
            return prior;
        }
        var row = request(id);
        if (!"TRANSFERRED".equals(row.get("return_status")) || !"MISSING".equals(row.get("inspection_result"))) {
            throw new LoanFailure("RETURN_MISSING_STATE_CHANGED", 409);
        }
        var followup = one("SELECT * FROM fams_return_followup WHERE return_id=? AND business_type='LOST_REPORT' FOR UPDATE", id);
        if (current.id() != number(followup, "processor_account_id")) {
            throw new LoanFailure("RETURN_NOT_PROCESSOR", 403);
        }
        checkVersion(followup, version);
        if (!"PENDING".equals(followup.get("followup_status"))) {
            throw new LoanFailure("RETURN_MISSING_STATE_CHANGED", 409);
        }
        long assetId = number(row, "asset_id");
        long followupId = number(followup, "id");
        var asset = one("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", assetId);
        checkVersion(asset, input.assetVersion());
        var lock = one("SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND business_type='RETURN_LOST_REPORT'"
                + " AND business_id=? AND released_at IS NULL FOR UPDATE", assetId, followupId);
        if (number(lock, "lock_version") != input.lockVersion()) {
            throw new LoanFailure("RETURN_LOCK_CHANGED", 409);
        }
        long assetVersion = input.assetVersion();
        if ("SEARCHING".equals(asset.get("exception_status")) && lossReports.getObject().latest(followupId) == null) {
            assetVersion = assets.prepareLegacyMissingReturn(assetId, assetVersion, id, current.id(), clock.instant());
        }
        long report = openMissing(actor, current, id, followupId, assetId, assetVersion,
                "RETURN_LOST_REPORT", followupId, input.lockVersion(), input.facts());
        audit(actor, current, id, "RETURN_MISSING_REPORTED", number(row, "version"), number(row, "version"),
                Map.of("followupId", Long.toString(followupId), "reportId", Long.toString(report)));
        return finish(current, "missing-loss", key, hash, id);
    }

    private long openMissing(Actor actor, Current current, long returnId, long followupId, long assetId,
            long assetVersion, String lockType, long lockBusinessId, long lockVersion, AssetReturnController.MissingLoss input) {
        if (input.circumstances().strip().length() < 10) {
            throw new LoanFailure("RETURN_MISSING_LOSS_FACTS_REQUIRED", 400);
        }
        var facts = new ReturnLossOpeningPort.Facts(input.lastSeenAt(), input.lastLocation(), input.circumstances());
        long reportId;
        try {
            reportId = lossReports.getObject().open(actor,
                    new ReturnLossOpeningPort.Opening(returnId, followupId, assetId, assetVersion, current.id(), facts));
        } catch (ReturnLossOpeningPort.Failure failure) {
            throw new LoanFailure(failure.getMessage(), failure.status());
        }
        if (db.update("UPDATE fams_asset_business_lock SET business_type='LOST_REPORT',business_id=?,business_item_id=?,"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type=? AND business_id=?"
                + " AND lock_version=? AND released_at IS NULL",
                reportId, reportId, assetId, lockType, lockBusinessId, lockVersion) != 1) {
            throw new LoanFailure("RETURN_LOCK_CHANGED", 409);
        }
        db.update("UPDATE fams_return_followup SET followup_status='ACCEPTED',version=version+1 WHERE id=?", followupId);
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='RETURN_LOST_REPORT'"
                + " AND business_id=? AND closed_at IS NULL", now(), followupId);
        return reportId;
    }

    void restoreMissing(long followupId, long assetId, long reportId) {
        var followup = one("SELECT * FROM fams_return_followup WHERE id=? AND business_type='LOST_REPORT'"
                + " AND followup_status='ACCEPTED' AND asset_id=? FOR UPDATE", followupId, assetId);
        if (db.update("UPDATE fams_asset_business_lock SET business_type='RETURN_LOST_REPORT',business_id=?,business_item_id=?,"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='LOST_REPORT' AND business_id=?"
                + " AND business_item_id=? AND released_at IS NULL", followupId, followupId, assetId, reportId, reportId) != 1) {
            throw new LoanFailure("RETURN_LOCK_CHANGED", 409);
        }
        db.update("UPDATE fams_return_followup SET followup_status='PENDING',version=version+1 WHERE id=?", followupId);
        db.update("INSERT INTO fams_todo (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('WAREHOUSE_OPERATOR',?,'RETURN_LOST_REPORT',?,'FOLLOWUP','OPEN',?)",
                number(followup, "processor_account_id"), followupId, now());
    }

    private void target(AssetReturnController.Inspection input) {
        if (input.warehouseId() == null || input.locationId() == null
                || input.warehouseVersion() == null || input.locationVersion() == null) {
            throw new LoanFailure("RETURN_TARGET_REQUIRED", 400);
        }
        var warehouse = one("SELECT * FROM fams_warehouse WHERE id=? FOR UPDATE", input.warehouseId());
        var location = one("SELECT * FROM fams_location WHERE id=? FOR UPDATE", input.locationId());
        if (!"ACTIVE".equals(warehouse.get("status")) || !"ACTIVE".equals(location.get("status"))
                || location.get("warehouse_id") == null || number(location, "warehouse_id") != input.warehouseId()
                || !"BIN".equals(location.get("location_type"))) { throw new LoanFailure("RETURN_TARGET_INVALID", 409); }
        checkVersion(warehouse, input.warehouseVersion());
        checkVersion(location, input.locationVersion());
    }

    private void currentAsset(Map<String, Object> row) {
        var asset = one("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", row.get("asset_id"));
        checkVersion(asset, number(row, "asset_version"));
        if (!"NORMAL".equals(asset.get("exception_status")) || !List.of("BORROWED", "IN_USE").contains(asset.get("lifecycle_status"))) {
            throw new LoanFailure("RETURN_ASSET_CHANGED", 409);
        }
    }

    private void visible(Current current, Map<String, Object> row) {
        if (!current.globalApplicant() && current.employeeId() != number(row, "responsible_employee_id")
                && !(current.has(Role.DEPARTMENT_ASSET_MANAGER) && current.organizationId() == number(row, "using_organization_id"))) {
            throw new LoanFailure("RETURN_NOT_FOUND", 404);
        }
    }

    private void role(Current current, Role role) {
        if (!current.has(role)) { throw new LoanFailure("RETURN_FORBIDDEN", 403); }
    }

    private void state(Map<String, Object> row, long version, String state) {
        checkVersion(row, version);
        if (!state.equals(row.get("return_status"))) { throw new LoanFailure("RETURN_STATE_CHANGED", 409); }
    }

    private void checkVersion(Map<String, Object> row, long version) {
        if (number(row, "version") != version) { throw new LoanFailure("RETURN_VERSION_CONFLICT", 409); }
    }

    private void release(long id, String reason) {
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason=?,lock_version=lock_version+1"
                + " WHERE business_type='ASSET_RETURN' AND business_id=? AND released_at IS NULL", now(), reason, id) != 1) {
            throw new LoanFailure("RETURN_LOCK_CHANGED", 409);
        }
    }

    private void todo(long id, String type, String pool, Long owner) {
        db.update("INSERT INTO fams_todo (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES (?,?,'ASSET_RETURN',?,?,'OPEN',?)", pool, owner, id, type, now());
    }

    private void closeTodos(long id) {
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='ASSET_RETURN'"
                + " AND business_id=? AND closed_at IS NULL", now(), id);
    }

    private void audit(Actor actor, Current current, long id, String action, Long before, Long after, Object detail) {
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                + "before_version,after_version,occurred_at,detail_json) VALUES (?,?,?,?,'ASSET_RETURN',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow(),
                action, id, before, after, now(), json.writeValueAsString(detail));
    }

    private Map<String, Object> replay(Current current, String action, String key, String hash) {
        var rows = db.queryForList("SELECT * FROM fams_idempotency_record WHERE actor_account_id=? AND operation_id=?"
                + " AND idempotency_key=? AND expires_at>? FOR UPDATE", current.id(), "return:" + action, key, now());
        if (rows.isEmpty()) {
            db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id=? AND operation_id=?"
                    + " AND idempotency_key=? AND expires_at<=?", current.id(), "return:" + action, key, now());
            return null;
        }
        var row = rows.getFirst();
        if (!hash.equals(row.get("payload_sha256"))) { throw new LoanFailure("RETURN_IDEMPOTENCY_CONFLICT", 409); }
        return json.readValue(row.get("response_body_json").toString(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(Current current, String action, String key, String hash, long id) {
        var result = view(request(id));
        db.update("INSERT INTO fams_idempotency_record (actor_account_id,operation_id,idempotency_key,payload_sha256,"
                + "result_status,http_status,response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'ASSET_RETURN',?,?,?,?,?)", current.id(), "return:" + action,
                key, hash, json.writeValueAsString(result), id, result.get("version"), now(), now(),
                Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private Map<String, Object> request(long id) { return one("SELECT * FROM fams_asset_return WHERE id=? FOR UPDATE", id); }
    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) { throw new LoanFailure("RETURN_NOT_FOUND", 404); }
        return rows.getFirst();
    }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private long lastId() { return java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class), "RETURN_ID_MISSING"); }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private String digest(String prefix, Object input) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((prefix + "\n" + json.writeValueAsString(input)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (key.equals("parameter_snapshot_json") || key.equals("active_asset_id")) { return; }
            String[] words = key.split("_");
            StringBuilder field = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) { field.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1)); }
            Object wire = value;
            if (value instanceof java.time.LocalDateTime date) { wire = date.toInstant(java.time.ZoneOffset.UTC).toString(); }
            else if (value instanceof Timestamp date) { wire = date.toInstant().toString(); }
            else if (value instanceof Number && (key.equals("id") || key.endsWith("_id") || key.endsWith("_by"))) { wire = value.toString(); }
            result.put(field.toString(), wire);
        });
        if (row.containsKey("parameter_snapshot_json") && linkedLoss(row)) {
            result.put("lossRecovery", json.readTree(row.get("parameter_snapshot_json").toString()).get("lossRecovery"));
        }
        return result;
    }
}
