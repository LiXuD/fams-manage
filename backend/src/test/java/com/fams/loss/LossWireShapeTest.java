package com.fams.loss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.asset.AssetLossPort;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-11a wire equivalence: every typed loss view must serialize exactly like the frozen
 * pre-refactor projections. legacyView is LossService.view verbatim (with legacyWire as its
 * value projection) and legacyRecoveryView is LossService.recoveryView verbatim; the detail
 * and receipt assemblies mirror the pre-refactor get()/rememberRecovery()/recoverToRepair()/
 * registerPhoto() key sets. Comparison round-trips through JSON text so integral node widths
 * normalize and object key order (never stable in the Map.of/Map.ofEntries responses) is
 * ignored, while key presence, explicit nulls and value shapes all count.
 */
class LossWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lossReportMatchesLegacyViewAcrossDriverShapesAndRecoveryKeys() {
        var row = reportRow();
        assertWireEquals(legacyView(row), LossViews.lossReport(JSON, row));

        var localDateTime = new LinkedHashMap<>(row);
        localDateTime.put("last_seen_at", LocalDateTime.of(2026, 11, 10, 2, 0, 0));
        localDateTime.put("confirmed_lost_at", LocalDateTime.of(2026, 11, 12, 3, 0, 0));
        localDateTime.put("recovered_at", null);
        localDateTime.put("recovery_snapshot_json", null);
        assertWireEquals(legacyView(localDateTime), LossViews.lossReport(JSON, localDateTime));

        var sqlDate = new LinkedHashMap<>(row);
        sqlDate.put("next_review_date", java.sql.Date.valueOf("2026-12-11"));
        assertWireEquals(legacyView(sqlDate), LossViews.lossReport(JSON, sqlDate));
        var localDate = new LinkedHashMap<>(row);
        localDate.put("next_review_date", LocalDate.of(2026, 12, 11));
        assertWireEquals(legacyView(localDate), LossViews.lossReport(JSON, localDate));

        // repairId/returnId stay explicit nulls; transferId only appears in the stored snapshot.
        var repaired = new LinkedHashMap<>(row);
        repaired.put("recovery_snapshot_json", "{\"execution\":{},\"repairId\":\"77\"}");
        assertWireEquals(legacyView(repaired), LossViews.lossReport(JSON, repaired));

        var returned = new LinkedHashMap<>(row);
        returned.put("recovery_snapshot_json",
                "{\"execution\":{},\"returnId\":\"88\",\"destination\":\"ASSET_RETURN\"}");
        assertWireEquals(legacyView(returned), LossViews.lossReport(JSON, returned));

        var transferred = new LinkedHashMap<>(row);
        transferred.put("recovery_snapshot_json",
                "{\"execution\":{},\"transferId\":\"99\",\"destination\":\"TRANSFER\"}");
        assertWireEquals(legacyView(transferred), LossViews.lossReport(JSON, transferred));

        var toRepair = new LinkedHashMap<>(row);
        toRepair.put("recovery_snapshot_json", "{\"execution\":{},\"repairId\":\"77\"}");
        assertWireEquals(
                legacyRecoverToRepairAssembly(toRepair, "77"),
                LossViews.withRepairId(LossViews.lossReport(JSON, toRepair), "77"));
    }

    @Test
    void lossEventMatchesLegacyWireProjectionOfHistoryRows() {
        var row = eventRow();
        assertWireEquals(List.of(legacyWire(row)), List.of(LossViews.lossEvent(row)));

        var local = new LinkedHashMap<>(row);
        local.put("occurred_at", LocalDateTime.of(2026, 11, 20, 1, 2, 3));
        assertWireEquals(List.of(legacyWire(local)), List.of(LossViews.lossEvent(local)));
    }

    @Test
    void lossRecoveryMatchesLegacyRecoveryViewPerRoleState() {
        var pending = recoveryRow("PENDING_ADMIN_DECISION");
        var admin = current(Role.ASSET_ADMIN, 11);
        assertWireEquals(
                legacyRecoveryView(admin, pending),
                LossViews.lossRecovery(
                        JSON,
                        pending,
                        new LossViews.RecoveryActions(
                                true,
                                true,
                                false,
                                false)));

        var verifying = recoveryRow("VERIFYING");
        verifying.put("processor_account_id", 13L);
        var operator = current(Role.WAREHOUSE_OPERATOR, 13);
        assertWireEquals(
                legacyRecoveryView(operator, verifying),
                LossViews.lossRecovery(
                        JSON,
                        verifying,
                        new LossViews.RecoveryActions(
                                false,
                                false,
                                false,
                                true)));

        var decided = recoveryRow("COMPLETED");
        decided.put("approval_snapshot_json", "{\"actorAccountId\":\"11\"}");
        decided.put("execution_snapshot_json", "{\"facts\":{}}");
        var creator = current(Role.ASSET_ADMIN, 11);
        assertWireEquals(
                legacyRecoveryView(creator, decided),
                LossViews.lossRecovery(
                        JSON,
                        decided,
                        new LossViews.RecoveryActions(false, false, false, false)));
    }

    @Test
    void lossDetailMatchesLegacyGetAssembly() {
        var row = reportRow();
        row.put("confirmation_snapshot_json", "{\"confirmedAt\":\"2026-11-12T03:00:00Z\"}");
        var base = legacyView(row);
        var event = eventRow();
        var recovery = recoveryRow("VERIFYING");
        recovery.put("processor_account_id", 13L);
        var operator = current(Role.WAREHOUSE_OPERATOR, 13);
        var photo = new LossService.Photo(
                "0f8fad5b-d9cb-469f-a165-70867728950e", "a.jpg", "image/jpeg", 10L, "CLEAN", 1L,
                Timestamp.from(Instant.parse("2026-11-11T02:00:00Z")).toInstant().toString());
        var recoveryAsset = new AssetLossPort.RecoveryView(
                "FA-1", null, 4L, "SEARCHING", "SEARCHING", "9", 1L, "bin-a",
                true, false, false);
        var legacy = new LinkedHashMap<>(base);
        legacy.put("lockVersion", null);
        legacy.put("photosAvailable", true);
        legacy.put("photos", List.of(photo));
        legacy.put("history", List.of(legacyWire(event)));
        legacy.put("historyTotal", 1L);
        legacy.put("confirmation", JSON.readTree(row.get("confirmation_snapshot_json").toString()));
        legacy.put("recoveryAsset", recoveryAsset);
        legacy.put("recoveries", List.of(legacyRecoveryView(operator, recovery)));
        legacy.put("recoveriesTotal", 1L);
        legacy.put("actions", legacyActions());

        var typed = LossViews.lossDetail(
                LossViews.lossReport(JSON, row),
                null,
                true,
                List.of(photo),
                List.of(LossViews.lossEvent(event)),
                1,
                JSON.readTree(row.get("confirmation_snapshot_json").toString()),
                recoveryAsset,
                List.of(LossViews.lossRecovery(JSON, recovery, recoveryActions())),
                1,
                reportActions());
        assertWireEquals(legacy, typed);

        var locked = new LinkedHashMap<>(legacy);
        locked.put("lockVersion", 4L);
        locked.put("confirmation", null);
        locked.put("recoveryAsset", null);
        locked.put("photosAvailable", false);
        locked.put("photos", List.of());
        var untyped = LossViews.lossDetail(
                LossViews.lossReport(JSON, row),
                4L,
                false,
                List.of(),
                List.of(LossViews.lossEvent(event)),
                1,
                null,
                null,
                List.of(LossViews.lossRecovery(JSON, recovery, recoveryActions())),
                1,
                reportActions());
        assertWireEquals(locked, untyped);
    }

    @Test
    void pageAndRecoveryReceiptMatchLegacyAssembly() {
        var row = reportRow();
        var legacyPage = Map.of(
                "items", List.of(legacyView(row)), "total", 1L, "page", 1, "pageSize", 20);
        assertWireEquals(legacyPage,
                new LossViews.Page<>(List.of(LossViews.lossReport(JSON, row)), 1, 1, 20));

        var attempt = recoveryRow("PENDING_ADMIN_DECISION");
        var legacyReceipt = new LinkedHashMap<>(legacyView(row));
        legacyReceipt.put("recoveryId", "5");
        legacyReceipt.put("recoveryVersion", attempt.get("version"));
        legacyReceipt.put("recoveryStatus", attempt.get("recovery_status"));
        assertWireEquals(legacyReceipt,
                LossViews.lossReportWithRecovery(JSON, row, "5",
                        ((Number) attempt.get("version")).longValue(),
                        (String) attempt.get("recovery_status")));

        var recovered = new LinkedHashMap<>(row);
        recovered.put("recovery_snapshot_json", "{\"transferId\":\"99\"}");
        var legacyTransferReceipt = new LinkedHashMap<>(legacyView(recovered));
        legacyTransferReceipt.put("recoveryId", "6");
        legacyTransferReceipt.put("recoveryVersion", 2L);
        legacyTransferReceipt.put("recoveryStatus", "COMPLETED");
        assertWireEquals(legacyTransferReceipt,
                LossViews.lossReportWithRecovery(JSON, recovered, "6", 2, "COMPLETED"));

        var legacyPhoto = Map.<String, Object>of(
                "id", "7",
                "objectId", "0f8fad5b-d9cb-469f-a165-70867728950e",
                "version", 2L);
        assertWireEquals(legacyPhoto,
                new LossViews.PhotoReceipt("7", "0f8fad5b-d9cb-469f-a165-70867728950e", 2));

        var legacyGrant = Map.<String, Object>of(
                "url", "/api/v1/loss-reports/7/photos/x/content?grant=t",
                "expiresAtEpochSecond", 1795200000L);
        assertWireEquals(legacyGrant,
                new LossViews.PhotoGrant("/api/v1/loss-reports/7/photos/x/content?grant=t", 1795200000L));
    }

    @Test
    void idempotencyReplayRoundTripsStoredReplies() {
        assertRoundTrip(LossViews.lossReport(JSON, reportRow()), LossViews.LossReport.class);
        var transferred = new LinkedHashMap<>(reportRow());
        transferred.put("recovery_snapshot_json", "{\"transferId\":\"99\"}");
        assertRoundTrip(LossViews.lossReport(JSON, transferred), LossViews.LossReport.class);

        var attempt = recoveryRow("PENDING_ADMIN_DECISION");
        assertRoundTrip(
                LossViews.lossReportWithRecovery(JSON, reportRow(), "5",
                        ((Number) attempt.get("version")).longValue(),
                        (String) attempt.get("recovery_status")),
                LossViews.LossReportWithRecovery.class);

        assertRoundTrip(
                new LossViews.PhotoReceipt("7", "0f8fad5b-d9cb-469f-a165-70867728950e", 2),
                LossViews.PhotoReceipt.class);
    }

    @Test
    void preRefactorStoredRepliesStillDeserialize() {
        // SUBMIT/DECIDE/... receipts stored the legacyView() map.
        var storedReport = JSON.writeValueAsString(legacyView(reportRow()));
        var report = JSON.readValue(storedReport, LossViews.LossReport.class);
        assertEquals(JSON.readTree(storedReport), JSON.readTree(JSON.writeValueAsString(report)));

        // RECOVERY_*_REQUEST receipts stored the extended view with recovery keys.
        var attempt = recoveryRow("PENDING_ADMIN_DECISION");
        var legacyReceipt = new LinkedHashMap<>(legacyView(reportRow()));
        legacyReceipt.put("recoveryId", "5");
        legacyReceipt.put("recoveryVersion", attempt.get("version"));
        legacyReceipt.put("recoveryStatus", attempt.get("recovery_status"));
        var storedReceipt = JSON.writeValueAsString(legacyReceipt);
        var receipt = JSON.readValue(storedReceipt, LossViews.LossReportWithRecovery.class);
        assertEquals(JSON.readTree(storedReceipt), JSON.readTree(JSON.writeValueAsString(receipt)));

        // RECOVER_TO_REPAIR receipts stored the view with the repairId override.
        var repaired = new LinkedHashMap<>(reportRow());
        repaired.put("recovery_snapshot_json", "{\"repairId\":\"77\"}");
        var storedRepair = JSON.writeValueAsString(legacyRecoverToRepairAssembly(repaired, "77"));
        var repair = JSON.readValue(storedRepair, LossViews.LossReport.class);
        assertEquals(JSON.readTree(storedRepair), JSON.readTree(JSON.writeValueAsString(repair)));

        // PHOTO receipts stored the three-key map.
        var storedPhoto = JSON.writeValueAsString(Map.<String, Object>of(
                "id", "7",
                "objectId", "0f8fad5b-d9cb-469f-a165-70867728950e",
                "version", 2L));
        var photo = JSON.readValue(storedPhoto, LossViews.PhotoReceipt.class);
        assertEquals(JSON.readTree(storedPhoto), JSON.readTree(JSON.writeValueAsString(photo)));
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

    private static Current current(Role role, long id) {
        return new Current(id, "u" + id, 3, 1, EnumSet.of(role));
    }

    private static LossViews.RecoveryActions recoveryActions() {
        return new LossViews.RecoveryActions(false, false, false, true);
    }

    private static LossViews.ReportActions reportActions() {
        return new LossViews.ReportActions(
                false, true, true, true, false, false, false, false, false,
                false, false, false, false, true);
    }

    /** The pre-typing get() actions map: 9 Map.of keys then 5 conditional puts. */
    private static Map<String, Object> legacyActions() {
        var actions = new LinkedHashMap<String, Object>(Map.of(
                "recoverOriginal", false,
                "requestRecovery", true,
                "confirmLost", true,
                "followUp", true,
                "decide", false,
                "cancel", false,
                "addPhoto", false,
                "claim", false,
                "search", false));
        actions.put("recoverFactory", false);
        actions.put("requestFactoryRecovery", false);
        actions.put("recoverInternalRepair", false);
        actions.put("requestInternalRepairRecovery", false);
        actions.put("requestScrap", true);
        return actions;
    }

    private static Map<String, Object> reportRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L);
        row.put("asset_id", 40L);
        row.put("creator_account_id", 11L);
        row.put("using_organization_id", 1L);
        row.put("responsible_employee_id", 3L);
        row.put("shared_responsible_employee_id", null);
        row.put("asset_version", 3L);
        row.put("source_snapshot_json",
                "{\"id\":\"40\",\"assetCode\":\"FA-1\",\"name\":\"laptop\",\"version\":3,"
                        + "\"lifecycle\":\"BORROWED\",\"custodyType\":\"PERSONAL\",\"usingOrganizationId\":\"1\","
                        + "\"responsibleEmployeeId\":\"3\",\"departmentResponsibleEmployeeId\":null,"
                        + "\"warehouseId\":null,\"locationId\":\"9\"}");
        row.put("last_seen_at", Timestamp.from(Instant.parse("2026-11-10T02:00:00Z")));
        row.put("last_location", "desk");
        row.put("circumstances", "gone");
        row.put("report_status", "SEARCHING");
        row.put("approval_snapshot_json", "{\"reason\":\"ok\"}");
        row.put("processor_account_id", 13L);
        row.put("ownership_version", 1L);
        row.put("next_review_date", LocalDate.of(2026, 12, 11));
        row.put("version", 2L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-10T02:10:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-11-12T02:10:00Z")));
        row.put("confirmed_lost_at", Timestamp.from(Instant.parse("2026-11-12T03:00:00Z")));
        row.put("confirmation_snapshot_json", null);
        row.put("last_follow_up_at", null);
        row.put("recovered_at", null);
        row.put("recovery_snapshot_json", null);
        return row;
    }

    private static Map<String, Object> eventRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 50L);
        row.put("report_id", 7L);
        row.put("action", "LOSS_CONFIRMED");
        row.put("before_version", 1L);
        row.put("after_version", 2L);
        row.put("actor_account_id", 13L);
        row.put("actor_username", "ops");
        row.put("actor_role", "WAREHOUSE_OPERATOR");
        row.put("detail_json", "{\"reviewDate\":\"2026-11-12\"}");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-12T03:00:00Z")));
        return row;
    }

    private static Map<String, Object> recoveryRow(String status) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 5L);
        row.put("report_id", 7L);
        row.put("asset_id", 40L);
        row.put("creator_account_id", 11L);
        row.put("asset_version", 3L);
        row.put("request_snapshot_json",
                "{\"assetVersion\":3,\"facts\":{\"condition\":\"INTACT\"},\"destination\":\"ASSET_RETURN\"}");
        row.put("recovery_status", status);
        row.put("approval_snapshot_json", null);
        row.put("processor_account_id", null);
        row.put("ownership_version", 0L);
        row.put("version", 0L);
        row.put("execution_snapshot_json", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-13T02:00:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-11-13T02:00:00Z")));
        row.put("active_guard", 1);
        return row;
    }

    /** Frozen pre-JAVA-11a LossService.view projection; reference oracle only. */
    private static Map<String, Object> legacyView(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_id", "assetId"),
                                Map.entry("creator_account_id", "creatorAccountId"),
                                Map.entry("version", "version"),
                                Map.entry("asset_version", "assetVersion"),
                                Map.entry("report_status", "status"),
                                Map.entry("last_seen_at", "lastSeenAt"),
                                Map.entry("last_location", "lastLocation"),
                                Map.entry("circumstances", "circumstances"),
                                Map.entry("processor_account_id", "processorAccountId"),
                                Map.entry("ownership_version", "ownershipVersion"),
                                Map.entry("recovered_at", "recoveredAt"),
                                Map.entry("confirmed_lost_at", "confirmedLostAt"),
                                Map.entry("last_follow_up_at", "lastFollowUpAt"),
                                Map.entry("next_review_date", "nextReviewDate"))
                        .entrySet()) {
            var value = row.get(field.getKey());
            result.put(
                    field.getValue(),
                    field.getKey().equals("id") || field.getKey().endsWith("_id")
                            ? value == null ? null : value.toString()
                            : legacyWire(value));
        }
        result.put("source", JSON.readTree(row.get("source_snapshot_json").toString()));
        var recovery = row.get("recovery_snapshot_json");
        var repair = recovery == null ? null : JSON.readTree(recovery.toString()).get("repairId");
        result.put("repairId", repair == null ? null : repair.asText());
        var linkedReturn = recovery == null ? null : JSON.readTree(recovery.toString()).get("returnId");
        result.put("returnId", linkedReturn == null ? null : linkedReturn.asText());
        if (recovery != null) {
            var linkedTransfer = JSON.readTree(recovery.toString()).get("transferId");
            if (linkedTransfer != null) {
                result.put("transferId", linkedTransfer.asText());
            }
        }
        return result;
    }

    /** Frozen pre-JAVA-11a recoverToRepair receipt: view() plus the repairId override. */
    private static Map<String, Object> legacyRecoverToRepairAssembly(
            Map<String, Object> row, String repairId) {
        var result = new LinkedHashMap<>(legacyView(row));
        result.put("repairId", repairId);
        return result;
    }

    /** Frozen pre-JAVA-11a wire() value projection; reference oracle only. */
    private static Object legacyWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : legacyWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(LossWireShapeTest::legacyWire).toList();
        }
        if (value instanceof Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return value;
    }

    /** Frozen pre-JAVA-11a LossService.recoveryView projection; reference oracle only. */
    private static Map<String, Object> legacyRecoveryView(Current current, Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String field :
                List.of(
                        "id",
                        "version",
                        "asset_version",
                        "recovery_status",
                        "creator_account_id",
                        "processor_account_id",
                        "ownership_version")) {
            String key =
                    switch (field) {
                        case "asset_version" -> "assetVersion";
                        case "recovery_status" -> "status";
                        case "creator_account_id" -> "creatorAccountId";
                        case "processor_account_id" -> "processorAccountId";
                        case "ownership_version" -> "ownershipVersion";
                        default -> field;
                    };
            Object value = row.get(field);
            result.put(
                    key,
                    field.equals("id") || field.endsWith("_id")
                            ? value == null ? null : value.toString()
                            : value);
        }
        result.put("request", JSON.readTree(row.get("request_snapshot_json").toString()));
        result.put(
                "approval",
                row.get("approval_snapshot_json") == null
                        ? null
                        : JSON.readTree(row.get("approval_snapshot_json").toString()));
        result.put(
                "execution",
                row.get("execution_snapshot_json") == null
                        ? null
                        : JSON.readTree(row.get("execution_snapshot_json").toString()));
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("recovery_status"));
        result.put(
                "actions",
                Map.of(
                        "decide",
                        pending && current.has(Role.ASSET_ADMIN),
                        "cancel",
                        pending
                                && (current.has(Role.ASSET_ADMIN)
                                        || current.id() == ((Number) row.get("creator_account_id")).longValue()),
                        "claim",
                        "WAITING_WAREHOUSE_CLAIM".equals(row.get("recovery_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR),
                        "execute",
                        "VERIFYING".equals(row.get("recovery_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id"))));
        return result;
    }
}
