package com.fams.reminder;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class ReminderFollowupService {
    private static final String ACTIVE_STAGE = """
            ((r.business_type='INBOUND' AND
            """ + InboundReminderState.CURRENT_STAGE + """
            ) OR (r.business_type='REQUISITION_ALLOCATION' AND r.stage='HANDOVER_RECEIPT' AND EXISTS (
                SELECT 1 FROM fams_requisition_allocation x
                JOIN fams_requisition_handover h ON h.allocation_id=x.id
                WHERE x.id=r.business_id AND x.allocation_status='DELIVERED_WAITING_RECEIPT'
                    AND h.receipt_deadline_at=r.deadline_at
            )) OR (r.business_type='TRANSFER_ITEM' AND r.stage='HANDOVER_RECEIPT' AND EXISTS (
                SELECT 1 FROM fams_transfer_item x WHERE x.id=r.business_id AND x.item_status='DELIVERED'
                    AND x.receipt_deadline_at=r.deadline_at
            )))
            """;
    static final String GROUP_NOTICE_VISIBLE = """
            (r.reminder_type<>'OVERDUE_UNRESOLVED_WORKDAY' OR (
            """ + ACTIVE_STAGE + """
                AND COALESCE((
                    SELECT f.next_followup_at FROM fams_reminder_followup f
                    WHERE f.business_type=r.business_type AND f.business_id=r.business_id
                        AND f.stage=r.stage AND f.deadline_at=r.deadline_at ORDER BY f.id DESC LIMIT 1
                )<=UTC_TIMESTAMP(3), TRUE)
            ))
            """;
    private static final String OCCURRENCE = """
            SELECT f.id,f.reminder_id,f.findings,f.next_followup_at,f.recorded_at,f.actor_account_id
            FROM fams_reminder_followup f
            """;
    private static final RowMapper<Followup> FOLLOWUP = (row, index) -> new Followup(
            row.getString("id"), row.getString("reminder_id"), row.getString("findings"),
            row.getTimestamp("next_followup_at").toInstant(), row.getTimestamp("recorded_at").toInstant(),
            row.getString("actor_account_id"));

    record Input(@NotBlank @Size(max = 1000) String findings, @NotNull Instant nextFollowupAt) {}

    record Followup(String id, String reminderId, String findings, Instant nextFollowupAt, Instant recordedAt,
            String actorAccountId) {}

    record View(Followup latest) {}

    private record Subject(String type, long id, String stage, Timestamp deadline) {}

    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final Clock clock;

    ReminderFollowupService(JdbcTemplate db, RequisitionActorContext actors, Clock clock) {
        this.db = db;
        this.actors = actors;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View get(RequisitionActorContext.Actor actor, long id) {
        requireAdmin(actor);
        var subject = subject(id);
        requireActive(id, clock.instant());
        var rows = db.query(OCCURRENCE + """
                WHERE f.business_type=? AND f.business_id=? AND f.stage=? AND f.deadline_at=?
                ORDER BY f.id DESC LIMIT 1
                """, FOLLOWUP, subject.type(), subject.id(), subject.stage(), subject.deadline());
        return new View(rows.isEmpty() ? null : rows.getFirst());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Followup record(RequisitionActorContext.Actor actor, long id, UUID key, Input input) {
        long administrator = requireAdmin(actor);
        var next = input.nextFollowupAt().truncatedTo(ChronoUnit.MILLIS);
        String findings = input.findings().strip();
        // revalidateLocked also serializes mutations by the same actor, including reuse of an idempotency key.
        var existing = byKey(administrator, key);
        if (existing != null) {
            if (!existing.reminderId().equals(Long.toString(id)) || !existing.findings().equals(findings)
                    || !existing.nextFollowupAt().equals(next)) {
                throw new ReminderFailure(409, "REMINDER_FOLLOWUP_KEY_CONFLICT");
            }
            return existing;
        }
        var subject = subject(id);
        lockSubject(subject);
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!next.isAfter(now)) {
            throw new ReminderFailure(400, "REMINDER_FOLLOWUP_TIME_INVALID");
        }
        requireActive(id, now);
        db.update("""
                INSERT INTO fams_reminder_followup (reminder_id,business_type,business_id,stage,deadline_at,
                    actor_account_id,request_key,findings,next_followup_at,recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, subject.type(), subject.id(), subject.stage(), subject.deadline(),
                administrator, key.toString(), findings, Timestamp.from(next), Timestamp.from(now));
        var saved = byKey(administrator, key);
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,'ASSET_ADMIN','REMINDER_FOLLOWUP_RECORDED',?,?,?,
                    JSON_OBJECT('followupId',?,'stage',?,'reminderId',?,'nextFollowupAt',?))
                """, actor.requestId().toString(), administrator, subject.type(), subject.id(), Timestamp.from(now),
                saved.id(), subject.stage(), Long.toString(id), next.toString());
        return saved;
    }

    private long requireAdmin(RequisitionActorContext.Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new ReminderFailure(403, "REMINDER_FOLLOWUP_FORBIDDEN");
        }
        return current.id();
    }

    private Subject subject(long id) {
        var rows = db.query("""
                SELECT r.business_type,r.business_id,r.stage,r.deadline_at FROM fams_business_reminder r
                WHERE r.id=? AND r.reminder_type IN ('OVERDUE','OVERDUE_UNRESOLVED_WORKDAY')
                    AND r.business_type IN ('INBOUND','REQUISITION_ALLOCATION','TRANSFER_ITEM')
                """, (row, index) -> new Subject(row.getString(1), row.getLong(2), row.getString(3),
                row.getTimestamp(4)), id);
        if (rows.isEmpty()) {
            throw new ReminderFailure(404, "REMINDER_FOLLOWUP_NOT_AVAILABLE");
        }
        return rows.getFirst();
    }

    private void lockSubject(Subject subject) {
        String table = switch (subject.type()) {
            case "INBOUND" -> "fams_inbound_receipt";
            case "REQUISITION_ALLOCATION" -> "fams_requisition_allocation";
            case "TRANSFER_ITEM" -> "fams_transfer_item";
            default -> throw new IllegalStateException("Invalid reminder subject type");
        };
        db.queryForList("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", subject.id());
    }

    private void requireActive(long id, Instant now) {
        if (db.queryForList("SELECT r.id FROM fams_business_reminder r WHERE r.id=? AND r.deadline_at<=? AND "
                + ACTIVE_STAGE, id, Timestamp.from(now)).isEmpty()) {
            throw new ReminderFailure(409, "REMINDER_FOLLOWUP_STAGE_CHANGED");
        }
    }

    private Followup byKey(long administrator, UUID key) {
        var rows = db.query(OCCURRENCE + "WHERE f.actor_account_id=? AND f.request_key=?",
                FOLLOWUP, administrator, key.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
