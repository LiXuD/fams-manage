package com.fams.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-08 wire equivalence for the loan deep-coupled ports. LoanRepairPort exposes no Map
 * signatures (its Context record is unchanged), but its Context is persisted verbatim into
 * {@code loan_snapshot_json} and repair-scrap proof blobs, so the stored shape and the replay
 * equality semantics are frozen here. LoanReturnRepairPort.list carried the only remaining Map
 * surface: the frozen legacyWire/legacyListAssembly oracles are the deleted pre-refactor wire()
 * projection and Map.of envelope, byte-for-byte. Comparison round-trips through JSON text so
 * integral node widths normalize and object key order (never stable in Map.of envelopes) is
 * ignored, while key presence, explicit nulls and value shapes all count.
 */
class LoanDeepWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void repairFollowupSourceMatchesLegacyWireProjectionForPendingAndLinkedRows() {
        var pending = followupSourceRow();
        assertWireEquals(legacyWire(pending), LoanViews.repairFollowupSource(pending));

        var linked = new LinkedHashMap<>(pending);
        linked.put("loan_id", null);
        linked.put("followup_status", "ACCEPTED");
        linked.put("linked_repair_id", 77L);
        linked.put("last_repair_id", 76L);
        linked.put("repair_status", "WAREHOUSE_PROCESSING");
        linked.put("version", 1L);
        assertWireEquals(legacyWire(linked), LoanViews.repairFollowupSource(linked));

        var closed = new LinkedHashMap<>(linked);
        closed.put("followup_status", "CLOSED");
        closed.put("repair_status", "COMPLETED");
        closed.put("version", 2L);
        assertWireEquals(legacyWire(closed), LoanViews.repairFollowupSource(closed));

        // Integral width variants: text round-trip normalizes them, wire()/typed agree.
        var narrowWidths = new LinkedHashMap<>(pending);
        narrowWidths.put("id", 32);
        narrowWidths.put("version", 0);
        assertWireEquals(legacyWire(narrowWidths), LoanViews.repairFollowupSource(narrowWidths));
    }

    @Test
    void repairFollowupSourceKeepsEveryKeyWithExplicitNulls() {
        var row = followupSourceRow();
        var typed = LoanViews.repairFollowupSource(row);
        // wire() always emitted all 11 snake_case keys; nullable ids/repair_status stay as null.
        // Exact-tree equality against the full key set also freezes the key set itself.
        assertEquals(
                JSON.readTree(
                        "{\"id\":\"32\",\"return_id\":\"9\",\"asset_id\":\"40\",\"loan_id\":\"8\","
                            + "\"followup_status\":\"PENDING\",\"processor_account_id\":\"13\","
                            + "\"reason\":\"damaged at inspection\",\"version\":0,"
                            + "\"linked_repair_id\":null,\"last_repair_id\":null,"
                            + "\"repair_status\":null}"),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    @Test
    void listPageEnvelopeMatchesLegacyMapOfAssembly() {
        var row = followupSourceRow();
        var legacy =
                Map.of(
                        "items",
                        List.of(legacyWire(row)),
                        "total",
                        1L,
                        "page",
                        1,
                        "pageSize",
                        20);
        assertWireEquals(
                legacy,
                new LoanViews.Page<>(List.of(LoanViews.repairFollowupSource(row)), 1, 1, 20));
    }

    @Test
    void loanRepairContextStoredShapeIsFrozenForLoanSnapshotAndProofColumns() {
        var context =
                new LoanRepairPort.Context(8L, 0L, 3L, 1L, LocalDate.of(2026, 11, 10));
        // RepairInitialPort writes writeValueAsString(loans.open(...)) into loan_snapshot_json
        // and ScrapInitialPort nests the same record under "borrowing" in repairEvidence proofs.
        assertEquals(
                JSON.readTree(
                        "{\"id\":8,\"version\":0,\"borrowerEmployeeId\":3,\"organizationId\":1,"
                                + "\"expectedReturnDate\":\"2026-11-10\"}"),
                JSON.readTree(JSON.writeValueAsString(context)));
        assertEquals(
                JSON.readTree(
                        "{\"borrowing\":{\"id\":8,\"version\":0,\"borrowerEmployeeId\":3,"
                                + "\"organizationId\":1,\"expectedReturnDate\":\"2026-11-10\"}}"),
                JSON.readTree(JSON.writeValueAsString(Map.of("borrowing", context))));
    }

    @Test
    void loanRepairContextReplayKeepsRecordEqualityForGuardComparisons() {
        var context =
                new LoanRepairPort.Context(8L, 3L, 3L, 1L, LocalDate.of(2026, 11, 10));
        var stored = JSON.writeValueAsString(context);
        // RepairService.inPlace/requireUnchanged and ScrapReviewService.repairBorrowing replay
        // the stored column and lean on Objects.equals against the live open() row.
        assertEquals(context, JSON.readValue(stored, LoanRepairPort.Context.class));
        assertEquals(
                context,
                JSON.readValue(
                        JSON.readTree(stored).toString(), LoanRepairPort.Context.class));
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(
                        JSON.writeValueAsString(
                                JSON.readValue(stored, LoanRepairPort.Context.class))));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** Mirrors the SELECT column list of LoanReturnRepairPort.list; JDBC Long/String shapes. */
    private static Map<String, Object> followupSourceRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 32L);
        row.put("return_id", 9L);
        row.put("asset_id", 40L);
        row.put("loan_id", 8L);
        row.put("followup_status", "PENDING");
        row.put("processor_account_id", 13L);
        row.put("reason", "damaged at inspection");
        row.put("version", 0L);
        row.put("linked_repair_id", null);
        row.put("last_repair_id", null);
        row.put("repair_status", null);
        return row;
    }

    /** Frozen pre-JAVA-08 LoanReturnRepairPort.wire projection; reference oracle only. */
    private static Map<String, Object> legacyWire(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, value) ->
                        result.put(
                                key,
                                (key.equals("id") || key.endsWith("_id")) && value != null
                                        ? value.toString()
                                        : value));
        return result;
    }
}
