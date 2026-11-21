package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.business.BorrowRequestDatePort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-05 wire equivalence: the typed BorrowRequestDatePort.eligible envelope must serialize
 * exactly like the frozen pre-refactor chain (raw JDBC rows through the loan clean projection).
 * legacyClean is LoanService.clean verbatim (frozen in JAVA-02 LoanWireShapeTest); it is the
 * reference oracle. Comparison round-trips through JSON text so integral node widths normalize
 * and object key order (never stable in Map.of responses) is ignored, while key presence,
 * explicit nulls and value shapes all count. Migrated from LoanWireShapeTest with unchanged
 * assertion semantics; the port envelope itself is now typed.
 */
class BorrowDateWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void borrowRequestChoiceMatchesLegacyCleanAtThePortBoundary() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 2L);
        row.put("version", 6L);
        row.put("expected_return_date", LocalDate.of(2026, 12, 1));
        row.put("request_status", "WAITING_HANDOVER");
        assertWireEquals(legacyClean(row), RequisitionBorrowDatePort.choice(row));
        var sqlDate = new LinkedHashMap<>(row);
        sqlDate.put("expected_return_date", java.sql.Date.valueOf("2026-12-01"));
        assertWireEquals(legacyClean(sqlDate), RequisitionBorrowDatePort.choice(sqlDate));

        // The port envelope is typed now; its wire shape stays the legacy Map.of assembly.
        var envelope = Map.of("items", List.of(row), "total", 1L, "page", 1, "pageSize", 20);
        var legacy = new LinkedHashMap<>(envelope);
        legacy.put("items", List.of(legacyClean(row)));
        assertWireEquals(legacy, new BorrowRequestDatePort.BorrowRequestChoicePage(
                List.of(RequisitionBorrowDatePort.choice(row)), 1, 1, 20));
    }

    @Test
    void nullDateKeepsExplicitNullAndEmptyPageKeepsAllKeys() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 2L);
        row.put("version", 6L);
        row.put("expected_return_date", null);
        row.put("request_status", "WAITING_HANDOVER");
        assertWireEquals(legacyClean(row), RequisitionBorrowDatePort.choice(row));

        var legacyEmpty = Map.of("items", List.of(), "total", 0L, "page", 1, "pageSize", 20);
        assertWireEquals(legacyEmpty, new BorrowRequestDatePort.BorrowRequestChoicePage(List.of(), 0, 1, 20));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** Frozen pre-JAVA-02 LoanService.clean projection; reference oracle only. */
    private static Map<String, Object> legacyClean(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if ("parameter_snapshot_json".equals(key) || "pending_target".equals(key)) { return; }
            String[] parts = key.split("_");
            StringBuilder name = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) { name.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1)); }
            Object wire = value;
            if (value instanceof java.time.LocalDateTime time) { wire = time.toInstant(java.time.ZoneOffset.UTC).toString(); }
            else if (value instanceof java.sql.Timestamp time) { wire = time.toInstant().toString(); }
            else if (value instanceof java.time.LocalDate || value instanceof java.sql.Date) { wire = value.toString(); }
            else if (value instanceof Number && (key.equals("id") || key.endsWith("_id") || key.equals("proposed_by") || key.equals("decided_by"))) { wire = value.toString(); }
            result.put(name.toString(), wire);
        });
        return result;
    }
}
