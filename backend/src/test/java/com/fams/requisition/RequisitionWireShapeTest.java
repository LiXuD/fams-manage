package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.masterdata.RequisitionMasterDataPort;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-14b1 wire equivalence: every typed requisition core/handover view must serialize exactly
 * like the frozen pre-refactor row projections. legacyClean is RequisitionService.clean and
 * legacyHandoverClean is RequisitionHandoverService.clean verbatim (reference oracles); the
 * detail/pending/grant/receipt assemblies copy the removed Map builders field by field.
 * Comparison round-trips through JSON text so integral node widths normalize and object key
 * order (never stable in the Map.of-based responses) is ignored, while key presence, explicit
 * nulls and value shapes all count.
 */
class RequisitionWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- header / list / get ----

    @Test
    void summaryMatchesLegacyClean() {
        assertWireEquals(legacyClean(headerRow()), RequisitionViews.summary(JSON, headerRow()));
        var local = new LinkedHashMap<>(headerRow());
        local.put("created_at", LocalDateTime.of(2026, 11, 1, 2, 0, 0));
        local.put("submitted_at", null);
        local.put("expected_return_date", null);
        assertWireEquals(legacyClean(local), RequisitionViews.summary(JSON, local));
        var localUpdated = new LinkedHashMap<>(local);
        localUpdated.put("updated_at", LocalDateTime.of(2026, 11, 2, 3, 4, 5, 600000000));
        localUpdated.put("expected_return_date", java.time.LocalDate.of(2026, 12, 1));
        assertWireEquals(legacyClean(localUpdated), RequisitionViews.summary(JSON, localUpdated));
    }

    @Test
    void listPageMatchesLegacyAssembly() {
        var legacy =
                Map.of(
                        "items", List.of(legacyClean(headerRow())),
                        "total", 7L,
                        "page", 2,
                        "pageSize", 50);
        var typed =
                new RequisitionViews.Page<>(
                        List.of(RequisitionViews.summary(JSON, headerRow())), 7, 2, 50);
        assertWireEquals(legacy, typed);
    }

    @Test
    void detailMatchesLegacyAssembly() {
        assertWireEquals(legacyDetail(), typedDetail());
    }

    @Test
    void detailKeepsExplicitNullKeysAndLegacyShapeUnderCompatGet() {
        var node = JSON.readTree(JSON.writeValueAsString(typedDetail()));
        // RequisitionService.clean always emitted every column key; nulls stay explicit.
        assertTrue(node.has("sourceRequestId"));
        assertTrue(node.get("sourceRequestId").isNull());
        assertTrue(node.get("demands").get(0).has("preferredBrand"));
        assertTrue(node.get("demands").get(0).get("preferredBrand").isNull());
        assertTrue(node.get("ownership").get(0).has("archivedAt"));
        assertTrue(node.get("ownership").get(0).get("archivedAt").isNull());
        // JAVA-14c deleted the RequisitionService.get Map wrapper; this inline convertValue of
        // the typed detail freezes the tree the removed wrapper used to hand ReassignmentService.
        Map<String, Object> viaCompatMap =
                JSON.convertValue(typedDetail(), new TypeReference<Map<String, Object>>() {});
        assertWireEquals(legacyDetail(), viaCompatMap);
    }

    // ---- child rows ----

    @Test
    void demandRowMatchesLegacyClean() {
        assertWireEquals(legacyClean(demandRow()), RequisitionViews.demand(JSON, demandRow()));
        var shared = new LinkedHashMap<>(demandRow());
        shared.put("custody_type", "SHARED");
        shared.put("target_employee_id", null);
        shared.put("target_department_employee_id", 4L);
        shared.put("expected_date", java.time.LocalDate.of(2026, 11, 20));
        assertWireEquals(legacyClean(shared), RequisitionViews.demand(JSON, shared));
    }

    @Test
    void handoverAndEventRowsMatchLegacyClean() {
        assertWireEquals(legacyClean(handoverRow()), RequisitionViews.handover(JSON, handoverRow()));
        var event = handoverEventRow();
        assertWireEquals(legacyClean(event), RequisitionViews.handoverEvent(event));
        var changed = new LinkedHashMap<>(event);
        changed.put("occurred_at", LocalDateTime.of(2026, 11, 3, 1, 2, 3));
        changed.put("observed_asset_code", null);
        assertWireEquals(legacyClean(changed), RequisitionViews.handoverEvent(changed));
    }

    @Test
    void decisionAndAllocationRowsMatchLegacyClean() {
        assertWireEquals(legacyClean(decisionRow()), RequisitionViews.decision(JSON, decisionRow()));
        assertWireEquals(legacyClean(allocationRow()), RequisitionViews.allocation(JSON, allocationRow()));
        var replaced = new LinkedHashMap<>(allocationRow());
        replaced.put("predecessor_allocation_id", 61L);
        replaced.put("approved_target_json", "{\"custodyType\":\"PERSONAL\",\"employeeId\":\"7\"}");
        replaced.put("receipt_target_employee_id", 7L);
        replaced.put("cancelled_at", Timestamp.from(Instant.parse("2026-11-05T00:00:00Z")));
        replaced.put("cancellation_reason", "错发");
        assertWireEquals(legacyClean(replaced), RequisitionViews.allocation(JSON, replaced));
    }

    @Test
    void unfulfillableOwnershipChangeFollowupRowsMatchLegacyClean() {
        assertWireEquals(
                legacyClean(unfulfillableRow()), RequisitionViews.unfulfillable(JSON, unfulfillableRow()));
        assertWireEquals(legacyClean(ownershipRow()), RequisitionViews.ownership(ownershipRow()));
        var archived = new LinkedHashMap<>(ownershipRow());
        archived.put("archived_at", Timestamp.from(Instant.parse("2026-11-06T00:00:00Z")));
        archived.put("archive_reason", "EXECUTION_FINISHED");
        archived.put("active_guard", null);
        assertWireEquals(legacyClean(archived), RequisitionViews.ownership(archived));
        assertWireEquals(
                legacyClean(changeRow()), RequisitionViews.changeProposal(JSON, changeRow()));
        assertWireEquals(legacyClean(followupRow()), RequisitionViews.followup(followupRow()));
    }

    @Test
    void inventoryPageMatchesLegacyAssembly() {
        var rows = List.of(inventoryRow());
        var legacy =
                Map.of(
                        "items", legacyCleanRows(rows),
                        "total", 3L,
                        "page", 1,
                        "pageSize", 50);
        var typed =
                new RequisitionViews.Page<>(
                        rows.stream().map(RequisitionViews::inventoryAsset).toList(), 3, 1, 50);
        assertWireEquals(legacy, typed);
        var noBrand = new LinkedHashMap<>(inventoryRow());
        noBrand.put("brand_id", null);
        noBrand.put("model", null);
        assertWireEquals(legacyClean(noBrand), RequisitionViews.inventoryAsset(noBrand));
    }

    // ---- handover exits ----

    @Test
    void pendingItemsMatchLegacyHandoverCleanBothDateTimeShapes() {
        var localShape = pendingRow(LocalDateTime.of(2026, 11, 3, 9, 0, 0));
        assertWireEquals(
                legacyHandoverClean(localShape), RequisitionViews.pendingReceiptItem(JSON, localShape));
        var legacyPage =
                Map.of(
                        "items", List.of(legacyHandoverClean(localShape)),
                        "total", 1L,
                        "page", 1,
                        "pageSize", 20);
        var typedPage =
                new RequisitionViews.Page<>(
                        List.of(RequisitionViews.pendingReceiptItem(JSON, localShape)), 1, 1, 20);
        assertWireEquals(legacyPage, typedPage);

        // Timestamp driver shape: the legacy handover clean() did NOT convert it, so the raw
        // value must pass through untouched (Object component), not normalize to a String.
        var raw = new Timestamp(1762160400123L);
        var timestampShape = pendingRow(raw);
        var typed = RequisitionViews.pendingReceiptItem(JSON, timestampShape);
        assertEquals(raw, typed.deliveredAt());
        assertWireEquals(legacyHandoverClean(timestampShape), typed);
    }

    @Test
    void grantReceiptResultAndReceiptDetailMatchLegacyAssemblies() {
        var legacyGrant =
                Map.of(
                        "grant", "tok",
                        "expiresAt", "2026-11-03T09:05:00Z",
                        "requestVersion", 2L,
                        "allocationVersion", 1L);
        assertWireEquals(
                legacyGrant, new RequisitionViews.Grant("tok", "2026-11-03T09:05:00Z", 2, 1));

        var legacyReceipt =
                new LinkedHashMap<String, Object>();
        legacyReceipt.put("allocationId", "60");
        legacyReceipt.put("requestId", "2");
        legacyReceipt.put("result", "RECEIVED");
        legacyReceipt.put("version", 9L);
        assertWireEquals(
                legacyReceipt,
                new RequisitionViews.ReceiptResult("60", "2", "RECEIVED", 9));

        var legacyDetailMap = new LinkedHashMap<String, Object>();
        legacyDetailMap.put("requestId", Long.toString(2L));
        legacyDetailMap.put("requestNo", headerRow().get("request_no"));
        legacyDetailMap.put("requestType", headerRow().get("request_type"));
        legacyDetailMap.put(
                "expectedReturnDate",
                headerRow().get("expected_return_date") == null
                        ? null
                        : headerRow().get("expected_return_date").toString());
        legacyDetailMap.put("requestVersion", 8L);
        legacyDetailMap.put("allocationId", Long.toString(60L));
        legacyDetailMap.put("allocationStatus", "DELIVERED_WAITING_RECEIPT");
        legacyDetailMap.put("allocationVersion", 1L);
        legacyDetailMap.put(
                "asset", JSON.readTree(allocationRow().get("asset_snapshot_json").toString()));
        legacyDetailMap.put("handover", legacyHandoverClean(receiptHandoverRow()));
        var typed =
                new RequisitionViews.ReceiptDetail(
                        Long.toString(2L),
                        (String) headerRow().get("request_no"),
                        (String) headerRow().get("request_type"),
                        headerRow().get("expected_return_date") == null
                                ? null
                                : headerRow().get("expected_return_date").toString(),
                        8,
                        Long.toString(60L),
                        "DELIVERED_WAITING_RECEIPT",
                        1,
                        JSON.readTree(allocationRow().get("asset_snapshot_json").toString()),
                        RequisitionViews.receiptHandover(receiptHandoverRow()));
        assertWireEquals(legacyDetailMap, typed);

        var borrow = new LinkedHashMap<>(headerRow());
        borrow.put("request_type", "BORROW");
        borrow.put("expected_return_date", java.sql.Date.valueOf("2026-12-01"));
        var borrowLegacy = new LinkedHashMap<>(legacyDetailMap);
        borrowLegacy.put("requestType", "BORROW");
        borrowLegacy.put("expectedReturnDate", "2026-12-01");
        var borrowTyped =
                new RequisitionViews.ReceiptDetail(
                        Long.toString(2L),
                        (String) borrow.get("request_no"),
                        (String) borrow.get("request_type"),
                        borrow.get("expected_return_date") == null
                                ? null
                                : borrow.get("expected_return_date").toString(),
                        8,
                        Long.toString(60L),
                        "DELIVERED_WAITING_RECEIPT",
                        1,
                        typed.asset(),
                        typed.handover());
        assertWireEquals(borrowLegacy, borrowTyped);
    }

    // ---- catalog conditional keys ----

    @Test
    void catalogPageKeepsEmployeeOrganizationIdOnlyForEmployeeKind() {
        var employee =
                new RequisitionMasterDataPort.CatalogChoice("3", "bob", 1, "1");
        var category = new RequisitionMasterDataPort.CatalogChoice("8", "laptop", 2, null);
        var legacy =
                Map.of(
                        "items", List.of(employee, category),
                        "page", 1,
                        "pageSize", 200,
                        "employeeId", "3",
                        "organizationId", "1",
                        "canApplyShared", false);
        var typed =
                new RequisitionViews.CatalogPage(
                        List.of(employee, category), 1, 200, "3", "1", false);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        // Conditional key discrimination: present for the employee row, absent for category.
        assertTrue(node.get("items").get(0).has("organizationId"));
        assertFalse(node.get("items").get(1).has("organizationId"));
    }

    // ---- idempotency stored-shape replays ----

    @Test
    void idempotencyReplayRoundTripsStoredDetailAndReceiptResult() {
        var storedDetail = JSON.writeValueAsString(typedDetail());
        assertEquals(
                JSON.readTree(storedDetail),
                JSON.readTree(
                        JSON.writeValueAsString(
                                JSON.readValue(storedDetail, RequisitionViews.Detail.class))));
        var receipt = new RequisitionViews.ReceiptResult("60", "2", "REFUSED", 9);
        var storedReceipt = JSON.writeValueAsString(receipt);
        assertEquals(
                JSON.readTree(storedReceipt),
                JSON.readTree(
                        JSON.writeValueAsString(
                                JSON.readValue(storedReceipt, RequisitionViews.ReceiptResult.class))));
    }

    @Test
    void preRefactorStoredDetailStillDeserializes() {
        // requisition:* and handover deliver/warehouse receipts stored this exact legacy tree.
        var stored = JSON.writeValueAsString(legacyDetail());
        var replayed = JSON.readValue(stored, RequisitionViews.Detail.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
    }

    @Test
    void preRefactorStoredReceiptResultStillDeserializes() {
        var legacyReceipt = new LinkedHashMap<String, Object>();
        legacyReceipt.put("allocationId", "60");
        legacyReceipt.put("requestId", "2");
        legacyReceipt.put("result", "RECEIVED");
        legacyReceipt.put("version", 9L);
        var stored = JSON.writeValueAsString(legacyReceipt);
        var replayed = JSON.readValue(stored, RequisitionViews.ReceiptResult.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
    }

    // ---- fixtures ----

    private static Map<String, Object> headerRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 2L);
        row.put("request_no", "REQ-2");
        row.put("creator_account_id", 11L);
        row.put("creator_username_snapshot", "ann");
        row.put("request_status", "WAITING_HANDOVER");
        row.put("request_version", 1L);
        row.put("parameter_version_id", 7L);
        row.put("parameter_snapshot_json", "{\"timezone\":\"Asia/Shanghai\"}");
        row.put("source_request_id", null);
        row.put("source_demand_id", null);
        row.put("source_reason_snapshot_json", null);
        row.put("submitted_at", Timestamp.from(Instant.parse("2026-11-01T02:00:00Z")));
        row.put("completed_at", null);
        row.put("version", 8L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-01T01:00:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-11-03T08:00:00Z")));
        row.put("request_type", "BORROW");
        row.put("expected_return_date", java.sql.Date.valueOf("2026-12-01"));
        return row;
    }

    private static Map<String, Object> demandRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 20L);
        row.put("request_id", 2L);
        row.put("line_no", 1);
        row.put("category_id", 8L);
        row.put("category_version_snapshot", 2L);
        row.put("category_name_snapshot", "laptop");
        row.put("specification", "14 inch");
        row.put("preferred_brand", null);
        row.put("preferred_model", "M1");
        row.put("purpose", "office");
        row.put("expected_date", java.sql.Date.valueOf("2026-11-20"));
        row.put("custody_type", "PERSONAL");
        row.put("target_organization_id", 1L);
        row.put("organization_version_snapshot", 0L);
        row.put("target_employee_id", 3L);
        row.put("target_department_employee_id", null);
        row.put("employee_version_snapshot", 1L);
        row.put("target_location_id", 9L);
        row.put("location_version_snapshot", 0L);
        row.put(
                "target_snapshot_json",
                "{\"organization\":{\"id\":1,\"name\":\"hq\"},\"employee\":{\"id\":3},\"location\":{\"id\":9}}");
        row.put("requested_quantity", 2);
        row.put("pending_allocation_quantity", 0);
        row.put("pending_handover_quantity", 1);
        row.put("completed_quantity", 1);
        row.put("unfulfillable_quantity", 0);
        row.put("cancelled_quantity", 0);
        row.put("version", 4L);
        return row;
    }

    private static Map<String, Object> handoverRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("allocation_id", 60L);
        row.put("delivered_by_account_id", 12L);
        row.put("delivered_at", Timestamp.from(Instant.parse("2026-11-03T09:00:00Z")));
        row.put("receipt_deadline_at", Timestamp.from(Instant.parse("2026-11-06T09:00:00Z")));
        row.put("destination_text", "front desk");
        row.put("temporary_employee_id", 3L);
        row.put("delivery_evidence", "hand delivered");
        row.put("target_snapshot_json", "{\"employee\":{\"id\":3}}");
        row.put("formal_custody_snapshot_json", "{\"id\":40,\"lifecycle_status\":\"IN_STOCK\"}");
        return row;
    }

    private static Map<String, Object> handoverEventRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 70L);
        row.put("allocation_id", 60L);
        row.put("event_type", "DELIVERED");
        row.put("actor_account_id", 12L);
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-03T09:00:01Z")));
        row.put("observed_asset_code", "FA-0040");
        row.put("evidence_text", "hand delivered");
        row.put("allocation_version_before", 0L);
        row.put("allocation_version_after", 1L);
        row.put("audit_event_id", 900L);
        return row;
    }

    private static Map<String, Object> decisionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 30L);
        row.put("request_id", 2L);
        row.put("request_version", 1L);
        row.put("decision_result", "APPROVED");
        row.put("approved_scope_json", "[{\"id\":20,\"requested_quantity\":2}]");
        row.put("comment_text", null);
        row.put("decided_by_account_id", 11L);
        row.put("decided_at", Timestamp.from(Instant.parse("2026-11-02T02:00:00Z")));
        row.put("audit_event_id", 800L);
        return row;
    }

    private static Map<String, Object> allocationRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 60L);
        row.put("request_id", 2L);
        row.put("demand_id", 20L);
        row.put("ownership_id", 40L);
        row.put("asset_id", 40L);
        row.put("asset_version_snapshot", 3L);
        row.put(
                "asset_snapshot_json",
                "{\"id\":\"40\",\"asset_code\":\"FA-0040\",\"name\":\"laptop\",\"brandId\":\"5\","
                        + "\"model\":\"M1\",\"version\":3,\"categoryId\":\"8\"}");
        row.put("business_lock_id", 50L);
        row.put("allocation_status", "DELIVERED_WAITING_RECEIPT");
        row.put("predecessor_allocation_id", null);
        row.put("version", 1L);
        row.put("allocated_at", Timestamp.from(Instant.parse("2026-11-02T03:00:00Z")));
        row.put("delivered_at", Timestamp.from(Instant.parse("2026-11-03T09:00:00Z")));
        row.put("received_at", null);
        row.put("cancelled_at", null);
        row.put("cancellation_reason", null);
        row.put("audit_event_id", 810L);
        row.put("approved_target_json", null);
        row.put("receipt_target_employee_id", null);
        row.put("lock_version", 2L);
        return row;
    }

    private static Map<String, Object> unfulfillableRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 33L);
        row.put("request_id", 2L);
        row.put("demand_id", 20L);
        row.put("quantity", 1);
        row.put("reason_code", "STOCK_SHORTAGE");
        row.put("reason_description", null);
        row.put("requirement_snapshot_json", "{\"id\":\"20\",\"custody_type\":\"PERSONAL\"}");
        row.put(
                "inventory_query_summary_json",
                "{\"categoryId\":\"8\",\"availableCount\":0,\"queriedAt\":\"2026-11-02T03:00:00Z\","
                        + "\"criteria\":\"ACTIVE / IN_STOCK / NORMAL / no active business lock\"}");
        row.put("recorded_by_account_id", 12L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-02T03:10:00Z")));
        row.put("audit_event_id", 820L);
        return row;
    }

    private static Map<String, Object> ownershipRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 40L);
        row.put("request_id", 2L);
        row.put("processor_account_id", 12L);
        row.put("ownership_version", 1L);
        row.put("acquired_at", Timestamp.from(Instant.parse("2026-11-02T02:30:00Z")));
        row.put("archived_at", null);
        row.put("archive_reason", null);
        row.put("active_guard", 1);
        return row;
    }

    private static Map<String, Object> changeRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 44L);
        row.put("request_id", 2L);
        row.put("request_version", 2L);
        row.put("previous_request_version", 1L);
        row.put("previous_request_status", "WAITING_HANDOVER");
        row.put("proposal_json", "{\"demands\":[],\"requestType\":\"BORROW\"}");
        row.put("proposal_status", "APPROVED");
        row.put("proposed_by_account_id", 11L);
        row.put("proposed_at", Timestamp.from(Instant.parse("2026-11-02T04:00:00Z")));
        row.put("decided_at", Timestamp.from(Instant.parse("2026-11-02T05:00:00Z")));
        row.put("audit_event_id", 830L);
        return row;
    }

    private static Map<String, Object> followupRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("source_request_id", 2L);
        row.put("source_demand_id", 20L);
        row.put("latest_request_id", 3L);
        row.put("version", 0L);
        row.put("updated_at", Timestamp.from(Instant.parse("2026-11-02T06:00:00Z")));
        return row;
    }

    private static Map<String, Object> inventoryRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 41L);
        row.put("asset_code", "FA-0041");
        row.put("name", "laptop");
        row.put("brand_id", 5L);
        row.put("model", "M1");
        row.put("version", 0L);
        row.put("category_id", 8L);
        return row;
    }

    private static Map<String, Object> pendingRow(Object deliveredAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 60L);
        row.put("request_id", 2L);
        row.put("request_no", "REQ-2");
        row.put("asset_snapshot_json", allocationRow().get("asset_snapshot_json"));
        row.put("version", 1L);
        row.put("delivered_at", deliveredAt);
        row.put(
                "receipt_deadline_at",
                deliveredAt instanceof LocalDateTime local
                        ? local.plusDays(3)
                        : Timestamp.from(Instant.parse("2026-11-06T09:00:00Z")));
        row.put("destination_text", "front desk");
        return row;
    }

    private static Map<String, Object> receiptHandoverRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("delivered_at", LocalDateTime.of(2026, 11, 3, 9, 0, 0));
        row.put("receipt_deadline_at", LocalDateTime.of(2026, 11, 6, 9, 0, 0));
        row.put("destination_text", "front desk");
        return row;
    }

    private static RequisitionViews.Detail typedDetail() {
        return RequisitionViews.detail(
                JSON,
                headerRow(),
                List.of(demandRow()),
                List.of(handoverRow()),
                List.of(handoverEventRow()),
                List.of(decisionRow()),
                List.of(allocationRow()),
                List.of(unfulfillableRow()),
                List.of(ownershipRow()),
                List.of(changeRow()),
                List.of(followupRow()));
    }

    /** Frozen pre-JAVA-14b1 RequisitionService.detail assembly; reference oracle. */
    private static Map<String, Object> legacyDetail() {
        var value = new LinkedHashMap<>(legacyClean(headerRow()));
        value.put("demands", legacyCleanRows(List.of(demandRow())));
        value.put("handovers", legacyCleanRows(List.of(handoverRow())));
        value.put("handoverEvents", legacyCleanRows(List.of(handoverEventRow())));
        value.put("decisions", legacyCleanRows(List.of(decisionRow())));
        value.put("allocations", legacyCleanRows(List.of(allocationRow())));
        value.put("unfulfillable", legacyCleanRows(List.of(unfulfillableRow())));
        value.put("ownership", legacyCleanRows(List.of(ownershipRow())));
        value.put("changes", legacyCleanRows(List.of(changeRow())));
        value.put("followups", legacyCleanRows(List.of(followupRow())));
        return value;
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** Frozen RequisitionService.clean projection (pre-JAVA-14b1); oracle only. */
    private static List<Map<String, Object>> legacyCleanRows(List<Map<String, Object>> values) {
        return values.stream().map(RequisitionWireShapeTest::legacyClean).toList();
    }

    private static Map<String, Object> legacyClean(Map<String, Object> value) {
        var result = new LinkedHashMap<String, Object>();
        value.forEach(
                (key, raw) -> {
                    String[] parts = key.split("_");
                    StringBuilder name = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length; i++) {
                        name.append(Character.toUpperCase(parts[i].charAt(0)))
                                .append(parts[i].substring(1));
                    }
                    String field = name.toString();
                    Object cleaned = raw;
                    if (raw instanceof Timestamp time) {
                        cleaned = time.toInstant().toString();
                    } else if (raw instanceof java.time.LocalDateTime time) {
                        cleaned = time.toInstant(java.time.ZoneOffset.UTC).toString();
                    } else if (raw instanceof java.sql.Date date) {
                        cleaned = date.toLocalDate().toString();
                    } else if (raw instanceof Number
                            && (field.equals("id") || field.endsWith("Id"))) {
                        cleaned = raw.toString();
                    } else if (field.endsWith("Json") && raw != null) {
                        cleaned = JSON.readValue(raw.toString(), Object.class);
                    }
                    result.put(field, cleaned);
                });
        return result;
    }

    /** Frozen RequisitionHandoverService.clean projection (pre-JAVA-14b1); oracle only. */
    private static Map<String, Object> legacyHandoverClean(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach(
                (key, value) -> {
                    StringBuilder name = new StringBuilder();
                    boolean upper = false;
                    for (char c : key.toCharArray()) {
                        if (c == '_') {
                            upper = true;
                        } else {
                            name.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                    }
                    result.put(
                            name.toString(),
                            value == null
                                    ? null
                                    : key.endsWith("_json")
                                            ? JSON.readTree(value.toString())
                                            : key.equals("id") || key.endsWith("_id")
                                                    ? value.toString()
                                                    : value instanceof java.time.LocalDateTime time
                                                            ? time.toInstant(
                                                                            java.time.ZoneOffset
                                                                                    .UTC)
                                                                    .toString()
                                                            : value);
                });
        return result;
    }
}
