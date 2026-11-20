package com.fams.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.business.ReturnLossOpeningPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-02 wire equivalence: every typed view must serialize exactly like the frozen
 * pre-refactor projections. legacyClean is LoanService.clean verbatim, legacyLoanView is
 * LoanService.loanView verbatim and legacyReturnView is AssetReturnService.view verbatim;
 * they are the reference oracles. Comparison round-trips through JSON text so integral
 * node widths normalize and object key order (never stable in Map.of responses) is ignored,
 * while key presence, explicit nulls and value shapes all count.
 */
class LoanWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), ZoneOffset.UTC);
    private static final ReturnLossOpeningPort.Summary SUMMARY =
            new ReturnLossOpeningPort.Summary("5", "CONFIRMED_LOST", true, "9");

    @Test
    void loanMatchesLegacyProjectionAcrossDriverDateShapesAndStates() {
        var row = loanRow();
        assertWireEquals(legacyLoanView(row), LoanViews.loan(JSON, CLOCK, row));

        var sqlDates = new LinkedHashMap<>(row);
        sqlDates.put("borrowed_date", java.sql.Date.valueOf("2026-11-01"));
        sqlDates.put("original_return_date", java.sql.Date.valueOf("2026-11-10"));
        sqlDates.put("expected_return_date", java.sql.Date.valueOf("2026-11-10"));
        assertWireEquals(legacyLoanView(sqlDates), LoanViews.loan(JSON, CLOCK, sqlDates));

        var closed = new LinkedHashMap<>(row);
        closed.put("relation_status", "RETURNED");
        closed.put("closed_at", Timestamp.from(Instant.parse("2026-11-12T06:00:00Z")));
        closed.put("open_asset_id", null);
        assertWireEquals(legacyLoanView(closed), LoanViews.loan(JSON, CLOCK, closed));

        var lost = new LinkedHashMap<>(row);
        lost.put("exception_status", "LOST");
        assertWireEquals(legacyLoanView(lost), LoanViews.loan(JSON, CLOCK, lost));

        var scrapped = new LinkedHashMap<>(row);
        scrapped.put("lifecycle_status", "SCRAPPED");
        assertWireEquals(legacyLoanView(scrapped), LoanViews.loan(JSON, CLOCK, scrapped));

        var initial = new LinkedHashMap<>(row);
        initial.put("source_type", "INITIAL_IMPORT");
        initial.put("source_request_id", null);
        initial.put("source_allocation_id", null);
        initial.put("initial_batch_id", "3f2b8c1e-9d4a-4e7f-8a1b-2c3d4e5f6a7b");
        initial.put("initial_sheet_row", 2);
        assertWireEquals(legacyLoanView(initial), LoanViews.loan(JSON, CLOCK, initial));
    }

    @Test
    void loanDetailAndPageMatchLegacyAssembly() {
        var row = loanRow();
        var extension = extensionRow();
        var reminder = reminderRow();
        var legacy = new LinkedHashMap<>(legacyLoanView(row));
        legacy.put("extensions", List.of(legacyClean(extension)));
        legacy.put("reminders", List.of(legacyClean(reminder)));
        var typed = LoanViews.loanDetail(JSON, CLOCK, row,
                List.of(LoanViews.extension(extension)), List.of(LoanViews.reminder(reminder)));
        assertWireEquals(legacy, typed);

        var legacyPage = Map.of("items", List.of(legacyLoanView(row)), "total", 1L, "page", 1, "pageSize", 20);
        assertWireEquals(legacyPage, new LoanViews.Page<>(List.of(LoanViews.loan(JSON, CLOCK, row)), 1, 1, 20));
    }

    @Test
    void extensionMatchesLegacyProjectionPendingDecidedAndBeforeReceipt() {
        var pending = extensionRow();
        assertWireEquals(legacyClean(pending), LoanViews.extension(pending));

        var decided = new LinkedHashMap<>(pending);
        decided.put("extension_status", "APPROVED");
        decided.put("decided_by", 12L);
        decided.put("decision_reason", "ok");
        decided.put("decided_business_date", LocalDate.of(2026, 11, 20));
        decided.put("overdue_from", LocalDate.of(2026, 11, 11));
        decided.put("overdue_through", LocalDate.of(2026, 11, 20));
        decided.put("decided_at", Timestamp.from(Instant.parse("2026-11-20T01:00:00Z")));
        decided.put("version", 1L);
        decided.put("pending_target", null);
        assertWireEquals(legacyClean(decided), LoanViews.extension(decided));

        var beforeReceipt = new LinkedHashMap<>(pending);
        beforeReceipt.put("loan_id", null);
        beforeReceipt.put("request_id", 2L);
        beforeReceipt.put("pending_target", "REQUEST:2");
        assertWireEquals(legacyClean(beforeReceipt), LoanViews.extension(beforeReceipt));

        var sqlDates = new LinkedHashMap<>(pending);
        sqlDates.put("original_due_date", java.sql.Date.valueOf("2026-11-10"));
        sqlDates.put("requested_due_date", java.sql.Date.valueOf("2026-12-10"));
        assertWireEquals(legacyClean(sqlDates), LoanViews.extension(sqlDates));

        var legacyPage = Map.of("items", List.of(legacyClean(pending)), "total", 1L, "page", 1, "pageSize", 20);
        assertWireEquals(legacyPage, new LoanViews.Page<>(List.of(LoanViews.extension(pending)), 1, 1, 20));
    }

    @Test
    void reminderMatchesLegacyProjection() {
        var row = reminderRow();
        assertWireEquals(legacyClean(row), LoanViews.reminder(row));
        var withoutOverdue = new LinkedHashMap<>(row);
        withoutOverdue.put("overdue_from", null);
        assertWireEquals(legacyClean(withoutOverdue), LoanViews.reminder(withoutOverdue));
    }

    @Test
    void borrowRequestChoiceMatchesLegacyCleanAtThePortBoundary() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 2L);
        row.put("version", 6L);
        row.put("expected_return_date", LocalDate.of(2026, 12, 1));
        row.put("request_status", "WAITING_HANDOVER");
        assertWireEquals(legacyClean(row), LoanViews.borrowRequestChoice(row));
        var sqlDate = new LinkedHashMap<>(row);
        sqlDate.put("expected_return_date", java.sql.Date.valueOf("2026-12-01"));
        assertWireEquals(legacyClean(sqlDate), LoanViews.borrowRequestChoice(sqlDate));

        // The port envelope keeps its Map shape; only the loan-side exit is typed.
        var envelope = Map.of("items", List.of(row), "total", 1L, "page", 1, "pageSize", 20);
        var legacy = new LinkedHashMap<>(envelope);
        legacy.put("items", List.of(legacyClean(row)));
        assertWireEquals(legacy, new LoanViews.Page<>(List.of(LoanViews.borrowRequestChoice(row)), 1, 1, 20));
    }

    @Test
    void assetReturnMatchesLegacyProjectionWithConditionalLossKeys() {
        var row = returnRow();
        assertWireEquals(legacyReturnView(row), LoanViews.assetReturn(JSON, row));

        var lossRecovery = new LinkedHashMap<>(row);
        lossRecovery.put("parameter_snapshot_json",
                "{\"timezone\":\"Asia/Shanghai\",\"lossRecovery\":{\"reportId\":\"5\",\"lossItemId\":\"5\","
                        + "\"lockVersion\":2,\"evidenceJson\":\"{}\"}}");
        assertWireEquals(legacyReturnView(lossRecovery), LoanViews.assetReturn(JSON, lossRecovery));

        var completed = new LinkedHashMap<>(row);
        completed.put("return_status", "COMPLETED");
        completed.put("decided_by", 12L);
        completed.put("decision_reason", "ok");
        completed.put("processor_account_id", 13L);
        completed.put("inspection_result", "NORMAL");
        completed.put("inspection_reason", "fine");
        completed.put("warehouse_id", 2L);
        completed.put("location_id", 9L);
        completed.put("decided_at", Timestamp.from(Instant.parse("2026-11-11T02:00:00Z")));
        completed.put("completed_at", LocalDateTime.of(2026, 11, 12, 3, 0, 0));
        completed.put("version", 5L);
        completed.put("ownership_version", 1L);
        completed.put("active_asset_id", null);
        assertWireEquals(legacyReturnView(completed), LoanViews.assetReturn(JSON, completed));

        // returnLossReport is only present when a followup summary exists.
        assertWireEquals(legacyReturnView(row), LoanViews.assetReturn(JSON, row, null));
        var withSummary = new LinkedHashMap<>(legacyReturnView(row));
        withSummary.put("returnLossReport", SUMMARY);
        assertWireEquals(withSummary, LoanViews.assetReturn(JSON, row, SUMMARY));

        var legacyPage = Map.of("items", List.of(legacyReturnView(row)), "total", 1L, "page", 1, "pageSize", 20);
        assertWireEquals(legacyPage, new LoanViews.Page<>(List.of(LoanViews.assetReturn(JSON, row)), 1, 1, 20));
    }

    @Test
    void assetReturnDetailMatchesLegacyAssembly() {
        var row = returnRow();
        var asset = assetRow();
        var repair = followupRow("REPAIR");
        var lost = followupRow("LOST_REPORT");
        var legacy = new LinkedHashMap<>(legacyReturnView(row));
        legacy.put("asset", legacyReturnView(asset));
        legacy.put("lockVersion", null);
        legacy.put("canReportMissing", false);
        legacy.put("followups", List.of(legacyReturnView(repair), legacyLostFollowup(lost, null, null, null, null)));
        var typed = LoanViews.assetReturnDetail(JSON, row, LoanViews.returnAsset(asset), null, false,
                List.of(LoanViews.repairFollowup(repair), LoanViews.lostReportFollowup(lost, null, null, null, null)));
        assertWireEquals(legacy, typed);

        var locked = new LinkedHashMap<>(legacyReturnView(row));
        locked.put("asset", legacyReturnView(asset));
        locked.put("lockVersion", 4L);
        locked.put("canReportMissing", true);
        locked.put("followups", List.of(legacyLostFollowup(lost, SUMMARY, "COMPLETED", 7L, true)));
        assertWireEquals(locked, LoanViews.assetReturnDetail(JSON, row, LoanViews.returnAsset(asset), 4L, true,
                List.of(LoanViews.lostReportFollowup(lost, SUMMARY, "COMPLETED", 7L, true))));
    }

    @Test
    void followupsMatchLegacyProjectionPerBusinessType() {
        var repair = followupRow("REPAIR");
        assertWireEquals(legacyReturnView(repair), LoanViews.repairFollowup(repair));
        var linkedRepair = new LinkedHashMap<>(repair);
        linkedRepair.put("linked_repair_id", 77L);
        linkedRepair.put("last_repair_id", 76L);
        linkedRepair.put("repair_status", "PROCESSING");
        assertWireEquals(legacyReturnView(linkedRepair), LoanViews.repairFollowup(linkedRepair));

        var lost = followupRow("LOST_REPORT");
        // lossReport is always present for LOST_REPORT, even as explicit null.
        assertWireEquals(legacyLostFollowup(lost, null, null, null, null),
                LoanViews.lostReportFollowup(lost, null, null, null, null));
        assertWireEquals(legacyLostFollowup(lost, SUMMARY, null, null, null),
                LoanViews.lostReportFollowup(lost, SUMMARY, null, null, null));
        assertWireEquals(legacyLostFollowup(lost, SUMMARY, "COMPLETED", 7L, true),
                LoanViews.lostReportFollowup(lost, SUMMARY, "COMPLETED", 7L, true));
    }

    @Test
    void catalogAndEligibleChoicesMatchLegacyProjection() {
        var warehouse = new LinkedHashMap<String, Object>();
        warehouse.put("id", 2L);
        warehouse.put("name", "main");
        warehouse.put("version", 1L);
        assertWireEquals(legacyReturnView(warehouse), LoanViews.returnCatalogChoice("warehouse", warehouse));

        var location = new LinkedHashMap<String, Object>();
        location.put("id", 9L);
        location.put("name", "bin-a");
        location.put("version", 1L);
        location.put("warehouse_id", 2L);
        assertWireEquals(legacyReturnView(location), LoanViews.returnCatalogChoice("location", location));
        var orphan = new LinkedHashMap<>(location);
        orphan.put("warehouse_id", null);
        assertWireEquals(legacyReturnView(orphan), LoanViews.returnCatalogChoice("location", orphan));

        var legacyCatalog = Map.of("items", List.of(legacyReturnView(warehouse)), "page", 1, "pageSize", 200);
        assertWireEquals(legacyCatalog, new LoanViews.ReturnCatalogPage(
                List.of(LoanViews.returnCatalogChoice("warehouse", warehouse)), 1, 200));

        var choice = new LinkedHashMap<String, Object>();
        choice.put("id", 40L);
        choice.put("asset_code", "FA-1");
        choice.put("name", "laptop");
        choice.put("version", 3L);
        choice.put("lifecycle_status", "BORROWED");
        assertWireEquals(legacyReturnView(choice), LoanViews.returnAssetChoice(choice));
        var legacyEligible = Map.of("items", List.of(legacyReturnView(choice)), "total", 1L, "page", 1, "pageSize", 20);
        assertWireEquals(legacyEligible,
                new LoanViews.Page<>(List.of(LoanViews.returnAssetChoice(choice)), 1, 1, 20));

        assertWireEquals(legacyReturnView(assetRow()), LoanViews.returnAsset(assetRow()));
    }

    @Test
    void reassignmentSnapshotMatchesLegacyAssembly() {
        var row = returnRow();
        row.put("return_status", "WAREHOUSE_PROCESSING");
        row.put("processor_account_id", 13L);
        row.put("version", 6L);
        row.put("ownership_version", 1L);
        Map<String, Object> check = Map.of("id", "9", "version", 6L, "status", "WAREHOUSE_PROCESSING",
                "assetVersion", 3L, "lockVersion", 4L, "assetCode", "FA-1");
        var operator = new LinkedHashMap<String, Object>();
        operator.put("id", 12L);
        operator.put("name", "ops");
        var legacy = new LinkedHashMap<String, Object>();
        legacy.put("id", "9");
        legacy.put("version", 6L);
        legacy.put("ownershipVersion", 1L);
        legacy.put("processorAccountId", "13");
        legacy.put("items", List.of(check));
        legacy.put("operators", List.of(Map.of("id", "12", "name", "ops")));
        var items = List.of(LoanViews.reassignmentItem(check));
        var operators = List.of(LoanViews.operator(operator));
        assertWireEquals(legacy, LoanViews.reassignmentSnapshot(9, row, items, operators, null));
        legacy.put("unavailableReason", "sick leave");
        assertWireEquals(legacy, LoanViews.reassignmentSnapshot(9, row, items, operators, "sick leave"));
    }

    @Test
    void idempotencyReplayRoundTripsStoredReplies() {
        var extension = LoanViews.extension(extensionRow());
        assertRoundTrip(extension, LoanViews.Extension.class);

        var lossRecovery = new LinkedHashMap<>(returnRow());
        lossRecovery.put("parameter_snapshot_json",
                "{\"timezone\":\"Asia/Shanghai\",\"lossRecovery\":{\"reportId\":\"5\",\"lossItemId\":\"5\","
                        + "\"lockVersion\":2,\"evidenceJson\":\"{}\"}}");
        assertRoundTrip(LoanViews.assetReturn(JSON, lossRecovery, SUMMARY), LoanViews.AssetReturn.class);

        var lost = followupRow("LOST_REPORT");
        var detail = LoanViews.assetReturnDetail(JSON, returnRow(), LoanViews.returnAsset(assetRow()), 4L, true,
                List.of(LoanViews.repairFollowup(followupRow("REPAIR")),
                        LoanViews.lostReportFollowup(lost, SUMMARY, "COMPLETED", 7L, true),
                        LoanViews.lostReportFollowup(lost, null, null, null, null)));
        assertRoundTrip(detail, LoanViews.AssetReturnDetail.class);
    }

    @Test
    void preRefactorStoredRepliesStillDeserialize() {
        // Loan propose/decide stored the clean() extension row.
        var storedExtension = JSON.writeValueAsString(legacyClean(extensionRow()));
        var extension = JSON.readValue(storedExtension, LoanViews.Extension.class);
        assertEquals(JSON.readTree(storedExtension), JSON.readTree(JSON.writeValueAsString(extension)));

        // Return finish stored the view() row, including the conditional lossRecovery key.
        var lossRecovery = new LinkedHashMap<>(returnRow());
        lossRecovery.put("parameter_snapshot_json",
                "{\"timezone\":\"Asia/Shanghai\",\"lossRecovery\":{\"reportId\":\"5\",\"lossItemId\":\"5\","
                        + "\"lockVersion\":2,\"evidenceJson\":\"{}\"}}");
        var storedReturn = JSON.writeValueAsString(legacyReturnView(lossRecovery));
        var assetReturn = JSON.readValue(storedReturn, LoanViews.AssetReturn.class);
        assertEquals(JSON.readTree(storedReturn), JSON.readTree(JSON.writeValueAsString(assetReturn)));

        // Reassign/unavailable stored the full get() assembly with mixed followup shapes.
        var lost = followupRow("LOST_REPORT");
        var legacy = new LinkedHashMap<>(legacyReturnView(returnRow()));
        legacy.put("asset", legacyReturnView(assetRow()));
        legacy.put("lockVersion", 4L);
        legacy.put("canReportMissing", true);
        legacy.put("followups", List.of(legacyReturnView(followupRow("REPAIR")),
                legacyLostFollowup(lost, SUMMARY, "COMPLETED", 7L, true), legacyLostFollowup(lost, null, null, null, null)));
        var storedDetail = JSON.writeValueAsString(legacy);
        var detail = JSON.readValue(storedDetail, LoanViews.AssetReturnDetail.class);
        assertEquals(JSON.readTree(storedDetail), JSON.readTree(JSON.writeValueAsString(detail)));
    }

    private static void assertRoundTrip(Object view, Class<?> type) {
        var stored = JSON.writeValueAsString(view);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static Map<String, Object> loanRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 8L);
        row.put("asset_id", 40L);
        row.put("borrower_employee_id", 3L);
        row.put("using_organization_id", 1L);
        row.put("source_type", "RECEIPT");
        row.put("source_request_id", 2L);
        row.put("source_allocation_id", 6L);
        row.put("initial_batch_id", null);
        row.put("initial_sheet_row", null);
        row.put("borrowed_date", LocalDate.of(2026, 11, 1));
        row.put("original_return_date", LocalDate.of(2026, 11, 10));
        row.put("expected_return_date", LocalDate.of(2026, 11, 10));
        row.put("parameter_snapshot_json", "{\"timezone\":\"Asia/Shanghai\"}");
        row.put("relation_status", "OPEN");
        row.put("closed_at", null);
        row.put("version", 0L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-01T02:00:00Z")));
        row.put("updated_at", LocalDateTime.of(2026, 11, 1, 3, 0, 0));
        row.put("open_asset_id", 40L);
        row.put("asset_code", "FA-1");
        row.put("name", "laptop");
        row.put("lifecycle_status", "BORROWED");
        row.put("exception_status", "NORMAL");
        row.put("current_asset_version", 3L);
        return row;
    }

    private static Map<String, Object> extensionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 30L);
        row.put("loan_id", 8L);
        row.put("request_id", null);
        row.put("target_version", 0L);
        row.put("original_due_date", LocalDate.of(2026, 11, 10));
        row.put("requested_due_date", LocalDate.of(2026, 12, 10));
        row.put("reason", "project extended");
        row.put("proposed_by", 11L);
        row.put("extension_status", "PENDING");
        row.put("decided_by", null);
        row.put("decision_reason", null);
        row.put("decided_business_date", null);
        row.put("overdue_from", null);
        row.put("overdue_through", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-15T02:00:00Z")));
        row.put("decided_at", null);
        row.put("version", 0L);
        row.put("pending_target", "LOAN:8");
        return row;
    }

    private static Map<String, Object> reminderRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 31L);
        row.put("loan_id", 8L);
        row.put("expected_return_date", LocalDate.of(2026, 11, 10));
        row.put("reminder_business_date", LocalDate.of(2026, 11, 20));
        row.put("reminder_type", "OVERDUE");
        row.put("overdue_from", LocalDate.of(2026, 11, 11));
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-20T00:00:00Z")));
        return row;
    }

    private static Map<String, Object> returnRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 9L);
        row.put("asset_id", 40L);
        row.put("loan_id", 8L);
        row.put("asset_version", 3L);
        row.put("responsible_employee_id", 3L);
        row.put("using_organization_id", 1L);
        row.put("proposed_by", 11L);
        row.put("reason", "project done");
        row.put("return_status", "PENDING_APPROVAL");
        row.put("decided_by", null);
        row.put("decision_reason", null);
        row.put("processor_account_id", null);
        row.put("inspection_result", null);
        row.put("inspection_reason", null);
        row.put("warehouse_id", null);
        row.put("location_id", null);
        row.put("parameter_snapshot_json", "{\"timezone\":\"Asia/Shanghai\"}");
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-10T02:00:00Z")));
        row.put("decided_at", null);
        row.put("completed_at", null);
        row.put("version", 0L);
        row.put("active_asset_id", 40L);
        row.put("ownership_version", 0L);
        return row;
    }

    private static Map<String, Object> followupRow(String businessType) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 32L);
        row.put("return_id", 9L);
        row.put("asset_id", 40L);
        row.put("loan_id", 8L);
        row.put("business_type", businessType);
        row.put("followup_status", "PENDING");
        row.put("processor_account_id", 13L);
        row.put("reason", "missing at acceptance");
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-12T02:00:00Z")));
        row.put("version", 0L);
        row.put("linked_repair_id", null);
        row.put("last_repair_id", null);
        row.put("repair_status", null);
        return row;
    }

    private static Map<String, Object> assetRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 40L);
        row.put("asset_code", "FA-1");
        row.put("name", "laptop");
        row.put("version", 3L);
        row.put("lifecycle_status", "BORROWED");
        row.put("exception_status", "NORMAL");
        return row;
    }

    /** Legacy followupView assembly for LOST_REPORT rows; conditional keys put only when present. */
    private static Map<String, Object> legacyLostFollowup(
            Map<String, Object> row, ReturnLossOpeningPort.Summary report, String recoveryReturnStatus,
            Long missingLossLockVersion, Boolean canRetryMissingLoss) {
        var result = new LinkedHashMap<>(legacyReturnView(row));
        result.put("lossReport", report);
        if (recoveryReturnStatus != null) {
            result.put("recoveryReturnStatus", recoveryReturnStatus);
        }
        if (missingLossLockVersion != null) {
            result.put("missingLossLockVersion", missingLossLockVersion);
            result.put("canRetryMissingLoss", canRetryMissingLoss);
        }
        return result;
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
            else if (value instanceof Timestamp time) { wire = time.toInstant().toString(); }
            else if (value instanceof java.time.LocalDate || value instanceof java.sql.Date) { wire = value.toString(); }
            else if (value instanceof Number && (key.equals("id") || key.endsWith("_id") || key.equals("proposed_by") || key.equals("decided_by"))) { wire = value.toString(); }
            result.put(name.toString(), wire);
        });
        return result;
    }

    /** Frozen pre-JAVA-02 LoanService.loanView projection; reference oracle only. */
    private static Map<String, Object> legacyLoanView(Map<String, Object> loan) {
        var result = new LinkedHashMap<>(legacyClean(loan));
        boolean open = "OPEN".equals(loan.get("relation_status")) && !"SCRAPPED".equals(loan.get("lifecycle_status"));
        boolean lost = "LOST".equals(loan.get("exception_status"));
        result.put("overdue", open && !lost && legacyDate(loan, "expected_return_date").isBefore(legacyToday(loan)));
        result.put("lostLiability", open && lost);
        return result;
    }

    private static LocalDate legacyToday(Map<String, Object> row) {
        var zone = ZoneId.of(JSON.readTree(row.get("parameter_snapshot_json").toString()).get("timezone").asText());
        return CLOCK.instant().atZone(zone).toLocalDate();
    }

    private static LocalDate legacyDate(Map<String, Object> row, String key) { return LocalDate.parse(row.get(key).toString()); }

    /** Frozen pre-JAVA-02 AssetReturnService.view projection; reference oracle only. */
    private static Map<String, Object> legacyReturnView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (key.equals("parameter_snapshot_json") || key.equals("active_asset_id")) { return; }
            String[] words = key.split("_");
            StringBuilder field = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) { field.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1)); }
            Object wire = value;
            if (value instanceof java.time.LocalDateTime date) { wire = date.toInstant(java.time.ZoneOffset.UTC).toString(); }
            else if (value instanceof Timestamp date) { wire = date.toInstant().toString(); }
            else if (value instanceof Number && (key.equals("id") || key.endsWith("_id") || key.endsWith("_by"))) { wire = value.toString(); }
            result.put(field.toString(), wire);
        });
        if (row.containsKey("parameter_snapshot_json") && legacyLinkedLoss(row)) {
            result.put("lossRecovery", JSON.readTree(row.get("parameter_snapshot_json").toString()).get("lossRecovery"));
        }
        return result;
    }

    private static boolean legacyLinkedLoss(Map<String, Object> row) {
        return JSON.readTree(row.get("parameter_snapshot_json").toString()).has("lossRecovery");
    }
}
