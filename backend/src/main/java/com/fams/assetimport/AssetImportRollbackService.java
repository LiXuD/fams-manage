package com.fams.assetimport;

import com.fams.asset.AssetInitialPublicationPort;
import com.fams.job.DurableJobService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Administrator-approved, operator-triggered rollback of an untouched initial batch. */
@Service
@Profile("fams-product")
public class AssetImportRollbackService {
    /** Rollback state projection: status, request round version and the queued job id once submitted. */
    public record Rollback(UUID batchId, String status, long version, UUID jobId) { }
    /**
     * One administrator work-queue entry: batch identity, published asset count, requesting
     * operator with the request reason and its timestamp.
     */
    public record Pending(UUID batchId, long version, int assetCount, String operatorUsername,
                          String reason, Instant requestedAt) { }

    private final JdbcTemplate db;
    private final AssetInitialPublicationPort assets;
    private final DurableJobService jobs;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * Wires the service to its persistence, asset-cancellation, job, JSON and clock
     * collaborators.
     *
     * @param db JDBC template for the batch, rollback, publication and event tables
     * @param assets port that reverses the published initial assets
     * @param jobs durable job service receiving the rollback task on queueing
     * @param json mapper used for audit event details
     * @param clock timestamp source for expiry and audit stamps
     */
    public AssetImportRollbackService(JdbcTemplate db, AssetInitialPublicationPort assets,
            DurableJobService jobs, ObjectMapper json, Clock clock) {
        this.db = db;
        this.assets = assets;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Loads the rollback state of a batch owned by the calling operator.
     *
     * @param operatorId owning operator account id
     * @param batchId batch whose rollback is loaded
     * @return current rollback projection
     * @throws IllegalStateException with code {@code ASSET_IMPORT_ROLLBACK_NOT_FOUND} when no
     *         rollback exists or the batch belongs to another operator
     */
    @Transactional(readOnly = true)
    public Rollback getOwned(long operatorId, UUID batchId) {
        var rows = db.queryForList("SELECT r.rollback_status,r.version,r.job_id FROM "
                + "fams_asset_import_rollback r JOIN fams_asset_import_batch b ON b.id=r.batch_id "
                + "WHERE r.batch_id=? AND b.actor_account_id=?", batchId.toString(), operatorId);
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_NOT_FOUND"); }
        var row = rows.getFirst();
        return new Rollback(batchId, (String) row.get("rollback_status"),
                ((Number) row.get("version")).longValue(), row.get("job_id") == null ? null
                        : UUID.fromString((String) row.get("job_id")));
    }

    /**
     * Pages the administrator work queue of PENDING rollback requests, ordered by request time
     * then batch id, 50 entries per page.
     *
     * @param page 1-based page number, at most 1,000,000
     * @return the pending entries of the requested page
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_REQUEST_INVALID} for an
     *         out-of-range page number
     */
    @Transactional(readOnly = true)
    public List<Pending> pending(int page) {
        if (page < 1 || page > 1_000_000) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        return db.query("SELECT r.batch_id,r.version,r.request_reason,r.requested_at,a.username,"
                + "(SELECT COUNT(*) FROM fams_asset_import_publication p "
                + "WHERE p.batch_id=r.batch_id) AS asset_count "
                + "FROM fams_asset_import_rollback r "
                + "JOIN fams_system_account a ON a.id=r.processor_account_id "
                + "WHERE r.rollback_status='PENDING' ORDER BY r.requested_at,r.batch_id LIMIT 50 OFFSET ?",
                (result, index) -> new Pending(UUID.fromString(result.getString("batch_id")),
                        result.getLong("version"), result.getInt("asset_count"),
                        result.getString("username"), result.getString("request_reason"),
                        result.getTimestamp("requested_at").toInstant()), (page - 1) * 50);
    }

    /**
     * Requests rollback of a published batch whose assets are still untouched. A PENDING request
     * replayed with the identical reason is idempotent; only a REJECTED request may be repeated,
     * which increments the rollback version.
     *
     * @param operatorId requesting operator account id, must own the batch
     * @param batchId published batch to roll back
     * @param reason mandatory request reason, at most 500 characters after trimming
     * @return rollback projection with status PENDING
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_ROLLBACK_REASON_INVALID}
     *         for a missing or oversized reason
     * @throws IllegalStateException with code {@code ASSET_IMPORT_NOT_FOUND} when unowned,
     *         {@code ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED} when the batch is not published or has
     *         no publication rows, {@code ASSET_IMPORT_ROLLBACK_DOWNSTREAM} when any published
     *         asset changed after publication, or {@code ASSET_IMPORT_ROLLBACK_LOCKED} when a
     *         non-rejected request already exists
     */
    @Transactional
    public Rollback request(long operatorId, UUID batchId, String reason) {
        requireReason(reason);
        var batches = db.queryForList("SELECT batch_status FROM fams_asset_import_batch "
                + "WHERE id=? AND actor_account_id=? FOR UPDATE", batchId.toString(), operatorId);
        if (batches.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        if (!"PUBLISHED".equals(batches.getFirst().get("batch_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED");
        }
        ensureNoDownstream(batchId);
        var rows = db.queryForList("SELECT rollback_status,version,request_reason FROM "
                + "fams_asset_import_rollback WHERE batch_id=? FOR UPDATE", batchId.toString());
        long version = 1;
        if (!rows.isEmpty()) {
            var previous = rows.getFirst();
            version = ((Number) previous.get("version")).longValue();
            if ("PENDING".equals(previous.get("rollback_status"))
                    && reason.strip().equals(previous.get("request_reason"))) {
                return new Rollback(batchId, "PENDING", version, null);
            }
            if (!"REJECTED".equals(previous.get("rollback_status"))) {
                throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_LOCKED");
            }
            version++;
            db.update("UPDATE fams_asset_import_rollback SET rollback_status='PENDING',"
                    + "processor_account_id=?,requested_by=?,request_reason=?,requested_at=?,"
                    + "decided_by=NULL,decision_reason=NULL,decided_at=NULL,job_id=NULL,"
                    + "executed_at=NULL,version=? WHERE batch_id=?", operatorId, operatorId,
                    reason.strip(), Timestamp.from(clock.instant()), version, batchId.toString());
        } else {
            db.update("INSERT INTO fams_asset_import_rollback (batch_id,rollback_status,"
                    + "processor_account_id,requested_by,request_reason,requested_at) "
                    + "VALUES (?,'PENDING',?,?,?,?)", batchId.toString(), operatorId, operatorId,
                    reason.strip(), Timestamp.from(clock.instant()));
        }
        event(batchId, operatorId, "ASSET_IMPORT_ROLLBACK_REQUESTED",
                Map.of("version", version, "reason", reason.strip()));
        return new Rollback(batchId, "PENDING", version, null);
    }

    /**
     * Records the administrator decision on a PENDING rollback request. The deciding
     * administrator must differ from the requesting operator, and approval re-proves that no
     * published asset acquired downstream changes.
     *
     * @param administratorId deciding asset-admin account id
     * @param batchId batch under rollback review
     * @param approve true to approve the rollback, false to reject it
     * @param reason mandatory decision reason, at most 500 characters after trimming
     * @return rollback projection with the recorded decision
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_ROLLBACK_REASON_INVALID}
     *         for a missing or oversized reason
     * @throws IllegalStateException with code {@code ASSET_IMPORT_NOT_FOUND} or
     *         {@code ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED} for a missing or unpublished batch,
     *         {@code ASSET_IMPORT_ROLLBACK_LOCKED} when no PENDING request exists or the
     *         administrator is the requester, or {@code ASSET_IMPORT_ROLLBACK_DOWNSTREAM} when
     *         approving despite changed assets
     */
    @Transactional
    public Rollback decide(long administratorId, UUID batchId, boolean approve, String reason) {
        requireReason(reason);
        lockPublished(batchId);
        var rows = db.queryForList("SELECT rollback_status,processor_account_id,version "
                + "FROM fams_asset_import_rollback WHERE batch_id=? FOR UPDATE", batchId.toString());
        if (rows.isEmpty() || !"PENDING".equals(rows.getFirst().get("rollback_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_LOCKED");
        }
        var row = rows.getFirst();
        if (((Number) row.get("processor_account_id")).longValue() == administratorId) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_LOCKED");
        }
        if (approve) { ensureNoDownstream(batchId); }
        db.update("UPDATE fams_asset_import_rollback SET rollback_status=?,decided_by=?,"
                + "decision_reason=?,decided_at=? WHERE batch_id=?", approve ? "APPROVED" : "REJECTED",
                administratorId, reason.strip(), Timestamp.from(clock.instant()), batchId.toString());
        event(batchId, administratorId, "ASSET_IMPORT_ROLLBACK_DECIDED",
                Map.of("approved", approve, "reason", reason.strip()));
        return new Rollback(batchId, approve ? "APPROVED" : "REJECTED",
                ((Number) row.get("version")).longValue(), null);
    }

    /**
     * Submits the durable rollback job for an APPROVED request; only the original requesting
     * operator may queue it, and the untouched-asset proof is repeated at queueing time. The
     * job is fenced by the batch source digest.
     *
     * @param operatorId requesting operator account id
     * @param batchId batch whose approved rollback is queued
     * @return rollback projection with status QUEUED and the submitted job id
     * @throws IllegalStateException with code {@code ASSET_IMPORT_NOT_FOUND} or
     *         {@code ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED} for a missing or unpublished batch,
     *         {@code ASSET_IMPORT_ROLLBACK_LOCKED} when the rollback is not APPROVED or belongs
     *         to another operator, or {@code ASSET_IMPORT_ROLLBACK_DOWNSTREAM} when any
     *         published asset changed after publication
     */
    @Transactional
    public Rollback queue(long operatorId, UUID batchId) {
        var batch = lockPublished(batchId);
        var rows = db.queryForList("SELECT rollback_status,processor_account_id,version "
                + "FROM fams_asset_import_rollback WHERE batch_id=? FOR UPDATE", batchId.toString());
        if (rows.isEmpty() || !"APPROVED".equals(rows.getFirst().get("rollback_status"))
                || ((Number) rows.getFirst().get("processor_account_id")).longValue() != operatorId) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_LOCKED");
        }
        ensureNoDownstream(batchId);
        var job = jobs.submit("ASSET_IMPORT_ROLLBACK", batchId.toString(), operatorId,
                batchId.toString(), (String) batch.get("source_sha256"));
        db.update("UPDATE fams_asset_import_rollback SET rollback_status='QUEUED',job_id=? "
                + "WHERE batch_id=?", job.id().toString(), batchId.toString());
        return new Rollback(batchId, "QUEUED", ((Number) rows.getFirst().get("version")).longValue(), job.id());
    }

    /**
     * Executes one queued rollback inside the fenced job-completion transaction. Verifies the
     * claim against the stored job id and processor, rechecks both actors' current roles with
     * row locks, re-proves that every published asset is still untouched, then cancels the
     * assets through the port, retires the publication rows and completes batch and rollback
     * with an audit event.
     *
     * @param claim durable job claim whose business reference is the batch id
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_ROLLBACK_JOB_INVALID} when
     *         the claim is not an ASSET_IMPORT_ROLLBACK job
     * @throws IllegalStateException with code {@code ASSET_IMPORT_ROLLBACK_JOB_INVALID} when the
     *         batch, rollback status, job id or processor no longer match the claim,
     *         {@code ASSET_IMPORT_AUTH_REVOKED} when a required current role was lost,
     *         {@code ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED} when the batch left the published
     *         state, or {@code ASSET_IMPORT_ROLLBACK_DOWNSTREAM} when an asset changed
     */
    public void rollback(DurableJobService.Claim claim) {
        if (!"ASSET_IMPORT_ROLLBACK".equals(claim.kind())) {
            throw new IllegalArgumentException("ASSET_IMPORT_ROLLBACK_JOB_INVALID");
        }
        UUID batchId = UUID.fromString(claim.businessReference());
        lockPublished(batchId);
        var rows = db.queryForList("SELECT rollback_status,processor_account_id,decided_by,"
                + "request_reason,job_id FROM fams_asset_import_rollback WHERE batch_id=? FOR UPDATE",
                batchId.toString());
        if (rows.isEmpty() || !"QUEUED".equals(rows.getFirst().get("rollback_status"))
                || !claim.id().toString().equals(rows.getFirst().get("job_id"))
                || ((Number) rows.getFirst().get("processor_account_id")).longValue() != claim.actorId()) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_JOB_INVALID");
        }
        var row = rows.getFirst();
        requireCurrentRole(claim.actorId(), "WAREHOUSE_OPERATOR");
        requireCurrentRole(((Number) row.get("decided_by")).longValue(), "ASSET_ADMIN");
        List<Long> assetIds = ensureNoDownstream(batchId);
        Instant now = clock.instant();
        for (long assetId : assetIds) {
            assets.cancel(assetId, batchId, claim.actorId(), (String) row.get("request_reason"), now);
        }
        db.update("UPDATE fams_asset_import_publication SET retired_at=? WHERE batch_id=?",
                Timestamp.from(now), batchId.toString());
        db.update("UPDATE fams_asset_import_batch SET batch_status='ROLLED_BACK',updated_at=? WHERE id=?",
                Timestamp.from(now), batchId.toString());
        db.update("UPDATE fams_asset_import_rollback SET rollback_status='COMPLETED',executed_at=? "
                + "WHERE batch_id=?", Timestamp.from(now), batchId.toString());
        event(batchId, claim.actorId(), "ASSET_IMPORT_ROLLED_BACK",
                Map.of("assetCount", assetIds.size()));
    }

    /**
     * Records a rollback failure on the job and, for permanent failures, on the rollback row.
     * Transient data-access failures are marked retryable and leave the rollback QUEUED for
     * lease recovery; permanent failures extract the {@code ASSET_IMPORT_*} code from the
     * exception when present and move the rollback from QUEUED to FAILED.
     *
     * @param claim durable job claim that failed
     * @param failure the exception that aborted the rollback
     */
    @Transactional
    public void recordFailure(DurableJobService.Claim claim, RuntimeException failure) {
        boolean retryable = failure instanceof TransientDataAccessException;
        String code = failure instanceof IllegalStateException state
                && state.getMessage() != null && state.getMessage().startsWith("ASSET_IMPORT_")
                        ? state.getMessage() : "ASSET_IMPORT_ROLLBACK_FAILED";
        jobs.fail(claim, code, retryable);
        if (!retryable) {
            db.update("UPDATE fams_asset_import_rollback SET rollback_status='FAILED' "
                    + "WHERE batch_id=? AND rollback_status='QUEUED'", claim.businessReference());
        }
    }

    private Map<String, Object> lockPublished(UUID batchId) {
        var batches = db.queryForList("SELECT batch_status,source_sha256 FROM fams_asset_import_batch "
                + "WHERE id=? FOR UPDATE", batchId.toString());
        if (batches.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        if (!"PUBLISHED".equals(batches.getFirst().get("batch_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED");
        }
        return batches.getFirst();
    }

    private List<Long> ensureNoDownstream(UUID batchId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT a.id,a.version,a.card_stage "
                + "FROM fams_asset_import_publication p JOIN fams_asset a ON a.id=p.asset_id "
                + "WHERE p.batch_id=? ORDER BY a.id FOR UPDATE", batchId.toString());
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED"); }
        List<Long> ids = new ArrayList<>();
        for (var row : rows) {
            if (((Number) row.get("version")).longValue() != 0
                    || !"ACTIVE".equals(row.get("card_stage"))) {
                throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
            }
            long assetId = ((Number) row.get("id")).longValue();
            assets.assertReversible(assetId, batchId);
            ids.add(assetId);
        }
        for (int offset = 0; offset < ids.size(); offset += 500) {
            List<Long> part = ids.subList(offset, Math.min(ids.size(), offset + 500));
            String markers = String.join(",", Collections.nCopies(part.size(), "?"));
            Integer changes = db.queryForObject("SELECT COUNT(*) FROM fams_asset_timeline_event "
                    + "WHERE asset_id IN (" + markers + ") AND event_type<>'ASSET_INITIAL_IMPORTED'",
                    Integer.class, part.toArray());
            // The batch owns its opening locks. Its downstream guards prove those records are untouched.
            List<Object> lockArgs = new ArrayList<>(part);
            lockArgs.add(batchId.toString());
            lockArgs.add(batchId.toString());
            Integer locks = db.queryForObject("SELECT COUNT(*) FROM fams_asset_business_lock l "
                    + "WHERE l.asset_id IN (" + markers + ") AND l.released_at IS NULL AND NOT EXISTS ("
                    + "SELECT 1 FROM fams_scrap_request r WHERE r.id=l.business_id"
                    + " AND l.business_type='SCRAP' AND r.initial_batch_id=?) AND NOT EXISTS ("
                    + "SELECT 1 FROM fams_repair_request r WHERE r.id=l.business_id"
                    + " AND l.business_type='REPAIR' AND r.initial_batch_id=?)",
                    Integer.class, lockArgs.toArray());
            if (changes == null || changes != 0 || locks == null || locks != 0) {
                throw new IllegalStateException("ASSET_IMPORT_ROLLBACK_DOWNSTREAM");
            }
        }
        return ids;
    }

    private void requireCurrentRole(long accountId, String role) {
        var rows = db.queryForList("SELECT a.id FROM fams_system_account a "
                + "JOIN fams_account_role r ON r.account_id=a.id WHERE a.id=? "
                + "AND a.status='ENABLED' AND r.role_code=? FOR UPDATE", accountId, role);
        if (rows.size() != 1) { throw new IllegalStateException("ASSET_IMPORT_AUTH_REVOKED"); }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new IllegalArgumentException("ASSET_IMPORT_ROLLBACK_REASON_INVALID");
        }
    }

    private void event(UUID batchId, long actorId, String action, Map<String, Object> detail) {
        db.update("INSERT INTO fams_asset_import_event (batch_id,actor_account_id,action,"
                + "detail_json,occurred_at) VALUES (?,?,?,?,?)", batchId.toString(), actorId,
                action, json.writeValueAsString(detail), Timestamp.from(clock.instant()));
    }
}
