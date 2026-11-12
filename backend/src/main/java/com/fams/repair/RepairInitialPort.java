package com.fams.repair;

import com.fams.asset.AssetRepairPort;
import com.fams.business.InitialRepairPort;
import com.fams.loan.LoanRepairPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
class RepairInitialPort implements InitialRepairPort {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetRepairPort assets;
    private final LoanRepairPort loans;

    RepairInitialPort(JdbcTemplate db, ObjectMapper json, AssetRepairPort assets, LoanRepairPort loans) {
        this.db = db;
        this.json = json;
        this.assets = assets;
        this.loans = loans;
    }

    @Override
    public void open(Opening opening) {
        var facts = opening.facts();
        boolean factory = facts.factory() != null;
        if (facts.occurredAt().isAfter(facts.acceptedAt()) || facts.acceptedAt().isAfter(opening.now())
                || factory && (facts.factory().dispatchedAt().isBefore(facts.acceptedAt())
                        || facts.factory().dispatchedAt().isAfter(opening.now()))) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_REPAIR_TIME_INVALID");
        }
        if (factory && facts.factory().due().isBefore(java.time.LocalDate.ofInstant(facts.factory().dispatchedAt(),
                java.time.ZoneId.of(opening.timezone())))) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_FACTORY_DATE_INVALID");
        }
        var source = new LinkedHashMap<>(assets.beginInitialRepair(opening.assetId(), factory));
        var origin = new LinkedHashMap<String, Object>();
        origin.put("batchId", opening.batch().toString());
        origin.put("sheetRow", opening.row());
        origin.put("operatorAccountId", Long.toString(opening.operatorId()));
        origin.put("administratorAccountId", Long.toString(opening.administratorId()));
        origin.put("businessDate", java.time.LocalDate.ofInstant(opening.now(), java.time.ZoneId.of(opening.timezone())));
        origin.put("stage", facts.stage());
        origin.put("evidence", facts.evidence());
        if (factory) {
            origin.put("factoryDestination", facts.factory().destination());
        }
        source.put("initialSource", origin);
        var borrowing = loans.open(opening.assetId());
        String approval = json.writeValueAsString(Map.of("initialSource", origin, "confirmedAt", opening.now(),
                "source", source, "allowedMethod", factory ? "INTERNAL_OR_FACTORY" : "INTERNAL_NO_COST"));
        db.update("""
                INSERT INTO fams_repair_request(asset_id,creator_account_id,using_organization_id,responsible_employee_id,
                    shared_responsible_employee_id,asset_version,source_snapshot_json,loan_snapshot_json,timezone_id,
                    fault,impact,occurred_at,repair_status,allowed_method,approval_snapshot_json,processor_account_id,
                    claimed_at,accepted_at,initial_batch_id,initial_sheet_row,created_at,updated_at)
                VALUES (?,?,?,?,?,0,?,?,?,?,?,?,'WAREHOUSE_PROCESSING',?,?,?,?,?,?,?,?,?)
                """, opening.assetId(), opening.operatorId(), source.get("usingOrganizationId"), source.get("responsibleEmployeeId"),
                source.get("departmentResponsibleEmployeeId"), json.writeValueAsString(source),
                borrowing == null ? null : json.writeValueAsString(borrowing), opening.timezone(), facts.fault(), facts.impact(),
                facts.occurredAt(), factory ? "INTERNAL_OR_FACTORY" : "INTERNAL_NO_COST", approval, opening.operatorId(),
                opening.now(), facts.acceptedAt(), opening.batch().toString(), opening.row(), opening.now(), opening.now());
        long repair = lastId();
        if (facts.physicalLocation() != null) {
            var place = db.queryForMap("SELECT id,version FROM fams_location WHERE id=? AND status='ACTIVE' FOR SHARE",
                    facts.physicalLocation());
            Long employee = db.queryForObject("SELECT employee_id FROM fams_system_account WHERE id=?", Long.class,
                    opening.operatorId());
            db.update("""
                    INSERT INTO fams_repair_physical_handling(repair_id,handler_employee_id,actual_location_id,
                        location_version,before_asset_version,occurred_at,evidence,actor_account_id,recorded_at)
                    VALUES (?,?,?,?,0,?,?,?,?)
                    """, repair, employee, facts.physicalLocation(), place.get("version"), facts.acceptedAt(),
                    facts.evidence(), opening.operatorId(), opening.now());
        }
        if (factory) {
            openFactory(opening, repair, source, approval);
        }
        db.update("INSERT INTO fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                + " VALUES (?,'REPAIR',?,?,?)", opening.assetId(), repair, repair, opening.now());
        db.update("INSERT INTO fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('WAREHOUSE_OPERATOR',?,'REPAIR',?,?,'OPEN',?)", opening.operatorId(), repair,
                factory ? "FACTORY_RETURN" : "PROCESS", opening.now());
        event(repair, opening.operatorId(), opening.operatorUsername(), "REPAIR_INITIAL_OPENED", 0, 0, origin, opening.now());
    }

    private void openFactory(Opening opening, long repair, Map<String, Object> source, String approval) {
        var facts = opening.facts().factory();
        String method = "NO_COST".equals(facts.costPolicy()) ? "FACTORY_NO_COST" : "FACTORY_COST_ALLOWED";
        db.update("""
                INSERT INTO fams_repair_factory_scope(repair_id,scope_status,allowed_method,request_snapshot_json,
                    request_reason,requested_by,requested_at,decision_snapshot_json,decision_reason,decided_by,
                    decided_at,cost_policy,cost_limit)
                VALUES (?,'APPROVED',?,?,'期初核验承接现有返厂范围',?,?,?,'期初核验确认已寄出',
                    ?,?,?,?)
                """, repair, method, approval, opening.operatorId(), opening.now(), approval, opening.administratorId(),
                opening.now(), facts.costPolicy(), facts.costLimit());
        long scope = lastId();
        var identity = new LinkedHashMap<String, Object>();
        identity.put("assetId", Long.toString(opening.assetId()));
        identity.put("assetCode", source.get("assetCode"));
        identity.put("serialNumber", opening.serialNumber());
        identity.put("name", source.get("name"));
        db.update("""
                INSERT INTO fams_repair_factory_round(repair_id,asset_id,scope_id,round_number,round_status,
                    provider_name,service_contact,service_phone,expected_return_date,fault,original_identity_json,
                    created_by,created_at,dispatched_at,dispatched_by,dispatch_evidence,dispatch_scope_id,initial_source)
                VALUES (?,?,?,?,'DISPATCHED',?,?,?,?,?,?,?,?,?,?,?,?,TRUE)
                """, repair, opening.assetId(), scope, facts.roundNumber(), facts.provider(), facts.contact(), facts.phone(), facts.due(),
                opening.facts().fault(), json.writeValueAsString(identity), opening.operatorId(), opening.now(),
                facts.dispatchedAt(), opening.operatorId(), facts.dispatchEvidence(), scope);
    }

    @Override
    public void assertReversible(long assetId, UUID batch) {
        var source = source(assetId, batch);
        if (source == null) {
            return;
        }
        long repair = ((Number) source.get("id")).longValue();
        var locks = db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND business_type='REPAIR'"
                + " AND business_id=? AND business_item_id=? AND lock_version=0 AND released_at IS NULL FOR UPDATE", assetId, repair, repair);
        if (!"WAREHOUSE_PROCESSING".equals(source.get("repair_status"))
                || ((Number) source.get("version")).longValue() != 0 || locks.size() != 1
                || !db.queryForList("SELECT id FROM fams_repair_factory_round WHERE repair_id=?"
                        + " AND (version<>0 OR NOT initial_source OR round_status<>'DISPATCHED')", repair).isEmpty()) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
    }

    @Override
    public void cancel(long assetId, UUID batch, long actorId, Instant now) {
        var source = source(assetId, batch);
        if (source == null) {
            return;
        }
        assertReversible(assetId, batch);
        long repair = ((Number) source.get("id")).longValue();
        db.update("UPDATE fams_repair_factory_round SET round_status='INITIAL_RETIRED',"
                + "cancel_reason='期初批次撤销，保留原寄出依据',version=version+1 WHERE repair_id=? AND initial_source", repair);
        db.update("UPDATE fams_repair_request SET repair_status='CANCELLED',version=version+1,updated_at=? WHERE id=?", now, repair);
        db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='INITIAL_IMPORT_ROLLBACK',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='REPAIR' AND business_id=? AND released_at IS NULL",
                now, assetId, repair);
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='REPAIR' AND business_id=? AND closed_at IS NULL",
                now, repair);
        event(repair, actorId, db.queryForObject("SELECT username FROM fams_system_account WHERE id=?", String.class, actorId),
                "REPAIR_INITIAL_ROLLED_BACK", 0, 1, Map.of("batchId", batch.toString()), now);
    }

    private Map<String, Object> source(long assetId, UUID batch) {
        var rows = db.queryForList("SELECT id,repair_status,version FROM fams_repair_request"
                + " WHERE asset_id=? AND initial_batch_id=? FOR UPDATE", assetId, batch.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long lastId() {
        return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private void event(long repair, long actor, String username, String action, long before, long after,
            Map<String, Object> facts, Instant time) {
        db.update("INSERT INTO fams_repair_event(repair_id,action,before_version,after_version,actor_account_id,"
                + "actor_username,actor_role,detail_json,occurred_at) VALUES (?,?,?,?,?,?,'WAREHOUSE_OPERATOR',?,?)",
                repair, action, before, after, actor, username, json.writeValueAsString(facts), time);
    }
}
