package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.fams.assetimport.AssetImportBatchService.Preview;
import com.fams.assetimport.AssetImportBatchService.Ticket;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JAVA-11c wire-shape freeze: the assetimport HTTP surface was already fully typed before
 * this batch (every controller exit returns a named record), so instead of a pre-refactor
 * projection oracle this test freezes the current wire shape of every response record as a
 * literal JSON tree. The row-to-record extraction expressions below are copied verbatim from
 * the current services so a future refactor must keep both the key set and the extraction
 * semantics. Comparison round-trips through JSON text (integral widths normalize, key order
 * ignored); key presence and explicit nulls count. The mapper mirrors the production HTTP
 * mapper: com.fams.contract.probe.JsonConfiguration restores ALWAYS value inclusion on top
 * of the Jackson 3 NON_NULL default, so null components serialize as explicit null keys.
 */
class AssetImportWireShapeTest {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private static final UUID BATCH_ID = UUID.fromString("3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d");
    private static final UUID JOB_ID = UUID.fromString("7b7cbb44-4c1a-4f4e-9c1a-8f3e2d1c0b9a");
    private static final String SOURCE_JSON = "{\"资产编码\":\"FA-1\",\"名称\":\" laptop \",\"自定义列\":\"\"}";

    @Test
    void ticketFreezesMappingPassthroughAndExplicitNullMapping() {
        Map<String, Object> row = rowOf("batch_status", "PREVIEWED",
                "preview_version", 2, "mapping_json", "{\"assetCode\":\"资产编码\",\"name\":\"名称\"}");
        // Verbatim extraction from AssetImportBatchService.get.
        Ticket typed = new Ticket(BATCH_ID, (String) row.get("batch_status"),
                ((Number) row.get("preview_version")).longValue(), jsonOrNull((String) row.get("mapping_json")));
        // Dynamic Excel column keys inside mapping_json pass through verbatim (adjudicated
        // dynamic-key passthrough; the mapping dictionary itself is never typed).
        assertEquals(JSON.readTree((String) row.get("mapping_json")), typed.mapping());
        assertWireEquals("""
                {"id":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"PREVIEWED","previewVersion":2,
                 "mapping":{"assetCode":"资产编码","name":"名称"}}""", typed);
        assertKeys(typed, "id", "status", "previewVersion", "mapping");

        assertWireEquals("""
                {"id":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"UPLOADED","previewVersion":0,
                 "mapping":null}""", new Ticket(BATCH_ID, "UPLOADED", 0, null));
        // Integer and Long JDBC widths for preview_version must not change the wire.
        Map<String, Object> longRow = rowOf("batch_status", "PREVIEWED",
                "preview_version", 2L, "mapping_json", null);
        Ticket longVersion = new Ticket(BATCH_ID, (String) longRow.get("batch_status"),
                ((Number) longRow.get("preview_version")).longValue(),
                jsonOrNull((String) longRow.get("mapping_json")));
        assertWireEquals("""
                {"id":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"PREVIEWED","previewVersion":2,
                 "mapping":null}""", longVersion);
    }

    @Test
    void previewFreezesCellErrorListAndCounters() {
        List<AssetImportWorkbook.CellError> errors = List.of(
                new AssetImportWorkbook.CellError("资产", 3, "资产编码", "FA-1", "DUPLICATE_ASSET_CODE"),
                new AssetImportWorkbook.CellError(4, "序列号", "SN-9", "INVALID_FORMAT"));
        // Verbatim assembly from AssetImportBatchService.preview (first 100 errors + moreErrors flag).
        Preview typed = new Preview(BATCH_ID, 2, 10, 8, 2, errors.stream().limit(100).toList(),
                errors.size() > 100);
        assertWireEquals("""
                {"id":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","version":2,"totalRows":10,"readyRows":8,
                 "errorRows":2,"moreErrors":false,"errors":[
                  {"sheet":"资产","sheetRow":3,"column":"资产编码","original":"FA-1","reason":"DUPLICATE_ASSET_CODE"},
                  {"sheet":"资产","sheetRow":4,"column":"序列号","original":"SN-9","reason":"INVALID_FORMAT"}]}""", typed);
        assertKeys(typed, "id", "version", "totalRows", "readyRows", "errorRows", "errors", "moreErrors");
    }

    @Test
    void previewRowPassesStoredDynamicColumnsVerbatimAndKeepsNullKeys() {
        String resolvedJson = "{\"sheetRow\":2,\"assetCode\":\"FA-1\"}";
        // Verbatim extraction from the AssetImportBatchService.rows row mapper.
        AssetImportBatchService.PreviewRow ready = new AssetImportBatchService.PreviewRow(
                2, "READY", JSON.readTree(SOURCE_JSON), jsonOrNull(resolvedJson), null);
        JsonNode readyWire = JSON.readTree(JSON.writeValueAsString(ready));
        assertEquals(JSON.readTree(SOURCE_JSON), readyWire.get("source"));
        assertEquals(JSON.readTree(resolvedJson), readyWire.get("resolved"));
        assertTrue(readyWire.has("errors"), "errors must stay an explicit null key");
        assertFalse(readyWire.hasNonNull("errors"));
        assertKeys(ready, "sheetRow", "status", "source", "resolved", "errors");

        String errorsJson = "[{\"sheet\":\"资产\",\"sheetRow\":3,\"column\":\"序列号\","
                + "\"original\":\"SN-9\",\"reason\":\"X\"}]";
        AssetImportBatchService.PreviewRow errorRow = new AssetImportBatchService.PreviewRow(
                3, "ERROR", JSON.readTree(SOURCE_JSON), null, jsonOrNull(errorsJson));
        JsonNode errorWire = JSON.readTree(JSON.writeValueAsString(errorRow));
        assertEquals(JSON.readTree(SOURCE_JSON), errorWire.get("source"));
        assertEquals(JSON.readTree(errorsJson), errorWire.get("errors"));
        assertTrue(errorWire.has("resolved"), "resolved must stay an explicit null key");
        assertFalse(errorWire.hasNonNull("resolved"));
    }

    @Test
    void rowPageAndColumnListFreezeEnvelopeShapes() {
        AssetImportBatchService.PreviewRow item = new AssetImportBatchService.PreviewRow(
                2, "READY", JSON.readTree("{\"a\":\"1\"}"), null, null);
        AssetImportBatchService.RowPage page = new AssetImportBatchService.RowPage(57, List.of(item));
        assertWireEquals("""
                {"total":57,"items":[{"sheetRow":2,"status":"READY","source":{"a":"1"},
                 "resolved":null,"errors":null}]}""", page);
        assertKeys(page, "total", "items");

        AssetImportBatchService.ColumnList columns =
                new AssetImportBatchService.ColumnList(List.of("资产编码", "名称", "序列号"));
        assertWireEquals("{\"columns\":[\"资产编码\",\"名称\",\"序列号\"]}", columns);
        assertKeys(columns, "columns");
    }

    @Test
    void reviewFreezesStringIdsAndExplicitNullDecisionKeys() {
        Map<String, Object> row = rowOf("review_status", "PENDING", "preview_version", 3L,
                "processor_account_id", 11L, "decided_by", null, "job_id", null);
        // Verbatim extraction from AssetImportReviewService.getOwned.
        AssetImportReviewService.Review pending = new AssetImportReviewService.Review(BATCH_ID,
                (String) row.get("review_status"),
                ((Number) row.get("preview_version")).longValue(),
                row.get("processor_account_id").toString(),
                row.get("decided_by") == null ? null : row.get("decided_by").toString(),
                row.get("job_id") == null ? null : UUID.fromString((String) row.get("job_id")));
        assertWireEquals("""
                {"batchId":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"PENDING","previewVersion":3,
                 "processorAccountId":"11","decidedBy":null,"jobId":null}""", pending);
        assertKeys(pending, "batchId", "status", "previewVersion", "processorAccountId",
                "decidedBy", "jobId");

        Map<String, Object> decided = rowOf("review_status", "APPROVED", "preview_version", 3L,
                "processor_account_id", 11L, "decided_by", 12L, "job_id", JOB_ID.toString());
        // Verbatim extraction from AssetImportReviewService.getOwned (decided branch).
        AssetImportReviewService.Review approved = new AssetImportReviewService.Review(BATCH_ID,
                (String) decided.get("review_status"),
                ((Number) decided.get("preview_version")).longValue(),
                decided.get("processor_account_id").toString(),
                decided.get("decided_by") == null ? null : decided.get("decided_by").toString(),
                decided.get("job_id") == null ? null : UUID.fromString((String) decided.get("job_id")));
        assertWireEquals("""
                {"batchId":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"APPROVED","previewVersion":3,
                 "processorAccountId":"11","decidedBy":"12",
                 "jobId":"7b7cbb44-4c1a-4f4e-9c1a-8f3e2d1c0b9a"}""", approved);
    }

    @Test
    void reviewPendingFreezesInstantAsIsoString() {
        // Verbatim extraction from the AssetImportReviewService.pending row mapper.
        AssetImportReviewService.Pending typed = new AssetImportReviewService.Pending(BATCH_ID, 3L, 8, 2,
                "ops-a", Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")).toInstant());
        assertWireEquals("""
                {"batchId":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","previewVersion":3,"readyRows":8,
                 "errorRows":2,"operatorUsername":"ops-a","requestedAt":"2026-11-20T02:00:00Z"}""", typed);
        assertKeys(typed, "batchId", "previewVersion", "readyRows", "errorRows",
                "operatorUsername", "requestedAt");
    }

    @Test
    void rollbackFreezesVersionAndJobIdShapes() {
        AssetImportRollbackService.Rollback queued = new AssetImportRollbackService.Rollback(
                BATCH_ID, "QUEUED", 2L, JOB_ID);
        assertWireEquals("""
                {"batchId":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"QUEUED","version":2,
                 "jobId":"7b7cbb44-4c1a-4f4e-9c1a-8f3e2d1c0b9a"}""", queued);
        assertKeys(queued, "batchId", "status", "version", "jobId");

        AssetImportRollbackService.Rollback pending = new AssetImportRollbackService.Rollback(
                BATCH_ID, "PENDING", 1L, null);
        assertWireEquals("""
                {"batchId":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","status":"PENDING","version":1,
                 "jobId":null}""", pending);
    }

    @Test
    void rollbackPendingFreezesCountAndReason() {
        // Verbatim extraction from the AssetImportRollbackService.pending row mapper.
        AssetImportRollbackService.Pending typed = new AssetImportRollbackService.Pending(BATCH_ID,
                1L, 42, "ops-a", "wrong rows",
                Timestamp.from(Instant.parse("2026-11-21T08:30:00Z")).toInstant());
        assertWireEquals("""
                {"batchId":"3f2b6c1a-5d4e-4a7b-9c8d-1e2f3a4b5c6d","version":1,"assetCount":42,
                 "operatorUsername":"ops-a","reason":"wrong rows","requestedAt":"2026-11-21T08:30:00Z"}""", typed);
        assertKeys(typed, "batchId", "version", "assetCount", "operatorUsername", "reason", "requestedAt");
    }

    @Test
    void windowAndOperatorFreezeStringIdsAndUtcExpiry() {
        Map<String, Object> row = rowOf("id", 7L, "assigned_operator_id", 3L,
                "expires_at", LocalDateTime.of(2026, 12, 1, 10, 0, 0));
        // Verbatim extraction from AssetInitializationWindowService.current.
        AssetInitializationWindowService.Window window = new AssetInitializationWindowService.Window(
                row.get("id").toString(), "OPEN", row.get("assigned_operator_id").toString(),
                ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC));
        assertWireEquals("""
                {"id":"7","status":"OPEN","assignedOperatorId":"3","expiresAt":"2026-12-01T10:00:00Z"}""", window);
        assertKeys(window, "id", "status", "assignedOperatorId", "expiresAt");

        AssetInitializationWindowService.EligibleOperator operator =
                new AssetInitializationWindowService.EligibleOperator("3", "ops-a");
        assertWireEquals("{\"id\":\"3\",\"username\":\"ops-a\"}", operator);
        assertKeys(operator, "id", "username");
    }

    @Test
    void everyResponseRecordRoundTripsThroughJsonText() {
        assertRoundTrip(new Ticket(BATCH_ID, "PREVIEWED", 2, JSON.readTree("{\"assetCode\":\"资产编码\"}")),
                Ticket.class);
        assertRoundTrip(new Preview(BATCH_ID, 2, 10, 8, 2, List.of(), false), Preview.class);
        AssetImportBatchService.PreviewRow row = new AssetImportBatchService.PreviewRow(
                2, "READY", JSON.readTree(SOURCE_JSON), null, null);
        assertRoundTrip(row, AssetImportBatchService.PreviewRow.class);
        assertRoundTrip(new AssetImportBatchService.RowPage(1, List.of(row)),
                AssetImportBatchService.RowPage.class);
        assertRoundTrip(new AssetImportReviewService.Review(BATCH_ID, "PENDING", 3, "11", null, null),
                AssetImportReviewService.Review.class);
        assertRoundTrip(new AssetImportReviewService.Pending(BATCH_ID, 3, 8, 2, "ops-a",
                Instant.parse("2026-11-20T02:00:00Z")), AssetImportReviewService.Pending.class);
        assertRoundTrip(new AssetImportRollbackService.Rollback(BATCH_ID, "PENDING", 1, null),
                AssetImportRollbackService.Rollback.class);
        assertRoundTrip(new AssetImportRollbackService.Pending(BATCH_ID, 1, 42, "ops-a", "r",
                Instant.parse("2026-11-21T08:30:00Z")), AssetImportRollbackService.Pending.class);
        assertRoundTrip(new AssetInitializationWindowService.Window("7", "OPEN", "3",
                Instant.parse("2026-12-01T10:00:00Z")), AssetInitializationWindowService.Window.class);
        assertRoundTrip(new AssetInitializationWindowService.EligibleOperator("3", "ops-a"),
                AssetInitializationWindowService.EligibleOperator.class);
    }

    private static void assertRoundTrip(Object typed, Class<?> type) {
        String stored = JSON.writeValueAsString(typed);
        assertEquals(JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static JsonNode jsonOrNull(String value) {
        return value == null ? null : JSON.readTree(value);
    }

    private static void assertWireEquals(String frozenJson, Object typed) {
        assertEquals(JSON.readTree(frozenJson), JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static void assertKeys(Object typed, String... keys) {
        Set<String> actual = new LinkedHashSet<>();
        JSON.readTree(JSON.writeValueAsString(typed)).propertyNames().forEach(actual::add);
        assertEquals(Set.of(keys), actual);
    }

    private static Map<String, Object> rowOf(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }
}
