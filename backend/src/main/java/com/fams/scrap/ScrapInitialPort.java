package com.fams.scrap;

import com.fams.business.InitialScrapPort;
import com.fams.asset.AssetScrapPort;
import com.fams.loan.LoanRepairPort;
import com.fams.loss.LossInitialPort;
import com.fams.repair.RepairService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Carries an already pending-scrap opening fact into the live approval queue without inventing an application. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class ScrapInitialPort implements InitialScrapPort {
    private static final List<String> BEFORE_LIFECYCLES = List.of("IN_STOCK", "IN_USE");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AssetScrapPort assets;
    private final LoanRepairPort loans;
    private final LossInitialPort losses;
    private final RepairService repairs;

    ScrapInitialPort(JdbcTemplate db, ObjectMapper json, AssetScrapPort assets, LoanRepairPort loans,
            LossInitialPort losses, RepairService repairs) {
        this.db = db;
        this.json = json;
        this.assets = assets;
        this.loans = loans;
        this.losses = losses;
        this.repairs = repairs;
    }

    @Override
    public void openRepair(Opening opening) {
        if (!"UNDER_REPAIR".equals(opening.beforeLifecycle()) || opening.row() < 2) {
            throw new IllegalStateException("ASSET_IMPORT_SCRAP_FACT_INVALID");
        }
        var source = new LinkedHashMap<>(assets.repairSource(opening.asset(), 0, false));
        var repair = db.queryForMap("""
                SELECT id,source_snapshot_json FROM fams_repair_request
                WHERE initial_batch_id=? AND initial_sheet_row=? AND asset_id=?
                AND repair_status='WAREHOUSE_PROCESSING' AND version=0 AND asset_version=0
                AND accepted_at IS NOT NULL AND processor_account_id=? FOR UPDATE
                """, opening.batch().toString(), opening.row(), opening.asset(), opening.actorId());
        long repairId = ((Number) repair.get("id")).longValue();
        var initial = Map.of("batchId", opening.batch().toString(), "sheetRow", Integer.toString(opening.row()));
        var proof = new LinkedHashMap<String, Object>();
        proof.put("repairVersion", 0);
        proof.put("original", json.readTree(repair.get("source_snapshot_json").toString()));
        proof.put("borrowing", loans.open(opening.asset()));
        source.put("repairEvidence", proof);
        source.put("initialSource", initial);
        db.update("""
                INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_business_id,
                    source_snapshot_json,reason,observed_condition,proposed_disposition,asset_version,
                    initial_batch_id,initial_sheet_row,created_at,updated_at,processor_account_id,ownership_version)
                VALUES (?,?,'REPAIR',?,?,?,?,?,0,?,?,?,?,?,1)
                """, opening.asset(), opening.actorId(), repairId, json.writeValueAsString(source),
                opening.reason(), opening.observedCondition(), opening.proposedDisposition(), opening.batch().toString(),
                opening.row(), opening.time(), opening.time(), opening.actorId());
        long id = java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        if (db.update("""
                UPDATE fams_asset_business_lock SET business_type='SCRAP',business_id=?,business_item_id=?
                WHERE asset_id=? AND business_type='REPAIR' AND business_id=? AND business_item_id=?
                AND lock_version=0 AND released_at IS NULL
                """, id, id, opening.asset(), repairId, repairId) != 1
                || db.update("""
                UPDATE fams_asset SET lifecycle_status='PENDING_SCRAP'
                WHERE id=? AND lifecycle_status='UNDER_REPAIR' AND version=0
                """, opening.asset()) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_SCRAP_FACT_INVALID");
        }
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='REPAIR'"
                + " AND business_id=? AND closed_at IS NULL", opening.time(), repairId);
        db.update("INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)", id, opening.time());
        event(id, "SCRAP_INITIAL_OPENED", opening.actorId(), opening.time(), Map.of(
                "batchId", opening.batch().toString(), "sheetRow", Integer.toString(opening.row()),
                "beforeLifecycle", opening.beforeLifecycle(), "repairId", Long.toString(repairId),
                "reason", opening.reason()));
    }

    @Override
    public void open(Opening opening) {
        if (!BEFORE_LIFECYCLES.contains(opening.beforeLifecycle()) || opening.row() < 2) {
            throw new IllegalStateException("ASSET_IMPORT_SCRAP_FACT_INVALID");
        }
        var source = new LinkedHashMap<String, Object>(db.queryForMap(
                "SELECT id,asset_code,name,serial_number,lifecycle_status,exception_status,card_stage,"
                        + "custody_type,storage_warehouse_id,current_location_id,using_organization_id,"
                        + "responsible_employee_id,department_responsible_employee_id,version"
                        + " FROM fams_asset WHERE id=? FOR UPDATE", opening.asset()));
        if (!"PENDING_SCRAP".equals(source.get("lifecycle_status")) || !"ACTIVE".equals(source.get("card_stage"))
                || !"NORMAL".equals(source.get("exception_status"))
                || ((Number) source.get("version")).longValue() != 0
                || !db.queryForList("SELECT id FROM fams_asset_business_lock WHERE asset_id=?"
                        + " AND released_at IS NULL FOR UPDATE", opening.asset()).isEmpty()) {
            throw new IllegalStateException("ASSET_IMPORT_SCRAP_FACT_INVALID");
        }
        // The frozen snapshot names the lawful pre-scrap state while the published card stays pending scrap.
        source.put("lifecycle_status", opening.beforeLifecycle());
        source.put("initialSource", Map.of("batchId", opening.batch().toString(),
                "sheetRow", Integer.toString(opening.row())));
        db.update("INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_snapshot_json,"
                + "reason,observed_condition,proposed_disposition,asset_version,initial_batch_id,"
                + "initial_sheet_row,created_at,updated_at) VALUES (?,?,'STANDALONE',?,?,?,?,0,?,?,?,?)",
                opening.asset(), opening.actorId(), json.writeValueAsString(source), opening.reason(),
                opening.observedCondition(), opening.proposedDisposition(), opening.batch().toString(),
                opening.row(), opening.time(), opening.time());
        long id = java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update("INSERT INTO fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,"
                + "acquired_at) VALUES (?,'SCRAP',?,?,?)", opening.asset(), id, id, opening.time());
        db.update("INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)", id, opening.time());
        event(id, "SCRAP_INITIAL_OPENED", opening.actorId(), opening.time(), Map.of(
                "batchId", opening.batch().toString(), "sheetRow", Integer.toString(opening.row()),
                "beforeLifecycle", opening.beforeLifecycle(), "reason", opening.reason()));
    }

    @Override
    public void openLoss(Opening opening, LossFacts facts, LocalDate businessDate) {
        var factory = "FACTORY_REPAIR".equals(opening.beforeLifecycle()) ? initialFactoryLoss(opening, facts) : null;
        var loss = losses.open(opening, facts, businessDate, factory == null ? null : factory.repairId(),
                factory == null ? null : factory.roundId());
        if (db.update("""
                UPDATE fams_asset SET exception_status='LOST'
                WHERE id=? AND lifecycle_status=? AND card_stage='ACTIVE' AND version=0 AND exception_status='NORMAL'
                """, opening.asset(), opening.beforeLifecycle()) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_INVALID");
        }
        var custody = json.readTree(loss.custodyJson());
        var source = new LinkedHashMap<>(assets.lossSource(opening.asset(), 0, opening.beforeLifecycle(), custody));
        var proof = new LinkedHashMap<String, Object>();
        proof.put("reportId", Long.toString(loss.id()));
        proof.put("reportVersion", 0);
        proof.put("confirmedAt", facts.confirmedAt());
        proof.put("confirmation", json.readTree(loss.confirmationJson()));
        var loan = loans.open(opening.asset());
        if ("BORROWED".equals(opening.beforeLifecycle())) {
            if (loan == null) {
                throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_INVALID");
            }
            proof.put("loanId", Long.toString(loan.id()));
            proof.put("loanVersion", loan.version());
        } else if (factory != null) {
            proof.put("factoryRepair", factory);
        } else if (loan != null) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_INVALID");
        }
        source.put("lossEvidence", proof);
        source.put("initialSource", custody.path("initialSource"));
        db.update("""
                INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_business_id,
                    source_snapshot_json,reason,observed_condition,proposed_disposition,asset_version,
                    initial_batch_id,initial_sheet_row,created_at,updated_at,processor_account_id,ownership_version)
                VALUES (?,?,'LOSS',?,?,?,?,?,0,?,?,?,?,?,?)
                """, opening.asset(), opening.actorId(), loss.id(), json.writeValueAsString(source),
                opening.reason(), opening.observedCondition(), opening.proposedDisposition(), opening.batch().toString(),
                opening.row(), opening.time(), opening.time(), factory == null ? null : opening.actorId(),
                factory == null ? 0 : 1);
        long id = java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        if (db.update("""
                UPDATE fams_asset SET lifecycle_status='PENDING_SCRAP' WHERE id=? AND version=0 AND exception_status='LOST'
                """, opening.asset()) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_INVALID");
        }
        db.update("INSERT INTO fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,"
                + "acquired_at) VALUES (?,'SCRAP',?,?,?)", opening.asset(), id, id, opening.time());
        db.update("INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)", id, opening.time());
        event(id, "SCRAP_INITIAL_OPENED", opening.actorId(), opening.time(), Map.of(
                "batchId", opening.batch().toString(), "sheetRow", Integer.toString(opening.row()),
                "beforeLifecycle", opening.beforeLifecycle(), "reportId", Long.toString(loss.id()),
                "reason", opening.reason()));
    }

    private RepairService.FactoryLossScrap initialFactoryLoss(Opening opening, LossFacts facts) {
        var row = db.queryForMap("""
                SELECT r.id AS repair_id,f.id AS round_id FROM fams_repair_request r
                JOIN fams_repair_factory_round f ON f.repair_id=r.id AND f.asset_id=r.asset_id
                WHERE r.initial_batch_id=? AND r.initial_sheet_row=? AND r.asset_id=?
                  AND r.version=0 AND f.initial_source AND f.version=0 AND f.dispatched_at<=?
                FOR UPDATE
                """, opening.batch().toString(), opening.row(), opening.asset(), facts.lastSeenAt());
        long repair = ((Number) row.get("repair_id")).longValue();
        var factory = repairs.factoryLossScrap(repair, ((Number) row.get("round_id")).longValue(),
                opening.asset(), opening.actorId());
        if (factory == null || db.update("""
                UPDATE fams_asset_business_lock SET released_at=?,release_reason='INITIAL_FACTORY_LOSS'
                WHERE asset_id=? AND business_type='REPAIR' AND business_id=? AND business_item_id=?
                  AND lock_version=0 AND released_at IS NULL
                """, opening.time(), opening.asset(), repair, repair) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_INVALID");
        }
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='REPAIR'"
                + " AND business_id=? AND closed_at IS NULL", opening.time(), repair);
        return factory;
    }

    @Override
    public void openFactoryDisposition(Opening opening, FactoryDisposition disposition) {
        var source = new LinkedHashMap<>(assets.repairSource(opening.asset(), 0, true));
        var repair = db.queryForMap("""
                SELECT r.id,r.source_snapshot_json,f.id AS round_id
                FROM fams_repair_request r JOIN fams_repair_factory_round f ON f.repair_id=r.id AND f.asset_id=r.asset_id
                WHERE r.initial_batch_id=? AND r.initial_sheet_row=? AND r.asset_id=? AND r.processor_account_id=?
                  AND r.version=0 AND r.asset_version=0 AND r.repair_status='WAREHOUSE_PROCESSING'
                  AND f.initial_source AND f.round_status='DISPATCHED' AND f.version=0 AND f.dispatched_at<=?
                FOR UPDATE
                """, opening.batch().toString(), opening.row(), opening.asset(), opening.actorId(), disposition.occurredAt());
        if (disposition.occurredAt().isAfter(opening.time()) || !opening.proposedDisposition().equals(disposition.method())) {
            throw new IllegalStateException("ASSET_IMPORT_FACTORY_DISPOSITION_INVALID");
        }
        long repairId = ((Number) repair.get("id")).longValue();
        var round = repair.get("round_id");
        db.update("""
                INSERT INTO fams_initial_factory_disposition(round_id,method,occurred_at,evidence) VALUES (?,?,?,?)
                """, round, disposition.method(), disposition.occurredAt(), disposition.evidence());
        long factId = java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        var proof = new LinkedHashMap<String, Object>();
        proof.put("repairVersion", 0);
        proof.put("original", json.readTree(repair.get("source_snapshot_json").toString()));
        proof.put("borrowing", loans.open(opening.asset()));
        proof.put("context", Map.of("roundId", Long.toString(((Number) round).longValue()),
                "method", disposition.method(), "occurredAt", disposition.occurredAt().toString(),
                "evidence", disposition.evidence()));
        source.put("repairEvidence", proof);
        source.put("initialSource", Map.of("batchId", opening.batch().toString(), "sheetRow", Integer.toString(opening.row())));
        db.update("""
                INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_business_id,source_item_id,
                    source_snapshot_json,reason,observed_condition,proposed_disposition,asset_version,
                    initial_batch_id,initial_sheet_row,created_at,updated_at,processor_account_id,ownership_version)
                VALUES (?,?,'FACTORY_REPLACEMENT',?,?,?,?,?,?,0,?,?,?,?,?,1)
                """, opening.asset(), opening.actorId(), repairId, factId, json.writeValueAsString(source),
                opening.reason(), opening.observedCondition(), opening.proposedDisposition(), opening.batch().toString(),
                opening.row(), opening.time(), opening.time(), opening.actorId());
        long id = java.util.Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        if (db.update("""
                UPDATE fams_asset_business_lock SET business_type='SCRAP',business_id=?,business_item_id=?
                WHERE asset_id=? AND business_type='REPAIR' AND business_id=? AND business_item_id=?
                  AND lock_version=0 AND released_at IS NULL
                """, id, id, opening.asset(), repairId, repairId) != 1
                || db.update("""
                UPDATE fams_asset SET lifecycle_status='PENDING_SCRAP'
                WHERE id=? AND lifecycle_status='FACTORY_REPAIR' AND version=0
                """, opening.asset()) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_FACTORY_DISPOSITION_INVALID");
        }
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='REPAIR'"
                + " AND business_id=? AND closed_at IS NULL", opening.time(), repairId);
        db.update("INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)", id, opening.time());
        event(id, "SCRAP_INITIAL_OPENED", opening.actorId(), opening.time(), Map.of(
                "batchId", opening.batch().toString(), "sheetRow", Integer.toString(opening.row()),
                "beforeLifecycle", opening.beforeLifecycle(), "factoryDispositionId", Long.toString(factId)));
    }

    @Override
    public void assertReversible(long asset, UUID batch) {
        guarded(asset, batch);
    }

    @Override
    public void cancel(long asset, UUID batch, Instant time) {
        var request = guarded(asset, batch);
        if (request.isEmpty()) {
            return;
        }
        long id = ((Number) request.get("id")).longValue();
        if ("LOSS".equals(request.get("source_type"))) {
            losses.cancel(((Number) request.get("source_business_id")).longValue(), asset,
                    ((Number) request.get("creator_account_id")).longValue(), time);
        }
        db.update("UPDATE fams_scrap_request SET request_status='CANCELLED',version=version+1,updated_at=?"
                + " WHERE id=?", time, id);
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_INITIAL_ROLLED_BACK',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND released_at IS NULL", time, asset, id, id) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_type='APPROVAL' AND closed_at IS NULL", time, id);
        event(id, "SCRAP_INITIAL_ROLLED_BACK", ((Number) request.get("creator_account_id")).longValue(), time,
                Map.of("batchId", batch.toString()));
    }

    private Map<String, Object> guarded(long asset, UUID batch) {
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE asset_id=? AND initial_batch_id=?"
                + " FOR UPDATE", asset, batch.toString());
        if (rows.isEmpty()) {
            return Map.of();
        }
        var request = rows.getFirst();
        if (rows.size() != 1 || !List.of("STANDALONE", "REPAIR", "LOSS", "FACTORY_REPLACEMENT").contains(request.get("source_type"))
                || !"PENDING_ADMIN_DECISION".equals(request.get("request_status"))
                || ((Number) request.get("version")).longValue() != 0) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
        return request;
    }

    private void event(long request, String action, long actor, Instant time, Map<String, String> detail) {
        var username = db.queryForObject("SELECT username FROM fams_system_account WHERE id=?", String.class, actor);
        var role = db.queryForObject("SELECT MIN(role_code) FROM fams_account_role WHERE account_id=?",
                String.class, actor);
        db.update("INSERT INTO fams_scrap_event(request_id,action,before_version,after_version,actor_account_id,"
                + "actor_username,actor_role,detail_json,occurred_at) VALUES (?,?,0,0,?,?,?,?,?)",
                request, action, actor, username, role, json.writeValueAsString(detail), time);
    }
}
