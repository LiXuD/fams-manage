package com.fams.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.attachment.AttachmentEvidencePort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-17c wire equivalence for the inventory finding/review/photo HTTP exits. Every {@code
 * legacyXxx} helper below is the pre-refactor Map assembly copied verbatim: {@code
 * InventoryFindingService.wire} (flat), {@code InventoryReviewService.wire} (recursive), the
 * finding-target/finding-detail/recollection-page assemblies, the review detail item document, the
 * normal-acceptance response and its accepted/conflict lines, the two upload receipt shapes, the
 * pre-refactor {@code InventoryPhotoController.list} item conversion and both download-grant maps.
 *
 * <p>Comparison round-trips through JSON text, so integral node widths normalise and object key
 * order (the legacy assemblies were HashMap/{@code Map.of}) is ignored, while key presence,
 * explicit nulls and value shapes all count. Three red lines get dedicated assertions: the two
 * projections of the same observation row differ inside {@code facts_json} (flat findings vs
 * recursive reviews), the DATETIME driver shapes (Timestamp/{@code LocalDateTime}) normalise to the
 * same instant text while the pass-through photo envelope keeps the raw instance, and the empty
 * finding subject stays a zero-key object instead of an all-null row.
 */
class InventoryFindingWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant INSTANT = Instant.parse("2026-11-01T02:00:00.123Z");
    private static final String OBJECT_ID = "6d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";
    private static final String EVENT_ID = "3f2b1c0d-9e8f-4a5b-8c7d-6e5f4a3b2c1d";
    private static final String DEVICE_ID = "aaaabbbb-cccc-dddd-eeee-ffff00001111";

    /** Stored facts document: camel {@code *Id} keys are numbers before the review wire runs. */
    private static final String FACTS_JSON =
            "{\"eventId\":\""
                    + EVENT_ID
                    + "\",\"itemId\":21,\"assignmentVersion\":2,\"recheckGeneration\":0,"
                    + "\"assetVersion\":5,\"deviceId\":\""
                    + DEVICE_ID
                    + "\",\"source\":\"SCAN\",\"assetCode\":\"FA-0001\",\"manualReason\":null,"
                    + "\"actual\":{\"locationId\":31,\"locationHint\":\"A-1\",\"custodyType\":"
                    + "\"STORAGE\",\"responsibleEmployeeId\":41,\"responsibilityHint\":null,"
                    + "\"condition\":\"GOOD\",\"unknownReason\":null,\"description\":null},"
                    + "\"rawObservedAt\":\"2026-11-01T02:00:00Z\",\"firstPersistedAt\":"
                    + "\"2026-11-01T02:00:01Z\",\"expiresAt\":\"2026-11-01T03:00:00Z\","
                    + "\"calibrationId\":null,\"clockAnomaly\":false,\"photoIds\":[\""
                    + OBJECT_ID
                    + "\"]}";

    private static final String RECEIPT_JSON =
            "{\"acceptedAt\":\"2026-11-01T02:00:02Z\",\"receiptId\":9,\"operatorName\":\"wan\"}";

    /** Asset snapshot of the review context; the recursive wire rewrites its camel id keys. */
    private static final String SNAPSHOT_JSON =
            "{\"assetCode\":\"FA-0001\",\"locationId\":31,\"custodyType\":\"STORAGE\","
                    + "\"responsibleEmployeeId\":41,\"departmentResponsibleEmployeeId\":42,"
                    + "\"usingOrganizationId\":51,\"owningOrganizationId\":52,\"lifecycle\":\"IN_USE\","
                    + "\"exception\":\"NORMAL\",\"warehouseId\":61,\"version\":5}";

    // ==================== oracles (verbatim pre-JAVA-17c assemblies) ====================

    /** Pre-refactor {@code InventoryFindingService.wire(Map)}: flat, facts/receipt only. */
    private static Map<String, Object> legacyFindingWire(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, value) ->
                        result.put(
                                key,
                                value != null
                                                && (key.equals("facts_json")
                                                        || key.equals("receipt_json"))
                                        ? JSON.readValue(value.toString(), Object.class)
                                        : value instanceof LocalDateTime time
                                                ? time.toInstant(java.time.ZoneOffset.UTC).toString()
                                                : value instanceof Timestamp time
                                                        ? time.toInstant().toString()
                                                        : value instanceof Number
                                                                        && (key.equals("id")
                                                                                || key.endsWith(
                                                                                        "_id"))
                                                                ? value.toString()
                                                                : value));
        return result;
    }

    /** Pre-refactor {@code InventoryReviewService.wire(Object)}: recursive over maps and lists. */
    private static Object legacyReviewWire(Object value) {
        if (value instanceof Map<?, ?> row) {
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach(
                    (key, child) -> {
                        String name = key.toString();
                        Object data =
                                name.endsWith("_json") && child instanceof String text
                                        ? JSON.readValue(text, Object.class)
                                        : child;
                        result.put(
                                name,
                                data instanceof Number
                                                && (name.equals("id")
                                                        || name.endsWith("_id")
                                                        || name.endsWith("Id"))
                                        ? data.toString()
                                        : legacyReviewWire(data));
                    });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InventoryFindingWireShapeTest::legacyReviewWire).toList();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        return value;
    }

    private static Map<String, Object> legacyTargetsEnvelope(List<Map<String, Object>> items) {
        return Map.of("items", items);
    }

    private static Map<String, Object> legacyFormalInboundTarget() {
        return Map.of(
                "kind",
                "FORMAL_INBOUND",
                "assetId",
                "88",
                "assetCode",
                "FA-0001",
                "ownerVersion",
                5L,
                "inboundReceiptId",
                "77",
                "receiptNumber",
                "IB-2026-0001",
                "status",
                "ACCEPTED");
    }

    private static Map<String, Object> legacyDuplicateAssetTarget() {
        return Map.of(
                "kind",
                "DUPLICATE_ASSET",
                "assetId",
                "88",
                "assetCode",
                "FA-0001",
                "assetName",
                "激光打印机",
                "ownerVersion",
                5L);
    }

    /** Pre-refactor finding detail: seven {@code Map.of} keys, subject possibly {@code Map.of()}. */
    private static Map<String, Object> legacyFindingDetail(
            List<Map<String, Object>> timeSubject,
            List<Map<String, Object>> recollections,
            Object taskVersion,
            Map<String, Object> observation,
            Map<String, Object> subject,
            List<Map<String, Object>> history,
            Object historyTotal) {
        return Map.of(
                "timeSubject", timeSubject,
                "recollections", recollections,
                "taskVersion", taskVersion,
                "observation", legacyFindingWire(observation),
                "subject", subject.isEmpty() ? Map.of() : legacyFindingWire(subject),
                "history", history,
                "historyTotal", historyTotal);
    }

    /** Pre-refactor recollection-targets page. */
    private static Map<String, Object> legacyObservationPage(
            List<Map<String, Object>> rows, long total, int page, int size, boolean recursive) {
        return Map.of(
                "items",
                        rows.stream()
                                .map(row -> recursive ? legacyReviewWire(row) : legacyFindingWire(row))
                                .toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    /** Pre-refactor decide result: a LinkedHashMap in exactly this insertion order. */
    private static Map<String, Object> legacyDecisionResult(
            long taskId, long observationId, long version, long subjectVersion, String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("observationId", Long.toString(observationId));
        result.put("version", version);
        result.put("subjectVersion", subjectVersion);
        result.put("status", status);
        return result;
    }

    /** Pre-refactor recollect result: eight {@code Map.of} keys. */
    private static Map<String, Object> legacyRecollectionResult(
            long taskId, long observationId, long version, long subjectVersion, long counter) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "observationId",
                Long.toString(observationId),
                "version",
                version,
                "subjectVersion",
                subjectVersion,
                "status",
                "CLOSED",
                "method",
                "RECOLLECTED",
                "counterObservationId",
                Long.toString(counter),
                "originalTimeConfidence",
                "UNVERIFIED");
    }

    /** Pre-refactor review detail item: a LinkedHashMap in exactly this insertion order. */
    private static Map<String, Object> legacyReviewItem(
            Map<String, Object> item,
            Object current,
            Object movementEvents,
            Map<String, Object> candidate,
            Map<String, Object> review,
            boolean canAcceptNormal,
            String problem) {
        var result = new LinkedHashMap<String, Object>();
        result.put("item", legacyReviewWire(item));
        result.put("current", current);
        result.put("movementEvents", movementEvents);
        result.put("candidate", candidate == null ? null : legacyReviewWire(candidate));
        result.put("review", review == null ? Map.of("version", 0) : legacyReviewWire(review));
        result.put("canAcceptNormal", canAcceptNormal);
        result.put("blockedReason", problem);
        return result;
    }

    /** Pre-refactor review detail document. */
    private static Map<String, Object> legacyReviewDetail(
            Map<String, Object> task,
            List<Map<String, Object>> items,
            long total,
            int page,
            int size,
            long reviewedCount) {
        return Map.of(
                "task",
                legacyReviewWire(task),
                "items",
                items,
                "total",
                total,
                "page",
                page,
                "pageSize",
                size,
                "reviewedCount",
                reviewedCount);
    }

    /** Pre-refactor normal-acceptance response and its two item shapes. */
    private static Map<String, Object> legacyAcceptResponse(
            long taskId,
            long afterVersion,
            int accepted,
            int conflicts,
            List<Map<String, Object>> outcomes,
            long reviewedCount) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "version",
                afterVersion,
                "accepted",
                accepted,
                "conflicts",
                conflicts,
                "items",
                outcomes,
                "reviewedCount",
                reviewedCount);
    }

    private static Map<String, Object> legacyAcceptedLine(long id, Map<String, Object> review) {
        return Map.of(
                "itemId",
                Long.toString(id),
                "status",
                "ACCEPTED",
                "review",
                legacyReviewWire(review));
    }

    private static Map<String, Object> legacyConflictLine(
            long id, String code, Map<String, Object> current) {
        return Map.of(
                "itemId",
                Long.toString(id),
                "status",
                "CONFLICT",
                "code",
                code,
                "current",
                current == null ? Map.of("version", 0) : legacyReviewWire(current));
    }

    /** Pre-refactor upload receipt of both branches (two {@code Map.of} keys). */
    private static Map<String, Object> legacyPhotoReceipt(String objectId) {
        return Map.<String, Object>of("objectId", objectId, "photoId", EVENT_ID);
    }

    /** Pre-refactor {@code InventoryPhotoController.list} item conversion, verbatim. */
    private static Map<String, Object> legacyInventoryPhotoItem(Map<String, Object> row) {
        var item = new LinkedHashMap<String, Object>(row);
        Object created = item.get("createdAt");
        if (created instanceof Timestamp time) {
            item.put("createdAt", time.toInstant().toString());
        } else if (created instanceof LocalDateTime time) {
            item.put("createdAt", time.toInstant(java.time.ZoneOffset.UTC).toString());
        }
        return item;
    }

    /** Pre-refactor download grant of both photo controllers (two {@code Map.of} keys). */
    private static Map<String, Object> legacyGrant(String url, long expiresAtEpochSecond) {
        return Map.of("url", url, "expiresAtEpochSecond", expiresAtEpochSecond);
    }

    // ==================== fixtures ====================

    private static Map<String, Object> observationRow(Object receivedAt, String outcome) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 91L);
        row.put("task_id", 7L);
        row.put("client_event_id", EVENT_ID);
        row.put("actor_account_id", 11L);
        row.put("binding_id", null);
        row.put("device_id", DEVICE_ID);
        row.put("item_id", 21L);
        row.put("assignment_version", 2L);
        row.put("recheck_generation", 0);
        row.put("snapshot_asset_version", 5L);
        row.put("source", "SCAN");
        row.put("asset_code", "FA-0001");
        row.put("raw_observed_at", Timestamp.from(INSTANT));
        row.put("first_persisted_at", Timestamp.from(INSTANT));
        row.put("client_expires_at", Timestamp.from(INSTANT));
        row.put("calibrated_observed_at", null);
        row.put("calibrated_persisted_at", null);
        row.put("calibration_id", null);
        row.put("received_at", receivedAt);
        row.put("facts_json", FACTS_JSON);
        row.put("payload_sha256", "a".repeat(64));
        row.put("fact_sha256", "b".repeat(64));
        row.put("outcome", outcome);
        row.put("receipt_json", RECEIPT_JSON);
        return row;
    }

    private static Map<String, Object> subjectRow(String dimension, boolean reviewed) {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 91L);
        row.put("dimension", dimension);
        row.put("task_id", 7L);
        row.put("item_id", null);
        row.put("recheck_generation", 0L);
        row.put("subject_status", reviewed ? "CLOSED" : "OPEN");
        row.put("method", reviewed ? "LINK_INBOUND" : null);
        row.put("reason", reviewed ? "重复卡片" : null);
        row.put("evidence", reviewed ? "inventory-inbound:77" : null);
        row.put("reviewer_account_id", reviewed ? 11L : null);
        row.put("reviewer_username", reviewed ? "admin" : null);
        row.put("reviewed_at", reviewed ? Timestamp.from(INSTANT) : null);
        row.put("version", reviewed ? 2L : 0L);
        return row;
    }

    private static Map<String, Object> decisionRow(long id, long afterVersion) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("task_id", 7L);
        row.put("observation_id", 91L);
        row.put("dimension", "FINDING");
        row.put("before_version", afterVersion - 1);
        row.put("after_version", afterVersion);
        row.put("method", "LINK_INBOUND");
        row.put("subject_status", "CLOSED");
        row.put("reason", "重复卡片");
        row.put("evidence", "inventory-inbound:77");
        row.put("reviewed_asset_version", 5L);
        row.put("actor_account_id", 11L);
        row.put("actor_username", "admin");
        row.put("occurred_at", Timestamp.from(INSTANT));
        return row;
    }

    private static Map<String, Object> historyRow(long id, boolean linked) {
        var row = decisionRow(id, 2L);
        row.put("assetId", linked ? "88" : null);
        row.put("inboundReceiptId", linked ? "77" : null);
        row.put("link_kind", linked ? "FORMAL_INBOUND" : null);
        row.put("owner_version", linked ? 5L : null);
        return row;
    }

    private static Map<String, Object> recollectionRow(long id) {
        var row = decisionRow(id, 3L);
        row.put("dimension", "TIME");
        row.put("method", "RECOLLECTED");
        row.put("counterObservationId", "92");
        row.put("counter_observed_at", Timestamp.from(INSTANT));
        return row;
    }

    private static Map<String, Object> taskRow(Object publishedAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L);
        row.put("name", "2026 年 Q4 盘点");
        row.put("task_status", "WAITING_REVIEW");
        row.put(
                "range_json",
                "{\"locationIds\":[3,4],\"from\":null,\"nested_json\":\"{\\\"warehouseId\\\":9,"
                        + "\\\"plain\\\":1.5}\",\"assetCount\":128}");
        row.put("starts_at", Timestamp.from(INSTANT));
        row.put("due_at", Timestamp.from(INSTANT));
        row.put("timezone_id", "Asia/Shanghai");
        row.put("parameter_version_id", 4L);
        row.put("expected_count", 128L);
        row.put("captured_result_count", 3L);
        row.put("assignment_version", 1L);
        row.put("version", 6L);
        row.put("creator_account_id", 11L);
        row.put("publisher_account_id", 11L);
        row.put("copied_from_task_id", null);
        row.put("created_at", Timestamp.from(INSTANT));
        row.put("updated_at", publishedAt);
        row.put("published_at", publishedAt);
        row.put("started_at", null);
        row.put("ended_at", null);
        row.put("end_reason", null);
        return row;
    }

    private static Map<String, Object> itemRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 21L);
        row.put("task_id", 7L);
        row.put("asset_id", 88L);
        row.put("asset_version", 5L);
        row.put("snapshot_json", SNAPSHOT_JSON);
        row.put("executor_account_id", 12L);
        row.put("assignment_version", 1L);
        row.put("result_count", 2L);
        row.put("recheck_generation", 0L);
        return row;
    }

    private static Map<String, Object> itemReviewRow(boolean notObserved) {
        var row = new LinkedHashMap<String, Object>();
        row.put("item_id", 21L);
        row.put("recheck_generation", 0L);
        row.put("task_id", 7L);
        row.put("observation_id", notObserved ? null : 91L);
        row.put("review_status", notObserved ? "NOT_OBSERVED" : "ACCEPTED");
        row.put("verified_observed_at", notObserved ? null : Timestamp.from(INSTANT));
        row.put("reviewed_asset_version", 5L);
        row.put("reason", "正常");
        row.put("evidence", "inventory-observation:91");
        row.put("reviewer_account_id", 11L);
        row.put("reviewer_username", "admin");
        row.put("reviewed_at", Timestamp.from(INSTANT));
        row.put("version", 1L);
        return row;
    }

    private static Map<String, Object> attachmentRow(Object createdAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("objectId", OBJECT_ID);
        row.put("filename", "photo.png");
        row.put("contentType", "image/png");
        row.put("sizeBytes", 1024L);
        row.put("scanStatus", "CLEAN");
        row.put("businessVersion", 0L);
        row.put("createdAt", createdAt);
        return row;
    }

    private static List<Map<String, Object>> observationRows() {
        return List.of(
                observationRow(Timestamp.from(INSTANT), "TIME_REVIEW"),
                observationRow(LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000), "FINDING_REVIEW"));
    }

    // ==================== finding exits ====================

    @Test
    void findingObservationRowsMatchTheFlatFindingWire() {
        for (var row : observationRows()) {
            assertWireEquals(legacyFindingWire(row), FindingViews.observation(JSON, row));
        }
        var tree =
                JSON.readTree(
                        JSON.writeValueAsString(
                                FindingViews.observation(
                                        JSON, observationRow(Timestamp.from(INSTANT), "FINDING_REVIEW"))));
        // Only the two known JSON columns are parsed; ids stringify, versions stay numeric.
        assertEquals("91", tree.path("id").asText());
        assertEquals("7", tree.path("task_id").asText());
        assertEquals(EVENT_ID, tree.path("client_event_id").asText());
        assertTrue(tree.path("facts_json").isObject());
        assertEquals(21, tree.path("facts_json").path("itemId").asInt(), "flat wire keeps numbers");
        assertEquals(9, tree.path("receipt_json").path("receiptId").asInt());
        assertEquals("2026-11-01T02:00:00.123Z", tree.path("received_at").asText());
        assertFalse(tree.path("binding_id").isMissingNode(), "nullable column stays explicit null");
        assertTrue(tree.path("binding_id").isNull());
        assertTrue(tree.path("calibrated_observed_at").isNull());
        assertEquals(0, tree.path("recheck_generation").asInt());
    }

    @Test
    void bothDateTimeDriverShapesProjectToTheSameInstantText() {
        var stamped = observationRow(Timestamp.from(INSTANT), "FINDING_REVIEW");
        var local = observationRow(LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000), "FINDING_REVIEW");
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(FindingViews.observation(JSON, stamped))),
                JSON.readTree(JSON.writeValueAsString(FindingViews.observation(JSON, local))));
        assertWireEquals(legacyFindingWire(local), FindingViews.observation(JSON, local));

        var stampedSubject = subjectRow("FINDING", true);
        var localSubject = subjectRow("FINDING", true);
        localSubject.put("reviewed_at", LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000));
        assertWireEquals(legacyFindingWire(stampedSubject), FindingViews.subject(JSON, stampedSubject));
        assertWireEquals(legacyFindingWire(localSubject), FindingViews.subject(JSON, localSubject));
        assertEquals(
                FindingViews.subject(JSON, stampedSubject).reviewedAt(),
                FindingViews.subject(JSON, localSubject).reviewedAt());
    }

    @Test
    void findingSubjectAndHistoryRowsMatchLegacyProjection() {
        for (String dimension : List.of("FINDING", "TIME")) {
            for (boolean reviewed : List.of(true, false)) {
                var row = subjectRow(dimension, reviewed);
                assertWireEquals(legacyFindingWire(row), FindingViews.subject(JSON, row));
            }
        }
        for (boolean linked : List.of(true, false)) {
            var row = historyRow(41L, linked);
            assertWireEquals(legacyFindingWire(row), FindingViews.decisionHistory(JSON, row));
            var tree = JSON.readTree(JSON.writeValueAsString(FindingViews.decisionHistory(JSON, row)));
            assertEquals("41", tree.path("id").asText());
            assertEquals(
                    linked ? "88" : "", tree.path("assetId").asText(), "CAST alias stays a string");
            assertEquals(linked, tree.path("link_kind").isTextual());
            assertEquals(linked, tree.path("owner_version").isNumber());
            assertTrue(tree.has("inboundReceiptId"));
        }
        var recollection = recollectionRow(42L);
        assertWireEquals(
                legacyFindingWire(recollection), FindingViews.decisionRecollection(JSON, recollection));
        var tree =
                JSON.readTree(
                        JSON.writeValueAsString(
                                FindingViews.decisionRecollection(JSON, recollection)));
        assertEquals("92", tree.path("counterObservationId").asText());
        assertEquals("2026-11-01T02:00:00.123Z", tree.path("counter_observed_at").asText());
        assertEquals("TIME", tree.path("dimension").asText());
    }

    @Test
    void findingDetailMatchesLegacyAssemblyWithSubjectRow() {
        var observation = observationRow(Timestamp.from(INSTANT), "TIME_REVIEW");
        var subject = subjectRow("FINDING", true);
        var legacy =
                legacyFindingDetail(
                        List.of(legacyFindingWire(subjectRow("TIME", false))),
                        List.of(legacyFindingWire(recollectionRow(42L))),
                        taskRow(Timestamp.from(INSTANT)).get("version"),
                        observation,
                        subject,
                        List.of(legacyFindingWire(historyRow(41L, true))),
                        1L);
        var typed =
                new FindingViews.FindingDetail(
                        List.of(FindingViews.subject(JSON, subjectRow("TIME", false))),
                        List.of(FindingViews.decisionRecollection(JSON, recollectionRow(42L))),
                        ((Number) taskRow(Timestamp.from(INSTANT)).get("version")).longValue(),
                        FindingViews.observation(JSON, observation),
                        FindingViews.subject(JSON, subject),
                        List.of(FindingViews.decisionHistory(JSON, historyRow(41L, true))),
                        1L);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(7, tree.size());
        assertTrue(tree.path("subject").has("reviewer_username"), "full subject row keeps every column");
        assertTrue(tree.path("subject").path("item_id").isNull());
        assertEquals("2026-11-01T02:00:00.123Z", tree.path("subject").path("reviewed_at").asText());
        assertEquals(6, tree.path("taskVersion").asInt());
        assertEquals(1, tree.path("historyTotal").asInt());
    }

    @Test
    void findingDetailEmptySubjectStaysAZeroKeyObject() {
        var observation = observationRow(Timestamp.from(INSTANT), "FINDING_REVIEW");
        var legacy =
                legacyFindingDetail(
                        List.of(),
                        List.of(),
                        taskRow(Timestamp.from(INSTANT)).get("version"),
                        observation,
                        Map.of(),
                        List.of(),
                        0L);
        var typed =
                new FindingViews.FindingDetail(
                        List.of(),
                        List.of(),
                        6L,
                        FindingViews.observation(JSON, observation),
                        FindingViews.noSubject(),
                        List.of(),
                        0L);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(tree.path("subject").isObject());
        assertEquals(0, tree.path("subject").size(), "Map.of() stays {} and never an all-null row");
        assertEquals(0, tree.path("timeSubject").size());
        assertEquals(0, tree.path("history").size());
    }

    @Test
    void recollectionTargetsPageMatchesLegacyAssembly() {
        var rows = observationRows();
        assertWireEquals(
                legacyObservationPage(rows, 2L, 1, 20, false),
                new FindingViews.ObservationPage(
                        rows.stream().map(row -> FindingViews.observation(JSON, row)).toList(),
                        2L,
                        1,
                        20));
    }

    @Test
    void findingTargetsMatchBothLegacyKeySets() {
        var formal =
                FindingViews.formalInboundTarget("88", "FA-0001", 5L, "77", "IB-2026-0001", "ACCEPTED");
        assertWireEquals(legacyTargetsEnvelope(List.of(legacyFormalInboundTarget())),
                new FindingViews.FindingTargets(List.of(formal)));
        var duplicate = FindingViews.duplicateAssetTarget("88", "FA-0001", "激光打印机", 5L);
        assertWireEquals(legacyTargetsEnvelope(List.of(legacyDuplicateAssetTarget())),
                new FindingViews.FindingTargets(List.of(duplicate)));

        var formalTree = JSON.readTree(JSON.writeValueAsString(formal));
        assertEquals(7, formalTree.size());
        assertFalse(formalTree.has("assetName"), "the other kind's key must be absent");
        var duplicateTree = JSON.readTree(JSON.writeValueAsString(duplicate));
        assertEquals(5, duplicateTree.size());
        assertFalse(duplicateTree.has("inboundReceiptId"));
        assertFalse(duplicateTree.has("receiptNumber"));
        assertFalse(duplicateTree.has("status"));
        assertWireEquals(
                legacyTargetsEnvelope(List.of()), new FindingViews.FindingTargets(List.of()));
    }

    // ==================== finding idempotent receipts ====================

    @Test
    void decisionReceiptReplaysLegacyStoredRows() {
        var legacy = legacyDecisionResult(7L, 91L, 4L, 3L, "PROCESSING");
        var stored = JSON.writeValueAsString(legacy);
        var replayed = JSON.readValue(stored, FindingViews.DecisionReceipt.class);
        assertWireEquals(legacy, replayed);
        // The legacy assembly was a LinkedHashMap in the record's declaration order, so newly
        // written response_json/audit detail_json rows stay byte-identical.
        assertEquals(stored, JSON.writeValueAsString(replayed));
        assertEquals(stored, JSON.writeValueAsString(new FindingViews.DecisionReceipt(
                "7", "91", 4L, 3L, "PROCESSING")));
    }

    @Test
    void recollectionReceiptReplaysLegacyStoredRows() {
        var legacy = legacyRecollectionResult(7L, 91L, 4L, 3L, 92L);
        var stored = JSON.writeValueAsString(legacy);
        var replayed = JSON.readValue(stored, FindingViews.RecollectionReceipt.class);
        assertWireEquals(legacy, replayed);
        // Replaying twice is stable; the legacy Map.of key order never was contractual.
        assertEquals(
                JSON.writeValueAsString(replayed),
                JSON.writeValueAsString(
                        JSON.readValue(
                                JSON.writeValueAsString(replayed),
                                FindingViews.RecollectionReceipt.class)));
        var tree = JSON.readTree(JSON.writeValueAsString(replayed));
        assertEquals("CLOSED", tree.path("status").asText());
        assertEquals("RECOLLECTED", tree.path("method").asText());
        assertEquals("92", tree.path("counterObservationId").asText());
        assertEquals("UNVERIFIED", tree.path("originalTimeConfidence").asText());
        assertEquals(8, tree.size());
    }

    // ==================== review exits ====================

    @Test
    void reviewRowsMatchTheRecursiveReviewWire() {
        for (Object publishedAt :
                List.of(
                        Timestamp.from(INSTANT),
                        LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var task = taskRow(publishedAt);
            assertWireEquals(legacyReviewWire(task), FindingViews.task(JSON, task));
        }
        var item = itemRow();
        assertWireEquals(legacyReviewWire(item), FindingViews.item(JSON, item));
        // false = ACCEPTED row with an observation; true = NOT_OBSERVED row with two null columns.
        for (boolean notObserved : List.of(true, false)) {
            var row = itemReviewRow(notObserved);
            assertWireEquals(legacyReviewWire(row), FindingViews.itemReview(JSON, row));
        }

        var taskTree =
                JSON.readTree(
                        JSON.writeValueAsString(
                                FindingViews.task(JSON, taskRow(Timestamp.from(INSTANT)))));
        // The nested *_json string is parsed and re-wired: warehouseId (camel Id) stringifies while
        // locationIds (plural) stays a numeric array.
        assertEquals("9", taskTree.path("range_json").path("nested_json").path("warehouseId").asText());
        assertEquals(1.5, taskTree.path("range_json").path("nested_json").path("plain").asDouble());
        assertEquals(3, taskTree.path("range_json").path("locationIds").get(0).asInt());
        assertTrue(taskTree.path("range_json").path("from").isNull());
        assertTrue(taskTree.path("copied_from_task_id").isNull());
        assertEquals("4", taskTree.path("parameter_version_id").asText());

        var notObserved =
                JSON.readTree(JSON.writeValueAsString(FindingViews.itemReview(JSON, itemReviewRow(true))));
        assertTrue(notObserved.path("observation_id").isNull());
        assertTrue(notObserved.path("verified_observed_at").isNull());
        assertEquals(13, notObserved.size());
    }

    @Test
    void reviewAndFindingProjectionsOfOneObservationRowDifferInsideFactsJson() {
        var row = observationRow(Timestamp.from(INSTANT), "FINDING_REVIEW");
        var flat = FindingViews.observation(JSON, row);
        var recursive = FindingViews.reviewObservation(JSON, row);
        assertWireEquals(legacyFindingWire(row), flat);
        assertWireEquals(legacyReviewWire(row), recursive);

        var flatTree = JSON.readTree(JSON.writeValueAsString(flat));
        var recursiveTree = JSON.readTree(JSON.writeValueAsString(recursive));
        assertEquals(21, flatTree.path("facts_json").path("itemId").asInt());
        assertEquals(
                flatTree.path("facts_json").path("itemId").asInt(),
                JSON.readTree(FACTS_JSON).path("itemId").asInt(),
                "the flat findings projection keeps the stored number");
        assertEquals("21", recursiveTree.path("facts_json").path("itemId").asText());
        assertEquals("31", recursiveTree.path("facts_json").path("actual").path("locationId").asText());
        assertEquals(
                flatTree.path("facts_json").path("actual").path("locationHint").asText(),
                recursiveTree.path("facts_json").path("actual").path("locationHint").asText());
        assertNotEquals(flatTree.path("facts_json"), recursiveTree.path("facts_json"));
        // Non-JSON columns project identically through both wires.
        assertEquals(flatTree.path("task_id"), recursiveTree.path("task_id"));
        assertEquals(flatTree.path("received_at"), recursiveTree.path("received_at"));
        assertEquals(flatTree.path("id"), recursiveTree.path("id"));
        assertEquals(24, recursiveTree.size());
    }

    @Test
    void reviewDetailMatchesLegacyAssemblyForRichAndLeanItems() {
        var task = taskRow(Timestamp.from(INSTANT));
        var item = itemRow();
        var candidate = observationRow(Timestamp.from(INSTANT), "ACCEPTED");
        var review = itemReviewRow(false);
        var movements =
                List.of(
                        new com.fams.asset.AssetInventoryReviewPort.Movement(
                                "501", "TRANSFERRED", INSTANT));
        var current = legacyReviewWire(JSON.readValue(SNAPSHOT_JSON, Object.class));

        var richLegacy =
                legacyReviewDetail(
                        task,
                        List.of(legacyReviewItem(item, current, movements, candidate, review, false, null)),
                        1L,
                        1,
                        20,
                        1L);
        var richTyped =
                new FindingViews.ReviewTaskDetail(
                        FindingViews.task(JSON, task),
                        List.of(
                                new FindingViews.ReviewTaskItem(
                                        FindingViews.item(JSON, item),
                                        FindingViews.wiredTree(
                                                JSON, JSON.readValue(SNAPSHOT_JSON, Object.class)),
                                        movements,
                                        FindingViews.reviewObservation(JSON, candidate),
                                        FindingViews.reviewState(JSON, review),
                                        false,
                                        null)),
                        1L,
                        1,
                        20,
                        1L);
        assertWireEquals(richLegacy, richTyped);
        var richTree = JSON.readTree(JSON.writeValueAsString(richTyped));
        assertEquals(6, richTree.size());
        assertEquals(7, richTree.path("items").get(0).size());
        assertTrue(richTree.path("items").get(0).path("blockedReason").isNull(),
                "always-present key keeps its explicit null");
        assertEquals("ACCEPTED", richTree.path("items").get(0).path("review").path("review_status").asText());
        assertEquals(
                "2026-11-01T02:00:00.123Z",
                richTree.path("items").get(0).path("movementEvents").get(0).path("occurredAt").asText());

        var leanLegacy =
                legacyReviewDetail(
                        task,
                        List.of(legacyReviewItem(item, null, List.of(), null, null, true, null)),
                        0L,
                        1,
                        20,
                        0L);
        var leanTyped =
                new FindingViews.ReviewTaskDetail(
                        FindingViews.task(JSON, task),
                        List.of(
                                new FindingViews.ReviewTaskItem(
                                        FindingViews.item(JSON, item),
                                        null,
                                        List.of(),
                                        null,
                                        FindingViews.reviewState(JSON, null),
                                        true,
                                        null)),
                        0L,
                        1,
                        20,
                        0L);
        assertWireEquals(leanLegacy, leanTyped);
        var leanTree = JSON.readTree(JSON.writeValueAsString(leanTyped));
        assertTrue(leanTree.path("items").get(0).path("current").isNull());
        assertEquals(0, leanTree.path("items").get(0).path("movementEvents").size());
        assertTrue(leanTree.path("items").get(0).path("candidate").isNull());
        assertEquals(1, leanTree.path("items").get(0).path("review").size());
        assertEquals(0, leanTree.path("items").get(0).path("review").path("version").asInt());
    }

    @Test
    void reviewObservationPageMatchesLegacyAssembly() {
        var rows = observationRows();
        assertWireEquals(
                legacyObservationPage(rows, 2L, 3, 50, true),
                new FindingViews.ObservationPage(
                        rows.stream().map(row -> FindingViews.reviewObservation(JSON, row)).toList(),
                        2L,
                        3,
                        50));
    }

    @Test
    void reviewStateUnionAndStoredTreeSerializeIdentically() {
        for (Map<String, Object> row : Arrays.asList(itemReviewRow(true), itemReviewRow(false), null)) {
            assertEquals(
                    JSON.writeValueAsString(FindingViews.reviewState(JSON, row)),
                    JSON.writeValueAsString(FindingViews.reviewNode(JSON, row)));
        }
        assertEquals("{\"version\":0}", JSON.writeValueAsString(FindingViews.reviewNode(JSON, null)));
        assertEquals(
                JSON.writeValueAsString(legacyReviewWire(itemReviewRow(true))),
                JSON.writeValueAsString(FindingViews.reviewState(JSON, itemReviewRow(true))));
    }

    @Test
    void normalBatchReceiptMatchesLegacyAssembly() {
        var review = itemReviewRow(true);
        var legacy =
                legacyAcceptResponse(
                        7L,
                        7L,
                        1,
                        2,
                        List.of(
                                legacyAcceptedLine(21L, review),
                                legacyConflictLine(22L, "INVENTORY_REVIEW_VERSION_CONFLICT", review),
                                legacyConflictLine(
                                        23L, "INVENTORY_REVIEW_ASSET_CHANGED", null)),
                        4L);
        var typed =
                new FindingViews.NormalBatchReceipt(
                        "7",
                        7L,
                        1,
                        2,
                        List.of(
                                new FindingViews.NormalBatchItem(
                                        "21",
                                        "ACCEPTED",
                                        FindingViews.reviewNode(JSON, review),
                                        null,
                                        null),
                                new FindingViews.NormalBatchItem(
                                        "22",
                                        "CONFLICT",
                                        null,
                                        "INVENTORY_REVIEW_VERSION_CONFLICT",
                                        FindingViews.reviewNode(JSON, review)),
                                new FindingViews.NormalBatchItem(
                                        "23",
                                        "CONFLICT",
                                        null,
                                        "INVENTORY_REVIEW_ASSET_CHANGED",
                                        FindingViews.reviewNode(JSON, null))),
                        4L);
        assertWireEquals(legacy, typed);
        var items = JSON.readTree(JSON.writeValueAsString(typed)).path("items");
        assertEquals(3, items.size());
        assertEquals(3, items.get(0).size());
        assertFalse(items.get(0).has("code"));
        assertFalse(items.get(0).has("current"));
        assertEquals(4, items.get(1).size());
        assertFalse(items.get(1).has("review"));
        assertEquals(0, items.get(2).path("current").path("version").asInt());
        assertEquals(6, JSON.readTree(JSON.writeValueAsString(typed)).size());
    }

    @Test
    void normalBatchReceiptReplaysLegacyStoredRows() {
        var legacy =
                legacyAcceptResponse(
                        7L,
                        7L,
                        1,
                        1,
                        List.of(
                                legacyAcceptedLine(21L, itemReviewRow(true)),
                                legacyConflictLine(22L, "INVENTORY_ITEM_NOT_FOUND", null)),
                        4L);
        var stored = JSON.writeValueAsString(legacy);
        var replayed = JSON.readValue(stored, FindingViews.NormalBatchReceipt.class);
        assertWireEquals(legacy, replayed);
        // Replaying twice is stable: the record's own serialisation round-trips.
        assertEquals(
                JSON.writeValueAsString(replayed),
                JSON.writeValueAsString(
                        JSON.readValue(
                                JSON.writeValueAsString(replayed),
                                FindingViews.NormalBatchReceipt.class)));
        assertEquals(2, replayed.items().size());
        assertEquals("INVENTORY_ITEM_NOT_FOUND", replayed.items().get(1).code());
        assertEquals(0, replayed.items().get(1).current().path("version").asInt());
    }

    // ==================== photo exits ====================

    @Test
    void photoUploadReceiptMatchesLegacyTwoKeys() {
        assertWireEquals(
                legacyPhotoReceipt(OBJECT_ID), new FindingViews.PhotoReceipt(OBJECT_ID, EVENT_ID));
        assertEquals(2, JSON.readTree(JSON.writeValueAsString(
                new FindingViews.PhotoReceipt(OBJECT_ID, EVENT_ID))).size());
    }

    @Test
    void observationPhotoListMatchesLegacyControllerProjectionAcrossDrivers() {
        for (Object createdAt :
                List.of(
                        Timestamp.from(INSTANT),
                        LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000),
                        "2026-11-01T02:00:00.123Z")) {
            var row = attachmentRow(createdAt);
            var legacy = List.of(legacyInventoryPhotoItem(row));
            var typed =
                    new FindingViews.PhotoList(
                            List.of(
                                    InventoryPhotoReadService.photo(
                                            AttachmentEvidencePort.fromRow(row))));
            assertWireEquals(Map.of("items", legacy), typed);
            var tree = JSON.readTree(JSON.writeValueAsString(typed));
            assertEquals(1, tree.size());
            assertEquals(7, tree.path("items").get(0).size());
            assertEquals(
                    createdAt instanceof String text
                            ? text
                            : "2026-11-01T02:00:00.123Z",
                    tree.path("items").get(0).path("createdAt").asText());
        }
        // Both driver shapes collapse to the same instant text, a text value passes through.
        assertEquals(
                JSON.readTree(
                        JSON.writeValueAsString(
                                new FindingViews.PhotoList(
                                        List.of(
                                                InventoryPhotoReadService.photo(
                                                        AttachmentEvidencePort.fromRow(
                                                                attachmentRow(Timestamp.from(INSTANT)))))))),
                JSON.readTree(
                        JSON.writeValueAsString(
                                new FindingViews.PhotoList(
                                        List.of(
                                                InventoryPhotoReadService.photo(
                                                        AttachmentEvidencePort.fromRow(
                                                                attachmentRow(LocalDateTime.of(
                                                                        2026, 11, 1, 2, 0, 0,
                                                                        123_000_000)))))))));
        var integerWidths = attachmentRow(Timestamp.from(INSTANT));
        integerWidths.put("sizeBytes", 1024);
        integerWidths.put("businessVersion", 0);
        assertWireEquals(
                Map.of("items", List.of(legacyInventoryPhotoItem(integerWidths))),
                new FindingViews.PhotoList(
                        List.of(
                                InventoryPhotoReadService.photo(
                                        AttachmentEvidencePort.fromRow(integerWidths)))));
    }

    @Test
    void repairEvidenceListPassesRawMetadataRowsThrough() {
        for (Object createdAt :
                List.of(Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var row = attachmentRow(createdAt);
            var metadata = AttachmentEvidencePort.fromRow(row);
            assertSame(createdAt, metadata.createdAt(), "pass-through envelope keeps the raw value");
            assertWireEquals(
                    Map.of("items", List.of(row)),
                    new FindingViews.RepairPhotoList(List.of(metadata)));
        }
        var rows =
                List.of(
                        AttachmentEvidencePort.fromRow(attachmentRow(Timestamp.from(INSTANT))),
                        AttachmentEvidencePort.fromRow(
                                attachmentRow(LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))));
        assertEquals(
                2,
                JSON.readTree(JSON.writeValueAsString(new FindingViews.RepairPhotoList(rows)))
                        .path("items")
                        .size());
    }

    @Test
    void downloadGrantMatchesLegacyTwoKeyAssembly() {
        var url =
                "/api/v1/inventory-observations/tasks/7/events/"
                        + EVENT_ID
                        + "/photos/"
                        + OBJECT_ID
                        + "/content?grant=token";
        assertWireEquals(
                legacyGrant(url, 1793520000L), new FindingViews.DownloadGrant(url, 1793520000L));
        var mini =
                "/api/v1/mini/inventory-repair-evidence/5/observations/9/photos/"
                        + OBJECT_ID
                        + "/content?grant=token";
        assertWireEquals(
                legacyGrant(mini, 1L), new FindingViews.DownloadGrant(mini, 1L));
        var tree = JSON.readTree(JSON.writeValueAsString(new FindingViews.DownloadGrant(mini, 1L)));
        assertEquals(2, tree.size());
        assertEquals(1, tree.path("expiresAtEpochSecond").asInt());
    }

    @Test
    void listComponentsAreDefensivelyCopied() {
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(attachmentRow(Timestamp.from(INSTANT)));
        var metadata = new ArrayList<>(List.of(AttachmentEvidencePort.fromRow(rows.getFirst())));
        var list = new FindingViews.RepairPhotoList(metadata);
        metadata.clear();
        assertEquals(1, list.items().size());
        var photos = new ArrayList<>(List.of(InventoryPhotoReadService.photo(
                AttachmentEvidencePort.fromRow(rows.getFirst()))));
        var photoList = new FindingViews.PhotoList(photos);
        photos.clear();
        assertEquals(1, photoList.items().size());
        var items = new ArrayList<>(List.of(new FindingViews.NormalBatchItem("1", "ACCEPTED",
                null, null, JSON.createObjectNode())));
        var receipt = new FindingViews.NormalBatchReceipt("7", 1L, 1, 0, items, 1L);
        items.clear();
        assertEquals(1, receipt.items().size());
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the same
        // bytes, and object key order was never stable in the HashMap/Map.of assemblies.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
