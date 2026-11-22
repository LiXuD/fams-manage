package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.asset.AssetRepairPort;
import com.fams.loan.LoanViews;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-16a wire equivalence for the repair core HTTP exits. Every typed RepairViews shape must
 * serialize exactly like the frozen pre-refactor projections: legacyView is the deleted
 * RepairService.view verbatim (with legacyWire as its value projection), legacyReturnSourceWire
 * is the deleted RepairService.returnSourceWire verbatim, legacyLoanWire/legacyFactoryWire/
 * legacyActions/legacyGetAssembly/legacyReassignmentAssembly mirror the pre-refactor Map
 * assemblies of get()/reassignment()/reassignmentSnapshot(). Comparison round-trips through
 * JSON text so integral node widths normalize and object key order (never stable in the
 * Map.of/Map.ofEntries responses) is ignored, while key presence, explicit nulls and value
 * shapes all count.
 */
class RepairCoreWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- header view (RepairSummary) ----

    @Test
    void summaryMatchesLegacyViewAcrossDriverShapes() {
        var row = headerRow();
        assertWireEquals(legacyView(row), RepairViews.summary(JSON, row));

        var local = new LinkedHashMap<>(row);
        local.put("occurred_at", LocalDateTime.of(2026, 11, 20, 1, 2, 3, 456_000_000));
        local.put("claimed_at", LocalDateTime.of(2026, 11, 21, 2, 0, 0));
        assertWireEquals(legacyView(local), RepairViews.summary(JSON, local));

        var fresh = new LinkedHashMap<>(row);
        fresh.put("processor_account_id", null);
        fresh.put("allowed_method", null);
        fresh.put("claimed_at", null);
        fresh.put("accepted_at", null);
        fresh.put("completed_at", null);
        var typed = RepairViews.summary(JSON, fresh);
        assertWireEquals(legacyView(fresh), typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        // The legacy view always emitted every mapped key: nulls stay explicit under ALWAYS.
        assertTrue(node.has("processorAccountId") && node.get("processorAccountId").isNull());
        assertTrue(node.has("acceptedAt") && node.get("acceptedAt").isNull());
        assertTrue(node.has("allowedMethod") && node.get("allowedMethod").isNull());
    }

    @Test
    void listPageMatchesLegacyMapOfAssembly() {
        var row = headerRow();
        var legacyPage =
                Map.<String, Object>of(
                        "items", List.of(legacyView(row)),
                        "total", 1L,
                        "page", 1,
                        "pageSize", 20);
        assertWireEquals(
                legacyPage,
                new RepairViews.Page<>(
                        List.of(RepairViews.summary(JSON, row)), 1, 1, 20));
    }

    // ---- sub-table row projections (frozen wire() over SELECT * rows) ----

    @Test
    void decisionRowMatchesLegacyWire() {
        var row = decisionRow();
        assertWireEquals(legacyWire(row), RepairViews.decision(JSON, row));
    }

    @Test
    void eventRowMatchesLegacyWireIncludingParsedDetailJson() {
        var row = eventRow();
        assertWireEquals(legacyWire(row), RepairViews.event(JSON, row));

        var local = new LinkedHashMap<>(row);
        local.put("occurred_at", LocalDateTime.of(2026, 11, 22, 5, 6, 7));
        assertWireEquals(legacyWire(local), RepairViews.event(JSON, local));
    }

    @Test
    void physicalHandlingAndInboundRowsMatchLegacyWire() {
        var handling = handlingRow();
        assertWireEquals(legacyWire(handling), RepairViews.physicalHandling(handling));
        var handlingLocal = new LinkedHashMap<>(handling);
        handlingLocal.put("occurred_at", LocalDateTime.of(2026, 11, 20, 1, 2, 3));
        handlingLocal.put("recorded_at", LocalDateTime.of(2026, 11, 20, 1, 2, 4));
        assertWireEquals(legacyWire(handlingLocal), RepairViews.physicalHandling(handlingLocal));

        var inbound = inboundRow();
        assertWireEquals(legacyWire(inbound), RepairViews.inbound(inbound));
    }

    @Test
    void returnHandoverRowMatchesLegacyWireWithGeneratedAndNullableColumns() {
        var row = returnHandoverRow("WAITING_RECEIPT");
        assertWireEquals(legacyWire(row), RepairViews.returnHandover(JSON, row));

        var received = new LinkedHashMap<>(row);
        received.put("handover_status", "RECEIVED");
        received.put("active_repair_id", null);
        received.put("receipt_at", Timestamp.from(Instant.parse("2026-11-25T04:00:00Z")));
        received.put("receipt_evidence", "ok");
        received.put("received_by", 15L);
        assertWireEquals(legacyWire(received), RepairViews.returnHandover(JSON, received));

        // Integral width variants: INT round and BIGINT accounts serialize by value.
        var narrow = new LinkedHashMap<>(row);
        narrow.put("round_number", Integer.valueOf(3));
        narrow.put("delivered_by", Integer.valueOf(13));
        assertWireEquals(legacyWire(narrow), RepairViews.returnHandover(JSON, narrow));
    }

    @Test
    void approvedTargetAndRecollectionRowsMatchLegacyWire() {
        var target = approvedTargetRow();
        assertWireEquals(legacyWire(target), RepairViews.approvedTarget(JSON, target));

        var recollection = recollectionRow();
        assertWireEquals(legacyWire(recollection), RepairViews.recollection(recollection));
    }

    // ---- actions matrix ----

    @Test
    void reportActionsMatchLegacyLinkedHashMapInBothStates() {
        var open = legacyActions(true, true);
        var typedOpen = typedActions(true, true);
        assertWireEquals(open, typedOpen);
        var openNode = JSON.readTree(JSON.writeValueAsString(typedOpen));
        assertTrue(openNode.has("manageFactory"));

        var legacy = legacyActions(false, false);
        var typed = typedActions(false, false);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        // Conditional key discrimination: manageFactory only existed for admin/processor.
        assertFalse(node.has("manageFactory"));
    }

    // ---- factory summary chip (RepairFactoryGuard.summary stays a Map; consumed shape frozen) ----

    @Test
    void factorySummaryMatchesLegacyWireOfGuardMapInBothStates() {
        var full = factorySummaryMap(true, true);
        assertWireEquals(legacyWire(full), RepairViews.factorySummary(full));

        var bare = factorySummaryMap(false, false);
        assertWireEquals(legacyWire(bare), RepairViews.factorySummary(bare));
        var node = JSON.readTree(JSON.writeValueAsString(RepairViews.factorySummary(bare)));
        assertTrue(node.get("scope").isNull());
        assertTrue(node.get("round").isNull());
    }

    // ---- detail assembly ----

    @Test
    void detailMatchesLegacyGetAssemblyInBothConditionalStates() {
        var row = headerRow();
        var origin = originContext();
        var evidenceFacts =
                List.of(
                        JSON.readTree(
                                inventoryFacts().get("source_context_json").toString()));
        var decisions = List.of(decisionRow());
        var events = List.of(eventRow());
        var handling = List.of(handlingRow());
        var inbound = List.of(inboundRow());
        var returns = List.of(returnHandoverRow("WAITING_RECEIPT"));
        var targets = List.of(approvedTargetRow());
        var recollections = List.of(recollectionRow());
        var photos = List.of(photo());
        var summary = RepairViews.summary(JSON, row);
        var typed =
                new RepairViews.RepairDetail(
                        summary.id(),
                        summary.assetId(),
                        summary.creatorAccountId(),
                        summary.version(),
                        summary.assetVersion(),
                        summary.status(),
                        summary.fault(),
                        summary.impact(),
                        summary.occurredAt(),
                        summary.allowedMethod(),
                        summary.processorAccountId(),
                        summary.ownershipVersion(),
                        summary.claimedAt(),
                        summary.acceptedAt(),
                        summary.completedAt(),
                        summary.source(),
                        evidenceFacts.stream().map(RepairViews::inventoryEvidence).toList(),
                        RepairViews.decisions(JSON, decisions),
                        RepairViews.events(JSON, events),
                        1L,
                        7L,
                        true,
                        photos,
                        JSON.readTree(origin.get("source_context_json").toString()),
                        RepairViews.physicalHandlings(handling),
                        RepairViews.inbounds(inbound),
                        9L,
                        LocalDate.of(2026, 12, 1),
                        RepairViews.returnHandovers(JSON, returns),
                        RepairViews.approvedTargets(JSON, targets),
                        RepairViews.recollections(recollections),
                        RepairViews.factorySummary(factorySummaryMap(true, true)),
                        "42",
                        typedActions(true, true));
        assertWireEquals(
                legacyGetAssembly(
                        row, origin, decisions, events, handling, inbound, returns, targets,
                        recollections, true, "42"),
                typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(node.has("factorySummary"));
        assertTrue(node.has("latestLossReportId"));
        assertTrue(node.path("actions").has("manageFactory"));
        // Always-present nullable detail keys stay explicit nulls under ALWAYS inclusion.
        var leanSummary = RepairViews.summary(JSON, row);
        var lean =
                new RepairViews.RepairDetail(
                        leanSummary.id(),
                        leanSummary.assetId(),
                        leanSummary.creatorAccountId(),
                        leanSummary.version(),
                        leanSummary.assetVersion(),
                        leanSummary.status(),
                        leanSummary.fault(),
                        leanSummary.impact(),
                        leanSummary.occurredAt(),
                        leanSummary.allowedMethod(),
                        leanSummary.processorAccountId(),
                        leanSummary.ownershipVersion(),
                        leanSummary.claimedAt(),
                        leanSummary.acceptedAt(),
                        leanSummary.completedAt(),
                        leanSummary.source(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0L,
                        null,
                        false,
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        typedActions(false, false));
        var leanNode = JSON.readTree(JSON.writeValueAsString(lean));
        assertFalse(leanNode.has("factorySummary"));
        assertFalse(leanNode.has("latestLossReportId"));
        assertFalse(leanNode.path("actions").has("manageFactory"));
        assertTrue(leanNode.has("lockVersion") && leanNode.get("lockVersion").isNull());
        assertTrue(leanNode.has("returnOrigin") && leanNode.get("returnOrigin").isNull());
        assertTrue(
                leanNode.has("loanExpectedReturnDate")
                        && leanNode.get("loanExpectedReturnDate").isNull());
    }

    // ---- return source exits ----

    @Test
    void returnSourceExitAndStoredContextMatchLegacyWire() {
        var legacy = new LinkedHashMap<>(legacyReturnSourceWire());
        legacy.put("asset", assetSnapshotTree());
        assertWireEquals(legacy, RepairViews.returnSourceDetail(returnSourceContext(), assetSnapshotTree()));

        // The same wire is persisted into fams_repair_return_origin.source_context_json.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacyReturnSourceWire())),
                JSON.readTree(JSON.writeValueAsString(RepairViews.returnSourceWire(returnSourceContext()))));
    }

    // ---- reassignment exits ----

    @Test
    void reassignmentExitMatchesLegacyAssemblyInBothStates() {
        var row = headerRow();
        var summary = factorySummaryMap(true, true);
        var legacy = legacyReassignmentAssembly(row, summary, "临时不可用", true);
        var typed =
                new RepairViews.ReassignmentView(
                        typedReassignmentSnapshot(row, summary, true),
                        RepairViews.operators(List.of(operatorRow())),
                        "临时不可用");
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(node.has("unavailableReason"));
        assertTrue(node.path("snapshot").has("factorySummary"));

        var without =
                new RepairViews.ReassignmentView(
                        typedReassignmentSnapshot(row, null, false),
                        List.of(),
                        null);
        assertWireEquals(legacyReassignmentAssembly(row, null, null, false), without);
        var leanNode = JSON.readTree(JSON.writeValueAsString(without));
        assertFalse(leanNode.has("unavailableReason"));
        assertFalse(leanNode.path("snapshot").has("factorySummary"));
        assertTrue(leanNode.path("snapshot").has("loan") && leanNode.path("snapshot").get("loan").isNull());
        assertTrue(
                leanNode.path("snapshot").has("outstandingReturn")
                        && leanNode.path("snapshot").get("outstandingReturn").isNull());
    }

    @Test
    void loanRefMatchesLegacyLoanWire() {
        var loan = new com.fams.loan.LoanRepairPort.Context(9L, 4L, 31L, 5L, LocalDate.of(2026, 12, 1));
        assertWireEquals(legacyLoanWire(loan), RepairViews.loanRef(loan));
    }

    // ---- photo/unavailability receipts ----

    @Test
    void photoReceiptGrantAndUnavailabilityMatchLegacyAssemblies() {
        assertWireEquals(
                Map.<String, Object>of(
                        "repairId", "7",
                        "objectId", "0f8fad5b-d9cb-469f-a165-70867728950e",
                        "version", 2L),
                new RepairViews.PhotoReceipt(
                        "7", "0f8fad5b-d9cb-469f-a165-70867728950e", 2));
        assertWireEquals(
                Map.<String, Object>of(
                        "url", "/api/v1/repairs/7/photos/x/content?grant=t",
                        "expiresAtEpochSecond", 1795200000L),
                new RepairViews.PhotoGrant("/api/v1/repairs/7/photos/x/content?grant=t", 1795200000L));
        assertWireEquals(
                Map.<String, Object>of("id", "7", "version", 3L, "recorded", true),
                new RepairViews.UnavailabilityReceipt("7", 3, true));
    }

    // ---- idempotency stored-shape replays (fams_repair_receipt.response_json) ----

    @Test
    void viewShapedStoredRepliesStillDeserialize() {
        // SUBMIT/DECIDE/CANCEL/CLAIM/ACCEPT/COMPLETE/ACCEPT_AWAY/INBOUND/RETURN_DELIVER/
        // RETURN_RECEIVE/RETURN_REFUSE/RETURN_RECOLLECT/RETURN_SOURCE stored legacyView() maps.
        var storedView = JSON.writeValueAsString(legacyView(headerRow()));
        var summary = JSON.readValue(storedView, RepairViews.RepairSummary.class);
        assertEquals(
                JSON.readTree(storedView),
                JSON.readTree(JSON.writeValueAsString(summary)));

        // REASSIGN stored the full legacy get() detail tree (both conditional states).
        var row = headerRow();
        var storedDetail =
                JSON.writeValueAsString(
                        legacyGetAssembly(
                                row, originContext(), List.of(decisionRow()), List.of(eventRow()),
                                List.of(handlingRow()), List.of(inboundRow()),
                                List.of(returnHandoverRow("WAITING_RECEIPT")),
                                List.of(approvedTargetRow()), List.of(recollectionRow()),
                                true, "42"));
        var detail = JSON.readValue(storedDetail, RepairViews.RepairDetail.class);
        assertEquals(
                JSON.readTree(storedDetail),
                JSON.readTree(JSON.writeValueAsString(detail)));
        var storedLeanDetail =
                JSON.writeValueAsString(
                        legacyGetAssembly(
                                row, originContext(), List.of(decisionRow()), List.of(eventRow()),
                                List.of(handlingRow()), List.of(inboundRow()),
                                List.of(returnHandoverRow("RECEIVED")),
                                List.of(approvedTargetRow()), List.of(recollectionRow()),
                                false, null));
        var leanDetail = JSON.readValue(storedLeanDetail, RepairViews.RepairDetail.class);
        assertEquals(
                JSON.readTree(storedLeanDetail),
                JSON.readTree(JSON.writeValueAsString(leanDetail)));

        // PHOTO receipts stored the three-key map; DECLARE_UNAVAILABLE its own three keys.
        var storedPhoto =
                JSON.writeValueAsString(
                        Map.<String, Object>of(
                                "repairId", "7",
                                "objectId", "0f8fad5b-d9cb-469f-a165-70867728950e",
                                "version", 2L));
        var photo = JSON.readValue(storedPhoto, RepairViews.PhotoReceipt.class);
        assertEquals(
                JSON.readTree(storedPhoto),
                JSON.readTree(JSON.writeValueAsString(photo)));
        var storedUnavailable =
                JSON.writeValueAsString(
                        Map.<String, Object>of("id", "7", "version", 3L, "recorded", true));
        var unavailable =
                JSON.readValue(storedUnavailable, RepairViews.UnavailabilityReceipt.class);
        assertEquals(
                JSON.readTree(storedUnavailable),
                JSON.readTree(JSON.writeValueAsString(unavailable)));
    }

    @Test
    void typedViewsRoundTripToTheirOwnStoredShape() {
        var row = headerRow();
        assertRoundTrip(RepairViews.summary(JSON, row), RepairViews.RepairSummary.class);

        var summary = RepairViews.summary(JSON, row);
        var detail =
                new RepairViews.RepairDetail(
                        summary.id(), summary.assetId(), summary.creatorAccountId(),
                        summary.version(), summary.assetVersion(), summary.status(),
                        summary.fault(), summary.impact(), summary.occurredAt(),
                        summary.allowedMethod(), summary.processorAccountId(),
                        summary.ownershipVersion(), summary.claimedAt(), summary.acceptedAt(),
                        summary.completedAt(), summary.source(),
                        List.of(
                                RepairViews.inventoryEvidence(
                                        JSON.readTree(
                                                inventoryFacts()
                                                        .get("source_context_json")
                                                        .toString()))),
                        RepairViews.decisions(JSON, List.of(decisionRow())),
                        RepairViews.events(JSON, List.of(eventRow())),
                        1, 7L, true, List.of(photo()),
                        JSON.readTree(originContext().get("source_context_json").toString()),
                        RepairViews.physicalHandlings(List.of(handlingRow())),
                        RepairViews.inbounds(List.of(inboundRow())),
                        9L, LocalDate.of(2026, 12, 1),
                        RepairViews.returnHandovers(JSON, List.of(returnHandoverRow("WAITING_RECEIPT"))),
                        RepairViews.approvedTargets(JSON, List.of(approvedTargetRow())),
                        RepairViews.recollections(List.of(recollectionRow())),
                        RepairViews.factorySummary(factorySummaryMap(true, true)),
                        "42", typedActions(true, true));
        assertRoundTrip(detail, RepairViews.RepairDetail.class);
        assertRoundTrip(
                new RepairViews.UnavailabilityReceipt("7", 3, true),
                RepairViews.UnavailabilityReceipt.class);
        assertRoundTrip(
                new RepairViews.PhotoReceipt("7", "0f8fad5b-d9cb-469f-a165-70867728950e", 2),
                RepairViews.PhotoReceipt.class);
    }

    @Test
    void getMapMirrorKeepsHeaderKeysForFrozenPortConsumers() {
        // InventoryRepairPort.link reads id/assetId/version/status from the Map mirror left for
        // the forbidden-to-change port consumers; this freezes the convertValue equivalence.
        var typed = RepairViews.summary(JSON, headerRow());
        Map<String, Object> mirror =
                JSON.convertValue(typed, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertEquals(typed.id(), mirror.get("id"));
        assertEquals(typed.assetId(), mirror.get("assetId").toString());
        assertEquals(typed.version(), ((Number) mirror.get("version")).longValue());
        assertEquals(typed.status(), mirror.get("status"));
        assertWireEquals(legacyView(headerRow()), mirror);
    }

    // ---- cross-domain components reused by the repair controller (zero-change proofs) ----

    @Test
    void crossDomainComponentShapesStayFrozen() {
        // LoanViews.RepairFollowupSource page (GET /repairs/return-sources, JAVA-08 frozen).
        var source =
                new LoanViews.RepairFollowupSource(
                        "5", "6", "7", "8", "PENDING", "9", "damaged", 1L, null, "10", "COMPLETED");
        var sourceNode = JSON.readTree(JSON.writeValueAsString(source));
        assertTrue(sourceNode.has("return_id") && sourceNode.get("return_id").isTextual());
        assertTrue(sourceNode.has("linked_repair_id") && sourceNode.get("linked_repair_id").isNull());
        assertTrue(sourceNode.has("version") && sourceNode.get("version").isNumber());

        // LoanViews.Followup polymorphism still resolves through the existing businessType key.
        LoanViews.Followup repair =
                new LoanViews.RepairFollowup(
                        "5", "6", "7", "8", "REPAIR", "PENDING", "9", "damaged",
                        "2026-11-20T01:02:03Z", 1L, null, "10", "COMPLETED");
        var followupJson = JSON.writeValueAsString(repair);
        assertFalse(JSON.readTree(followupJson).has("@type"));
        LoanViews.Followup replayed = JSON.readValue(followupJson, LoanViews.Followup.class);
        assertSame(repair.getClass(), replayed.getClass());
        assertWireEquals(JSON.readTree(followupJson), replayed);

        // AssetRepairPort.ReturnTarget eleven camelCase keys (JAVA-10b frozen).
        var target =
                new AssetRepairPort.ReturnTarget(
                        "PERSONAL", "3", "张三", 2L, "1", "总部", 3L, "9", "工位A", 4L, "11");
        var targetNode = JSON.readTree(JSON.writeValueAsString(target));
        assertEquals(11, size(targetNode));
        assertTrue(targetNode.get("employeeId").isTextual());
        assertTrue(targetNode.get("employeeVersion").isNumber());

        // AssetRepairPort.PhysicalPage keeps the per-kind item key sets (JAVA-10b frozen).
        var warehouses =
                new AssetRepairPort.PhysicalPage(
                        List.of(new AssetRepairPort.WarehouseTarget("1", "主仓", 2L)), 1, 1, 20);
        var warehouseNode = JSON.readTree(JSON.writeValueAsString(warehouses));
        assertTrue(warehouseNode.get("items").get(0).has("id"));
        assertFalse(warehouseNode.get("items").get(0).has("warehouse_id"));
        var locations =
                new AssetRepairPort.PhysicalPage(
                        List.of(new AssetRepairPort.LocationTarget("2", "库位", 3L, null, "BIN")),
                        1,
                        1,
                        20);
        var locationNode = JSON.readTree(JSON.writeValueAsString(locations));
        assertTrue(
                locationNode.get("items").get(0).has("warehouse_id")
                        && locationNode.get("items").get(0).get("warehouse_id").isNull());
    }

    // ---- helpers ----

    private static int size(JsonNode node) {
        int count = 0;
        for (var ignored : node.propertyNames()) {
            count++;
        }
        return count;
    }

    private static void assertRoundTrip(Object view, Class<?> type) {
        var stored = JSON.writeValueAsString(view);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the same
        // bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static Map<String, Object> headerRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L);
        row.put("asset_id", 40L);
        row.put("creator_account_id", 11L);
        row.put("using_organization_id", 1L);
        row.put("responsible_employee_id", 3L);
        row.put("shared_responsible_employee_id", null);
        row.put("asset_version", 4L);
        row.put(
                "source_snapshot_json",
                "{\"id\":\"40\",\"assetCode\":\"FA-1\",\"name\":\"laptop\",\"version\":3,"
                        + "\"lifecycle\":\"BORROWED\",\"custodyType\":\"PERSONAL\","
                        + "\"usingOrganizationId\":\"1\",\"owningOrganizationId\":\"1\","
                        + "\"responsibleEmployeeId\":\"3\","
                        + "\"departmentResponsibleEmployeeId\":null,\"warehouseId\":null,"
                        + "\"locationId\":\"9\",\"locationName\":\"工位A\"}");
        row.put("loan_snapshot_json", "{\"id\":9,\"version\":4}");
        row.put("timezone_id", "Asia/Shanghai");
        row.put("fault", "屏幕碎裂");
        row.put("impact", "HIGH");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-20T01:02:03.456Z")));
        row.put("repair_status", "WAREHOUSE_PROCESSING");
        row.put("allowed_method", "INTERNAL_NO_COST");
        row.put("approval_snapshot_json", "{\"result\":\"APPROVE\"}");
        row.put("processor_account_id", 13L);
        row.put("ownership_version", 2L);
        row.put("claimed_at", Timestamp.from(Instant.parse("2026-11-21T02:00:00Z")));
        row.put("accepted_at", Timestamp.from(Instant.parse("2026-11-22T03:00:00Z")));
        row.put("completed_at", null);
        row.put("version", 5L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-20T01:02:04Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-11-22T03:00:01Z")));
        return row;
    }

    private static Map<String, Object> decisionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 20L);
        row.put("repair_id", 7L);
        row.put("before_version", 0L);
        row.put("after_version", 1L);
        row.put("result", "APPROVE");
        row.put("allowed_method", "INTERNAL_NO_COST");
        row.put("snapshot_json", "{\"fault\":\"屏幕碎裂\",\"photos\":[]}");
        row.put("reason", "同意");
        row.put("actor_account_id", 11L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-20T05:00:00Z")));
        return row;
    }

    private static Map<String, Object> eventRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 30L);
        row.put("repair_id", 7L);
        row.put("action", "REPAIR_DECIDED");
        row.put("before_version", 0L);
        row.put("after_version", 1L);
        row.put("actor_account_id", 11L);
        row.put("actor_username", "admin");
        row.put("actor_role", "ASSET_ADMIN");
        row.put("detail_json", "{\"result\":\"APPROVE\"}");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-20T05:00:01Z")));
        return row;
    }

    private static Map<String, Object> handlingRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("repair_id", 7L);
        row.put("handler_employee_id", 3L);
        row.put("actual_location_id", 9L);
        row.put("location_version", 4L);
        row.put("before_asset_version", 3L);
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-22T03:00:00Z")));
        row.put("evidence", "现场接收");
        row.put("actor_account_id", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-22T03:00:02Z")));
        return row;
    }

    private static Map<String, Object> inboundRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("repair_id", 7L);
        row.put("warehouse_id", 2L);
        row.put("location_id", 9L);
        row.put("warehouse_version", 1L);
        row.put("location_version", 4L);
        row.put("before_asset_version", 4L);
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-26T01:00:00Z")));
        row.put("evidence", "入库");
        row.put("actor_account_id", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-26T01:00:01Z")));
        return row;
    }

    private static Map<String, Object> returnHandoverRow(String status) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 50L);
        row.put("repair_id", 7L);
        row.put("round_number", 1);
        row.put("handover_status", status);
        row.put("active_repair_id", "WAITING_RECEIPT".equals(status) ? 7L : null);
        row.put(
                "target_snapshot_json",
                "{\"custodyType\":\"PERSONAL\",\"employeeId\":\"3\",\"employeeName\":\"张三\","
                        + "\"employeeVersion\":2,\"organizationId\":\"1\","
                        + "\"organizationName\":\"总部\",\"organizationVersion\":3,"
                        + "\"locationId\":\"9\",\"locationName\":\"工位A\",\"locationVersion\":4,"
                        + "\"receiverAccountId\":\"11\"}");
        row.put("before_asset_version", 4L);
        row.put("delivered_at", Timestamp.from(Instant.parse("2026-11-26T02:00:00Z")));
        row.put("delivery_evidence", "送达");
        row.put("delivered_by", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-26T02:00:01Z")));
        row.put("receipt_at", null);
        row.put("receipt_evidence", null);
        row.put("received_by", null);
        return row;
    }

    private static Map<String, Object> approvedTargetRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 60L);
        row.put("repair_id", 7L);
        row.put("proposal_id", 70L);
        row.put(
                "target_json",
                "{\"custodyType\":\"SHARED\",\"employeeId\":\"4\",\"organizationId\":\"2\","
                        + "\"locationId\":\"10\",\"receiverAccountId\":\"12\","
                        + "\"employeeVersion\":1,\"organizationVersion\":1,\"locationVersion\":1}");
        row.put("receiver_account_id", 12L);
        row.put("employee_id", 4L);
        row.put("organization_id", 2L);
        row.put("custody_type", "SHARED");
        row.put("approved_by", 11L);
        row.put("approved_at", Timestamp.from(Instant.parse("2026-11-26T03:00:00Z")));
        return row;
    }

    private static Map<String, Object> recollectionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("handover_id", 50L);
        row.put("handler_employee_id", 3L);
        row.put("actual_location_id", 9L);
        row.put("location_version", 4L);
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-26T04:00:00Z")));
        row.put("evidence", "收回");
        row.put("actor_account_id", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-26T04:00:01Z")));
        return row;
    }

    private static Map<String, Object> originContext() {
        var row = new LinkedHashMap<String, Object>();
        row.put("repair_id", 7L);
        row.put("followup_id", 5L);
        row.put(
                "source_context_json",
                JSON.writeValueAsString(
                        RepairViews.returnSourceWire(returnSourceContext())));
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-19T00:00:00Z")));
        return row;
    }

    private static Map<String, Object> inventoryFacts() {
        // Mirrors the legacy lambda: facts = readTree(source_context_json), then a Map.of of
        // taskId/observationId/clientEventId asText plus the raw photoIds node.
        return Map.of(
                "source_context_json",
                "{\"taskId\":\"t1\",\"observationId\":\"o1\",\"clientEventId\":\"c1\","
                        + "\"photoIds\":[\"0f8fad5b-d9cb-469f-a165-70867728950e\"]}");
    }

    private static RepairService.Photo photo() {
        return new RepairService.Photo(
                "0f8fad5b-d9cb-469f-a165-70867728950e",
                "a.jpg",
                "image/jpeg",
                10L,
                "CLEAN",
                1L,
                Timestamp.from(Instant.parse("2026-11-20T02:00:00Z"))
                        .toInstant()
                        .toString());
    }

    private static com.fams.loan.LoanReturnRepairPort.Context returnSourceContext() {
        return new com.fams.loan.LoanReturnRepairPort.Context(
                5L,
                2L,
                6L,
                40L,
                9L,
                13L,
                2L,
                9L,
                Instant.parse("2026-11-19T02:00:00Z"),
                "外壳破损",
                "{\"timezone\":\"Asia/Shanghai\"}",
                3L);
    }

    private static JsonNode assetSnapshotTree() {
        return JSON.readTree(
                "{\"id\":\"40\",\"assetCode\":\"FA-1\",\"name\":\"laptop\",\"version\":3,"
                        + "\"lifecycle\":\"UNDER_REPAIR\",\"custodyType\":\"STORAGE\","
                        + "\"usingOrganizationId\":null,\"owningOrganizationId\":\"1\","
                        + "\"responsibleEmployeeId\":null,\"departmentResponsibleEmployeeId\":null,"
                        + "\"warehouseId\":\"2\",\"locationId\":\"9\",\"locationName\":\"库位A\"}");
    }

    /** The pre-typing RepairService.returnSourceWire projection; reference oracle only. */
    private static Map<String, Object> legacyReturnSourceWire() {
        var source = returnSourceContext();
        var result = new LinkedHashMap<String, Object>();
        result.put("followupId", Long.toString(source.id()));
        result.put("version", source.version());
        result.put("returnId", Long.toString(source.returnId()));
        result.put("assetId", Long.toString(source.assetId()));
        result.put("closedLoanId", source.loanId() == null ? null : source.loanId().toString());
        result.put("processorAccountId", Long.toString(source.processorAccountId()));
        result.put("warehouseId", Long.toString(source.warehouseId()));
        result.put("locationId", Long.toString(source.locationId()));
        result.put("inspectedAt", source.inspectedAt().toString());
        result.put("inspectionReason", source.reason());
        result.put("lockVersion", source.lockVersion());
        return result;
    }

    /** The pre-typing RepairService.loanWire projection; reference oracle only. */
    private static Map<String, Object> legacyLoanWire(com.fams.loan.LoanRepairPort.Context loan) {
        return Map.of(
                "id", Long.toString(loan.id()),
                "version", loan.version(),
                "borrowerEmployeeId", Long.toString(loan.borrowerEmployeeId()),
                "organizationId", Long.toString(loan.organizationId()),
                "expectedReturnDate", loan.expectedReturnDate().toString());
    }

    /** Guard-style RepairFactoryGuard.summary output (16b surface) for the chip comparison. */
    private static Map<String, Object> factorySummaryMap(boolean withScope, boolean withRound) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "scope",
                withScope
                        ? Map.of("id", "80", "scopeStatus", "PENDING", "version", 2L)
                        : null);
        Map<String, Object> round = null;
        if (withRound) {
            round = new LinkedHashMap<>();
            round.put("id", "70");
            round.put("roundNumber", 1L);
            round.put("roundStatus", "DISPATCHED");
            round.put("expectedReturnDate", "2026-12-05");
            round.put("dispatchedAt", Timestamp.from(Instant.parse("2026-11-24T02:00:00Z")));
            round.put("receivedAt", null);
            round.put("version", 3L);
        }
        result.put("round", round);
        result.put("actualDispatchCount", 1L);
        result.put("followupOpen", true);
        return result;
    }

    /** The frozen pre-JAVA-16a detail assembly of RepairService.get(); reference oracle only. */
    private static Map<String, Object> legacyGetAssembly(
            Map<String, Object> row,
            Map<String, Object> origin,
            List<Map<String, Object>> decisions,
            List<Map<String, Object>> events,
            List<Map<String, Object>> handling,
            List<Map<String, Object>> inbound,
            List<Map<String, Object>> returns,
            List<Map<String, Object>> targets,
            List<Map<String, Object>> recollections,
            boolean withExtras,
            String latestLossReportId) {
        var result = new LinkedHashMap<>(legacyView(row));
        result.put(
                "inventoryEvidence",
                inventoryFacts().values().stream()
                        .map(raw -> {
                            var facts = JSON.readTree(raw.toString());
                            return Map.of(
                                    "taskId", facts.path("taskId").asText(),
                                    "observationId", facts.path("observationId").asText(),
                                    "clientEventId", facts.path("clientEventId").asText(),
                                    "photoIds", facts.path("photoIds"));
                        })
                        .toList());
        result.put("decisions", legacyWire(decisions));
        result.put("history", legacyWire(events));
        result.put("historyTotal", 1L);
        result.put("lockVersion", withExtras ? 7L : null);
        result.put("photosAvailable", withExtras);
        result.put("photos", withExtras ? List.of(photo()) : List.of());
        result.put(
                "returnOrigin",
                origin == null
                        ? null
                        : JSON.readTree(origin.get("source_context_json").toString()));
        result.put("physicalHandling", legacyWire(handling));
        result.put("inbound", legacyWire(inbound));
        result.put("loanVersion", withExtras ? 9L : null);
        result.put(
                "loanExpectedReturnDate",
                withExtras ? LocalDate.of(2026, 12, 1) : null);
        result.put("returnHandovers", legacyWire(returns));
        result.put("approvedReturnTargets", legacyWire(targets));
        result.put("returnRecollections", legacyWire(recollections));
        if (withExtras) {
            result.put("factorySummary", legacyWire(factorySummaryMap(true, true)));
        }
        if (withExtras && latestLossReportId != null) {
            result.put("latestLossReportId", latestLossReportId);
        }
        result.put("actions", legacyActions(withExtras, true));
        return result;
    }

    /** The pre-typing get() actions LinkedHashMap assembly; reference oracle only. */
    private static Map<String, Object> legacyActions(boolean withManageFactory, boolean open) {
        var actions =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "approve", false,
                                "claim", true,
                                "acceptInPlace", false,
                                "completeInPlace", false,
                                "acceptAway", false,
                                "closeInbound", false,
                                "addFaultPhoto", false,
                                "cancel", false));
        actions.put("requestScrap", false);
        actions.put("deliverReturn", !open);
        actions.put("changeReturnTarget", !open);
        actions.put("receiveReturn", !open);
        actions.put("refuseReturn", !open);
        actions.put("recollectReturn", !open);
        actions.put("reassign", true);
        actions.put("proxyReturn", !open);
        if (withManageFactory) {
            actions.put("manageFactory", true);
        }
        // followupOpen flips these eleven keys to false when the factory summary is open.
        actions.put("requestLoss", false);
        return actions;
    }

    private static RepairViews.ReportActions typedActions(boolean withManageFactory, boolean open) {
        return new RepairViews.ReportActions(
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                !open,
                !open,
                !open,
                !open,
                !open,
                true,
                !open,
                withManageFactory ? true : null,
                false);
    }

    private static Map<String, Object> legacyReassignmentAssembly(
            Map<String, Object> row,
            Map<String, Object> summary,
            String unavailableReason,
            boolean withLoan) {
        var result =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "snapshot",
                                legacyReassignmentAssemblyParts(row, summary, withLoan),
                                "operators",
                                withLoan
                                        ? legacyWire(List.of(operatorRow()))
                                        : List.of()));
        if (unavailableReason != null) {
            result.put("unavailableReason", unavailableReason);
        }
        return result;
    }

    private static Map<String, Object> legacyReassignmentAssemblyParts(
            Map<String, Object> row, Map<String, Object> summary, boolean withLoan) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", row.get("id").toString());
        snapshot.put("version", row.get("version"));
        snapshot.put("ownershipVersion", row.get("ownership_version"));
        snapshot.put("processorAccountId", row.get("processor_account_id").toString());
        snapshot.put("assetVersion", row.get("asset_version"));
        snapshot.put("lockVersion", 7L);
        snapshot.put("acceptedAt", legacyWire(row.get("accepted_at")));
        snapshot.put("physicalHandling", legacyWire(List.of(handlingRow())));
        snapshot.put(
                "outstandingReturn", withLoan ? legacyWire(returnHandoverRow("WAITING_RECEIPT")) : null);
        snapshot.put("lastPhysicalTime", Instant.parse("2026-11-26T04:00:00Z"));
        snapshot.put("approvedTargetId", withLoan ? "60" : null);
        snapshot.put(
                "loan",
                withLoan
                        ? legacyLoanWire(
                                new com.fams.loan.LoanRepairPort.Context(
                                        9L, 4L, 31L, 5L, LocalDate.of(2026, 12, 1)))
                        : null);
        if (summary != null) {
            snapshot.put("factorySummary", legacyWire(summary));
        }
        return snapshot;
    }

    private static Map<String, Object> operatorRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 14L);
        row.put("name", "仓管");
        return row;
    }

    /**
     * Typed-side mirror of the new RepairService.reassignmentSnapshot assembly, so the oracle
     * map and the record are compared through their serialized trees.
     */
    private static RepairViews.ReassignmentSnapshot typedReassignmentSnapshot(
            Map<String, Object> row, Map<String, Object> summary, boolean withLoan) {
        return new RepairViews.ReassignmentSnapshot(
                row.get("id").toString(),
                ((Number) row.get("version")).longValue(),
                ((Number) row.get("ownership_version")).longValue(),
                row.get("processor_account_id").toString(),
                ((Number) row.get("asset_version")).longValue(),
                7L,
                RepairViews.timeValue(row.get("accepted_at")),
                RepairViews.physicalHandlings(List.of(handlingRow())),
                withLoan
                        ? RepairViews.returnHandover(JSON, returnHandoverRow("WAITING_RECEIPT"))
                        : null,
                Instant.parse("2026-11-26T04:00:00Z"),
                withLoan ? "60" : null,
                withLoan
                        ? RepairViews.loanRef(
                                new com.fams.loan.LoanRepairPort.Context(
                                        9L, 4L, 31L, 5L, LocalDate.of(2026, 12, 1)))
                        : null,
                summary == null ? null : RepairViews.factorySummary(summary));
    }

    /** Frozen pre-JAVA-16a RepairService.view projection; reference oracle only. */
    private static Map<String, Object> legacyView(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_id", "assetId"),
                                Map.entry("creator_account_id", "creatorAccountId"),
                                Map.entry("version", "version"),
                                Map.entry("asset_version", "assetVersion"),
                                Map.entry("repair_status", "status"),
                                Map.entry("fault", "fault"),
                                Map.entry("impact", "impact"),
                                Map.entry("occurred_at", "occurredAt"),
                                Map.entry("allowed_method", "allowedMethod"),
                                Map.entry("processor_account_id", "processorAccountId"),
                                Map.entry("ownership_version", "ownershipVersion"),
                                Map.entry("claimed_at", "claimedAt"),
                                Map.entry("accepted_at", "acceptedAt"),
                                Map.entry("completed_at", "completedAt"))
                        .entrySet()) {
            Object value = row.get(field.getKey());
            result.put(
                    field.getValue(),
                    field.getKey().equals("id") || field.getKey().endsWith("_id")
                            ? value == null ? null : value.toString()
                            : legacyWire(value));
        }
        result.put(
                "source", JSON.readValue(row.get("source_snapshot_json").toString(), Object.class));
        return result;
    }

    /** Frozen pre-JAVA-16a RepairService.wire projection; reference oracle only. */
    private static Object legacyWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : k.toString().endsWith("_json")
                                                            && v instanceof String text
                                                    ? JSON.readValue(text, Object.class)
                                                    : legacyWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RepairCoreWireShapeTest::legacyWire).toList();
        }
        if (value instanceof Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toString();
        }
        return value;
    }
}
