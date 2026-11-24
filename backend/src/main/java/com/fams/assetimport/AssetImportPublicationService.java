package com.fams.assetimport;

import com.fams.asset.AssetInitialPublicationPort;
import com.fams.job.DurableJobService;
import com.fams.masterdata.AssetMasterDataPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Rechecks current authority and source facts inside the fenced job completion transaction. */
@Service
@Profile("fams-product")
public class AssetImportPublicationService {
    private final JdbcTemplate db;
    private final AssetImportCatalogRepository catalog;
    private final AssetInitialPublicationPort assets;
    private final AssetMasterDataPort masterData;
    private final DurableJobService jobs;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * Wires the service to its persistence, catalog, publication, master-data, job, JSON and
     * clock collaborators.
     *
     * @param db JDBC template for the batch, review, window and publication tables
     * @param catalog fresh catalog snapshot source for the publication-time preflight recheck
     * @param assets port creating the initial asset records
     * @param masterData source of the current timezone and parameter version for publication
     * @param jobs durable job service used to fail the claim on permanent errors
     * @param json mapper for the stored column mapping and event details
     * @param clock timestamp source for expiry and audit checks
     */
    public AssetImportPublicationService(JdbcTemplate db, AssetImportCatalogRepository catalog,
            AssetInitialPublicationPort assets, AssetMasterDataPort masterData,
            DurableJobService jobs, ObjectMapper json, Clock clock) {
        this.db = db;
        this.catalog = catalog;
        this.assets = assets;
        this.masterData = masterData;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Publishes one queued batch inside the fenced job-completion transaction. Locks the batch,
     * treats an already PUBLISHED batch as an idempotent no-op, then rechecks the whole approval
     * chain against current facts: the review must still be APPROVED under an open, unexpired
     * window with the claim actor as processor, both the processor's warehouse-operator role and
     * the approver's asset-admin role are re-verified with row locks, and a fresh preflight run
     * must reproduce exactly the persisted READY rows. Only then are the initial assets created
     * row by row and the batch marked PUBLISHED with an audit event.
     *
     * @param claim durable job claim whose business reference is the batch id
     * @throws IllegalArgumentException with code {@code ASSET_IMPORT_JOB_INVALID} when the claim
     *         is not an ASSET_IMPORT job
     * @throws IllegalStateException with code {@code ASSET_IMPORT_JOB_INVALID} when the batch is
     *         missing, fenced to another job or not in QUEUED state,
     *         {@code ASSET_IMPORT_AUTH_REVOKED} when the review, window or current roles no
     *         longer authorize publication, or {@code ASSET_IMPORT_STALE_PREVIEW} when the fresh
     *         preflight disagrees with the persisted ready rows
     */
    public void publish(DurableJobService.Claim claim) {
        if (!"ASSET_IMPORT".equals(claim.kind())) {
            throw new IllegalArgumentException("ASSET_IMPORT_JOB_INVALID");
        }
        UUID batchId = UUID.fromString(claim.businessReference());
        var batches = db.queryForList("SELECT * FROM fams_asset_import_batch WHERE id=? FOR UPDATE",
                batchId.toString());
        if (batches.isEmpty() || !claim.id().toString().equals(batches.getFirst().get("job_id"))) {
            throw new IllegalStateException("ASSET_IMPORT_JOB_INVALID");
        }
        var batch = batches.getFirst();
        if ("PUBLISHED".equals(batch.get("batch_status"))) { return; }
        if (!"QUEUED".equals(batch.get("batch_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_JOB_INVALID");
        }
        var reviews = db.queryForList("SELECT r.review_status,r.processor_account_id,r.decided_by,"
                + "w.window_status,w.expires_at FROM fams_asset_import_review r "
                + "JOIN fams_initialization_window w ON w.id=r.window_id "
                + "WHERE r.batch_id=? FOR UPDATE", batchId.toString());
        if (reviews.isEmpty() || !"APPROVED".equals(reviews.getFirst().get("review_status"))
                || !"OPEN".equals(reviews.getFirst().get("window_status"))
                || !((LocalDateTime) reviews.getFirst().get("expires_at"))
                        .toInstant(ZoneOffset.UTC).isAfter(clock.instant())
                || ((Number) reviews.getFirst().get("processor_account_id")).longValue() != claim.actorId()) {
            throw new IllegalStateException("ASSET_IMPORT_AUTH_REVOKED");
        }
        long administratorId = ((Number) reviews.getFirst().get("decided_by")).longValue();
        requireCurrentRole(claim.actorId(), "WAREHOUSE_OPERATOR");
        requireCurrentRole(administratorId, "ASSET_ADMIN");
        var source = AssetImportWorkbook.parse((byte[]) batch.get("source_xlsx"));
        Map<String, String> mapping = json.readValue((String) batch.get("mapping_json"),
                new TypeReference<Map<String, String>>() { });
        Map<String, String> selected = AssetImportPreflight.columns(source, mapping);
        Set<String> codes = identifiers(source, selected.get("assetCode"));
        Set<String> serials = identifiers(source, selected.get("serialNumber"));
        var now = clock.instant();
        var checked = AssetImportPreflight.check(source, mapping, catalog.snapshot(codes, serials), now);
        List<Integer> savedReady = db.queryForList("SELECT sheet_row FROM fams_asset_import_row "
                + "WHERE batch_id=? AND row_status='READY' ORDER BY sheet_row", Integer.class,
                batchId.toString());
        if (!savedReady.equals(checked.ready().stream().map(AssetImportPreflight.ReadyRow::sheetRow).toList())
                || checked.ready().size() != ((Number) batch.get("ready_rows")).intValue()) {
            throw new IllegalStateException("ASSET_IMPORT_STALE_PREVIEW");
        }
        String username = db.queryForObject("SELECT username FROM fams_system_account WHERE id=?",
                String.class, claim.actorId());
        var timezone = masterData.currentZone();
        LocalDate businessDate = LocalDate.ofInstant(now, timezone);
        long parameterId = masterData.currentParameterVersionId();
        for (var row : checked.ready()) {
            var input = new AssetInitialPublicationPort.InitialAsset(row.assetCode(), row.name(),
                    row.categoryId(), row.organizationId(), row.model(), row.unit(), row.serialNumber(),
                    row.state(), row.warehouseId(), row.locationId(), row.usingOrganizationId(), row.employeeId(),
                    row.borrowedDate(), row.expectedReturnDate(), row.preScrapState(), row.scrapReason(),
                    row.scrapCondition(), row.proposedDisposition(), row.preRepairState(), row.repairFacts(),
                    row.lossFacts(), row.factoryDisposition());
            var context = new AssetInitialPublicationPort.Context(batchId, row.sheetRow(), claim.actorId(),
                    username, parameterId, businessDate, now, administratorId, timezone.getId());
            var created = assets.create(input, context);
            db.update("INSERT INTO fams_asset_import_publication (batch_id,sheet_row,asset_id,asset_code,"
                    + "published_at) VALUES (?,?,?,?,?)", batchId.toString(), row.sheetRow(),
                    created.id(), created.code(), Timestamp.from(now));
        }
        db.update("UPDATE fams_asset_import_batch SET batch_status='PUBLISHED',updated_at=? WHERE id=?",
                Timestamp.from(now), batchId.toString());
        db.update("INSERT INTO fams_asset_import_event (batch_id,actor_account_id,action,detail_json,"
                + "occurred_at) VALUES (?,?,'ASSET_IMPORT_PUBLISHED',?,?)", batchId.toString(),
                claim.actorId(), json.writeValueAsString(Map.of("readyRows", checked.ready().size(),
                        "errorRows", checked.errors().stream().map(AssetImportWorkbook.CellError::sheetRow)
                                .collect(Collectors.toSet()).size())), Timestamp.from(now));
    }

    /**
     * Records a publication failure on the job and, for permanent failures, on the batch.
     * Transient data-access failures are marked retryable and leave the batch QUEUED so lease
     * recovery can rerun them; permanent failures extract the {@code ASSET_IMPORT_*} code from
     * the exception when present and move the batch from QUEUED to FAILED with an audit event.
     *
     * @param claim durable job claim that failed
     * @param failure the exception that aborted publication
     */
    @Transactional
    public void recordFailure(DurableJobService.Claim claim, RuntimeException failure) {
        boolean retryable = failure instanceof TransientDataAccessException;
        String code = failure instanceof IllegalStateException state
                && state.getMessage() != null && state.getMessage().startsWith("ASSET_IMPORT_")
                        ? state.getMessage() : "ASSET_IMPORT_PUBLICATION_FAILED";
        jobs.fail(claim, code, retryable);
        if (!retryable) {
            UUID batchId = UUID.fromString(claim.businessReference());
            var now = Timestamp.from(clock.instant());
            db.update("UPDATE fams_asset_import_batch SET batch_status='FAILED',updated_at=? "
                    + "WHERE id=? AND batch_status='QUEUED'", now, batchId.toString());
            db.update("INSERT INTO fams_asset_import_event (batch_id,actor_account_id,action,"
                    + "detail_json,occurred_at) VALUES (?,?,'ASSET_IMPORT_PUBLICATION_FAILED',?,?)",
                    batchId.toString(), claim.actorId(), json.writeValueAsString(Map.of("code", code)), now);
        }
    }

    private void requireCurrentRole(long accountId, String role) {
        var rows = db.queryForList("SELECT a.id FROM fams_system_account a "
                + "JOIN fams_account_role r ON r.account_id=a.id WHERE a.id=? AND a.status='ENABLED' "
                + "AND r.role_code=? FOR UPDATE", accountId, role);
        if (rows.size() != 1) {
            throw new IllegalStateException("ASSET_IMPORT_AUTH_REVOKED");
        }
    }

    private Set<String> identifiers(AssetImportWorkbook.Parsed parsed, String column) {
        if (column == null) { return Set.of(); }
        Set<String> values = new HashSet<>();
        for (var row : parsed.rows()) {
            String value = row.values().get(column).strip();
            if (!value.isEmpty()) { values.add(value); }
        }
        return values;
    }
}
