package com.fams.reminder;

import com.fams.auth.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * On-duty notification contacts for the two shared pools. A duty person only receives escalated notices and
 * never gains approval or processing rights, so this is notification metadata over the existing pools. Each
 * change replaces the active row as an immutable chain so later escalation stays attributable to who was on duty.
 */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DutyService {
    private static final String OPERATION = "replaceDutyAssignment";
    private static final List<String> POOLS = List.of("ASSET_ADMIN", "WAREHOUSE_OPERATOR");
    private static final String ACTIVE_MEMBER =
            " SELECT a.id FROM fams_system_account a"
            + " JOIN fams_employee e ON e.id=a.employee_id"
            + " JOIN fams_organization o ON o.id=e.organization_id"
            + " JOIN fams_account_role r ON r.account_id=a.id AND r.role_code=?"
            + " WHERE a.id=? AND a.status='ENABLED' AND a.initial_password_required=FALSE"
            + " AND e.status='ACTIVE' AND o.status='ACTIVE'";
    private static final String ACTIVE_ROW =
            " SELECT d.id,d.sequence_no,d.primary_account_id,d.backup_account_id,d.effective_at,"
            + " p.name primary_name,b.name backup_name FROM fams_duty_assignment d"
            + " JOIN fams_system_account pa ON pa.id=d.primary_account_id"
            + " JOIN fams_employee p ON p.id=pa.employee_id"
            + " LEFT JOIN fams_system_account ba ON ba.id=d.backup_account_id"
            + " LEFT JOIN fams_employee b ON b.id=ba.employee_id"
            + " WHERE d.role_code=? AND d.duty_status='ACTIVE'";

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    DutyService(JdbcTemplate db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    public record DutyAccount(String id, String name, boolean active) {}

    public record DutyAssignment(String roleCode, String version, DutyAccount primary,
            DutyAccount backup, OffsetDateTime effectiveAt) {}

    public record DutyAssignmentList(List<DutyAssignment> items) {
        public DutyAssignmentList { items = List.copyOf(items); }
    }

    public DutyAssignmentList current(long accountId) {
        var roles = roles(accountId);
        var administrator = roles.contains(Role.SYSTEM_ADMIN) || roles.contains(Role.ASSET_ADMIN);
        List<DutyAssignment> items = new ArrayList<>();
        for (var pool : administrator ? POOLS : List.of("WAREHOUSE_OPERATOR")) {
            var row = read(pool);
            if (row != null) {
                items.add(row);
            }
        }
        return new DutyAssignmentList(items);
    }

    public DutyAssignment replace(long actorId, UUID requestId, String roleCode,
            long primaryAccountId, Long backupAccountId, long expectedVersion, String key) {
        if (!POOLS.contains(roleCode) || primaryAccountId < 1
                || (backupAccountId != null && backupAccountId == primaryAccountId)) {
            throw new ReminderFailure(400, "DUTY_ASSIGNMENT_INVALID");
        }
        var digest = digest(roleCode, expectedVersion, primaryAccountId, backupAccountId);
        var replay = replay(actorId, key, digest);
        if (replay != null) {
            return replay;
        }
        var locked = db.queryForList(" SELECT id,sequence_no FROM fams_duty_assignment"
                + " WHERE role_code=? AND duty_status='ACTIVE' FOR UPDATE", roleCode);
        var previousId = locked.isEmpty() ? null : number(locked.getFirst().get("id"));
        var sequence = locked.isEmpty() ? 0L : number(locked.getFirst().get("sequence_no"));
        if (sequence != expectedVersion) {
            throw new ReminderFailure(409, "DUTY_VERSION_CONFLICT");
        }
        requireMember(roleCode, primaryAccountId);
        if (backupAccountId != null) {
            requireMember(roleCode, backupAccountId);
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (previousId != null && db.update(" UPDATE fams_duty_assignment SET duty_status='SUPERSEDED',"
                + " superseded_at=? WHERE id=? AND duty_status='ACTIVE'", Timestamp.from(now), previousId) != 1) {
            throw new ReminderFailure(409, "DUTY_VERSION_CONFLICT");
        }
        db.update(" INSERT INTO fams_duty_assignment(role_code,sequence_no,primary_account_id,"
                + "backup_account_id,duty_status,effective_at,created_by,created_at)"
                + " VALUES (?,?,?,?, 'ACTIVE',?,?,?)", roleCode, sequence + 1, primaryAccountId,
                backupAccountId, Timestamp.from(now), actorId, Timestamp.from(now));
        var created = number(db.queryForList(" SELECT id FROM fams_duty_assignment WHERE role_code=?"
                + " AND sequence_no=?", roleCode, sequence + 1).getFirst().get("id"));
        audit(actorId, requestId, roleCode, created, sequence, now);
        var result = read(roleCode);
        record(actorId, key, digest, result, created, sequence + 1, now);
        return result;
    }

    private Set<Role> roles(long accountId) {
        Set<Role> roles = new HashSet<>();
        db.queryForList("SELECT role_code FROM fams_account_role WHERE account_id=?", String.class, accountId)
                .forEach(code -> roles.add(Role.valueOf(code)));
        return roles;
    }

    private DutyAssignment read(String roleCode) {
        var rows = db.queryForList(ACTIVE_ROW, roleCode);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        var primaryId = number(row.get("primary_account_id"));
        var backupId = row.get("backup_account_id") == null ? null : number(row.get("backup_account_id"));
        return new DutyAssignment(roleCode, Long.toString(number(row.get("sequence_no"))),
                new DutyAccount(Long.toString(primaryId), (String) row.get("primary_name"),
                        isMember(roleCode, primaryId)),
                backupId == null ? null : new DutyAccount(Long.toString(backupId),
                        (String) row.get("backup_name"), isMember(roleCode, backupId)),
                OffsetDateTime.ofInstant(instant(row.get("effective_at")), ZoneOffset.UTC));
    }

    // MySQL DATETIME columns come back as UTC wall-clock LocalDateTime through the driver mapping.
    private Instant instant(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC)
                : ((Timestamp) value).toInstant();
    }

    private boolean isMember(String roleCode, long accountId) {
        return !db.queryForList(ACTIVE_MEMBER, roleCode, accountId).isEmpty();
    }

    private void requireMember(String roleCode, long accountId) {
        if (!isMember(roleCode, accountId)) {
            throw new ReminderFailure(409, "DUTY_MEMBER_INACTIVE");
        }
    }

    private DutyAssignment replay(long actorId, String key, String digest) {
        var rows = db.queryForList(" SELECT payload_sha256,response_body_json FROM fams_idempotency_record"
                + " WHERE actor_account_id=? AND operation_id=? AND idempotency_key=? AND expires_at>?",
                actorId, OPERATION, key, Timestamp.from(clock.instant()));
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        if (!digest.equals(row.get("payload_sha256"))) {
            throw new ReminderFailure(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        }
        return json.readValue(String.valueOf(row.get("response_body_json")), DutyAssignment.class);
    }

    private void record(long actorId, String key, String digest, DutyAssignment result,
            long resourceId, long version, Instant now) {
        var moment = Timestamp.from(now);
        db.update(" INSERT INTO fams_idempotency_record(actor_account_id,operation_id,idempotency_key,"
                + "payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,"
                + "resource_version,created_at,completed_at,expires_at)"
                + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'DUTY_ASSIGNMENT',?,?,?,?,?)", actorId, OPERATION,
                key, digest, json.writeValueAsString(result), resourceId, version, moment, moment,
                Timestamp.from(now.plus(Duration.ofHours(24))));
    }

    private void audit(long actorId, UUID requestId, String roleCode,
            long resourceId, long beforeVersion, Instant now) {
        db.update(" INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json)"
                + " VALUES (?,?,'SYSTEM_ADMIN','DUTY_ASSIGNMENT_REPLACED','DUTY_ASSIGNMENT',?,?,?,?,?)",
                requestId.toString(), actorId, resourceId, beforeVersion, beforeVersion + 1,
                Timestamp.from(now), json.writeValueAsString(Map.of("roleCode", roleCode)));
    }

    private String digest(String roleCode, long version, long primaryAccountId, Long backupAccountId) {
        var canonical = "F1\n" + OPERATION + "\n" + roleCode + "\n" + version + "\n" + primaryAccountId
                + "\n" + (backupAccountId == null ? "" : backupAccountId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }
}
