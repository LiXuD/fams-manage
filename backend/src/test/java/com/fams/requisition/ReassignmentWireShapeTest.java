package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-14c wire equivalence for the requisition reassignment/unavailability exits. The legacy
 * assemblies (pre-JAVA-14c inspect snapshot, checks/operator projections and the
 * declareUnavailable/reassign receipts taken through the removed RequisitionService.get Map
 * facade) are copied field by field as oracles. Comparison round-trips through JSON text, so
 * object key order (never stable in the Map.of-based snapshot) and integral node widths
 * normalize, while key presence, explicit nulls and the conditional unavailableReason key count.
 */
class ReassignmentWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- inspect exit ----

    @Test
    void inspectSnapshotMatchesLegacyAssembly() {
        var checks = legacyChecks();
        var operators = operatorRows();
        var legacy = legacyInspect(2L, checks, operators, null);
        var typed = typedSnapshot(checks, operators, null);
        assertWireEquals(legacy, typed);
    }

    @Test
    void inspectKeepsUnavailableReasonAsConditionalKey() {
        var checks = legacyChecks();
        var operators = operatorRows();

        var declared = typedSnapshot(checks, operators, "库内盘点，暂无法处理");
        var node = JSON.readTree(JSON.writeValueAsString(declared));
        assertTrue(node.has("unavailableReason"));
        assertEquals("库内盘点，暂无法处理", node.get("unavailableReason").asText());
        assertWireEquals(legacyInspect(2L, checks, operators, "库内盘点，暂无法处理"), declared);

        // Null state: the legacy LinkedHashMap only put the key when a declaration existed, so
        // the typed snapshot must omit it entirely (NON_NULL over the global ALWAYS inclusion).
        var absent = JSON.readTree(JSON.writeValueAsString(typedSnapshot(checks, operators, null)));
        assertFalse(absent.has("unavailableReason"));
    }

    @Test
    void inspectItemProjectionsMatchLegacyCheckRows() {
        // Pending row carries the live lock version, finished rows freeze -1 (frozen branch).
        var rows = legacyChecks();
        for (int i = 0; i < rows.size(); i++) {
            assertWireEquals(rows.get(i), RequisitionViews.reassignmentItem(rows.get(i)));
        }
        assertWireEquals(
                Map.of("id", "12", "name", "王仓管"),
                RequisitionViews.operator(operatorRows().get(0)));
    }

    // ---- get -> getDetail consumption-surface equivalence ----

    @Test
    void removedGetFacadeMapSerializedIdenticallyToTypedDetail() {
        // Pre-JAVA-14c declareUnavailable/reassign stored and returned
        // writeValueAsString(json.convertValue(getDetail(...), Map)). Prove the record exit and
        // the deleted Map facade are tree-identical, both against the legacy clean assembly.
        var detail = typedDetail();
        Map<String, Object> viaCompatMap =
                JSON.convertValue(detail, new TypeReference<Map<String, Object>>() {});
        assertWireEquals(legacyDetail(), detail);
        assertWireEquals(legacyDetail(), viaCompatMap);
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(viaCompatMap)),
                JSON.readTree(JSON.writeValueAsString(detail)));
    }

    @Test
    void detailExitKeepsExplicitNullKeys() {
        var node = JSON.readTree(JSON.writeValueAsString(typedDetail()));
        assertTrue(node.has("sourceRequestId"));
        assertTrue(node.get("sourceRequestId").isNull());
        assertTrue(node.get("demands").get(0).has("preferredBrand"));
        assertTrue(node.get("demands").get(0).get("preferredBrand").isNull());
        assertTrue(node.get("ownership").get(0).has("archivedAt"));
        assertTrue(node.get("ownership").get(0).get("archivedAt").isNull());
    }

    // ---- idempotency stored-shape replays ----

    @Test
    void preRefactorReassignmentReceiptsStillDeserializeAsDetail() {
        // fams_idempotency_record.response_body_json stored by declareUnavailable/reassign before
        // this batch is exactly the legacy detail tree; replays must round-trip it loss-free.
        var stored = JSON.writeValueAsString(legacyDetail());
        var replayed = JSON.readValue(stored, RequisitionViews.Detail.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
    }

    @Test
    void typedDetailReceiptRoundTripsToItself() {
        var stored = JSON.writeValueAsString(typedDetail());
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(
                        JSON.writeValueAsString(
                                JSON.readValue(stored, RequisitionViews.Detail.class))));
    }

    // ---- assemblies under test ----

    /** Copies the frozen pre-JAVA-14c inspect snapshot builder field by field. */
    private static Map<String, Object> legacyInspect(
            long id,
            List<Map<String, Object>> checks,
            List<Map<String, Object>> operators,
            String declared) {
        var snapshot =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "id", Long.toString(id),
                                "version", headerRow().get("version"),
                                "ownershipVersion", ownershipRow().get("ownership_version"),
                                "processorAccountId",
                                        ownershipRow().get("processor_account_id").toString(),
                                "items", checks,
                                "operators",
                                        operators.stream()
                                                .map(o ->
                                                        Map.of(
                                                                "id", o.get("id").toString(),
                                                                "name", o.get("name")))
                                                .toList()));
        if (declared != null) {
            snapshot.put("unavailableReason", declared);
        }
        return snapshot;
    }

    private static RequisitionViews.ReassignmentSnapshot typedSnapshot(
            List<Map<String, Object>> checks,
            List<Map<String, Object>> operators,
            String declared) {
        return new RequisitionViews.ReassignmentSnapshot(
                Long.toString(2L),
                ((Number) headerRow().get("version")).longValue(),
                ((Number) ownershipRow().get("ownership_version")).longValue(),
                ownershipRow().get("processor_account_id").toString(),
                checks.stream().map(RequisitionViews::reassignmentItem).toList(),
                operators.stream().map(RequisitionViews::operator).toList(),
                declared);
    }

    /** Copies the frozen RequisitionReassignmentService.checks row Map projection. */
    private static List<Map<String, Object>> legacyChecks() {
        var rows = new ArrayList<Map<String, Object>>();
        // Pending allocation: live lock version from fams_asset_business_lock.
        rows.add(
                Map.of(
                        "id", "60",
                        "version", 1L,
                        "status", "ALLOCATED",
                        "assetVersion", 3L,
                        "lockVersion", 2L,
                        "assetCode", "FA-0040"));
        // Finished allocation: no live lock, lockVersion frozen to -1L.
        rows.add(
                Map.of(
                        "id", "61",
                        "version", 4L,
                        "status", "RECEIVED",
                        "assetVersion", 5L,
                        "lockVersion", -1L,
                        "assetCode", "FA-0041"));
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> operatorRows() {
        return List.of(
                Map.of("id", 12L, "name", "王仓管"),
                Map.of("id", 13L, "name", "李仓管"));
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
        value.put("demands", List.of(legacyClean(demandRow())));
        value.put("handovers", List.of(legacyClean(handoverRow())));
        value.put("handoverEvents", List.of(legacyClean(handoverEventRow())));
        value.put("decisions", List.of(legacyClean(decisionRow())));
        value.put("allocations", List.of(legacyClean(allocationRow())));
        value.put("unfulfillable", List.of(legacyClean(unfulfillableRow())));
        value.put("ownership", List.of(legacyClean(ownershipRow())));
        value.put("changes", List.of(legacyClean(changeRow())));
        value.put("followups", List.of(legacyClean(followupRow())));
        return value;
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** Frozen RequisitionService.clean projection; oracle only. */
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
                    } else if (raw instanceof LocalDateTime time) {
                        cleaned = time.toInstant(java.time.ZoneOffset.UTC).toString();
                    } else if (raw instanceof java.sql.Date date) {
                        cleaned = date.toLocalDate().toString();
                    } else if (raw instanceof Number && (field.equals("id") || field.endsWith("Id"))) {
                        cleaned = raw.toString();
                    } else if (field.endsWith("Json") && raw != null) {
                        cleaned = JSON.readValue(raw.toString(), Object.class);
                    }
                    result.put(field, cleaned);
                });
        return result;
    }

    // ---- fixtures (same frozen JDBC-shaped rows as RequisitionWireShapeTest) ----

    private static Map<String, Object> headerRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 2L);
        row.put("request_no", "REQ-2");
        row.put("creator_account_id", 11L);
        row.put("creator_username_snapshot", "ann");
        row.put("request_status", "WAREHOUSE_PROCESSING");
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
        row.put("pending_allocation_quantity", 1);
        row.put("pending_handover_quantity", 0);
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
        row.put("allocation_status", "ALLOCATED");
        row.put("predecessor_allocation_id", null);
        row.put("version", 1L);
        row.put("allocated_at", Timestamp.from(Instant.parse("2026-11-03T08:00:00Z")));
        row.put("delivered_at", null);
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
}
