package com.fams.scrap;

import com.fams.business.InitialScrapPort;
import java.time.Instant;
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

    ScrapInitialPort(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
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
        if (rows.size() != 1 || !"STANDALONE".equals(request.get("source_type"))
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
