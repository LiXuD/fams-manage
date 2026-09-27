package com.fams.job;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business owners supply current authorization and side effects inside the fenced completion transaction. */
@Service
@Profile("fams-product")
public class DurableJobService {
    public record Ticket(UUID id, String status) { }
    public record Claim(UUID id, String kind, String businessReference, long actorId,
                        String owner, long generation, int attempts, Instant leaseUntil) { }
    private final JdbcTemplate db;
    private final Clock clock;

    public DurableJobService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional
    public Ticket submit(String kind, String businessReference, long actorId, String key, String requestHash) {
        requireToken(kind, 40);
        requireToken(key, 128);
        if (actorId < 1 || businessReference == null || businessReference.isBlank() || businessReference.length() > 128
                || requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid job request");
        }
        var now = Timestamp.from(clock.instant());
        db.update("INSERT INTO fams_durable_job (id,job_kind,business_reference,actor_account_id,request_key,"
                + "request_sha256,next_attempt_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE id=id", UUID.randomUUID().toString(), kind, businessReference,
                actorId, key, requestHash, now, now, now);
        var row = db.queryForMap("SELECT id,job_status,business_reference,request_sha256 FROM fams_durable_job "
                + "WHERE actor_account_id=? AND job_kind=? AND request_key=? FOR UPDATE", actorId, kind, key);
        if (!businessReference.equals(row.get("business_reference")) || !requestHash.equals(row.get("request_sha256"))) {
            throw new IllegalStateException("JOB_REQUEST_CONFLICT");
        }
        return new Ticket(UUID.fromString((String) row.get("id")), (String) row.get("job_status"));
    }

    @Transactional
    public Optional<Claim> claim(String kind, String owner, Duration lease) {
        requireToken(kind, 40);
        requireToken(owner, 128);
        requireLease(lease);
        Instant now = clock.instant();
        var rows = db.queryForList("SELECT * FROM fams_durable_job WHERE job_kind=? AND "
                + "((job_status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=?) "
                + "OR (job_status='RUNNING' AND lease_until<=?)) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                kind, Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) { return Optional.empty(); }
        var row = rows.getFirst();
        UUID id = UUID.fromString((String) row.get("id"));
        long generation = ((Number) row.get("execution_generation")).longValue() + 1;
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        Instant until = now.plus(lease);
        db.update("UPDATE fams_durable_job SET job_status='RUNNING',execution_generation=?,attempts=?,"
                + "lease_owner=?,lease_until=?,updated_at=? WHERE id=?", generation, attempts, owner,
                Timestamp.from(until), Timestamp.from(now), id.toString());
        return Optional.of(new Claim(id, kind, (String) row.get("business_reference"),
                ((Number) row.get("actor_account_id")).longValue(), owner, generation, attempts, until));
    }

    @Transactional
    public Instant renew(Claim claim, Duration lease) {
        requireLease(lease);
        current(claim);
        Instant until = clock.instant().plus(lease);
        db.update("UPDATE fams_durable_job SET lease_until=?,updated_at=? WHERE id=?",
                Timestamp.from(until), Timestamp.from(clock.instant()), claim.id().toString());
        return until;
    }

    @Transactional
    public void complete(Claim claim, String resultReference, Runnable authorizedBusinessWrite) {
        if (resultReference == null || resultReference.isBlank() || resultReference.length() > 255
                || authorizedBusinessWrite == null) { throw new IllegalArgumentException("Invalid completion"); }
        current(claim);
        authorizedBusinessWrite.run();
        // Reject and roll back work that exhausted its lease while the row lock was held.
        current(claim);
        db.update("UPDATE fams_durable_job SET job_status='SUCCEEDED',result_reference=?,lease_owner=NULL,"
                + "lease_until=NULL,last_error_code=NULL,updated_at=? WHERE id=?", resultReference,
                Timestamp.from(clock.instant()), claim.id().toString());
    }

    @Transactional
    public void fail(Claim claim, String errorCode, boolean retryable) {
        requireToken(errorCode, 100);
        var row = current(claim);
        int attempts = ((Number) row.get("attempts")).intValue();
        long delay = Math.min(3600, 30L << Math.min(7, Math.max(0, attempts - 1)));
        Instant now = clock.instant();
        db.update("UPDATE fams_durable_job SET job_status=?,last_error_code=?,next_attempt_at=?,"
                + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?",
                retryable ? "RETRY_WAIT" : "FAILED", errorCode, Timestamp.from(now.plusSeconds(delay)),
                Timestamp.from(now), claim.id().toString());
    }

    private Map<String, Object> current(Claim claim) {
        if (claim == null) { throw new IllegalArgumentException("Missing job claim"); }
        var rows = db.queryForList("SELECT * FROM fams_durable_job WHERE id=? FOR UPDATE", claim.id().toString());
        if (rows.isEmpty()) { throw new IllegalStateException("JOB_LEASE_LOST"); }
        var row = rows.getFirst();
        if (!"RUNNING".equals(row.get("job_status")) || !claim.owner().equals(row.get("lease_owner"))
                || claim.generation() != ((Number) row.get("execution_generation")).longValue()
                || !((Timestamp) row.get("lease_until")).toInstant().isAfter(clock.instant())) {
            throw new IllegalStateException("JOB_LEASE_LOST");
        }
        return row;
    }

    private void requireLease(Duration lease) {
        if (lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0
                || lease.compareTo(Duration.ofHours(1)) > 0) { throw new IllegalArgumentException("Invalid job lease"); }
    }

    private void requireToken(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max || !value.matches("[\\x21-\\x7e]+")) {
            throw new IllegalArgumentException("Invalid job token");
        }
    }
}
