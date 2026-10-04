package com.fams.assetimport;

import com.fams.job.DurableJobService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Holds a batch for administrator review before submitting its durable publication task. */
@Service
@Profile("fams-product")
public class AssetImportReviewService {
    public record Review(UUID batchId, String status, long previewVersion,
                         String processorAccountId, String decidedBy, UUID jobId) { }
    public record Pending(UUID batchId, long previewVersion, int readyRows, int errorRows,
                          String operatorUsername, java.time.Instant requestedAt) { }

    private final JdbcTemplate db;
    private final DurableJobService jobs;
    private final ObjectMapper json;
    private final Clock clock;

    public AssetImportReviewService(JdbcTemplate db, DurableJobService jobs, ObjectMapper json, Clock clock) {
        this.db = db;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Review getOwned(long operatorId, UUID batchId) {
        var rows = db.queryForList("SELECT r.review_status,r.preview_version,r.processor_account_id,"
                + "r.decided_by,b.job_id FROM fams_asset_import_review r "
                + "JOIN fams_asset_import_batch b ON b.id=r.batch_id "
                + "WHERE r.batch_id=? AND b.actor_account_id=?", batchId.toString(), operatorId);
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_REVIEW_NOT_FOUND"); }
        var row = rows.getFirst();
        return new Review(batchId, (String) row.get("review_status"),
                ((Number) row.get("preview_version")).longValue(),
                row.get("processor_account_id").toString(),
                row.get("decided_by") == null ? null : row.get("decided_by").toString(),
                row.get("job_id") == null ? null : UUID.fromString((String) row.get("job_id")));
    }

    @Transactional(readOnly = true)
    public List<Pending> pending(int page) {
        if (page < 1 || page > 1_000_000) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        return db.query("SELECT r.batch_id,r.preview_version,r.requested_at,b.ready_rows,"
                + "b.error_rows,a.username FROM fams_asset_import_review r "
                + "JOIN fams_asset_import_batch b ON b.id=r.batch_id "
                + "JOIN fams_system_account a ON a.id=r.processor_account_id "
                + "WHERE r.review_status='PENDING' ORDER BY r.requested_at,r.batch_id LIMIT 50 OFFSET ?",
                (result, index) -> new Pending(UUID.fromString(result.getString("batch_id")),
                        result.getLong("preview_version"), result.getInt("ready_rows"),
                        result.getInt("error_rows"), result.getString("username"),
                        result.getTimestamp("requested_at").toInstant()), (page - 1) * 50);
    }

    @Transactional(readOnly = true)
    public AssetImportBatchService.RowPage rowsForAdmin(UUID batchId, String status, int page) {
        if (batchId == null || status == null || page < 1 || page > 1_000_000
                || !Set.of("READY", "ERROR").contains(status)) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        Integer pending = db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_review "
                + "WHERE batch_id=? AND review_status='PENDING'", Integer.class, batchId.toString());
        if (pending == null || pending == 0) {
            throw new IllegalStateException("ASSET_IMPORT_REVIEW_NOT_FOUND");
        }
        int total = java.util.Objects.requireNonNull(db.queryForObject(
                "SELECT COUNT(*) FROM fams_asset_import_row WHERE batch_id=? AND row_status=?",
                Integer.class, batchId.toString(), status));
        var items = db.query("SELECT sheet_row,row_status,source_json,resolved_json,errors_json "
                + "FROM fams_asset_import_row WHERE batch_id=? AND row_status=? "
                + "ORDER BY sheet_row LIMIT 50 OFFSET ?", (result, index) ->
                        new AssetImportBatchService.PreviewRow(result.getInt("sheet_row"),
                                result.getString("row_status"), json.readTree(result.getString("source_json")),
                                result.getString("resolved_json") == null ? null
                                        : json.readTree(result.getString("resolved_json")),
                                result.getString("errors_json") == null ? null
                                        : json.readTree(result.getString("errors_json"))),
                batchId.toString(), status, (page - 1) * 50);
        return new AssetImportBatchService.RowPage(total, items);
    }

    @Transactional
    public Review request(long operatorId, UUID batchId, long expectedVersion) {
        if (operatorId < 1 || batchId == null || expectedVersion < 1) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        var batch = db.queryForList("SELECT batch_status,preview_version,ready_rows FROM fams_asset_import_batch "
                + "WHERE id=? AND actor_account_id=? FOR UPDATE", batchId.toString(), operatorId);
        if (batch.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        var state = batch.getFirst();
        if (!"PREVIEWED".equals(state.get("batch_status"))
                || ((Number) state.get("ready_rows")).intValue() == 0) {
            throw new IllegalStateException("ASSET_IMPORT_NOT_READY");
        }
        if (((Number) state.get("preview_version")).longValue() != expectedVersion) {
            throw new IllegalStateException("ASSET_IMPORT_VERSION_CONFLICT");
        }
        var windows = db.queryForList("SELECT id,expires_at FROM fams_initialization_window "
                + "WHERE window_status='OPEN' AND assigned_operator_id=? FOR UPDATE", operatorId);
        if (windows.isEmpty() || !((LocalDateTime) windows.getFirst().get("expires_at"))
                .toInstant(ZoneOffset.UTC).isAfter(clock.instant())) {
            throw new IllegalStateException("ASSET_IMPORT_WINDOW_CLOSED");
        }
        long windowId = ((Number) windows.getFirst().get("id")).longValue();
        var reviews = db.queryForList("SELECT review_status,preview_version,processor_account_id,decided_by "
                + "FROM fams_asset_import_review WHERE batch_id=? FOR UPDATE", batchId.toString());
        if (!reviews.isEmpty()) {
            var previous = reviews.getFirst();
            if ("PENDING".equals(previous.get("review_status"))
                    && ((Number) previous.get("preview_version")).longValue() == expectedVersion) {
                return new Review(batchId, "PENDING", expectedVersion,
                        Long.toString(operatorId), null, null);
            }
            if (!"REJECTED".equals(previous.get("review_status"))) {
                throw new IllegalStateException("ASSET_IMPORT_REVIEW_LOCKED");
            }
            db.update("UPDATE fams_asset_import_review SET window_id=?,processor_account_id=?,"
                    + "preview_version=?,review_status='PENDING',requested_at=?,decided_by=NULL,"
                    + "decided_at=NULL,decision_reason=NULL WHERE batch_id=?", windowId, operatorId,
                    expectedVersion, Timestamp.from(clock.instant()), batchId.toString());
        } else {
            db.update("INSERT INTO fams_asset_import_review (batch_id,window_id,processor_account_id,"
                    + "preview_version,review_status,requested_at) VALUES (?,?,?,?,'PENDING',?)",
                    batchId.toString(), windowId, operatorId, expectedVersion,
                    Timestamp.from(clock.instant()));
        }
        event(batchId, operatorId, "ASSET_IMPORT_REVIEW_REQUESTED",
                Map.of("version", expectedVersion, "windowId", windowId));
        return new Review(batchId, "PENDING", expectedVersion, Long.toString(operatorId), null, null);
    }

    @Transactional
    public Review decide(long administratorId, UUID batchId, boolean approve, String reason,
            Integer confirmedReadyRows, Integer confirmedErrorRows, boolean sampleVerified) {
        if (administratorId < 1 || batchId == null || reason == null || reason.isBlank()
                || reason.strip().length() > 500) {
            throw new IllegalArgumentException("ASSET_IMPORT_DECISION_INVALID");
        }
        var batches = db.queryForList("SELECT batch_status,preview_version,source_sha256,"
                + "ready_rows,error_rows FROM "
                + "fams_asset_import_batch WHERE id=? FOR UPDATE", batchId.toString());
        if (batches.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        var reviews = db.queryForList("SELECT r.review_status,r.preview_version,r.processor_account_id,"
                + "w.window_status,w.expires_at FROM fams_asset_import_review r "
                + "JOIN fams_initialization_window w ON w.id=r.window_id "
                + "WHERE r.batch_id=? FOR UPDATE", batchId.toString());
        if (reviews.isEmpty() || !"PENDING".equals(reviews.getFirst().get("review_status"))
                || !"PREVIEWED".equals(batches.getFirst().get("batch_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_REVIEW_LOCKED");
        }
        var review = reviews.getFirst();
        long processorId = ((Number) review.get("processor_account_id")).longValue();
        long version = ((Number) review.get("preview_version")).longValue();
        if (processorId == administratorId
                || version != ((Number) batches.getFirst().get("preview_version")).longValue()) {
            throw new IllegalStateException("ASSET_IMPORT_REVIEW_LOCKED");
        }
        if (approve && (!"OPEN".equals(review.get("window_status"))
                || !((LocalDateTime) review.get("expires_at"))
                        .toInstant(ZoneOffset.UTC).isAfter(clock.instant()))) {
            throw new IllegalStateException("ASSET_IMPORT_WINDOW_CLOSED");
        }
        if (approve && (!sampleVerified || confirmedReadyRows == null || confirmedErrorRows == null
                || confirmedReadyRows != ((Number) batches.getFirst().get("ready_rows")).intValue()
                || confirmedErrorRows != ((Number) batches.getFirst().get("error_rows")).intValue())) {
            throw new IllegalArgumentException("ASSET_IMPORT_DECISION_INVALID");
        }
        UUID jobId = null;
        if (approve) {
            var job = jobs.submit("ASSET_IMPORT", batchId.toString(), processorId,
                    batchId.toString(), (String) batches.getFirst().get("source_sha256"));
            jobId = job.id();
            db.update("UPDATE fams_asset_import_batch SET batch_status='QUEUED',job_id=?,updated_at=? "
                    + "WHERE id=?", jobId.toString(), Timestamp.from(clock.instant()), batchId.toString());
        }
        db.update("UPDATE fams_asset_import_review SET review_status=?,decided_by=?,decided_at=?,"
                + "decision_reason=? WHERE batch_id=?", approve ? "APPROVED" : "REJECTED",
                administratorId, Timestamp.from(clock.instant()), reason.strip(), batchId.toString());
        event(batchId, administratorId, "ASSET_IMPORT_REVIEW_DECIDED",
                Map.of("approved", approve, "version", version, "reason", reason.strip()));
        return new Review(batchId, approve ? "APPROVED" : "REJECTED", version,
                Long.toString(processorId), Long.toString(administratorId), jobId);
    }

    private void event(UUID batchId, long actorId, String action, Map<String, Object> detail) {
        db.update("INSERT INTO fams_asset_import_event (batch_id,actor_account_id,action,"
                + "detail_json,occurred_at) VALUES (?,?,?,?,?)", batchId.toString(), actorId,
                action, json.writeValueAsString(detail), Timestamp.from(clock.instant()));
    }
}
