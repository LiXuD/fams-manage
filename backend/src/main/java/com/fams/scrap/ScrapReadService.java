package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Read side of the scrap domain. It answers five queries — scrap-eligible assets, the request list,
 * overdue dispositions, one request detail and the execution choice catalog — and owns the row-level
 * visibility predicate that all of them share.
 *
 * <p>Visibility is expressed once in {@code SCOPE} as a disjunction of role-based branches over the
 * joined request and asset rows, and is applied identically by the list query, the count query and the
 * single-row {@code visible} lookup, so a row a caller cannot see is indistinguishable from a row that
 * does not exist: both surface as 404 {@code SCRAP_NOT_FOUND}. The branches are asset administrators,
 * the creator, audit-only readers restricted to their specified organizations, the responsible
 * employee, the using organization's department asset manager, and warehouse operators holding an
 * approved decision or an own or claimable correction. Organization and employee identifiers are read
 * out of {@code source_snapshot_json} with {@code JSON_EXTRACT}, which is why the snapshot keys the
 * submission froze are part of the read contract and not only of the stored shape.
 *
 * <p>An audit-only caller who holds no other role is recognised by {@code auditOnly} and receives every
 * action flag false, so the read surface stays available for inspection without offering any mutation.
 */
@Service
@Profile("fams-product")
public class ScrapReadService {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapReadService.class);

    private static final String SCOPE = " FROM fams_scrap_request r JOIN fams_asset a ON a.id=r.asset_id"
            + " WHERE (? OR r.creator_account_id=?"
            + " OR (? AND EXISTS (SELECT 1 FROM fams_account_authorization aa"
            + " JOIN fams_account_data_scope ds ON ds.authorization_id=aa.id"
            + " JOIN fams_account_scope_organization os ON os.data_scope_id=ds.id"
            + " WHERE aa.account_id=? AND aa.role_code='AUDIT_READ_ONLY' AND aa.superseded_at IS NULL"
            + " AND aa.authorization_status='ACTIVE' AND ds.scope_type='SPECIFIED_ORGANIZATIONS'"
            + " AND CAST(os.organization_id AS CHAR)=COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,"
            + "'$.using_organization_id')),"
            + " 'null'),CAST(a.owning_organization_id AS CHAR))))"
            + " OR (? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.responsible_employee_id')) AS "
            + "UNSIGNED)=?)"
            + " OR (? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.using_organization_id')) AS "
            + "UNSIGNED)=?)"
            + " OR (? AND (JSON_UNQUOTE(JSON_EXTRACT(r.decision_snapshot_json,'$.decision.result'))='APPROVE'"
            + " OR EXISTS (SELECT 1 FROM fams_scrap_correction c WHERE c.request_id=r.id"
            + " AND (c.correction_status='WAITING_WAREHOUSE_CLAIM'"
            + " OR (c.processor_account_id=? AND c.correction_status IN ('PROCESSING','COMPLETED')))))))";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;
    private final Clock clock;

    /**
     * Wires the database access, the snapshot mapper, the actor revalidation source, the asset-side
     * scrap port and the clock. The port is a dependency because the eligible query is defined by the
     * asset domain — only it knows which asset states may enter scrap — while the clock is needed to
     * resolve the overdue cutoff against the configured business timezone.
     *
     * @param db JDBC template running the scoped scrap queries
     * @param json mapper used to parse snapshot JSON and to read the business parameter timezone
     * @param actors actor context supplying the locked identity revalidation used by every query
     * @param assets asset-side scrap port answering the eligible-asset page
     * @param clock time source for the overdue cutoff and the business date
     */
    public ScrapReadService(JdbcTemplate db, ObjectMapper json, RequisitionActorContext actors,
            AssetScrapPort assets, Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
    }

    /**
     * Pages the assets currently eligible to enter scrap. The rule is owned by the asset domain, so this
     * method only revalidates the actor under the identity lock and forwards the request; it adds no
     * scrap-side filter of its own.
     *
     * @param actor warehouse operator or administrator asking which assets may be scrapped
     * @param page one-based page number, already constrained to positive by the controller
     * @param size page size, already constrained to at most 100 by the controller
     * @return eligible page envelope defined by {@link AssetScrapPort}, stringifying asset identifiers
     * @throws AssetHandoverPort.Failure as raised by the asset port when the actor may not run the query
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional
    public AssetScrapPort.EligiblePage eligible(Actor actor, int page, int size) {
        LOG.debug("Listing scrap-eligible assets actor={} page={} size={}", actor.id(), page, size);
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    /**
     * Pages the scrap requests visible to the caller, newest first by id. The actor is revalidated with
     * the scrap-reader variant, which additionally admits the audit-only role, and the shared scope
     * predicate is bound to both the row query and the count query so the total always matches the
     * visible set rather than the whole table.
     *
     * <p>The returned page carries the {@code canListOverdueDispositions} flag, letting a client show or
     * hide the follow-up entry point without a second call, and no {@code businessDate}, which only the
     * overdue projection defines.
     *
     * @param actor reading actor whose roles and identifiers select the visible rows
     * @param page one-based page number, already constrained to positive by the controller
     * @param size page size, already constrained to at most 100 by the controller
     * @return page envelope of request views without history, plus the visible total and the follow-up flag
     * @throws com.fams.auth.AuthOperationException when the caller holds no role the scrap reader accepts
     */
    @Transactional
    public ScrapViews.RequestPage list(Actor actor, int page, int size) {
        LOG.debug("Listing scrap requests actor={} page={} size={}", actor.id(), page, size);
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

    /**
     * Pages the scrap dispositions that are past their follow-up deadline, oldest due date first. This
     * is a work queue rather than a general listing, so it is restricted to asset administrators and
     * warehouse operators; anyone else receives 403 {@code SCRAP_DISPOSITION_FOLLOW_UP_FORBIDDEN} rather
     * than an empty page, because the endpoint is not part of the ordinary read surface.
     *
     * <p>Overdue is evaluated two ways against the same instant. A disposition carrying a precise
     * {@code due_at} is overdue once that timestamp has passed; a legacy row with only a
     * {@code due_date} is overdue once that date is strictly before today. Today is resolved in the
     * timezone held in the active business parameter snapshot, not in the server zone, so the queue
     * boundary matches the business calendar the due dates were entered against.
     *
     * <p>Administrators see every overdue disposition; a warehouse operator sees only the ones assigned
     * to them as processor. Only rows whose request is {@code EXECUTED} and whose disposition is still
     * {@code PENDING_DISPOSITION} qualify.
     *
     * @param actor asset administrator or warehouse operator reading the follow-up queue
     * @param page one-based page number, already constrained to positive by the controller
     * @param size page size, already constrained to at most 100 by the controller
     * @return page envelope of request views without history, with the follow-up flag true and
     *     {@code businessDate} set to the resolved business today
     * @throws AssetHandoverPort.Failure 403 when the actor holds neither role, or as raised by the
     *     projection when a request row cannot be read
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional
    public ScrapViews.RequestPage overdueDispositions(Actor actor, int page, int size) {
        LOG.debug("Listing overdue scrap dispositions actor={} page={} size={}", actor.id(), page, size);
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

    /**
     * Reads the full detail of one scrap request. This is the only query that assembles the history, so
     * the returned view carries the {@code history} key while list projections leave it null. Events are
     * read newest first and capped at 100 rows, which bounds the payload for a long-lived request that
     * has passed through decision, claim, execution, correction, reversal and recovery.
     *
     * <p>Visibility uses the same scoped predicate as the list, so a request outside the caller's scope
     * is reported as absent rather than as forbidden. The actor is revalidated with the scrap-reader
     * variant, which admits the audit-only role; that role then receives every action flag false from
     * the projection.
     *
     * @param actor reading actor whose scope decides visibility and whose roles decide the action flags
     * @param id fams_scrap_request primary key to read
     * @return request view including locks, disposition, the newest correction, reversal and recovery
     *     rows, computed actions and up to 100 history entries
     * @throws AssetHandoverPort.Failure 404 {@code SCRAP_NOT_FOUND} when the request does not exist or
     *     is not visible to the actor
     * @throws com.fams.auth.AuthOperationException when the caller holds no role the scrap reader accepts
     */
    @Transactional
    public ScrapViews.Request get(Actor actor, long id) {
        LOG.debug("Reading scrap request detail actor={} requestId={}", actor.id(), id);
        var current = actors.revalidateScrapReaderLocked(actor);
        var row = visible(current, id);
        var history = db.queryForList("SELECT id,action,actor_username,occurred_at"
                + " FROM fams_scrap_event WHERE request_id=? ORDER BY id DESC LIMIT 100", id)
                .stream().map(ScrapViews::historyEntry).toList();
        return view(current, row, history);
    }

    /**
     * Pages the selectable targets for one step of one scrap request. The catalog is not a generic
     * lookup: admission is decided per request and per kind, so a caller only ever sees the choices that
     * the step they are about to take can actually consume. Each choice carries the current row version,
     * which the caller echoes back as the pinned {@code locationVersion} of the execution payload; a
     * target that moved in between is then rejected by the review service instead of being written.
     *
     * <p>Three follow-up intents bypass the warehouse-operator requirement, because they are opened by
     * other roles: an administrator starting a correction of a {@code RESTORE_REQUIRED} standalone
     * request, a reversal requester facing a pending disposition, and a recovery requester facing a loss
     * write-off. Each is additionally blocked when an active row of that follow-up already exists, so
     * the catalog cannot be used to prepare a duplicate. Without one of those intents the caller must be
     * a warehouse operator who either processes this {@code PROCESSING} request or owns its
     * {@code PENDING_DISPOSITION} row when asking for employees; anything else is 403
     * {@code SCRAP_PROCESSOR_REQUIRED}.
     *
     * <p>Only usable rows are offered. Locations must be {@code ACTIVE} and, when they belong to a
     * warehouse, that warehouse must be {@code ACTIVE} too; bins must additionally be of type
     * {@code BIN} with an active warehouse; employees must be {@code ACTIVE} inside an {@code ACTIVE}
     * organization.
     *
     * @param actor caller preparing an execution, correction, reversal or recovery payload
     * @param id fams_scrap_request primary key the step belongs to
     * @param kind catalog to read: {@code location} for any active location, {@code bin} for active bin
     *     locations only, or {@code employee} for active employees
     * @param page one-based page number, already constrained to positive by the controller
     * @param size page size, already constrained to at most 100 by the controller
     * @return page of choices ordered by id, each stringifying its identifier and carrying its version
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_CATALOG_INVALID} for any other kind, 403
     *     {@code SCRAP_PROCESSOR_REQUIRED} when no admissible intent applies, or 404
     *     {@code SCRAP_NOT_FOUND} when the request does not exist or is not visible
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional
    public ScrapViews.ChoicePage catalog(Actor actor, long id, String kind, int page, int size) {
        LOG.debug("Reading scrap catalog actor={} requestId={} kind={} page={}", actor.id(), id, kind, page);
        var current = actors.revalidateLocked(actor);
        var row = visible(current, id);
        boolean processing = "PROCESSING".equals(row.get("request_status"))
                && Objects.equals(row.get("processor_account_id"), current.id());
        boolean disposing = "employee".equals(kind) && "EXECUTED".equals(row.get("request_status"))
                && !db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                        + " AND processor_account_id=? AND disposition_status='PENDING_DISPOSITION'",
                        id, current.id()).isEmpty();
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

    /**
     * Recovery follows a loss write-off, so administrators, warehouse operators and the original applicant register it.
     */
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
        var dispositions = db.queryForList(
                "SELECT disposition_status,DATE_FORMAT(due_date,'%Y-%m-%d') due_date,version,custody_snapshot_json,"
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
