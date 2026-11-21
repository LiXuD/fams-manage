package com.fams.scrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JAVA-03 wire equivalence: every typed scrap view must serialize exactly like the frozen
 * pre-refactor projections. legacyView is ScrapReadService.view verbatim (its five
 * db.queryForList call sites are replaced by the supplied row lists, which is exactly what
 * the refactored service passes into ScrapViews.request); legacyCorrectionView,
 * legacyReversalView and legacyRecoveryView are the deleted private helpers verbatim.
 * Comparison round-trips through JSON text so integral node widths normalize and object key
 * order (never stable in Map.of responses) is ignored, while key presence, explicit nulls
 * and value shapes all count.
 */
class ScrapWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Current ADMIN = new Current(12L, "admin", 0L, 0L, Set.of(Role.ASSET_ADMIN));
    private static final Current WAREHOUSE = new Current(13L, "ops", 0L, 0L, Set.of(Role.WAREHOUSE_OPERATOR));
    private static final Current OTHER_WAREHOUSE = new Current(15L, "ops2", 0L, 0L, Set.of(Role.WAREHOUSE_OPERATOR));
    private static final Current CREATOR = new Current(11L, "creator", 3L, 1L, Set.of(Role.EMPLOYEE));
    private static final Current AUDIT = new Current(14L, "audit", 0L, 0L, Set.of(Role.AUDIT_READ_ONLY));
    private static final String BATCH = "3f2b8c1e-9d4a-4e7f-8a1b-2c3d4e5f6a7b";

    @Test
    void standalonePendingMatchesLegacyProjectionForEveryReader() {
        var row = requestRow("STANDALONE", "PENDING_ADMIN_DECISION");
        var locks = List.of(lockRow());
        for (Current actor : List.of(ADMIN, WAREHOUSE, CREATOR, AUDIT)) {
            assertWireEquals(legacyView(actor, row, locks, List.of(), List.of(), List.of(), List.of()),
                    ScrapViews.request(actor, JSON, row, locks, List.of(), List.of(), List.of(), List.of(), null));
        }
        // Without an active lock the cancel action and lockVersion key change together.
        assertWireEquals(legacyView(ADMIN, row, List.of(), List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(ADMIN, JSON, row, List.of(), List.of(), List.of(), List.of(), List.of(), null));
    }

    @Test
    void executedPendingDispositionMatchesLegacyProjection() {
        var row = requestRow("STANDALONE", "EXECUTED");
        row.put("processor_account_id", 13L);
        row.put("version", 5L);
        var dispositions = List.of(dispositionRow("PENDING_DISPOSITION", "2026-12-01", null));
        for (Current actor : List.of(ADMIN, WAREHOUSE, OTHER_WAREHOUSE, CREATOR, AUDIT)) {
            assertWireEquals(legacyView(actor, row, List.of(), dispositions, List.of(), List.of(), List.of()),
                    ScrapViews.request(actor, JSON, row, List.of(), dispositions, List.of(), List.of(), List.of(), null));
        }
        // An open reversal suppresses initiateReversal and enables the decision/claim/execute chain.
        var pending = List.of(reversalRow("PENDING_ADMIN_DECISION", null));
        assertWireEquals(legacyView(ADMIN, row, List.of(), dispositions, List.of(), pending, List.of()),
                ScrapViews.request(ADMIN, JSON, row, List.of(), dispositions, List.of(), pending, List.of(), null));
        var processing = List.of(reversalRow("PROCESSING", 13L));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), dispositions, List.of(), processing, List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), dispositions, List.of(), processing, List.of(), null));
        assertWireEquals(legacyView(OTHER_WAREHOUSE, row, List.of(), dispositions, List.of(), processing, List.of()),
                ScrapViews.request(OTHER_WAREHOUSE, JSON, row, List.of(), dispositions, List.of(), processing, List.of(), null));
    }

    @Test
    void disposedCompletionMatchesLegacyProjectionIncludingFactoryShape() {
        var row = requestRow("STANDALONE", "EXECUTED");
        row.put("processor_account_id", 13L);
        String completion = "{\"facts\":{\"occurredAt\":\"2026-11-20T02:00:00Z\",\"method\":\"销毁\","
                + "\"recipient\":\"回收商\"},\"operatorName\":\"ops\"}";
        var disposed = List.of(dispositionRow("DISPOSED", "2026-12-01", completion));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), disposed, List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), disposed, List.of(), List.of(), List.of(), null));

        // completion_snapshot_json present but status not DISPOSED: the completion key must stay absent.
        var pendingWithSnapshot = List.of(dispositionRow("PENDING_DISPOSITION", "2026-12-01", completion));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), pendingWithSnapshot, List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), pendingWithSnapshot, List.of(), List.of(), List.of(), null));

        // DISPOSED without a completion snapshot also omits the key.
        var disposedWithoutSnapshot = List.of(dispositionRow("DISPOSED", "2026-12-01", null));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), disposedWithoutSnapshot, List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), disposedWithoutSnapshot, List.of(), List.of(), List.of(), null));

        // Factory completions carry only occurredAt/method and hide the custody snapshot.
        var factory = requestRow("FACTORY_REPLACEMENT", "EXECUTED");
        factory.put("processor_account_id", 13L);
        factory.put("source_business_id", 7L);
        factory.put("source_item_id", 8L);
        factory.put("source_snapshot_json", factorySource(false));
        var factoryDisposed = List.of(dispositionRow("DISPOSED", null,
                "{\"facts\":{\"occurredAt\":\"2026-11-10T02:00:00Z\",\"method\":\"FACTORY_DESTROYED\","
                        + "\"recipient\":\"provider\",\"recordedBy\":13},\"verifiedBy\":13}"));
        assertWireEquals(legacyView(WAREHOUSE, factory, List.of(), factoryDisposed, List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, factory, List.of(), factoryDisposed, List.of(), List.of(), List.of(), null));
    }

    @Test
    void lossWriteOffMatchesLegacyProjectionWithReducedExecutionAndRecovery() {
        var row = requestRow("LOSS", "EXECUTED");
        row.put("source_business_id", 5L);
        row.put("processor_account_id", 13L);
        row.put("execution_snapshot_json", "{\"lossEvidence\":{\"reportId\":\"5\"},\"evidence\":\"photo proof\","
                + "\"writtenOffAt\":\"2026-11-18T02:00:00Z\"}");
        var dispositions = List.of(dispositionRow("LOSS_WRITTEN_OFF", null,
                "{\"lossEvidence\":{\"reportId\":\"5\"},\"evidence\":\"photo proof\","
                        + "\"writtenOffAt\":\"2026-11-18T02:00:00Z\"}"));
        // No recovery yet: administrators, warehouse operators and the applicant may initiate one.
        for (Current actor : List.of(ADMIN, WAREHOUSE, CREATOR, AUDIT)) {
            assertWireEquals(legacyView(actor, row, List.of(), dispositions, List.of(), List.of(), List.of()),
                    ScrapViews.request(actor, JSON, row, List.of(), dispositions, List.of(), List.of(), List.of(), null));
        }
        var pending = List.of(recoveryRow("PENDING_ADMIN_DECISION", null));
        assertWireEquals(legacyView(ADMIN, row, List.of(), dispositions, List.of(), List.of(), pending),
                ScrapViews.request(ADMIN, JSON, row, List.of(), dispositions, List.of(), List.of(), pending, null));
        var processing = List.of(recoveryRow("PROCESSING", 13L));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), dispositions, List.of(), List.of(), processing),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), dispositions, List.of(), List.of(), processing, null));
        assertWireEquals(legacyView(OTHER_WAREHOUSE, row, List.of(), dispositions, List.of(), List.of(), processing),
                ScrapViews.request(OTHER_WAREHOUSE, JSON, row, List.of(), dispositions, List.of(), List.of(), processing, null));
        var completed = List.of(recoveryRow("COMPLETED", 13L));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), dispositions, List.of(), List.of(), completed),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), dispositions, List.of(), List.of(), completed, null));
    }

    @Test
    void factoryReplacementMatchesLegacyProjectionWithInitialEvidence() {
        var row = requestRow("FACTORY_REPLACEMENT", "PROCESSING");
        row.put("source_business_id", 7L);
        row.put("source_item_id", 8L);
        row.put("processor_account_id", 13L);
        row.put("initial_batch_id", BATCH);
        row.put("initial_sheet_row", 2);
        row.put("source_snapshot_json", factorySource(true));
        row.put("decision_snapshot_json", "{\"decision\":{\"result\":\"APPROVE\"},\"decidedBy\":\"12\","
                + "\"decidedAt\":\"2026-11-12T02:00:00Z\",\"attachmentEvidence\":[{\"objectId\":\"x\"}]}");
        // The processor sees executeFactory and the imported evidence; the admin does not.
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), List.of(), List.of(), List.of(), List.of(), null));
        assertWireEquals(legacyView(ADMIN, row, List.of(), List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(ADMIN, JSON, row, List.of(), List.of(), List.of(), List.of(), List.of(), null));

        // Pending decision: the admin decides and still receives the imported evidence key.
        var pending = new LinkedHashMap<>(row);
        pending.put("request_status", "PENDING_ADMIN_DECISION");
        pending.put("processor_account_id", null);
        assertWireEquals(legacyView(ADMIN, pending, List.of(lockRow()), List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(ADMIN, JSON, pending, List.of(lockRow()), List.of(), List.of(), List.of(), List.of(), null));

        // Non-initial factory rows never carry the initial* keys.
        var plain = requestRow("FACTORY_REPLACEMENT", "PROCESSING");
        plain.put("source_business_id", 7L);
        plain.put("source_item_id", 8L);
        plain.put("processor_account_id", 13L);
        plain.put("source_snapshot_json", factorySource(false));
        assertWireEquals(legacyView(WAREHOUSE, plain, List.of(), List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, plain, List.of(), List.of(), List.of(), List.of(), List.of(), null));
    }

    @Test
    void repairProcessingMatchesLegacyProjectionWithDecisionSnapshot() {
        var row = requestRow("REPAIR", "PROCESSING");
        row.put("source_business_id", 7L);
        row.put("processor_account_id", 13L);
        row.put("decision_snapshot_json", "{\"decision\":{\"result\":\"APPROVE\",\"approvedDisposition\":\"销毁\"},"
                + "\"decidedBy\":\"12\",\"decidedAt\":\"2026-11-12T02:00:00Z\","
                + "\"attachmentEvidence\":[{\"objectId\":\"x\"}]}");
        var locks = List.of(lockRow());
        assertWireEquals(legacyView(WAREHOUSE, row, locks, List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, locks, List.of(), List.of(), List.of(), List.of(), null));
        assertWireEquals(legacyView(OTHER_WAREHOUSE, row, locks, List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(OTHER_WAREHOUSE, JSON, row, locks, List.of(), List.of(), List.of(), List.of(), null));
    }

    @Test
    void correctionLifecycleMatchesLegacyProjection() {
        var row = requestRow("STANDALONE", "RESTORE_REQUIRED");
        // No correction yet: the admin may initiate one.
        assertWireEquals(legacyView(ADMIN, row, List.of(lockRow()), List.of(), List.of(), List.of(), List.of()),
                ScrapViews.request(ADMIN, JSON, row, List.of(lockRow()), List.of(), List.of(), List.of(), List.of(), null));
        var pending = List.of(correctionRow("PENDING_ADMIN_DECISION", null));
        assertWireEquals(legacyView(ADMIN, row, List.of(lockRow()), List.of(), pending, List.of(), List.of()),
                ScrapViews.request(ADMIN, JSON, row, List.of(lockRow()), List.of(), pending, List.of(), List.of(), null));
        var claimable = List.of(correctionRow("WAITING_WAREHOUSE_CLAIM", null));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(lockRow()), List.of(), claimable, List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(lockRow()), List.of(), claimable, List.of(), List.of(), null));
        var processing = List.of(correctionRow("PROCESSING", 13L));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(lockRow()), List.of(), processing, List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(lockRow()), List.of(), processing, List.of(), List.of(), null));
        assertWireEquals(legacyView(OTHER_WAREHOUSE, row, List.of(lockRow()), List.of(), processing, List.of(), List.of()),
                ScrapViews.request(OTHER_WAREHOUSE, JSON, row, List.of(lockRow()), List.of(), processing, List.of(), List.of(), null));
        var completed = List.of(correctionRow("COMPLETED", 13L));
        assertWireEquals(legacyView(WAREHOUSE, row, List.of(), List.of(), completed, List.of(), List.of()),
                ScrapViews.request(WAREHOUSE, JSON, row, List.of(), List.of(), completed, List.of(), List.of(), null));
    }

    @Test
    void auditOnlyReaderSeesAllActionsDeniedAcrossSubRecords() {
        var row = requestRow("STANDALONE", "EXECUTED");
        row.put("processor_account_id", 13L);
        var dispositions = List.of(dispositionRow("PENDING_DISPOSITION", "2026-12-01", null));
        var corrections = List.of(correctionRow("PROCESSING", 13L));
        var reversals = List.of(reversalRow("PROCESSING", 13L));
        var recoveries = List.of(recoveryRow("PROCESSING", 13L));
        var legacy = legacyView(AUDIT, row, List.of(), dispositions, corrections, reversals, recoveries);
        var typed = ScrapViews.request(AUDIT, JSON, row, List.of(), dispositions, corrections, reversals, recoveries, null);
        assertWireEquals(legacy, typed);
        @SuppressWarnings("unchecked")
        var actions = (Map<String, Object>) legacy.get("actions");
        assertEquals(19, actions.size());
        actions.values().forEach(allowed -> assertEquals(Boolean.FALSE, allowed));
    }

    @Test
    void initialSourceAndHistoryMatchLegacyProjectionAcrossDriverTemporalShapes() {
        var row = requestRow("STANDALONE", "PENDING_ADMIN_DECISION");
        row.put("initial_batch_id", BATCH);
        row.put("initial_sheet_row", 2);
        var timestampHistory = List.of(historyRow(Timestamp.from(Instant.parse("2026-11-01T02:00:00Z"))));
        var legacy = new LinkedHashMap<>(legacyView(ADMIN, row, List.of(lockRow()), List.of(), List.of(), List.of(), List.of()));
        legacy.put("history", timestampHistory);
        assertWireEquals(legacy, ScrapViews.request(ADMIN, JSON, row, List.of(lockRow()), List.of(), List.of(),
                List.of(), List.of(), timestampHistory.stream().map(ScrapViews::historyEntry).toList()));

        var localDateTimeHistory = List.of(historyRow(LocalDateTime.of(2026, 11, 1, 2, 0, 0)));
        var legacyLocal = new LinkedHashMap<>(legacyView(ADMIN, row, List.of(lockRow()), List.of(), List.of(), List.of(), List.of()));
        legacyLocal.put("history", localDateTimeHistory);
        assertWireEquals(legacyLocal, ScrapViews.request(ADMIN, JSON, row, List.of(lockRow()), List.of(), List.of(),
                List.of(), List.of(), localDateTimeHistory.stream().map(ScrapViews::historyEntry).toList()));

        // get() always carries the history key, even when empty.
        var legacyEmpty = new LinkedHashMap<>(legacyView(ADMIN, row, List.of(lockRow()), List.of(), List.of(), List.of(), List.of()));
        legacyEmpty.put("history", List.of());
        assertWireEquals(legacyEmpty, ScrapViews.request(ADMIN, JSON, row, List.of(lockRow()), List.of(), List.of(),
                List.of(), List.of(), List.of()));
    }

    @Test
    void pagesMatchLegacyAssembly() {
        var row = requestRow("STANDALONE", "PENDING_ADMIN_DECISION");
        var locks = List.of(lockRow());
        var legacyList = Map.of("items", List.of(legacyView(ADMIN, row, locks, List.of(), List.of(), List.of(), List.of())),
                "total", 1L, "page", 1, "pageSize", 20, "canListOverdueDispositions", true);
        assertWireEquals(legacyList, new ScrapViews.RequestPage(
                List.of(ScrapViews.request(ADMIN, JSON, row, locks, List.of(), List.of(), List.of(), List.of(), null)),
                1, 1, 20, true, null));

        var today = LocalDate.of(2026, 11, 20);
        var legacyOverdue = Map.of("items", List.of(legacyView(ADMIN, row, locks, List.of(), List.of(), List.of(), List.of())),
                "total", 1L, "page", 1, "pageSize", 20, "businessDate", today, "canListOverdueDispositions", true);
        assertWireEquals(legacyOverdue, new ScrapViews.RequestPage(
                List.of(ScrapViews.request(ADMIN, JSON, row, locks, List.of(), List.of(), List.of(), List.of(), null)),
                1, 1, 20, true, today));

        var catalogRow = new LinkedHashMap<String, Object>();
        catalogRow.put("id", 9L);
        catalogRow.put("name", "bin-a");
        catalogRow.put("version", 1L);
        var legacyCatalogItem = Map.of("id", catalogRow.get("id").toString(), "name", catalogRow.get("name"),
                "version", catalogRow.get("version"));
        var legacyCatalog = Map.of("items", List.of(legacyCatalogItem), "total", 1L, "page", 1, "pageSize", 100);
        assertWireEquals(legacyCatalog, new ScrapViews.ChoicePage(List.of(ScrapViews.choice(catalogRow)), 1, 1, 100));

        // Frozen AssetScrapPort.eligible assembly; the port envelope stays Map, the scrap exit converts.
        var assetRow = new LinkedHashMap<String, Object>();
        assetRow.put("id", 40L);
        assetRow.put("asset_code", "FA-1");
        assetRow.put("name", "laptop");
        assetRow.put("version", 3L);
        var legacyEligibleItem = Map.of("id", assetRow.get("id").toString(), "assetCode", assetRow.get("asset_code"),
                "name", assetRow.get("name"), "version", assetRow.get("version"));
        var envelope = Map.of("items", List.of(legacyEligibleItem), "total", 1L, "page", 1, "pageSize", 20);
        assertWireEquals(envelope, ScrapViews.eligiblePage(envelope));
    }

    @Test
    void attachmentViewsMatchLegacyProjection() {
        var legacyCapabilities = new LinkedHashMap<String, Object>();
        legacyCapabilities.put("canRead", true);
        legacyCapabilities.put("canUploadRequest", true);
        legacyCapabilities.put("canUploadDisposition", false);
        legacyCapabilities.put("requestVersion", 0L);
        legacyCapabilities.put("dispositionVersion", null);
        assertWireEquals(legacyCapabilities, new ScrapViews.AttachmentCapabilities(true, true, false, 0L, null));
        legacyCapabilities.put("dispositionVersion", 2L);
        assertWireEquals(legacyCapabilities, new ScrapViews.AttachmentCapabilities(true, true, false, 0L, 2L));

        String objectId = "6d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";
        var legacyReceipt = Map.<String, Object>of("id", "9", "kind", "request", "objectId", objectId, "version", 1L);
        assertWireEquals(legacyReceipt, new ScrapViews.AttachmentReceipt("9", "request", objectId, 1));

        var legacyGrant = Map.of("url", "/api/v1/scrap-requests/9/attachments/request/" + objectId + "/content?grant=t",
                "expiresAtEpochSecond", 1763600000L);
        assertWireEquals(legacyGrant,
                new ScrapViews.AttachmentGrant("/api/v1/scrap-requests/9/attachments/request/" + objectId + "/content?grant=t",
                        1763600000L));

        var metadataRow = new LinkedHashMap<String, Object>();
        metadataRow.put("objectId", objectId);
        metadataRow.put("filename", "photo.png");
        metadataRow.put("contentType", "image/png");
        metadataRow.put("sizeBytes", 1024L);
        metadataRow.put("scanStatus", "CLEAN");
        metadataRow.put("businessVersion", 0L);
        metadataRow.put("createdAt", Timestamp.from(Instant.parse("2026-11-01T02:00:00Z")));
        assertWireEquals(metadataRow, AttachmentEvidencePort.fromRow(metadataRow));
        var localDateTime = new LinkedHashMap<>(metadataRow);
        localDateTime.put("createdAt", LocalDateTime.of(2026, 11, 1, 2, 0, 0));
        assertWireEquals(localDateTime, AttachmentEvidencePort.fromRow(localDateTime));
    }

    @Test
    void attachmentReceiptRoundTripsAndPreRefactorStoredRepliesStillDeserialize() {
        String objectId = "6d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";
        var receipt = new ScrapViews.AttachmentReceipt("9", "disposition", objectId, 3);
        var stored = JSON.writeValueAsString(receipt);
        assertEquals(JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, ScrapViews.AttachmentReceipt.class))));

        // The pre-refactor register() stored Map.of with the same four keys.
        for (String kind : List.of("request", "disposition")) {
            var legacy = JSON.writeValueAsString(
                    Map.<String, Object>of("id", "9", "kind", kind, "objectId", objectId, "version", 1L));
            var replayed = JSON.readValue(legacy, ScrapViews.AttachmentReceipt.class);
            assertEquals(JSON.readTree(legacy), JSON.readTree(JSON.writeValueAsString(replayed)));
        }
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static Map<String, Object> requestRow(String sourceType, String status) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 9L);
        row.put("asset_id", 40L);
        row.put("creator_account_id", 11L);
        row.put("processor_account_id", null);
        row.put("source_type", sourceType);
        row.put("source_business_id", null);
        row.put("source_item_id", null);
        row.put("source_snapshot_json", "{\"asset_code\":\"FA-1\",\"name\":\"laptop\",\"serial_number\":\"SN-1\","
                + "\"lifecycle_status\":\"IN_USE\"}");
        row.put("reason", "broken beyond repair");
        row.put("observed_condition", "screen cracked");
        row.put("proposed_disposition", "销毁");
        row.put("request_status", status);
        row.put("decision_snapshot_json", null);
        row.put("execution_snapshot_json", null);
        row.put("asset_version", 3L);
        row.put("ownership_version", 0L);
        row.put("version", 0L);
        row.put("initial_batch_id", null);
        row.put("initial_sheet_row", null);
        return row;
    }

    private static String factorySource(boolean initial) {
        var context = new LinkedHashMap<String, Object>();
        context.put("method", "FACTORY_DESTROYED");
        context.put("occurredAt", "2026-11-10T02:00:00Z");
        if (initial) {
            context.put("evidence", "initial factory evidence");
        }
        var proof = new LinkedHashMap<String, Object>();
        proof.put("repairVersion", 2);
        proof.put("context", context);
        var source = new LinkedHashMap<String, Object>();
        source.put("asset_code", "FA-1");
        source.put("name", "laptop");
        source.put("serial_number", "SN-1");
        source.put("repairEvidence", proof);
        return JSON.writeValueAsString(source);
    }

    private static Map<String, Object> lockRow() {
        return new LinkedHashMap<>(Map.of("lock_version", 4L));
    }

    private static Map<String, Object> dispositionRow(String status, String dueDate, String completionJson) {
        var row = new LinkedHashMap<String, Object>();
        row.put("disposition_status", status);
        row.put("due_date", dueDate);
        row.put("version", 0L);
        row.put("custody_snapshot_json", "{\"assetCode\":\"FA-1\",\"lifecycle_status\":\"IN_USE\"}");
        row.put("completion_snapshot_json", completionJson);
        row.put("processor_account_id", 13L);
        return row;
    }

    private static Map<String, Object> correctionRow(String status, Long processor) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 21L);
        row.put("correction_status", status);
        row.put("processor_account_id", processor);
        row.put("asset_version", 3L);
        row.put("lock_version", 4L);
        row.put("target_location_id", 9L);
        row.put("target_location_version", 1L);
        row.put("ownership_version", 0L);
        row.put("version", 0L);
        row.put("reason", "wrong bin");
        row.put("target_location_name", "bin-a");
        return row;
    }

    private static Map<String, Object> reversalRow(String status, Long processor) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 22L);
        row.put("reversal_status", status);
        row.put("processor_account_id", processor);
        row.put("asset_version", 3L);
        row.put("target_location_id", 9L);
        row.put("target_location_version", 1L);
        row.put("ownership_version", 0L);
        row.put("version", 0L);
        row.put("reason", "needed again");
        row.put("target_location_name", "bin-a");
        return row;
    }

    private static Map<String, Object> recoveryRow(String status, Long processor) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 23L);
        row.put("recovery_status", status);
        row.put("processor_account_id", processor);
        row.put("asset_version", 3L);
        row.put("reason", "found in warehouse");
        row.put("found_at", "2026-11-15T02:00:00.000000");
        row.put("temporary_location_id", 8L);
        row.put("temporary_location_version", 1L);
        row.put("temporary_location_name", "temp-shelf");
        row.put("temporary_custodian_employee_id", 3L);
        row.put("temporary_custodian_name", "ops");
        row.put("target_location_id", 9L);
        row.put("target_location_version", 1L);
        row.put("target_location_name", "bin-a");
        row.put("ownership_version", 0L);
        row.put("version", 0L);
        return row;
    }

    private static Map<String, Object> historyRow(Object occurredAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 31L);
        row.put("action", "SCRAP_SUBMITTED");
        row.put("actor_username", "creator");
        row.put("occurred_at", occurredAt);
        return row;
    }

    /** Frozen pre-JAVA-03 ScrapReadService.view projection; reference oracle only. */
    private static Map<String, Object> legacyView(Current actor, Map<String, Object> row,
            List<Map<String, Object>> locks, List<Map<String, Object>> dispositions,
            List<Map<String, Object>> correctionRows, List<Map<String, Object>> reversalRows,
            List<Map<String, Object>> recoveryRows) {
        var result = new LinkedHashMap<String, Object>();
        long id = ((Number) row.get("id")).longValue();
        result.put("id", Long.toString(id));
        result.put("assetId", row.get("asset_id").toString());
        result.put("sourceType", row.get("source_type"));
        result.put("sourceBusinessId", row.get("source_business_id") == null ? null : row.get("source_business_id").toString());
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id") == null
                ? null : row.get("processor_account_id").toString());
        result.put("status", row.get("request_status"));
        result.put("reason", row.get("reason"));
        result.put("observedCondition", row.get("observed_condition"));
        result.put("proposedDisposition", row.get("proposed_disposition"));
        var source = JSON.readTree(row.get("source_snapshot_json").toString());
        result.put("source", Map.of("assetCode", source.path("asset_code").asText(),
                "name", source.path("name").asText(), "serialNumber", source.path("serial_number").asText("")));
        result.put("initialSource", row.get("initial_batch_id") == null ? null
                : Map.of("batchId", row.get("initial_batch_id").toString(),
                        "sheetRow", row.get("initial_sheet_row")));
        var decision = row.get("decision_snapshot_json") == null
                ? null : JSON.readTree(row.get("decision_snapshot_json").toString());
        if (decision instanceof tools.jackson.databind.node.ObjectNode object) {
            object.remove("attachmentEvidence");
        }
        result.put("decision", decision);
        boolean lossSource = "LOSS".equals(row.get("source_type"));
        boolean factory = "FACTORY_REPLACEMENT".equals(row.get("source_type"));
        if (factory) {
            var context = source.path("repairEvidence").path("context");
            result.put("factoryDisposition", context.path("method").asText());
            if (row.get("initial_batch_id") != null) {
                result.put("initialFactoryDispositionAt", context.path("occurredAt").asText());
            }
        }
        var execution = row.get("execution_snapshot_json") == null
                ? null : JSON.readTree(row.get("execution_snapshot_json").toString());
        result.put("execution", lossSource && execution != null
                ? Map.of("writtenOffAt", execution.path("writtenOffAt").asText()) : execution);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        boolean owner = actor.has(Role.WAREHOUSE_OPERATOR)
                && java.util.Objects.equals(actor.id(), row.get("processor_account_id"));
        Map<String, Object> disposition = dispositions.isEmpty() ? null : new LinkedHashMap<>(dispositions.getFirst());
        boolean canDispose = disposition != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && java.util.Objects.equals(disposition.get("processor_account_id"), actor.id())
                && "EXECUTED".equals(row.get("request_status")) && ScrapReviewService.physicalSource(row.get("source_type"))
                && "PENDING_DISPOSITION".equals(disposition.get("disposition_status"));
        if (disposition != null) {
            disposition.remove("processor_account_id");
            if (lossSource || factory) {
                disposition.remove("custody_snapshot_json");
            }
            Object completion = disposition.remove("completion_snapshot_json");
            if (completion != null && "DISPOSED".equals(disposition.get("disposition_status"))) {
                var snapshot = JSON.readTree(completion.toString());
                var facts = snapshot.path("facts");
                disposition.put("completion", factory ? Map.of("occurredAt", facts.path("occurredAt").asText(),
                        "method", facts.path("method").asText()) : Map.of("occurredAt", facts.path("occurredAt").asText(),
                        "method", facts.path("method").asText(), "recipient", facts.path("recipient").asText(),
                        "operatorName", snapshot.path("operatorName").asText()));
            }
        }
        result.put("disposition", disposition);
        var correction = correctionRows.isEmpty() ? null : legacyCorrectionView(correctionRows.getFirst());
        var reversal = reversalRows.isEmpty() ? null : legacyReversalView(reversalRows.getFirst());
        var recovery = recoveryRows.isEmpty() ? null : legacyRecoveryView(recoveryRows.getFirst());
        boolean writtenOff = disposition != null && "LOSS_WRITTEN_OFF".equals(disposition.get("disposition_status"));
        boolean pendingDisposal = disposition != null
                && "PENDING_DISPOSITION".equals(disposition.get("disposition_status"));
        boolean correctionPending = correction != null && "PENDING_ADMIN_DECISION".equals(correction.get("status"));
        boolean correctionClaimable = correction != null && "WAITING_WAREHOUSE_CLAIM".equals(correction.get("status"));
        boolean correctionProcessor = correction != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && !correctionRows.isEmpty() && java.util.Objects.equals(actor.id(),
                        correctionRows.getFirst().get("processor_account_id"));
        var actions = new LinkedHashMap<String, Object>();
        actions.put("decide", actor.has(Role.ASSET_ADMIN) && "PENDING_ADMIN_DECISION".equals(row.get("request_status")));
        actions.put("cancel", !locks.isEmpty() && ScrapReviewService.mayCancel(actor, row));
        actions.put("claim", actor.has(Role.WAREHOUSE_OPERATOR)
                && "WAITING_WAREHOUSE_CLAIM".equals(row.get("request_status")));
        actions.put("execute", owner && "PROCESSING".equals(row.get("request_status"))
                && ScrapReviewService.physicalSource(row.get("source_type")));
        actions.put("executeLoss", owner && "PROCESSING".equals(row.get("request_status")) && lossSource);
        actions.put("executeFactory", owner && "PROCESSING".equals(row.get("request_status")) && factory);
        if (factory && row.get("initial_batch_id") != null
                && (Boolean.TRUE.equals(actions.get("decide")) || Boolean.TRUE.equals(actions.get("executeFactory")))) {
            result.put("initialFactoryDispositionEvidence",
                    source.path("repairEvidence").path("context").path("evidence").asText());
        }
        actions.put("dispose", canDispose);
        actions.put("initiateCorrection", actor.has(Role.ASSET_ADMIN)
                && "RESTORE_REQUIRED".equals(row.get("request_status"))
                && "STANDALONE".equals(row.get("source_type")) && correction == null);
        actions.put("decideCorrection", correctionPending && actor.has(Role.ASSET_ADMIN));
        actions.put("claimCorrection", correctionClaimable && actor.has(Role.WAREHOUSE_OPERATOR));
        actions.put("executeCorrection", correctionProcessor && "PROCESSING".equals(correction.get("status")));
        actions.put("initiateReversal", reversal == null && pendingDisposal
                && legacyMayRequestReversal(actor, row));
        actions.put("decideReversal", reversal != null
                && "PENDING_ADMIN_DECISION".equals(reversal.get("status")) && actor.has(Role.ASSET_ADMIN));
        actions.put("claimReversal", reversal != null
                && "WAITING_WAREHOUSE_CLAIM".equals(reversal.get("status")) && actor.has(Role.WAREHOUSE_OPERATOR));
        actions.put("executeReversal", reversal != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && java.util.Objects.equals(actor.id(), reversalRows.getFirst().get("processor_account_id"))
                && "PROCESSING".equals(reversal.get("status")));
        actions.put("initiateRecovery", recovery == null && writtenOff && legacyMayRequestRecovery(actor, row));
        actions.put("decideRecovery", recovery != null
                && "PENDING_ADMIN_DECISION".equals(recovery.get("status")) && actor.has(Role.ASSET_ADMIN));
        actions.put("claimRecovery", recovery != null
                && "WAITING_WAREHOUSE_CLAIM".equals(recovery.get("status")) && actor.has(Role.WAREHOUSE_OPERATOR));
        actions.put("executeRecovery", recovery != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && java.util.Objects.equals(actor.id(), recoveryRows.getFirst().get("processor_account_id"))
                && "PROCESSING".equals(recovery.get("status")));
        if (legacyAuditOnly(actor)) {
            actions.replaceAll((action, allowed) -> false);
        }
        result.put("actions", actions);
        result.put("correction", correction);
        result.put("reversal", reversal);
        result.put("recovery", recovery);
        return result;
    }

    /** Frozen pre-JAVA-03 ScrapReadService.recoveryView; reference oracle only. */
    private static Map<String, Object> legacyRecoveryView(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString());
        result.put("status", row.get("recovery_status"));
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("foundAt", row.get("found_at"));
        result.put("temporaryLocationId", row.get("temporary_location_id").toString());
        result.put("temporaryLocationVersion", row.get("temporary_location_version"));
        result.put("temporaryLocationName", row.get("temporary_location_name"));
        result.put("temporaryCustodianEmployeeId", row.get("temporary_custodian_employee_id").toString());
        result.put("temporaryCustodianName", row.get("temporary_custodian_name"));
        result.put("targetLocationId", row.get("target_location_id").toString());
        result.put("targetLocationVersion", row.get("target_location_version"));
        result.put("targetLocationName", row.get("target_location_name"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id") == null
                ? null : row.get("processor_account_id").toString());
        result.put("reason", row.get("reason"));
        return result;
    }

    /** Frozen pre-JAVA-03 ScrapReadService.reversalView; reference oracle only. */
    private static Map<String, Object> legacyReversalView(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString());
        result.put("status", row.get("reversal_status"));
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("targetLocationId", row.get("target_location_id").toString());
        result.put("targetLocationVersion", row.get("target_location_version"));
        result.put("targetLocationName", row.get("target_location_name"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id") == null
                ? null : row.get("processor_account_id").toString());
        result.put("reason", row.get("reason"));
        return result;
    }

    /** Frozen pre-JAVA-03 ScrapReadService.correctionView; reference oracle only. */
    private static Map<String, Object> legacyCorrectionView(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString());
        result.put("status", row.get("correction_status"));
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("lockVersion", row.get("lock_version"));
        result.put("targetLocationId", row.get("target_location_id").toString());
        result.put("targetLocationVersion", row.get("target_location_version"));
        result.put("targetLocationName", row.get("target_location_name"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id") == null
                ? null : row.get("processor_account_id").toString());
        result.put("reason", row.get("reason"));
        return result;
    }

    /** Frozen pre-JAVA-03 ScrapReadService.mayRequestReversal; reference oracle only. */
    private static boolean legacyMayRequestReversal(Current actor, Map<String, Object> row) {
        return "EXECUTED".equals(row.get("request_status")) && ScrapReviewService.physicalSource(row.get("source_type"))
                && (actor.has(Role.ASSET_ADMIN) || actor.has(Role.WAREHOUSE_OPERATOR)
                        || java.util.Objects.equals(actor.id(), row.get("creator_account_id")));
    }

    /** Frozen pre-JAVA-03 ScrapReadService.mayRequestRecovery; reference oracle only. */
    private static boolean legacyMayRequestRecovery(Current actor, Map<String, Object> row) {
        return "EXECUTED".equals(row.get("request_status"))
                && (actor.has(Role.ASSET_ADMIN) || actor.has(Role.WAREHOUSE_OPERATOR)
                        || java.util.Objects.equals(actor.id(), row.get("creator_account_id")));
    }

    /** Frozen pre-JAVA-03 ScrapReadService.auditOnly; reference oracle only. */
    private static boolean legacyAuditOnly(Current actor) {
        return actor.has(Role.AUDIT_READ_ONLY) && !actor.has(Role.ASSET_ADMIN)
                && !actor.has(Role.WAREHOUSE_OPERATOR) && !actor.has(Role.DEPARTMENT_ASSET_MANAGER)
                && !actor.has(Role.EMPLOYEE);
    }
}
