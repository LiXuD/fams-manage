package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Profile("fams-product")
@Transactional
public class ScrapReadService {
    private static final String SCOPE = " FROM fams_scrap_request r JOIN fams_asset a ON a.id=r.asset_id"
            + " WHERE (? OR r.creator_account_id=?"
            + " OR (? AND EXISTS (SELECT 1 FROM fams_account_authorization aa"
            + " JOIN fams_account_data_scope ds ON ds.authorization_id=aa.id"
            + " JOIN fams_account_scope_organization os ON os.data_scope_id=ds.id"
            + " WHERE aa.account_id=? AND aa.role_code='AUDIT_READ_ONLY' AND aa.superseded_at IS NULL"
            + " AND aa.authorization_status='ACTIVE' AND ds.scope_type='SPECIFIED_ORGANIZATIONS'"
            + " AND CAST(os.organization_id AS CHAR)=COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.using_organization_id')),"
            + " 'null'),CAST(a.owning_organization_id AS CHAR))))"
            + " OR (? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.responsible_employee_id')) AS UNSIGNED)=?)"
            + " OR (? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.using_organization_id')) AS UNSIGNED)=?)"
            + " OR (? AND (JSON_UNQUOTE(JSON_EXTRACT(r.decision_snapshot_json,'$.decision.result'))='APPROVE'"
            + " OR EXISTS (SELECT 1 FROM fams_scrap_correction c WHERE c.request_id=r.id"
            + " AND (c.correction_status='WAITING_WAREHOUSE_CLAIM'"
            + " OR (c.processor_account_id=? AND c.correction_status IN ('PROCESSING','COMPLETED')))))))";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;
    private final Clock clock;

    public ScrapReadService(JdbcTemplate db, ObjectMapper json, RequisitionActorContext actors,
            AssetScrapPort assets, Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
    }

    public AssetScrapPort.EligiblePage eligible(Actor actor, int page, int size) {
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    public ScrapViews.RequestPage list(Actor actor, int page, int size) {
        var current = actors.revalidateScrapReaderLocked(actor);
        Object[] scope = scope(current);
        var args = new ArrayList<Object>(Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        return new ScrapViews.RequestPage(
                db.queryForList("SELECT r.*" + SCOPE
                        + " ORDER BY r.id DESC LIMIT ? OFFSET ?", args.toArray()).stream()
                        .map(row -> view(current, row)).toList(),
                Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + SCOPE, Long.class, scope)),
                page, size, canListOverdue(current), null);
    }

    public ScrapViews.RequestPage overdueDispositions(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        if (!canListOverdue(current)) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_FOLLOW_UP_FORBIDDEN", 403);
        }
        String settings = db.queryForObject("SELECT v.snapshot_json FROM fams_business_parameter_state s"
                + " JOIN fams_business_parameter_version v ON v.id=s.current_version_id"
                + " WHERE s.singleton_id=1 AND v.status='ACTIVE'", String.class);
        var now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(json.readTree(settings).path("timezone").asText()));
        var cutoff = Timestamp.from(now);
        String where = " FROM fams_scrap_disposition d JOIN fams_scrap_request r ON r.id=d.request_id"
                + " WHERE d.disposition_status='PENDING_DISPOSITION' AND r.request_status='EXECUTED'"
                + " AND (d.due_at<=? OR (d.due_at IS NULL AND d.due_date<?)) AND (? OR d.processor_account_id=?)";
        boolean administrator = current.has(Role.ASSET_ADMIN);
        var items = db.queryForList("SELECT r.*" + where + " ORDER BY d.due_date,d.request_id LIMIT ? OFFSET ?",
                cutoff, today, administrator, current.id(), size, ((long) page - 1) * size).stream()
                .map(row -> view(current, row)).toList();
        return new ScrapViews.RequestPage(items, Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + where,
                        Long.class, cutoff, today, administrator, current.id())),
                page, size, true, today);
    }

    private static boolean canListOverdue(Current current) {
        return current.has(Role.ASSET_ADMIN) || current.has(Role.WAREHOUSE_OPERATOR);
    }

    public ScrapViews.Request get(Actor actor, long id) {
        var current = actors.revalidateScrapReaderLocked(actor);
        var row = visible(current, id);
        var history = db.queryForList("SELECT id,action,actor_username,occurred_at"
                + " FROM fams_scrap_event WHERE request_id=? ORDER BY id DESC LIMIT 100", id)
                .stream().map(ScrapViews::historyEntry).toList();
        return view(current, row, history);
    }

    public ScrapViews.ChoicePage catalog(Actor actor, long id, String kind, int page, int size) {
        var current = actors.revalidateLocked(actor);
        var row = visible(current, id);
        boolean processing = "PROCESSING".equals(row.get("request_status"))
                && Objects.equals(row.get("processor_account_id"), current.id());
        boolean disposing = "employee".equals(kind) && "EXECUTED".equals(row.get("request_status"))
                && !db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                        + " AND processor_account_id=? AND disposition_status='PENDING_DISPOSITION'", id, current.id()).isEmpty();
        boolean correctionTarget = "bin".equals(kind) && current.has(Role.ASSET_ADMIN)
                && "RESTORE_REQUIRED".equals(row.get("request_status")) && "STANDALONE".equals(row.get("source_type"))
                && db.queryForList("SELECT id FROM fams_scrap_correction WHERE request_id=?"
                        + " AND active_guard=1", id).isEmpty();
        boolean reversalTarget = "bin".equals(kind) && mayRequestReversal(current, row)
                && db.queryForList("SELECT id FROM fams_scrap_reversal WHERE request_id=?"
                        + " AND active_guard=1", id).isEmpty()
                && !db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                        + " AND disposition_status='PENDING_DISPOSITION'", id).isEmpty();
        boolean recoveryTarget = ("bin".equals(kind) || "location".equals(kind) || "employee".equals(kind))
                && mayRequestRecovery(current, row)
                && db.queryForList("SELECT id FROM fams_scrap_recovery WHERE request_id=?"
                        + " AND (active_guard=1 OR recovery_status='COMPLETED')", id).isEmpty()
                && !db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                        + " AND disposition_status='LOSS_WRITTEN_OFF'", id).isEmpty();
        if (!correctionTarget && !reversalTarget && !recoveryTarget
                && (!current.has(Role.WAREHOUSE_OPERATOR) || !(processing || disposing))) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        String from = switch (kind) {
            case "location" -> " FROM fams_location a LEFT JOIN fams_warehouse p ON p.id=a.warehouse_id"
                    + " WHERE a.status='ACTIVE' AND (a.warehouse_id IS NULL OR p.status='ACTIVE')";
            case "bin" -> " FROM fams_location a JOIN fams_warehouse p ON p.id=a.warehouse_id"
                    + " WHERE a.status='ACTIVE' AND a.location_type='BIN' AND p.status='ACTIVE'";
            case "employee" -> " FROM fams_employee a JOIN fams_organization p ON p.id=a.organization_id"
                    + " WHERE a.status='ACTIVE' AND p.status='ACTIVE'";
            default -> throw new AssetHandoverPort.Failure("SCRAP_CATALOG_INVALID", 400);
        };
        var rows = db.queryForList("SELECT a.id,a.name,a.version" + from + " ORDER BY a.id LIMIT ? OFFSET ?",
                size, ((long) page - 1) * size).stream().map(ScrapViews::choice).toList();
        return new ScrapViews.ChoicePage(rows, Objects.requireNonNull(db.queryForObject(
                "SELECT COUNT(*)" + from, Long.class)), page, size);
    }

    static boolean mayRequestReversal(Current actor, Map<String, Object> row) {
        return "EXECUTED".equals(row.get("request_status")) && ScrapReviewService.physicalSource(row.get("source_type"))
                && (actor.has(Role.ASSET_ADMIN) || actor.has(Role.WAREHOUSE_OPERATOR)
                        || Objects.equals(actor.id(), row.get("creator_account_id")));
    }

    /** Recovery follows a loss write-off, so administrators, warehouse operators and the original applicant register it. */
    static boolean mayRequestRecovery(Current actor, Map<String, Object> row) {
        return "EXECUTED".equals(row.get("request_status"))
                && (actor.has(Role.ASSET_ADMIN) || actor.has(Role.WAREHOUSE_OPERATOR)
                        || Objects.equals(actor.id(), row.get("creator_account_id")));
    }

    private Map<String, Object> visible(Current current, long id) {
        var args = new ArrayList<Object>(Arrays.asList(scope(current)));
        args.add(id);
        var rows = db.queryForList("SELECT r.*" + SCOPE + " AND r.id=?", args.toArray());
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        return rows.getFirst();
    }

    private Object[] scope(Current actor) {
        return new Object[] {actor.has(Role.ASSET_ADMIN), auditOnly(actor) ? null : actor.id(),
                actor.has(Role.AUDIT_READ_ONLY), actor.id(),
                actor.has(Role.EMPLOYEE), actor.employeeId(),
                actor.has(Role.DEPARTMENT_ASSET_MANAGER), actor.organizationId(),
                actor.has(Role.WAREHOUSE_OPERATOR), actor.id()};
    }

    static boolean auditOnly(Current actor) {
        return actor.has(Role.AUDIT_READ_ONLY) && !actor.has(Role.ASSET_ADMIN)
                && !actor.has(Role.WAREHOUSE_OPERATOR) && !actor.has(Role.DEPARTMENT_ASSET_MANAGER)
                && !actor.has(Role.EMPLOYEE);
    }

    private ScrapViews.Request view(Current actor, Map<String, Object> row) {
        return view(actor, row, null);
    }

    private ScrapViews.Request view(Current actor, Map<String, Object> row, List<ScrapViews.HistoryEntry> history) {
        long id = ((Number) row.get("id")).longValue();
        var locks = db.queryForList("SELECT lock_version FROM fams_asset_business_lock"
                + " WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND released_at IS NULL", row.get("asset_id"), id, id);
        var dispositions = db.queryForList("SELECT disposition_status,DATE_FORMAT(due_date,'%Y-%m-%d') due_date,version,custody_snapshot_json,"
                + "completion_snapshot_json,processor_account_id FROM fams_scrap_disposition WHERE request_id=?", id);
        var correctionRows = db.queryForList("SELECT c.id,c.correction_status,c.processor_account_id,c.asset_version,"
                + "c.lock_version,c.target_location_id,c.target_location_version,c.ownership_version,c.version,"
                + "c.reason,l.name AS target_location_name"
                + " FROM fams_scrap_correction c JOIN fams_location l ON l.id=c.target_location_id"
                + " WHERE c.request_id=? AND (c.active_guard=1 OR c.correction_status='COMPLETED')"
                + " ORDER BY c.active_guard DESC,c.id DESC LIMIT 1", id);
        var reversalRows = db.queryForList("SELECT r.id,r.reversal_status,r.processor_account_id,r.asset_version,"
                + "r.target_location_id,r.target_location_version,r.ownership_version,r.version,r.reason,"
                + "l.name AS target_location_name"
                + " FROM fams_scrap_reversal r JOIN fams_location l ON l.id=r.target_location_id"
                + " WHERE r.request_id=? AND (r.active_guard=1 OR r.reversal_status='COMPLETED')"
                + " ORDER BY r.active_guard DESC,r.id DESC LIMIT 1", id);
        var recoveryRows = db.queryForList("SELECT r.id,r.recovery_status,r.processor_account_id,r.asset_version,"
                + "r.reason,DATE_FORMAT(r.found_at,'%Y-%m-%dT%H:%i:%s.%f') found_at,"
                + "r.temporary_location_id,r.temporary_location_version,t.name AS temporary_location_name,"
                + "r.temporary_custodian_employee_id,e.name AS temporary_custodian_name,"
                + "r.target_location_id,r.target_location_version,l.name AS target_location_name,"
                + "r.ownership_version,r.version"
                + " FROM fams_scrap_recovery r JOIN fams_location l ON l.id=r.target_location_id"
                + " JOIN fams_location t ON t.id=r.temporary_location_id"
                + " JOIN fams_employee e ON e.id=r.temporary_custodian_employee_id"
                + " WHERE r.request_id=? AND (r.active_guard=1 OR r.recovery_status='COMPLETED')"
                + " ORDER BY r.active_guard DESC,r.id DESC LIMIT 1", id);
        return ScrapViews.request(actor, json, row, locks, dispositions, correctionRows, reversalRows,
                recoveryRows, history);
    }
}
