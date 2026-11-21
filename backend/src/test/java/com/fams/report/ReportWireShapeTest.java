package com.fams.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JAVA-12a wire-shape freeze: every report-domain HTTP exit was already fully typed before this
 * batch (each operations-reports/workbench controller returns a named service record, a
 * {@code List<...>} of named records, or CSV text), so instead of a pre-refactor projection oracle
 * this test freezes the current wire shape of every response record as a literal JSON tree
 * (JAVA-11c/JAVA-08 finished-state method). The row-to-record extraction expressions below are
 * copied verbatim from the current services so a future refactor must keep both the key set and
 * the extraction semantics. Comparison round-trips through JSON text (integral widths normalize,
 * key order ignored); key presence and explicit nulls count. The mapper mirrors the production
 * HTTP mapper: com.fams.contract.probe.JsonConfiguration restores ALWAYS value inclusion on top of
 * the Jackson 3 NON_NULL default, so null components serialize as explicit null keys everywhere in
 * this domain (same adjudication as AssetImportWireShapeTest).
 */
class ReportWireShapeTest {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-20T02:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-11-21T08:30:00Z");

    @Test
    void assetCustodyFreezesStringGroupIdsAndExplicitNullJoins() {
        Map<String, Object> group = rowOf("group_id", 0L, "name", null, "total", 3L);
        // Verbatim extraction from AssetCustodyReportService.groups (COALESCE(g.id,0) stays a string id).
        AssetCustodyReportService.Group unassigned = new AssetCustodyReportService.Group(
                (String) asString(group.get("group_id")), (String) group.get("name"),
                ((Number) group.get("total")).longValue());
        assertWireEquals("{\"id\":\"0\",\"name\":null,\"count\":3}", unassigned);
        assertKeys(unassigned, "id", "name", "count");

        AssetCustodyReportService.Distribution distribution = new AssetCustodyReportService.Distribution(
                List.of(unassigned, new AssetCustodyReportService.Group("7", "总部", 12L)),
                List.of(new AssetCustodyReportService.Group("21", "办公设备", 15L)),
                List.of(new AssetCustodyReportService.CustodyGroup("PERSONAL", 5L)),
                new AssetCustodyReportService.CustodyQuality(9L, 15L, 0L));
        assertWireEquals("""
                {"organizations":[{"id":"0","name":null,"count":3},{"id":"7","name":"总部","count":12}],
                 "categories":[{"id":"21","name":"办公设备","count":15}],
                 "custody":[{"custodyType":"PERSONAL","count":5}],
                 "custodyQuality":{"complete":9,"effective":15,"excluded":0}}""", distribution);
        assertKeys(distribution, "organizations", "categories", "custody", "custodyQuality");

        Map<String, Object> row = rowOf("id", 41L, "asset_code", "FA-1", "name", "笔记本电脑",
                "lifecycle_status", "IN_USE", "exception_status", "NORMAL", "custody_type", "PERSONAL",
                "organization_name", null, "responsible_name", null, "warehouse_name", null,
                "location_name", "A-1-01", "last_inventory_business_date", LocalDate.of(2026, 11, 1));
        // Verbatim extraction from the AssetCustodyReportService.query row mapper.
        AssetCustodyReportService.Row item = new AssetCustodyReportService.Row(
                asString(row.get("id")), (String) row.get("asset_code"), (String) row.get("name"),
                (String) row.get("lifecycle_status"), (String) row.get("exception_status"),
                (String) row.get("custody_type"), (String) row.get("organization_name"),
                (String) row.get("responsible_name"), (String) row.get("warehouse_name"),
                (String) row.get("location_name"), (LocalDate) row.get("last_inventory_business_date"));
        assertWireEquals("""
                {"assetId":"41","assetCode":"FA-1","name":"笔记本电脑","lifecycle":"IN_USE",
                 "exception":"NORMAL","custody":"PERSONAL","organization":null,"responsible":null,
                 "warehouse":null,"location":"A-1-01","lastInventory":"2026-11-01"}""", item);
        assertKeys(item, "assetId", "assetCode", "name", "lifecycle", "exception", "custody", "organization",
                "responsible", "warehouse", "location", "lastInventory");

        AssetCustodyReportService.Page page = new AssetCustodyReportService.Page(List.of(item), "41");
        assertWireEquals("{\"items\":[" + JSON.writeValueAsString(item) + "],\"nextBefore\":\"41\"}", page);
        assertKeys(page, "items", "nextBefore");
        // Never-more rows freezes nextBefore as an explicit null key.
        JsonNode tail = JSON.readTree(JSON.writeValueAsString(new AssetCustodyReportService.Page(List.of(), null)));
        assertTrue(tail.has("nextBefore") && tail.get("nextBefore").isNull());
    }

    @Test
    void assetMovementFreezesNestedCursorPageAndTrendEnums() {
        Map<String, Object> row = rowOf("fact_id", 88L, "business_id", 12L, "asset_id", 41L,
                "asset_code", "FA-1", "occurred_at", Timestamp.from(OCCURRED_AT), "outcome", "ACCEPTED");
        // Verbatim extraction from the AssetMovementReportService.query row mapper.
        AssetMovementReportService.Row item = new AssetMovementReportService.Row(
                asString(row.get("fact_id")), asString(row.get("business_id")), asString(row.get("asset_id")),
                (String) row.get("asset_code"), ((Timestamp) row.get("occurred_at")).toInstant(),
                (String) row.get("outcome"));
        assertWireEquals("""
                {"factId":"88","businessId":"12","assetId":"41","assetCode":"FA-1",
                 "occurredAt":"2026-11-20T02:00:00Z","outcome":"ACCEPTED"}""", item);
        assertKeys(item, "factId", "businessId", "assetId", "assetCode", "occurredAt", "outcome");

        // Movement (and timeline) nextBefore is a nested cursor object, unlike the string pages.
        AssetMovementReportService.Page page = new AssetMovementReportService.Page(List.of(item),
                new AssetMovementReportService.Cursor(item.occurredAt(), item.factId()));
        assertWireEquals("""
                {"items":[{"factId":"88","businessId":"12","assetId":"41","assetCode":"FA-1",
                  "occurredAt":"2026-11-20T02:00:00Z","outcome":"ACCEPTED"}],
                 "nextBefore":{"occurredAt":"2026-11-20T02:00:00Z","factId":"88"}}""", page);
        assertKeys(page, "items", "nextBefore");
        JsonNode tail = JSON.readTree(JSON.writeValueAsString(new AssetMovementReportService.Page(List.of(), null)));
        assertTrue(tail.has("nextBefore") && tail.get("nextBefore").isNull(), "cursor null stays an explicit key");

        AssetMovementReportService.TrendMonth month = new AssetMovementReportService.TrendMonth(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
                List.of(new AssetMovementReportService.TrendCount(AssetMovementReportService.Kind.INBOUND, 3L),
                        new AssetMovementReportService.TrendCount(AssetMovementReportService.Kind.SCRAP, 0L)));
        assertWireEquals("""
                {"from":"2026-11-01","toExclusive":"2026-12-01","counts":[
                 {"kind":"INBOUND","count":3},{"kind":"SCRAP","count":0}]}""", month);
        assertKeys(month, "from", "toExclusive", "counts");
    }

    @Test
    void assetTimelineFreezesNestedCursorAndNullRoundLink() {
        Map<String, Object> row = rowOf("id", 701L, "asset_code", "FA-1", "event_type", "ASSET_ACCEPTED",
                "occurred_at", Timestamp.from(OCCURRED_AT), "business_type", "INBOUND", "business_id", 12L,
                "factory_round_id", null, "summary", "");
        // Verbatim extraction from the AssetTimelineReportService.query row mapper.
        AssetTimelineReportService.Row item = new AssetTimelineReportService.Row(
                asString(row.get("id")), (String) row.get("asset_code"), (String) row.get("event_type"),
                ((Timestamp) row.get("occurred_at")).toInstant(), (String) row.get("business_type"),
                asString(row.get("business_id")), asString(row.get("factory_round_id")),
                (String) row.get("summary"));
        assertWireEquals("""
                {"eventId":"701","assetCode":"FA-1","eventType":"ASSET_ACCEPTED",
                 "occurredAt":"2026-11-20T02:00:00Z","businessType":"INBOUND","businessId":"12",
                 "factoryRoundId":null,"summary":""}""", item);
        assertKeys(item, "eventId", "assetCode", "eventType", "occurredAt", "businessType", "businessId",
                "factoryRoundId", "summary");

        AssetTimelineReportService.Page page = new AssetTimelineReportService.Page(List.of(item),
                new AssetTimelineReportService.Cursor(item.occurredAt(), item.eventId()));
        assertWireEquals("""
                {"items":[{"eventId":"701","assetCode":"FA-1","eventType":"ASSET_ACCEPTED",
                  "occurredAt":"2026-11-20T02:00:00Z","businessType":"INBOUND","businessId":"12",
                  "factoryRoundId":null,"summary":""}],
                 "nextBefore":{"occurredAt":"2026-11-20T02:00:00Z","eventId":"701"}}""", page);
    }

    @Test
    void businessExceptionFreezesProcessedAtNullBranch() {
        Map<String, Object> row = rowOf("id", 501L, "business_type", "TRANSFER", "business_id", 9L,
                "asset_code", "FA-1", "fact_status", "PENDING", "registered_at", Timestamp.from(OCCURRED_AT),
                "processed_at", null);
        // Verbatim extraction from the BusinessExceptionReportService.query row mapper.
        Timestamp processed = (Timestamp) row.get("processed_at");
        BusinessExceptionReportService.Row item = new BusinessExceptionReportService.Row(
                asString(row.get("id")), (String) row.get("business_type"), asString(row.get("business_id")),
                (String) row.get("asset_code"), (String) row.get("fact_status"),
                ((Timestamp) row.get("registered_at")).toInstant(), processed == null ? null : processed.toInstant());
        assertWireEquals("""
                {"recordId":"501","businessType":"TRANSFER","businessId":"9","assetCode":"FA-1",
                 "status":"PENDING","registeredAt":"2026-11-20T02:00:00Z","processedAt":null}""", item);
        assertKeys(item, "recordId", "businessType", "businessId", "assetCode", "status", "registeredAt",
                "processedAt");

        Timestamp done = Timestamp.from(NEXT_DAY);
        BusinessExceptionReportService.Row processedRow = new BusinessExceptionReportService.Row(
                "501", "TRANSFER", "9", "FA-1", "COMPLETED", OCCURRED_AT, done.toInstant());
        assertWireEquals("""
                {"recordId":"501","businessType":"TRANSFER","businessId":"9","assetCode":"FA-1",
                 "status":"COMPLETED","registeredAt":"2026-11-20T02:00:00Z",
                 "processedAt":"2026-11-21T08:30:00Z"}""", processedRow);
        BusinessExceptionReportService.Page page = new BusinessExceptionReportService.Page(List.of(item), "501");
        assertKeys(page, "items", "nextBefore");
    }

    @Test
    void exceptionDiscoveryFreezesSummaryAndCategoryWithoutDerivedTotalKey() {
        // Category.total() is a derivation helper: the record wire keeps only the five components.
        ExceptionDiscoveryReportService.Category category = new ExceptionDiscoveryReportService.Category(
                ExceptionDiscoveryReportService.Kind.LOAN_OVERDUE, 10L, 2L, 1L, 0L);
        assertEquals(13L, category.total());
        assertWireEquals("{\"kind\":\"LOAN_OVERDUE\",\"onTime\":10,\"late\":2,\"missing\":1,\"pending\":0}", category);
        assertKeys(category, "kind", "onTime", "late", "missing", "pending");

        ExceptionDiscoveryReportService.Summary summary = new ExceptionDiscoveryReportService.Summary(
                OCCURRED_AT, 13L, 10L, List.of(category, new ExceptionDiscoveryReportService.Category(
                        ExceptionDiscoveryReportService.Kind.FACTORY_OVERDUE, 0L, 0L, 0L, 0L)));
        assertWireEquals("""
                {"evaluatedAt":"2026-11-20T02:00:00Z","total":13,"onTime":10,"categories":[
                 {"kind":"LOAN_OVERDUE","onTime":10,"late":2,"missing":1,"pending":0},
                 {"kind":"FACTORY_OVERDUE","onTime":0,"late":0,"missing":0,"pending":0}]}""", summary);
        assertKeys(summary, "evaluatedAt", "total", "onTime", "categories");
    }

    @Test
    void inboundTimingFreezesStartedAtAndElapsedNullBranches() {
        Map<String, Object> row = rowOf("id", 301L, "receipt_no", "IN-2026-0001", "receipt_id", 12L,
                "processor_account_id", null, "stage_result", "APPROVED", "started_at", null,
                "finished_at", Timestamp.from(NEXT_DAY));
        // Verbatim extraction from the InboundTimingReportService.query row mapper.
        Timestamp started = (Timestamp) row.get("started_at");
        Timestamp finished = (Timestamp) row.get("finished_at");
        InboundTimingReportService.Row item = new InboundTimingReportService.Row(
                asString(row.get("id")), (String) row.get("receipt_no"), asString(row.get("receipt_id")),
                asString(row.get("processor_account_id")), (String) row.get("stage_result"),
                started == null ? null : started.toInstant(), finished.toInstant(),
                started == null || finished.before(started) ? null
                        : java.time.Duration.between(started.toInstant(), finished.toInstant()).toSeconds());
        assertWireEquals("""
                {"factId":"301","receiptNo":"IN-2026-0001","receiptId":"12","processorAccountId":null,
                 "status":"APPROVED","startedAt":null,"finishedAt":"2026-11-21T08:30:00Z","elapsedSeconds":null}""", item);
        assertKeys(item, "factId", "receiptNo", "receiptId", "processorAccountId", "status", "startedAt",
                "finishedAt", "elapsedSeconds");

        // CLAIM-style row: processor id is a CAST(... AS CHAR) string, elapsed seconds is a Long.
        InboundTimingReportService.Row claimed = new InboundTimingReportService.Row(
                "302", "IN-2026-0001", "12", "11", "CLAIMED", OCCURRED_AT, NEXT_DAY, 23400L);
        assertWireEquals("""
                {"factId":"302","receiptNo":"IN-2026-0001","receiptId":"12","processorAccountId":"11",
                 "status":"CLAIMED","startedAt":"2026-11-20T02:00:00Z","finishedAt":"2026-11-21T08:30:00Z",
                 "elapsedSeconds":23400}""", claimed);
        assertKeys(new InboundTimingReportService.Page(List.of(claimed), "302"), "items", "nextBefore");
    }

    @Test
    void inventoryLateFreezesResultEnumAndOptionalLinkedTaskKeys() {
        Map<String, Object> row = rowOf("observation_id", 201L, "task_id", 33L, "task_name", "年度盘点",
                "asset_code", "FA-1", "received_at", Timestamp.from(OCCURRED_AT), "comparison_kind", "DIFFERENCE",
                "result", "OPEN", "linked_task_id", null, "linked_task_status", null);
        // Verbatim extraction from the InventoryLateReportService.query row mapper.
        InventoryLateReportService.Row item = new InventoryLateReportService.Row(
                asString(row.get("observation_id")), asString(row.get("task_id")), (String) row.get("task_name"),
                (String) row.get("asset_code"), ((Timestamp) row.get("received_at")).toInstant(),
                (String) row.get("comparison_kind"), InventoryLateReportService.Result.valueOf(
                        (String) row.get("result")),
                asString(row.get("linked_task_id")), asString(row.get("linked_task_status")));
        assertWireEquals("""
                {"observationId":"201","taskId":"33","taskName":"年度盘点","assetCode":"FA-1",
                 "receivedAt":"2026-11-20T02:00:00Z","comparison":"DIFFERENCE","result":"OPEN",
                 "linkedTaskId":null,"linkedTaskStatus":null}""", item);
        assertKeys(item, "observationId", "taskId", "taskName", "assetCode", "receivedAt", "comparison",
                "result", "linkedTaskId", "linkedTaskStatus");
        assertKeys(new InventoryLateReportService.Page(List.of(item), "201"), "items", "nextBefore");
    }

    @Test
    void lossPageFreezesCountsFacetAndRecoveredNullBranches() {
        // Verbatim extraction from the LossReportService.query row mapper (STATE CASE branch).
        LossReportService.Row searching = new LossReportService.Row(
                "401", "41", "FA-1", null, LossReportService.State.SEARCHING, OCCURRED_AT, null, null);
        assertWireEquals("""
                {"reportId":"401","assetId":"41","assetCode":"FA-1","organization":null,"state":"SEARCHING",
                 "reportedAt":"2026-11-20T02:00:00Z","confirmedLostAt":null,"recoveredAt":null}""", searching);
        assertKeys(searching, "reportId", "assetId", "assetCode", "organization", "state", "reportedAt",
                "confirmedLostAt", "recoveredAt");

        Timestamp confirmed = Timestamp.from(NEXT_DAY);
        LossReportService.Row recovered = new LossReportService.Row("402", "42", "FA-2", "总部",
                LossReportService.State.RECOVERED, OCCURRED_AT, confirmed.toInstant(), NEXT_DAY);
        assertWireEquals("""
                {"reportId":"402","assetId":"42","assetCode":"FA-2","organization":"总部","state":"RECOVERED",
                 "reportedAt":"2026-11-20T02:00:00Z","confirmedLostAt":"2026-11-21T08:30:00Z",
                 "recoveredAt":"2026-11-21T08:30:00Z"}""", recovered);
        LossReportService.Count count = new LossReportService.Count(
                LossReportService.State.valueOf("CONFIRMED_LOST"), 4L);
        LossReportService.Page page = new LossReportService.Page(List.of(count), List.of(searching), "401");
        assertWireEquals("""
                {"counts":[{"state":"CONFIRMED_LOST","count":4}],
                 "items":[{"reportId":"401","assetId":"41","assetCode":"FA-1","organization":null,
                  "state":"SEARCHING","reportedAt":"2026-11-20T02:00:00Z","confirmedLostAt":null,"recoveredAt":null}],
                 "nextBefore":"401"}""", page);
        assertKeys(page, "counts", "items", "nextBefore");
    }

    @Test
    void operationsSummaryFreezesMatrixFacetsAndStringCalculatedAt() {
        // calculatedAt is clock.instant().toString() (String), unlike the Instant fields elsewhere.
        OperationsReportService.Summary summary = new OperationsReportService.Summary(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), "2026-11-22T00:00:00Z",
                new OperationsReportService.Inventory(2L, 100L, 98L, 2L),
                List.of(new OperationsReportService.Count("ASSET_IMPORT_PUBLISHED", 3L)),
                List.of(new OperationsReportService.AssetCount("IN_USE", "NORMAL", 42L)),
                new OperationsReportService.ImportErrorQuality(7L, 1L));
        assertWireEquals("""
                {"from":"2026-11-01","toExclusive":"2026-12-01","calculatedAt":"2026-11-22T00:00:00Z",
                 "inventory":{"sealedTasks":2,"expected":100,"accepted":98,"notObserved":2},
                 "openingBatches":[{"status":"ASSET_IMPORT_PUBLISHED","count":3}],
                 "currentAssets":[{"lifecycle":"IN_USE","exception":"NORMAL","count":42}],
                 "importErrorQuality":{"locatedRows":7,"failedRows":1}}""", summary);
        assertKeys(summary, "from", "toExclusive", "calculatedAt", "inventory", "openingBatches",
                "currentAssets", "importErrorQuality");
    }

    @Test
    void overdueLoanPageFreezesInstantCalculatedAtAndDateCells() {
        Map<String, Object> row = rowOf("id", 601L, "asset_id", 41L, "asset_code", "FA-1", "borrower", "张三",
                "organization", "总部", "expected_return_date", LocalDate.of(2026, 11, 10),
                "business_date", LocalDate.of(2026, 11, 20));
        // Verbatim extraction from the OverdueLoanReportService.query candidate mapper.
        LocalDate due = (LocalDate) row.get("expected_return_date");
        LocalDate today = (LocalDate) row.get("business_date");
        OverdueLoanReportService.Row item = new OverdueLoanReportService.Row(
                asString(row.get("id")), asString(row.get("asset_id")), (String) row.get("asset_code"),
                (String) row.get("borrower"), (String) row.get("organization"), due, today,
                java.time.temporal.ChronoUnit.DAYS.between(due, today));
        assertWireEquals("""
                {"loanId":"601","assetId":"41","assetCode":"FA-1","borrower":"张三","organization":"总部",
                 "expectedReturnDate":"2026-11-10","businessDate":"2026-11-20","overdueDays":10}""", item);
        assertKeys(item, "loanId", "assetId", "assetCode", "borrower", "organization", "expectedReturnDate",
                "businessDate", "overdueDays");
        OverdueLoanReportService.Page page = new OverdueLoanReportService.Page(OCCURRED_AT, List.of(item), "601");
        assertWireEquals("""
                {"calculatedAt":"2026-11-20T02:00:00Z","items":[{"loanId":"601","assetId":"41",
                 "assetCode":"FA-1","borrower":"张三","organization":"总部",
                 "expectedReturnDate":"2026-11-10","businessDate":"2026-11-20","overdueDays":10}],
                 "nextBefore":"601"}""", page);
        assertKeys(page, "calculatedAt", "items", "nextBefore");
    }

    @Test
    void repairOperationsFreezesBigDecimalsAndTotalsFacet() {
        // DECIMAL(18,3) column: the wire keeps the stored scale verbatim (SUM and row values alike).
        Map<String, Object> row = rowOf("fact_id", 901L, "repair_id", 55L, "asset_id", 41L,
                "asset_code", "FA-1", "occurred_at", Timestamp.from(OCCURRED_AT), "fact_status", "ACCEPTED",
                "actual_cost", new BigDecimal("1234.560"));
        // Verbatim extraction from the RepairOperationsReportService.query row mapper.
        RepairOperationsReportService.Row item = new RepairOperationsReportService.Row(
                asString(row.get("fact_id")), asString(row.get("repair_id")), asString(row.get("asset_id")),
                (String) row.get("asset_code"), ((Timestamp) row.get("occurred_at")).toInstant(),
                (String) row.get("fact_status"), (BigDecimal) row.get("actual_cost"));
        assertWireEquals("""
                {"factId":"901","repairId":"55","assetId":"41","assetCode":"FA-1",
                 "occurredAt":"2026-11-20T02:00:00Z","status":"ACCEPTED","actualCost":1234.560}""", item);
        assertKeys(item, "factId", "repairId", "assetId", "assetCode", "occurredAt", "status", "actualCost");

        RepairOperationsReportService.Row costless = new RepairOperationsReportService.Row(
                "901", "55", "41", "FA-1", OCCURRED_AT, "SUBMITTED", null);
        JsonNode costlessWire = JSON.readTree(JSON.writeValueAsString(costless));
        assertTrue(costlessWire.has("actualCost") && costlessWire.get("actualCost").isNull(),
                "INTERNAL-kind NULL actual_cost stays an explicit null key");

        RepairOperationsReportService.Page page = new RepairOperationsReportService.Page(
                new RepairOperationsReportService.Totals(2L, 1L, new BigDecimal("1234.560")),
                List.of(item, costless), "901");
        assertWireEquals("""
                {"totals":{"count":2,"confirmedCostCount":1,"actualCost":1234.560},
                 "items":[{"factId":"901","repairId":"55","assetId":"41","assetCode":"FA-1",
                   "occurredAt":"2026-11-20T02:00:00Z","status":"ACCEPTED","actualCost":1234.560},
                  {"factId":"901","repairId":"55","assetId":"41","assetCode":"FA-1",
                   "occurredAt":"2026-11-20T02:00:00Z","status":"SUBMITTED","actualCost":null}],
                 "nextBefore":"901"}""", page);
        assertKeys(page, "totals", "items", "nextBefore");
    }

    @Test
    void scrapDispositionFreezesBusinessDateHeadAndStateFacet() {
        Map<String, Object> row = rowOf("id", 1001L, "asset_id", 41L, "asset_code", "FA-1", "processor", "李四",
                "due_date", LocalDate.of(2026, 11, 25), "disposition_recorded_at", null);
        // Verbatim extraction from the ScrapDispositionReportService.query row mapper.
        Timestamp recorded = (Timestamp) row.get("disposition_recorded_at");
        ScrapDispositionReportService.Row item = new ScrapDispositionReportService.Row(
                asString(row.get("id")), asString(row.get("asset_id")), (String) row.get("asset_code"),
                (String) row.get("processor"),
                ScrapDispositionReportService.State.valueOf("OVERDUE"),
                (LocalDate) row.get("due_date"), recorded == null ? null : recorded.toInstant());
        assertWireEquals("""
                {"requestId":"1001","assetId":"41","assetCode":"FA-1","processor":"李四","state":"OVERDUE",
                 "dueDate":"2026-11-25","dispositionRecordedAt":null}""", item);
        assertKeys(item, "requestId", "assetId", "assetCode", "processor", "state", "dueDate",
                "dispositionRecordedAt");

        ScrapDispositionReportService.Page page = new ScrapDispositionReportService.Page(
                LocalDate.of(2026, 11, 22),
                List.of(new ScrapDispositionReportService.Count(
                        ScrapDispositionReportService.State.PENDING_DISPOSITION, 5L)),
                List.of(item), "1001");
        assertWireEquals("""
                {"businessDate":"2026-11-22","counts":[{"state":"PENDING_DISPOSITION","count":5}],
                 "items":[{"requestId":"1001","assetId":"41","assetCode":"FA-1","processor":"李四",
                  "state":"OVERDUE","dueDate":"2026-11-25","dispositionRecordedAt":null}],
                 "nextBefore":"1001"}""", page);
        assertKeys(page, "businessDate", "counts", "items", "nextBefore");
    }

    @Test
    void sealedInventoryFreezesTaskProgressAndExecutorBreakdown() {
        Map<String, Object> taskRow = rowOf("task_id", 33L, "name", "年度盘点", "created_at",
                Timestamp.from(OCCURRED_AT), "expected_count", 100L, "accepted_count", 98L,
                "not_observed_count", 2L, "difference_count", 1L, "conflict_count", 0L);
        // Verbatim extraction from the SealedInventoryReportService.query row mapper.
        SealedInventoryReportService.Task task = new SealedInventoryReportService.Task(
                asString(taskRow.get("task_id")), (String) taskRow.get("name"),
                ((Timestamp) taskRow.get("created_at")).toInstant(),
                ((Number) taskRow.get("expected_count")).longValue(),
                ((Number) taskRow.get("accepted_count")).longValue(),
                ((Number) taskRow.get("not_observed_count")).longValue(),
                ((Number) taskRow.get("difference_count")).longValue(),
                ((Number) taskRow.get("conflict_count")).longValue());
        assertWireEquals("""
                {"taskId":"33","name":"年度盘点","sealedAt":"2026-11-20T02:00:00Z","expected":100,
                 "accepted":98,"notObserved":2,"differenceEvidence":1,"conflictEvidence":0}""", task);
        assertKeys(task, "taskId", "name", "sealedAt", "expected", "accepted", "notObserved",
                "differenceEvidence", "conflictEvidence");
        assertKeys(new SealedInventoryReportService.Page(List.of(task), "33"), "items", "nextBefore");

        // progress.dueAt is never null (NOT NULL column) but the executor id IS NULL branch must stay explicit.
        SealedInventoryReportService.Progress progress = new SealedInventoryReportService.Progress(
                "34", "抽盘", "IN_PROGRESS", NEXT_DAY, 50L, 12L);
        SealedInventoryReportService.ProgressPage progressPage =
                new SealedInventoryReportService.ProgressPage(List.of(progress), null);
        assertWireEquals("""
                {"items":[{"taskId":"34","name":"抽盘","status":"IN_PROGRESS","dueAt":"2026-11-21T08:30:00Z",
                  "expected":50,"accepted":12}],"nextBefore":null}""", progressPage);
        assertKeys(progressPage, "items", "nextBefore");

        SealedInventoryReportService.ExecutorBreakdown breakdown = new SealedInventoryReportService.ExecutorBreakdown(
                "33", SealedInventoryReportService.ExecutorSource.SEALED, 100L, 99L,
                List.of(new SealedInventoryReportService.Executor(null, 1L, 0L, 0L, 1L),
                        new SealedInventoryReportService.Executor("11", 98L, 96L, 3L, 2L)));
        assertWireEquals("""
                {"taskId":"33","source":"SEALED","expected":100,"counted":99,"items":[
                 {"executorAccountId":null,"expected":1,"accepted":0,"differences":0,"notObserved":1},
                 {"executorAccountId":"11","expected":98,"accepted":96,"differences":3,"notObserved":2}]}""",
                breakdown);
        assertKeys(breakdown, "taskId", "source", "expected", "counted", "items");

        SealedInventoryReportService.TraceabilityPage traceability = new SealedInventoryReportService.TraceabilityPage(
                new SealedInventoryReportService.TraceabilityCounts(6L, 5L),
                List.of(new SealedInventoryReportService.TraceabilityTask("33", "年度盘点", "SEALED",
                        OCCURRED_AT, SealedInventoryReportService.ExecutorSource.CURRENT, 100L, 100L, true)),
                "33");
        assertWireEquals("""
                {"counts":{"published":6,"traceable":5},
                 "items":[{"taskId":"33","name":"年度盘点","status":"SEALED",
                  "publishedAt":"2026-11-20T02:00:00Z","source":"CURRENT","expected":100,"counted":100,
                  "traceable":true}],
                 "nextBefore":"33"}""", traceability);
        assertKeys(traceability, "counts", "items", "nextBefore");
    }

    @Test
    void tagCoverageAndWorkbenchFreezeAggregateCounters() {
        TagCoverageReportService.Summary coverage = new TagCoverageReportService.Summary(
                OCCURRED_AT, 100L, 90L, 80L, 70L, 10L);
        assertWireEquals("""
                {"evaluatedAt":"2026-11-20T02:00:00Z","total":100,"covered":90,"placement":80,"inventory":70,
                 "withoutTag":10}""", coverage);
        assertKeys(coverage, "evaluatedAt", "total", "covered", "placement", "inventory", "withoutTag");

        Map<String, Object> todoRow = rowOf("id", 77L, "business_type", "INVENTORY_TASK", "business_id", 33L,
                "todo_type", "INVENTORY_DIFFERENCE", "opened_at", Timestamp.from(OCCURRED_AT));
        // Verbatim extraction from the WorkbenchTodoService.list row mapper.
        WorkbenchTodoService.Todo todo = new WorkbenchTodoService.Todo(
                asString(todoRow.get("id")), (String) todoRow.get("business_type"),
                asString(todoRow.get("business_id")), (String) todoRow.get("todo_type"),
                ((Timestamp) todoRow.get("opened_at")).toInstant());
        assertKeys(todo, "id", "businessType", "businessId", "stage", "openedAt");
        // Pool-scope todos may carry a NULL business_id (IF() branch): explicit null key on the wire.
        WorkbenchTodoService.Todo pooled = new WorkbenchTodoService.Todo(
                "77", "REQUISITION", null, "REASSIGNMENT_REQUIRED", OCCURRED_AT);
        assertWireEquals("""
                {"id":"77","businessType":"REQUISITION","businessId":null,"stage":"REASSIGNMENT_REQUIRED",
                 "openedAt":"2026-11-20T02:00:00Z"}""", pooled);
        WorkbenchTodoService.Page page = new WorkbenchTodoService.Page(List.of(pooled), "77",
                List.of(new WorkbenchTodoService.StageCount("REASSIGNMENT_REQUIRED", 2L)));
        assertWireEquals("""
                {"items":[{"id":"77","businessType":"REQUISITION","businessId":null,
                  "stage":"REASSIGNMENT_REQUIRED","openedAt":"2026-11-20T02:00:00Z"}],
                 "nextBefore":"77","stageCounts":[{"stage":"REASSIGNMENT_REQUIRED","count":2}]}""", page);
        assertKeys(page, "items", "nextBefore", "stageCounts");
    }

    @Test
    void everyResponseRecordRoundTripsThroughJsonText() {
        assertRoundTrip(new AssetCustodyReportService.Group("0", null, 3L), AssetCustodyReportService.Group.class);
        assertRoundTrip(new AssetCustodyReportService.CustodyGroup("PERSONAL", 5L),
                AssetCustodyReportService.CustodyGroup.class);
        assertRoundTrip(new AssetCustodyReportService.CustodyQuality(9L, 15L, 0L),
                AssetCustodyReportService.CustodyQuality.class);
        assertRoundTrip(new AssetCustodyReportService.Distribution(List.of(), List.of(), List.of(),
                new AssetCustodyReportService.CustodyQuality(0L, 0L, 0L)),
                AssetCustodyReportService.Distribution.class);
        assertRoundTrip(new AssetCustodyReportService.Row("41", "FA-1", "笔记本电脑", "IN_USE", "NORMAL",
                "PERSONAL", null, null, null, null, null), AssetCustodyReportService.Row.class);
        assertRoundTrip(new AssetCustodyReportService.Page(List.of(), null), AssetCustodyReportService.Page.class);
        AssetMovementReportService.Row movement = new AssetMovementReportService.Row(
                "88", "12", "41", "FA-1", OCCURRED_AT, "ACCEPTED");
        assertRoundTrip(movement, AssetMovementReportService.Row.class);
        assertRoundTrip(new AssetMovementReportService.Page(List.of(movement),
                new AssetMovementReportService.Cursor(OCCURRED_AT, "88")), AssetMovementReportService.Page.class);
        assertRoundTrip(new AssetMovementReportService.TrendMonth(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 1), List.of(new AssetMovementReportService.TrendCount(
                        AssetMovementReportService.Kind.INBOUND, 3L))), AssetMovementReportService.TrendMonth.class);
        assertRoundTrip(new AssetTimelineReportService.Row("701", "FA-1", "ASSET_ACCEPTED", OCCURRED_AT,
                "INBOUND", "12", null, ""), AssetTimelineReportService.Row.class);
        assertRoundTrip(new AssetTimelineReportService.Page(List.of(),
                new AssetTimelineReportService.Cursor(OCCURRED_AT, "701")),
                AssetTimelineReportService.Page.class);
        assertRoundTrip(new BusinessExceptionReportService.Row("501", "TRANSFER", "9", "FA-1", "PENDING",
                OCCURRED_AT, null), BusinessExceptionReportService.Row.class);
        assertRoundTrip(new BusinessExceptionReportService.Page(List.of(), null),
                BusinessExceptionReportService.Page.class);
        assertRoundTrip(new ExceptionDiscoveryReportService.Category(
                ExceptionDiscoveryReportService.Kind.LOAN_OVERDUE, 10L, 2L, 1L, 0L),
                ExceptionDiscoveryReportService.Category.class);
        assertRoundTrip(new ExceptionDiscoveryReportService.Summary(OCCURRED_AT, 13L, 10L, List.of()),
                ExceptionDiscoveryReportService.Summary.class);
        assertRoundTrip(new InboundTimingReportService.Row("302", "IN-1", "12", "11", "CLAIMED",
                OCCURRED_AT, NEXT_DAY, 23400L), InboundTimingReportService.Row.class);
        assertRoundTrip(new InboundTimingReportService.Page(List.of(), null),
                InboundTimingReportService.Page.class);
        assertRoundTrip(new InventoryLateReportService.Row("201", "33", "年度盘点", "FA-1", OCCURRED_AT,
                "DIFFERENCE", InventoryLateReportService.Result.OPEN, null, null),
                InventoryLateReportService.Row.class);
        assertRoundTrip(new InventoryLateReportService.Page(List.of(), null),
                InventoryLateReportService.Page.class);
        assertRoundTrip(new LossReportService.Count(LossReportService.State.RECOVERED, 1L),
                LossReportService.Count.class);
        assertRoundTrip(new LossReportService.Row("401", "41", "FA-1", null, LossReportService.State.SEARCHING,
                OCCURRED_AT, null, null), LossReportService.Row.class);
        assertRoundTrip(new LossReportService.Page(List.of(), List.of(), null), LossReportService.Page.class);
        assertRoundTrip(new OperationsReportService.Summary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
                "2026-11-22T00:00:00Z", new OperationsReportService.Inventory(2L, 100L, 98L, 2L),
                List.of(new OperationsReportService.Count("ASSET_IMPORT_PUBLISHED", 3L)),
                List.of(new OperationsReportService.AssetCount("IN_USE", "NORMAL", 42L)),
                new OperationsReportService.ImportErrorQuality(7L, 1L)), OperationsReportService.Summary.class);
        assertRoundTrip(new OverdueLoanReportService.Row("601", "41", "FA-1", "张三", "总部",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20), 10L), OverdueLoanReportService.Row.class);
        assertRoundTrip(new OverdueLoanReportService.Page(OCCURRED_AT, List.of(), null),
                OverdueLoanReportService.Page.class);
        assertRoundTrip(new RepairOperationsReportService.Totals(2L, 1L, new BigDecimal("1234.560")),
                RepairOperationsReportService.Totals.class);
        assertRoundTrip(new RepairOperationsReportService.Row("901", "55", "41", "FA-1", OCCURRED_AT,
                "ACCEPTED", null), RepairOperationsReportService.Row.class);
        assertRoundTrip(new RepairOperationsReportService.Page(
                new RepairOperationsReportService.Totals(0L, 0L, null), List.of(), null),
                RepairOperationsReportService.Page.class);
        assertRoundTrip(new ScrapDispositionReportService.Count(
                ScrapDispositionReportService.State.DISPOSED, 2L), ScrapDispositionReportService.Count.class);
        assertRoundTrip(new ScrapDispositionReportService.Row("1001", "41", "FA-1", "李四",
                ScrapDispositionReportService.State.OVERDUE, LocalDate.of(2026, 11, 25), null),
                ScrapDispositionReportService.Row.class);
        assertRoundTrip(new ScrapDispositionReportService.Page(LocalDate.of(2026, 11, 22), List.of(), List.of(), null),
                ScrapDispositionReportService.Page.class);
        assertRoundTrip(new SealedInventoryReportService.Task("33", "年度盘点", OCCURRED_AT, 100L, 98L, 2L, 1L, 0L),
                SealedInventoryReportService.Task.class);
        assertRoundTrip(new SealedInventoryReportService.Page(List.of(), null),
                SealedInventoryReportService.Page.class);
        assertRoundTrip(new SealedInventoryReportService.Progress("34", "抽盘", "IN_PROGRESS", NEXT_DAY, 50L, 12L),
                SealedInventoryReportService.Progress.class);
        assertRoundTrip(new SealedInventoryReportService.ProgressPage(List.of(), null),
                SealedInventoryReportService.ProgressPage.class);
        assertRoundTrip(new SealedInventoryReportService.Executor(null, 1L, 0L, 0L, 1L),
                SealedInventoryReportService.Executor.class);
        assertRoundTrip(new SealedInventoryReportService.ExecutorBreakdown("33",
                SealedInventoryReportService.ExecutorSource.SEALED, 100L, 99L, List.of()),
                SealedInventoryReportService.ExecutorBreakdown.class);
        assertRoundTrip(new SealedInventoryReportService.TraceabilityCounts(6L, 5L),
                SealedInventoryReportService.TraceabilityCounts.class);
        assertRoundTrip(new SealedInventoryReportService.TraceabilityTask("33", "年度盘点", "SEALED", OCCURRED_AT,
                SealedInventoryReportService.ExecutorSource.CURRENT, 100L, 100L, true),
                SealedInventoryReportService.TraceabilityTask.class);
        assertRoundTrip(new SealedInventoryReportService.TraceabilityPage(
                new SealedInventoryReportService.TraceabilityCounts(0L, 0L), List.of(), null),
                SealedInventoryReportService.TraceabilityPage.class);
        assertRoundTrip(new TagCoverageReportService.Summary(OCCURRED_AT, 100L, 90L, 80L, 70L, 10L),
                TagCoverageReportService.Summary.class);
        assertRoundTrip(new WorkbenchTodoService.Todo("77", "REQUISITION", null, "REASSIGNMENT_REQUIRED",
                OCCURRED_AT), WorkbenchTodoService.Todo.class);
        assertRoundTrip(new WorkbenchTodoService.StageCount("REASSIGNMENT_REQUIRED", 2L),
                WorkbenchTodoService.StageCount.class);
        assertRoundTrip(new WorkbenchTodoService.Page(List.of(), null, List.of()),
                WorkbenchTodoService.Page.class);
    }

    private static void assertRoundTrip(Object typed, Class<?> type) {
        String stored = JSON.writeValueAsString(typed);
        assertEquals(JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    /** Mirrors {@code ResultSet.getString} on numeric JDBC values (stringified ids on the wire). */
    private static String asString(Object value) {
        return value == null ? null : value.toString();
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
