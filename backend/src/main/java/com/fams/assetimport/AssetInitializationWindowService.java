package com.fams.assetimport;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** System administrator control for a bounded initialization window. */
@Service
@Profile("fams-product")
public class AssetInitializationWindowService {
    public record Window(String id, String status, String assignedOperatorId, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final Clock clock;

    public AssetInitializationWindowService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

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
        Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Window(id.toString(), "OPEN", Long.toString(operatorId), expiresAt);
    }

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
