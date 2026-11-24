package com.fams.loss;

import com.fams.asset.AssetLossPort;
import com.fams.business.InitialScrapPort.LossFacts;
import com.fams.business.InitialScrapPort.Opening;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Preserves verified opening evidence in the loss domain for subsequent write-off or recovery. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LossInitialPort {
    private static final Logger LOG = LoggerFactory.getLogger(LossInitialPort.class);
    public record Confirmed(long id, String custodyJson, String confirmationJson) { }

    private final JdbcTemplate db;
    private final AssetLossPort assets;
    private final ObjectMapper json;

    LossInitialPort(JdbcTemplate db, AssetLossPort assets, ObjectMapper json) {
        this.db = db;
        this.assets = assets;
        this.json = json;
    }

    public Confirmed open(Opening opening, LossFacts facts, LocalDate businessDate, String timezone, Long repair, Long round) {
        boolean factory = "FACTORY_REPAIR".equals(opening.beforeLifecycle());
        AssetLossPort.Snapshot snapshot = factory
                ? assets.factorySubmission(opening.asset(), 0) : assets.initialLossSnapshot(opening.asset());
        if (factory && (repair == null || round == null)
                || !List.of("BORROWED", "IN_STOCK", "IN_USE", "FACTORY_REPAIR").contains(opening.beforeLifecycle())
                || !opening.beforeLifecycle().equals(snapshot.lifecycle())
                || facts.lastSeenAt().isAfter(facts.confirmedAt()) || facts.confirmedAt().isAfter(opening.time())) {
            throw new IllegalStateException("ASSET_IMPORT_INITIAL_LOSS_INVALID");
        }
        var provenance = Map.<String, Object>of("batchId", opening.batch().toString(), "sheetRow", opening.row(),
                "businessDate", businessDate, "operatorAccountId", Long.toString(opening.actorId()));
        var custody = new AssetLossPort.StoredSource(snapshot, null,
                factory ? Map.of("repairId", Long.toString(repair), "roundId", Long.toString(round),
                        "processorAccountId", Long.toString(opening.actorId())) : null,
                null, provenance);
        var confirmation = Map.<String, Object>of("initialSource", provenance, "confirmedAt", facts.confirmedAt(),
                "responsibilityEvidence", facts.evidence());
        String custodyJson = json.writeValueAsString(custody);
        String confirmationJson = json.writeValueAsString(confirmation);
        db.update("""
                INSERT INTO fams_loss_report(asset_id,creator_account_id,using_organization_id,responsible_employee_id,
                    shared_responsible_employee_id,asset_version,source_snapshot_json,last_seen_at,last_location,
                    circumstances,report_status,confirmed_lost_at,confirmation_snapshot_json,processor_account_id,
                    ownership_version,next_review_date,created_at,updated_at)
                VALUES (?,?,?,?,?,0,?,?,?,?,'CONFIRMED_LOST',?,?,?,1,?,?,?)
                """, opening.asset(), opening.actorId(), snapshot.usingOrganizationId(), snapshot.responsibleEmployeeId(),
                snapshot.departmentResponsibleEmployeeId(), custodyJson, facts.lastSeenAt(),
                facts.lastLocation(), opening.reason(), facts.confirmedAt(), confirmationJson,
                opening.actorId(), businessDate.plusDays(30), opening.time(), opening.time());
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update("""
                INSERT INTO fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)
                VALUES ('WAREHOUSE_OPERATOR',?,'LOST_REPORT',?,'LOST_FOLLOW_UP','OPEN',?)
                """, opening.actorId(), id, opening.time());
        var schedule = new LinkedHashMap<String, Object>(provenance);
        schedule.put("reviewDate", businessDate.plusDays(30).toString());
        schedule.put("reviewTimezone", timezone);
        event(id, opening.actorId(), "LOSS_INITIAL_OPENED", 0, schedule, opening.time());
        LOG.debug("Initial loss rows written for actor {} repair {}.", opening.actorId(), repair);
        return new Confirmed(id, custodyJson, confirmationJson);
    }

    public void cancel(long report, long asset, long actor, Instant time) {
        if (db.update("""
                UPDATE fams_loss_report SET report_status='CANCELLED',version=1,updated_at=?
                WHERE id=? AND asset_id=? AND report_status='CONFIRMED_LOST' AND version=0 AND recovered_at IS NULL
                """, time, report, asset) != 1) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
        }
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='LOST_REPORT'"
                + " AND business_id=? AND closed_at IS NULL", time, report);
        event(report, actor, "LOSS_INITIAL_ROLLED_BACK", 1, Map.of("assetId", Long.toString(asset)), time);
        LOG.debug("UPDATE fams_todo closed for loss report {} asset {}.", report, asset);
    }

    private void event(long report, long actor, String action, long version, Map<String, ?> detail, Instant time) {
        db.update("""
                INSERT INTO fams_loss_event(report_id,action,before_version,after_version,actor_account_id,
                    actor_username,actor_role,detail_json,occurred_at)
                SELECT ?,?,0,?,id,username,'WAREHOUSE_OPERATOR',?,? FROM fams_system_account WHERE id=?
                """, report, action, version, json.writeValueAsString(detail), time, actor);
    }
}
