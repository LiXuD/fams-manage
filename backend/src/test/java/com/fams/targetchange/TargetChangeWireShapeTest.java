package com.fams.targetchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.transfer.TransferViews;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-04 wire equivalence for the target change envelope: the workflow clean() projection,
 * the requisition handover clean() projection and the three domain context assemblies are
 * frozen verbatim below as the reference oracle. Comparison round-trips through JSON text so
 * integral node widths normalize and key order never participates; key presence, explicit
 * nulls and value shapes all count.
 */
class TargetChangeWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> WIRE_MAP = new TypeReference<>() {};

    @Test
    void changeMatchesLegacyCleanProjection() {
        var row = changeRow();
        assertWireEquals(legacyClean(row, Map.of()), TargetChangeViews.change(JSON, row, Map.of()));
        var display = Map.of("employeeName", "ann", "organizationName", "hq", "locationName", "shelf");
        assertWireEquals(legacyClean(row, display), TargetChangeViews.change(JSON, row, display));
    }

    @Test
    void changeHandlesDecidedRowAndBothJdbcTimeForms() {
        var decided = new LinkedHashMap<>(changeRow());
        decided.put("change_status", "APPROVED");
        decided.put("approver_account_id", 11L);
        decided.put("decision_reason", "ok");
        decided.put("decided_at", Timestamp.from(Instant.parse("2026-11-02T03:04:05Z")));
        decided.put("version", 1L);
        var display = Map.of("locationName", "shelf");
        assertWireEquals(legacyClean(decided, display), TargetChangeViews.change(JSON, decided, display));
        var localDateTime = new LinkedHashMap<>(decided);
        localDateTime.put("created_at", LocalDateTime.of(2026, 11, 1, 2, 0, 0));
        localDateTime.put("decided_at", LocalDateTime.of(2026, 11, 2, 3, 4, 5));
        assertWireEquals(legacyClean(localDateTime, display), TargetChangeViews.change(JSON, localDateTime, display));
    }

    @Test
    void pageMatchesLegacyAssembly() {
        var change = TargetChangeViews.change(JSON, changeRow(), Map.of());
        var legacy = Map.<String, Object>of(
                "items", List.of(legacyClean(changeRow(), Map.of())),
                "total", 1L, "page", 1, "pageSize", 20, "canApprove", true, "canExecute", false);
        assertWireEquals(legacy, new TargetChangeViews.Page(List.of(change), 1, 1, 20, true, false));
    }

    @Test
    void requisitionContextMatchesLegacyAssembly() {
        var handoverRow = new LinkedHashMap<String, Object>();
        handoverRow.put("delivered_at", LocalDateTime.of(2026, 11, 1, 2, 0, 0));
        handoverRow.put("receipt_deadline_at", LocalDateTime.of(2026, 11, 4, 2, 0, 0));
        handoverRow.put("destination_text", "front desk");
        var legacy = legacyRequisitionContext(List.of(handoverRow));
        var typed = new TargetChangeViews.RequisitionContext(
                "REQUISITION", "2", "5", 4, 6, 1, "WAITING_DELIVERY", 2, "40", 3, "FA-1",
                new TargetChangeViews.RequisitionTarget("PERSONAL", "1", 1, "3", 2, "9", 3),
                1, "12", 4,
                List.of(TargetChangeViews.handover(JSON, handoverRow)));
        assertWireEquals(legacy, typed);
        // The stored source_snapshot_json is the serialized context; it must equal the legacy
        // Map JSON the workflow used to store and later compare against.
        assertEquals(JSON.readTree(JSON.writeValueAsString(legacy)), JSON.readTree(JSON.writeValueAsString(typed)));
    }

    @Test
    void requisitionHandoverKeepsRawTimestampPassThrough() {
        // The requisition clean() only converts LocalDateTime; Timestamp passes through raw
        // (unlike the workflow clean()). Both driver forms must stay wire-identical.
        var timestampRow = new LinkedHashMap<String, Object>();
        timestampRow.put("delivered_at", Timestamp.from(Instant.parse("2026-11-01T02:00:00Z")));
        timestampRow.put("receipt_deadline_at", null);
        timestampRow.put("destination_text", null);
        assertWireEquals(legacyHandoverClean(timestampRow), TargetChangeViews.handover(JSON, timestampRow));
        var localDateTimeRow = new LinkedHashMap<String, Object>();
        localDateTimeRow.put("delivered_at", LocalDateTime.of(2026, 11, 1, 2, 0, 0));
        localDateTimeRow.put("receipt_deadline_at", LocalDateTime.of(2026, 11, 4, 2, 0, 0));
        localDateTimeRow.put("destination_text", "front desk");
        assertWireEquals(legacyHandoverClean(localDateTimeRow), TargetChangeViews.handover(JSON, localDateTimeRow));
    }

    @Test
    void repairContextMatchesLegacyAssemblyWithAndWithoutApprovedTarget() {
        var target = new LinkedHashMap<String, Object>();
        target.put("custodyType", "PERSONAL");
        target.put("organizationId", "1");
        target.put("employeeId", "3");
        target.put("locationId", "9");
        var legacy = legacyRepairContext(target, "7", 2L);
        var typed = new TargetChangeViews.RepairContext(
                "REPAIR", "2", "40", 6, 1, "12", 3, "FA-1", target, "7", 2L,
                "2026-11-01T02:00:00Z", 4L);
        assertWireEquals(legacy, typed);
        assertEquals(JSON.readTree(JSON.writeValueAsString(legacy)), JSON.readTree(JSON.writeValueAsString(typed)));
        var original = legacyRepairContext(target, null, null);
        assertWireEquals(original, new TargetChangeViews.RepairContext(
                "REPAIR", "2", "40", 6, 1, "12", 3, "FA-1", target, null, null,
                "2026-11-01T02:00:00Z", 4L));
    }

    @Test
    void transferContextImplementsEnvelopeWithoutShapeChange() {
        var target = new TransferViews.TargetWire(
                null, "STORAGE", null, null, "2", "9", Map.of("warehouse", 1L, "location", 2L));
        var source = new TransferViews.SourceView(
                "40", 3, "FA-1", "laptop", "1", "STORAGE", null, null, null, "2", "9",
                "IN_STOCK", "NORMAL", "ACTIVE", "7");
        var physical = new TransferViews.PhysicalView("front desk", "photo", "2026-11-01T02:00:00Z", "2026-11-04T02:00:00Z");
        var typed = new TransferViews.TargetChangeContext(
                "TRANSFER", "2", "5", 6, 4, 1, "DELIVERED", "40", 3, "FA-1", "12", 1, 4,
                target, source, physical);
        TargetChangeViews.Context envelope = typed;
        assertEquals("TRANSFER", envelope.businessType());
        assertEquals(6L, envelope.version());
        assertEquals("FA-1", envelope.assetCode());
        assertEquals(target, envelope.target());
        // JAVA-01's port boundary converted the record to a Map; removing it is wire-neutral.
        assertWireEquals(JSON.convertValue(typed, WIRE_MAP), typed);
    }

    @Test
    void contextComparisonSurvivesHttpAndStoredJsonRoundTrips() {
        // Red line 1 (JAVA-01 regression): propose compares the live context against the raw
        // HTTP proposal Map (Integer widths) and decide compares it against the stored JSON
        // tree. The workflow keeps the pre-refactor text round-trip; prove both full chains.
        var requisition = new TargetChangeViews.RequisitionContext(
                "REQUISITION", "2", "5", 4, 6, 1, "WAITING_DELIVERY", 2, "40", 3, "FA-1",
                new TargetChangeViews.RequisitionTarget("PERSONAL", "1", 1, "3", 2, "9", 3),
                1, "12", 4, List.of());
        var repair = new TargetChangeViews.RepairContext(
                "REPAIR", "2", "40", 6, 1, "12", 3, "FA-1",
                Map.of("custodyType", "PERSONAL", "organizationId", "1", "employeeId", "3", "locationId", "9"),
                "7", 2L, "2026-11-01T02:00:00Z", 4L);
        for (TargetChangeViews.Context context : List.of(requisition, repair)) {
            String stored = JSON.writeValueAsString(context);
            // propose: raw client Map deserialized from HTTP JSON (small numbers -> Integer).
            Map<String, Object> viaHttp = JSON.readValue(stored, WIRE_MAP);
            assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(viaHttp)));
            assertEquals(
                    JSON.readTree(JSON.writeValueAsString(context)),
                    JSON.readTree(JSON.writeValueAsString(viaHttp)));
            // decide: stored source_snapshot_json read back as a raw tree.
            JsonNode viaStoredTree = JSON.readTree(stored);
            assertEquals(JSON.readTree(JSON.writeValueAsString(context)), viaStoredTree);
            // target sub-comparison (workflow same(snapshot.target(), canonical.target())).
            assertEquals(
                    JSON.readTree(JSON.writeValueAsString(context.target())),
                    JSON.readTree(JSON.writeValueAsString(viaHttp.get("target"))));
        }
    }

    @Test
    void idempotencyReplayRoundTripsStoredChange() {
        var display = Map.of("employeeName", "ann");
        var change = TargetChangeViews.change(JSON, changeRow(), display);
        var stored = JSON.writeValueAsString(change);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, TargetChangeViews.Change.class))));
        // Pre-JAVA-04 storage was the raw clean() Map (snake_case keys, conditional display).
        var legacyStored = JSON.writeValueAsString(legacyClean(changeRow(), display));
        var replayed = JSON.readValue(legacyStored, TargetChangeViews.Change.class);
        assertEquals(JSON.readTree(legacyStored), JSON.readTree(JSON.writeValueAsString(replayed)));
        assertEquals("30", replayed.id());
        assertEquals(0L, replayed.version());
        // Without a display the conditional key must stay absent on replay.
        var withoutDisplay = JSON.writeValueAsString(legacyClean(changeRow(), Map.of()));
        var replayedPlain = JSON.readValue(withoutDisplay, TargetChangeViews.Change.class);
        assertEquals(JSON.readTree(withoutDisplay), JSON.readTree(JSON.writeValueAsString(replayedPlain)));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static Map<String, Object> changeRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 30L);
        row.put("business_type", "REPAIR");
        row.put("business_id", 2L);
        row.put("item_id", 40L);
        row.put("source_snapshot_json", "{\"businessType\":\"REPAIR\",\"version\":6}");
        row.put("proposed_target_json", "{\"custodyType\":\"PERSONAL\",\"locationId\":\"9\"}");
        row.put("reason", "shelf move");
        row.put("observed_asset_code", "FA-1");
        row.put("physical_verification", "checked");
        row.put("change_status", "PENDING");
        row.put("creator_account_id", 12L);
        row.put("approver_account_id", null);
        row.put("decision_reason", null);
        row.put("version", 0L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-01T02:00:00Z")));
        row.put("decided_at", null);
        return row;
    }

    /** Frozen pre-JAVA-04 TargetChangeWorkflow.clean projection; reference oracle only. */
    private static Map<String, Object> legacyClean(Map<String, Object> row, Map<String, String> display) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : row.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value != null && key.endsWith("_json")) {
                value = JSON.readTree(value.toString());
            } else if (value instanceof Timestamp timestamp) {
                value = timestamp.toInstant().toString();
            } else if (value instanceof LocalDateTime time) {
                value = time.toInstant(ZoneOffset.UTC).toString();
            } else if (value != null && (key.equals("id") || key.endsWith("_id"))) {
                value = value.toString();
            }
            result.put(key, value);
        }
        if (!display.isEmpty()) {
            result.put("targetDisplay", display);
        }
        return result;
    }

    /** Frozen pre-JAVA-04 RequisitionHandoverService.clean projection; reference oracle only. */
    private static Map<String, Object> legacyHandoverClean(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
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
                                                    : value instanceof LocalDateTime time
                                                            ? time.toInstant(ZoneOffset.UTC).toString()
                                                            : value);
                });
        return result;
    }

    private static Map<String, Object> legacyRequisitionContext(List<Map<String, Object>> handoverRows) {
        var target = new LinkedHashMap<String, Object>();
        target.put("custodyType", "PERSONAL");
        target.put("organizationId", "1");
        target.put("organizationVersion", 1L);
        target.put("employeeId", "3");
        target.put("employeeVersion", 2L);
        target.put("locationId", "9");
        target.put("locationVersion", 3L);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("businessType", "REQUISITION");
        snapshot.put("businessId", "2");
        snapshot.put("itemId", "5");
        snapshot.put("requestVersion", 4L);
        snapshot.put("version", 6L);
        snapshot.put("itemVersion", 1L);
        snapshot.put("itemStatus", "WAITING_DELIVERY");
        snapshot.put("demandVersion", 2L);
        snapshot.put("assetId", "40");
        snapshot.put("assetVersion", 3L);
        snapshot.put("assetCode", "FA-1");
        snapshot.put("target", target);
        snapshot.put("ownershipVersion", 1L);
        snapshot.put("processorAccountId", "12");
        snapshot.put("lockVersion", 4L);
        snapshot.put("handover", handoverRows.stream().map(TargetChangeWireShapeTest::legacyHandoverClean).toList());
        return snapshot;
    }

    private static Map<String, Object> legacyRepairContext(
            Map<String, Object> target, String approvedTargetId, Long loanVersion) {
        var result = new LinkedHashMap<String, Object>();
        result.put("businessType", "REPAIR");
        result.put("businessId", "2");
        result.put("itemId", "40");
        result.put("version", 6L);
        result.put("ownershipVersion", 1L);
        result.put("processorAccountId", "12");
        result.put("assetVersion", 3L);
        result.put("assetCode", "FA-1");
        result.put("target", target);
        result.put("approvedTargetId", approvedTargetId);
        result.put("loanVersion", loanVersion);
        result.put("lastPhysicalTime", "2026-11-01T02:00:00Z");
        result.put("lockVersion", 4L);
        return result;
    }
}
