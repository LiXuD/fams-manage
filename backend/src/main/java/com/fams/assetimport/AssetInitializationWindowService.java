package com.fams.assetimport;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** System administrator control for a bounded initialization window. */
@Service
@Profile("fams-product")
public class AssetInitializationWindowService {
    /** Window projection: stringified id, OPEN/CLOSED status, assigned operator and UTC expiry. */
    public record Window(String id, String status, String assignedOperatorId, Instant expiresAt) { }
    /** Assignable operator option: stringified account id and username. */
    public record EligibleOperator(String id, String username) { }

    private final JdbcTemplate db;
    private final Clock clock;

    /**
     * Wires the service to the window tables and its expiry clock.
     *
     * @param db JDBC template for the window and account tables
     * @param clock timestamp source for expiry validation
     */
    public AssetInitializationWindowService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * Lists the operators eligible for window assignment: enabled accounts holding the
     * warehouse-operator role, ordered by username then account id.
     *
     * @return eligible operator options
     */
    @Transactional(readOnly = true)
    public List<EligibleOperator> operators() {
        return db.query("SELECT a.id,a.username FROM fams_system_account a "
                + "JOIN fams_account_role r ON r.account_id=a.id "
                + "WHERE a.status='ENABLED' AND r.role_code='WAREHOUSE_OPERATOR' "
                + "ORDER BY a.username,a.id", (result, index) -> new EligibleOperator(
                        Long.toString(result.getLong("id")), result.getString("username")));
    }

    /**
     * Loads the single currently usable window. An OPEN row whose expiry has passed is reported
     * as absent rather than stale, so callers never act on an expired window.
     *
     * @return projection of the open, unexpired window
     * @throws IllegalStateException with code {@code ASSET_IMPORT_WINDOW_NOT_FOUND} when no open
     *         window exists or the open window has expired
     */
    @Transactional(readOnly = true)
    public Window current() {
        var rows = db.queryForList("SELECT id,assigned_operator_id,expires_at FROM "
                + "fams_initialization_window WHERE window_status='OPEN'");
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_WINDOW_NOT_FOUND"); }
        var row = rows.getFirst();
        Instant expiry = ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC);
        if (!expiry.isAfter(clock.instant())) {
            throw new IllegalStateException("ASSET_IMPORT_WINDOW_NOT_FOUND");
        }
        return new Window(row.get("id").toString(), "OPEN",
                row.get("assigned_operator_id").toString(), expiry);
    }

    /**
     * Opens a new initialization window for one eligible warehouse operator. At most one window
     * may be alive: a still-unexpired open window blocks the call, while an expired one is
     * closed first as part of the same transaction.
     *
     * @param administratorId system-admin account opening the window, recorded as opener
     * @param operatorId warehouse operator the window is assigned to
     * @param reason mandatory opening reason, at most 500 characters after trimming
     * @param expiresAt expiry instant, strictly in the future and at most 30 days ahead
     * @return projection of the newly opened window
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_WINDOW_INVALID} for bad
     *         ids, reason or expiry bounds, or {@code ASSET_IMPORT_OPERATOR_INVALID} when the
     *         operator is not an enabled warehouse operator
     * @throws IllegalStateException with code {@code ASSET_IMPORT_WINDOW_OPEN} when an unexpired
     *         window is already open
     */
    @Transactional
    public Window open(long administratorId, long operatorId, String reason, Instant expiresAt) {
        Instant now = clock.instant();
        if (administratorId < 1 || operatorId < 1 || reason == null || reason.isBlank()
                || reason.strip().length() > 500 || expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plus(30, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("ASSET_IMPORT_WINDOW_INVALID");
        }
        Integer eligible = db.queryForObject("SELECT COUNT(*) FROM fams_system_account a "
                + "JOIN fams_account_role r ON r.account_id=a.id "
                + "WHERE a.id=? AND a.status='ENABLED' AND r.role_code='WAREHOUSE_OPERATOR'",
                Integer.class, operatorId);
        if (eligible == null || eligible != 1) {
            throw new IllegalArgumentException("ASSET_IMPORT_OPERATOR_INVALID");
        }
        var current = db.queryForList("SELECT id,expires_at FROM fams_initialization_window "
                + "WHERE window_status='OPEN' FOR UPDATE");
        if (!current.isEmpty()) {
            long id = ((Number) current.getFirst().get("id")).longValue();
            LocalDateTime expiry = (LocalDateTime) current.getFirst().get("expires_at");
            if (expiry.toInstant(ZoneOffset.UTC).isAfter(now)) {
                throw new IllegalStateException("ASSET_IMPORT_WINDOW_OPEN");
            }
            db.update("UPDATE fams_initialization_window SET window_status='CLOSED',"
                    + "closed_by=?,closed_at=? WHERE id=?", administratorId, Timestamp.from(now), id);
        }
        db.update("INSERT INTO fams_initialization_window (window_status,assigned_operator_id,"
                + "reason,opened_by,opened_at,expires_at) VALUES ('OPEN',?,?,?,?,?)",
                operatorId, reason.strip(), administratorId, Timestamp.from(now), Timestamp.from(expiresAt));
        Long id = java.util.Objects.requireNonNull(db.queryForObject(
                "SELECT LAST_INSERT_ID()", Long.class));
        return new Window(id.toString(), "OPEN", Long.toString(operatorId), expiresAt);
    }

    /**
     * Closes an open window ahead of its expiry, recording the closing administrator and time.
     *
     * @param administratorId system-admin account closing the window
     * @param windowId window to close
     * @return projection of the window with status CLOSED
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_WINDOW_INVALID} for a bad
     *         administrator or window id
     * @throws IllegalStateException with code {@code ASSET_IMPORT_WINDOW_NOT_FOUND} when the
     *         window does not exist, or {@code ASSET_IMPORT_WINDOW_CLOSED} when it is not open
     */
    @Transactional
    public Window close(long administratorId, long windowId) {
        if (administratorId < 1 || windowId < 1) {
            throw new IllegalArgumentException("ASSET_IMPORT_WINDOW_INVALID");
        }
        var rows = db.queryForList("SELECT assigned_operator_id,expires_at,window_status "
                + "FROM fams_initialization_window WHERE id=? FOR UPDATE", windowId);
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_WINDOW_NOT_FOUND"); }
        var row = rows.getFirst();
        if (!"OPEN".equals(row.get("window_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_WINDOW_CLOSED");
        }
        db.update("UPDATE fams_initialization_window SET window_status='CLOSED',"
                + "closed_by=?,closed_at=? WHERE id=?", administratorId,
                Timestamp.from(clock.instant()), windowId);
        return new Window(Long.toString(windowId), "CLOSED",
                row.get("assigned_operator_id").toString(),
                ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC));
    }
}
