package com.fams.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
 * JAVA-04 wire equivalence for the proxy receipt envelope: every typed shape must serialize
 * exactly like the frozen pre-refactor Map projections (the legacy* helpers below are those
 * projections, kept verbatim as the reference oracle). Comparison round-trips through JSON
 * text so integral node widths normalize and key order never participates; key presence,
 * explicit nulls and value shapes all count.
 */
class ProxyWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> WIRE_MAP = new TypeReference<>() {};

    @Test
    void requisitionSnapshotMatchesLegacyProjection() {
        var legacy = legacyRequisitionSnapshot();
        var typed = new ProxyViews.RequisitionSnapshot(
                "REQUISITION", "2", "5", "REQ-2", 6, 4, 1, "40", 3, "FA-1", "3",
                "PERSONAL", 4, "12", 1, 2);
        assertWireEquals(legacy, typed);
    }

    @Test
    void transferSnapshotMatchesLegacyProjection() {
        var legacy = legacyTransferSnapshot();
        var typed = new ProxyViews.TransferSnapshot(
                "TRANSFER", "2", "5", "TRANSFER-2", 6, 4, 1, "40", 3, "FA-1", "3",
                "STORAGE", "12", 1, 4,
                JSON.readTree("{\"id\":40,\"current_location_id\":9}"),
                JSON.readTree("{\"warehouseId\":\"2\",\"locationId\":\"9\"}"),
                JSON.readTree("{\"handoverAcceptanceDeadlineNaturalDays\":3}"));
        assertWireEquals(legacy, typed);
        // JAVA-01's port boundary converted the record to a Map; removing that layer must be
        // wire-neutral.
        assertWireEquals(JSON.convertValue(typed, WIRE_MAP), typed);
    }

    @Test
    void repairSnapshotMatchesLegacyProjectionWithAndWithoutLoan() {
        var target = repairTarget();
        var loan = Map.<String, Object>of(
                "id", "8", "version", 2L, "borrowerEmployeeId", "3",
                "organizationId", "1", "expectedReturnDate", "2026-12-01");
        var legacy = legacyRepairSnapshot(target, loan, "7");
        var typed = new ProxyViews.RepairSnapshot(
                "REPAIR", "2", "40", "40", 6, 1, "12", 3, "FA-1", "9",
                "2026-11-01T02:00:00Z", "3", target, loan, "7", 4L);
        assertWireEquals(legacy, typed);
        var withoutLoan = legacyRepairSnapshot(target, null, null);
        assertWireEquals(withoutLoan, new ProxyViews.RepairSnapshot(
                "REPAIR", "2", "40", "40", 6, 1, "12", 3, "FA-1", "9",
                "2026-11-01T02:00:00Z", "3", target, null, null, 4L));
    }

    @Test
    void receiptResultsMatchLegacyProjections() {
        var transferLegacy = new LinkedHashMap<String, Object>();
        transferLegacy.put("confirmationId", "10");
        transferLegacy.put("requestId", "2");
        transferLegacy.put("itemId", "5");
        transferLegacy.put("result", "RECEIVED");
        transferLegacy.put("assetCode", "FA-1");
        transferLegacy.put("targetEmployeeId", "3");
        assertWireEquals(transferLegacy, new ProxyViews.TransferReceiptResult("10", "2", "5", "RECEIVED", "FA-1", "3"));

        var requisitionLegacy = Map.<String, Object>of(
                "confirmationId", "10", "requestId", "2", "allocationId", "5",
                "result", "RECEIVED", "targetEmployeeId", "3", "assetCode", "FA-1");
        assertWireEquals(requisitionLegacy, new ProxyViews.RequisitionReceiptResult("10", "2", "5", "RECEIVED", "3", "FA-1"));

        var repairLegacy = Map.<String, Object>of(
                "businessId", "2", "repairId", "2", "itemId", "40", "assetCode", "FA-1",
                "targetEmployeeId", "3", "status", "COMPLETED", "version", 7L);
        assertWireEquals(repairLegacy, new ProxyViews.RepairReceiptResult("2", "2", "40", "FA-1", "3", "COMPLETED", 7));
    }

    @Test
    void proxySnapshotComparisonSurvivesHttpRoundTripForAllDomains() {
        // Red line 1 (JAVA-01 regression): the expected snapshot travels record -> HTTP JSON
        // -> deserialized record (or raw client Map with Integer widths). The services keep the
        // pre-refactor text round-trip comparison; prove it accepts every full-chain variant
        // and that both-record trees agree after the round trip.
        List<ProxyViews.ProxySnapshot> snapshots = List.of(
                new ProxyViews.RequisitionSnapshot(
                        "REQUISITION", "2", "5", "REQ-2", 6, 4, 1, "40", 3, "FA-1", "3",
                        "PERSONAL", 4, "12", 1, 2),
                new ProxyViews.TransferSnapshot(
                        "TRANSFER", "2", "5", "TRANSFER-2", 6, 4, 1, "40", 3, "FA-1", "3",
                        "STORAGE", "12", 1, 4,
                        JSON.readTree("{\"id\":40,\"current_location_id\":9}"),
                        JSON.readTree("{\"warehouseId\":\"2\",\"locationId\":\"9\"}"),
                        JSON.readTree("{\"handoverAcceptanceDeadlineNaturalDays\":3}")),
                new ProxyViews.RepairSnapshot(
                        "REPAIR", "2", "40", "40", 6, 1, "12", 3, "FA-1", "9",
                        "2026-11-01T02:00:00Z", "3", repairTarget(),
                        Map.of("id", "8", "version", 2L, "borrowerEmployeeId", "3",
                                "organizationId", "1", "expectedReturnDate", "2026-12-01"),
                        "7", 4L));
        for (var snapshot : snapshots) {
            String http = JSON.writeValueAsString(snapshot);
            // record -> HTTP JSON -> record (the typed execute path after JAVA-04).
            var viaHttpRecord = JSON.readValue(http, ProxyViews.ProxySnapshot.class);
            assertInstanceOf(snapshot.getClass(), viaHttpRecord);
            assertEquals(JSON.readTree(http), JSON.readTree(JSON.writeValueAsString(viaHttpRecord)));
            assertEquals(
                    JSON.readTree(JSON.writeValueAsString(snapshot)),
                    JSON.readTree(JSON.writeValueAsString(viaHttpRecord)));
            // record -> HTTP JSON -> raw client Map (Integer widths, the pre-JAVA-04 path).
            var viaHttpMap = JSON.readValue(http, WIRE_MAP);
            assertEquals(
                    JSON.readTree(JSON.writeValueAsString(snapshot)),
                    JSON.readTree(JSON.writeValueAsString(viaHttpMap)));
            // Stored business_snapshot_json replayed into the sealed interface stays equivalent.
            assertEquals(
                    JSON.readTree(http),
                    JSON.readTree(JSON.writeValueAsString(JSON.readValue(http, ProxyViews.ProxySnapshot.class))));
        }
    }

    @Test
    void preRefactorStoredSnapshotsStillDeserializeThroughDiscriminator() {
        // Old storage was written from the legacy Map projections; EXISTING_PROPERTY adds no
        // key, so those exact JSON shapes must replay into the matching subtype.
        Map<String, Object> requisition = legacyRequisitionSnapshot();
        Map<String, Object> transfer = legacyTransferSnapshot();
        Map<String, Object> repair = legacyRepairSnapshot(repairTarget(), null, "7");
        for (var legacy : List.of(requisition, transfer, repair)) {
            String stored = JSON.writeValueAsString(legacy);
            var replayed = JSON.readValue(stored, ProxyViews.ProxySnapshot.class);
            assertEquals(legacy.get("businessType"), replayed.businessType());
            assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
        }
        assertInstanceOf(ProxyViews.RequisitionSnapshot.class,
                JSON.readValue(JSON.writeValueAsString(requisition), ProxyViews.ProxySnapshot.class));
        assertInstanceOf(ProxyViews.TransferSnapshot.class,
                JSON.readValue(JSON.writeValueAsString(transfer), ProxyViews.ProxySnapshot.class));
        assertInstanceOf(ProxyViews.RepairSnapshot.class,
                JSON.readValue(JSON.writeValueAsString(repair), ProxyViews.ProxySnapshot.class));
    }

    @Test
    void confirmationMatchesLegacyWireProjection() {
        var row = confirmationRow();
        assertWireEquals(legacyWire(row), ProxyViews.confirmation(JSON, row));
        var executed = new LinkedHashMap<>(row);
        executed.put("confirmation_status", "EXECUTED");
        executed.put("evidence_snapshot_json", "[{\"objectId\":\"o-1\",\"sizeBytes\":\"10\"}]");
        executed.put("submitted_at", Timestamp.from(Instant.parse("2026-11-01T03:00:00Z")));
        executed.put("approver_account_id", 11L);
        executed.put("decision_reason", "ok");
        executed.put("decided_at", LocalDateTime.of(2026, 11, 1, 4, 0, 0));
        executed.put("executor_account_id", 12L);
        executed.put("executed_at", Timestamp.from(Instant.parse("2026-11-01T05:00:00Z")));
        executed.put("execution_result_json", "{\"result\":\"RECEIVED\",\"targetEmployeeId\":\"3\"}");
        executed.put("version", 4L);
        assertWireEquals(legacyWire(executed), ProxyViews.confirmation(JSON, executed));
    }

    @Test
    void confirmationPageMatchesLegacyAssembly() {
        var item = ProxyViews.confirmation(JSON, confirmationRow());
        var legacy = Map.<String, Object>of(
                "items", List.of(legacyWire(confirmationRow())),
                "page", 1, "pageSize", 20, "total", 1L, "canApprove", true, "canExecute", false);
        assertWireEquals(legacy, new ProxyViews.ConfirmationPage(List.of(item), 1, 20, 1, true, false));
    }

    @Test
    void idempotencyReplayRoundTripsStoredConfirmation() {
        var confirmation = ProxyViews.confirmation(JSON, confirmationRow());
        var stored = JSON.writeValueAsString(confirmation);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, ProxyViews.Confirmation.class))));
        // Pre-JAVA-04 storage was the raw wire() Map; it must replay into the record unchanged.
        var legacyStored = JSON.writeValueAsString(legacyWire(confirmationRow()));
        var replayed = JSON.readValue(legacyStored, ProxyViews.Confirmation.class);
        assertEquals(JSON.readTree(legacyStored), JSON.readTree(JSON.writeValueAsString(replayed)));
        assertEquals("10", replayed.id());
        assertEquals(1L, replayed.version());
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static Map<String, Object> legacyRequisitionSnapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("businessType", "REQUISITION");
        result.put("businessId", "2");
        result.put("itemId", "5");
        result.put("requestNo", "REQ-2");
        result.put("version", 6L);
        result.put("requestVersion", 4L);
        result.put("itemVersion", 1L);
        result.put("assetId", "40");
        result.put("assetVersion", 3L);
        result.put("assetCode", "FA-1");
        result.put("targetEmployeeId", "3");
        result.put("custodyType", "PERSONAL");
        result.put("lockVersion", 4L);
        result.put("processorAccountId", "12");
        result.put("ownershipVersion", 1L);
        result.put("demandVersion", 2L);
        return result;
    }

    private static Map<String, Object> legacyTransferSnapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("businessType", "TRANSFER");
        result.put("businessId", "2");
        result.put("itemId", "5");
        result.put("requestNo", "TRANSFER-2");
        result.put("version", 6L);
        result.put("approvedVersion", 4L);
        result.put("itemVersion", 1L);
        result.put("assetId", "40");
        result.put("assetVersion", 3L);
        result.put("assetCode", "FA-1");
        result.put("targetEmployeeId", "3");
        result.put("custodyType", "STORAGE");
        result.put("processorAccountId", "12");
        result.put("ownershipVersion", 1L);
        result.put("lockVersion", 4L);
        result.put("sourceSnapshot", JSON.readTree("{\"id\":40,\"current_location_id\":9}"));
        result.put("targetSnapshot", JSON.readTree("{\"warehouseId\":\"2\",\"locationId\":\"9\"}"));
        result.put("parameterSnapshot", JSON.readTree("{\"handoverAcceptanceDeadlineNaturalDays\":3}"));
        return result;
    }

    private static Map<String, Object> repairTarget() {
        var target = new LinkedHashMap<String, Object>();
        target.put("custodyType", "PERSONAL");
        target.put("employeeId", "3");
        target.put("employeeName", "ann");
        target.put("employeeVersion", 2L);
        target.put("organizationId", "1");
        target.put("organizationName", "hq");
        target.put("organizationVersion", 1L);
        target.put("locationId", "9");
        target.put("locationName", "shelf");
        target.put("locationVersion", 3L);
        target.put("receiverAccountId", "12");
        return target;
    }

    private static Map<String, Object> legacyRepairSnapshot(
            Map<String, Object> target, Map<String, Object> loan, String approvedTargetId) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("businessType", "REPAIR");
        snapshot.put("businessId", "2");
        snapshot.put("itemId", "40");
        snapshot.put("assetId", "40");
        snapshot.put("version", 6L);
        snapshot.put("ownershipVersion", 1L);
        snapshot.put("processorAccountId", "12");
        snapshot.put("assetVersion", 3L);
        snapshot.put("assetCode", "FA-1");
        snapshot.put("handoverId", "9");
        snapshot.put("deliveredAt", "2026-11-01T02:00:00Z");
        snapshot.put("targetEmployeeId", target.get("employeeId"));
        snapshot.put("target", target);
        snapshot.put("loan", loan);
        snapshot.put("approvedTargetId", approvedTargetId);
        snapshot.put("lockVersion", 4L);
        return snapshot;
    }

    private static Map<String, Object> confirmationRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 10L);
        row.put("business_type", "TRANSFER");
        row.put("business_id", 2L);
        row.put("item_id", 5L);
        row.put("business_snapshot_json", JSON.writeValueAsString(legacyTransferSnapshot()));
        row.put("evidence_metadata_json", "{\"actualSigner\":\"ann\",\"delegatedReceipt\":false}");
        row.put("confirmation_status", "DRAFT");
        row.put("version", 1L);
        row.put("previous_confirmation_id", null);
        row.put("observed_asset_code", "FA-1");
        row.put("actual_signer", "ann");
        row.put("actual_received_at", LocalDateTime.of(2026, 11, 1, 2, 0, 0));
        row.put("cannot_confirm_reason", "receiver absent");
        row.put("contact_record", "called twice");
        row.put("delegated_receipt", Boolean.FALSE);
        row.put("delegation_relationship", null);
        row.put("delegation_basis", null);
        row.put("evidence_kind", "SIGNED_RECEIPT");
        row.put("collection_method", "SCAN_CONFIRMED");
        row.put("manual_reason", null);
        row.put("evidence_snapshot_json", null);
        row.put("creator_account_id", 12L);
        row.put("submitted_at", null);
        row.put("approver_account_id", null);
        row.put("decision_reason", null);
        row.put("decided_at", null);
        row.put("executor_account_id", null);
        row.put("executed_at", null);
        row.put("execution_result_json", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-01T02:30:00Z")));
        row.put("updated_at", LocalDateTime.of(2026, 11, 1, 2, 30, 0));
        return row;
    }

    /** Frozen pre-JAVA-04 RequisitionProxyConfirmationService.wire projection; oracle only. */
    private static Map<String, Object> legacyWire(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (k, v) -> {
                    StringBuilder key = new StringBuilder();
                    boolean upper = false;
                    for (char c : k.toCharArray()) {
                        if (c == '_') {
                            upper = true;
                        } else {
                            key.append(upper ? Character.toUpperCase(c) : c);
                            upper = false;
                        }
                    }
                    result.put(
                            key.toString(),
                            v == null
                                    ? null
                                    : k.endsWith("_json")
                                            ? JSON.readTree(v.toString())
                                            : k.equals("id") || k.endsWith("_id")
                                                    ? v.toString()
                                                    : v);
                });
        return result;
    }
}
