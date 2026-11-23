package com.fams.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.loss.InventoryLossPort;

import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-17a wire equivalence: every typed inventory selection/difference/readiness view must
 * serialize exactly like the frozen pre-refactor Map assembly. The {@code legacyXxx} methods below
 * are verbatim copies of the pre-JAVA-17a {@code InventorySelectionService}/{@code
 * InventoryDifferenceService}/{@code InventoryReadinessService} projections (including the three
 * different {@code wire()} variants and the {@code Map.of}/LinkedHashMap assemblies) and act as the
 * reference oracle.
 *
 * <p>Comparison runs on JSON text round-trip trees, so key presence, explicit nulls and value shapes
 * all count while object key order never did (the legacy envelopes were {@code Map.of}/HashMap with
 * unstable order). Coverage: all 15 exits, both states of every conditional key, each
 * idempotency-stored shape replayed from its pre-batch JSON, the driver-shape variants of DATETIME
 * and numeric columns, and the JAVA-17b consumption surface of {@code InventorySealEvidence}.
 */
class InventorySelectionWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ================= selection exits =================

    @Test
    void selectionDetailWithFullReviewMatchesLegacyAssembly() {
        var task = taskRow();
        var item = inventoryItemRow();
        var review = reviewRow(true);
        var context = assetContext();
        var legacy =
                legacySelectionDetail(
                        900L, task, 946L, item, review, DIGEST, context,
                        List.of(selectionEventRow()), 2, 20, 3L);
        var typed =
                new SelectionViews.SelectionDetailView(
                        Long.toString(900L),
                        number(task, "version"),
                        Long.toString(946L),
                        number(item, "recheck_generation"),
                        SelectionViews.reviewChip(review),
                        DIGEST,
                        context.movements(),
                        SelectionViews.selectionEvents(JSON, List.of(selectionEventRow())),
                        2,
                        20,
                        3L);
        assertWireEquals(legacy, typed);

        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals("900", tree.path("taskId").asText());
        assertEquals(7, tree.path("taskVersion").asLong());
        assertEquals(0, tree.path("recheckGeneration").asLong());
        // The review row keeps its two nullable columns as explicit nulls under Include.ALWAYS.
        assertTrue(tree.path("review").has("observation_id"));
        assertTrue(tree.path("review").path("observation_id").isNull());
        assertTrue(tree.path("review").path("verified_observed_at").isNull());
        assertTrue(tree.path("review").has("reviewer_username"));
        assertEquals(13, countKeys(tree.path("review")));
        // ids stringify, plain numbers stay numeric, DATETIME becomes an ISO instant.
        assertEquals("946", tree.path("history").get(0).path("item_id").asText());
        assertEquals(4, tree.path("history").get(0).path("review_version").asLong());
        assertEquals("2026-10-17T09:30:00.123Z",
                tree.path("history").get(0).path("verified_observed_at").asText());
        assertEquals("IN_USE",
                tree.path("history").get(0).path("physical_snapshot_json").path("lifecycle").asText());
        assertEquals(
                "41",
                tree.path("history").get(0).path("physical_snapshot_json").path("locationId").asText(),
                "physical_snapshot_json carries only asText strings, so the wire recursion is identity");
        assertEquals(3, tree.path("total").asLong());
    }

    @Test
    void selectionDetailWithoutReviewRowEmitsOnlyTheVersionMarker() {
        var task = taskRow();
        var item = inventoryItemRow();
        var context = assetContext();
        var legacy =
                legacySelectionDetail(
                        900L, task, 946L, item, Map.of(), DIGEST, context, List.of(), 1, 20, 0L);
        var typed =
                new SelectionViews.SelectionDetailView(
                        Long.toString(900L),
                        number(task, "version"),
                        Long.toString(946L),
                        number(item, "recheck_generation"),
                        SelectionViews.reviewChip(Map.of()),
                        DIGEST,
                        context.movements(),
                        SelectionViews.selectionEvents(JSON, List.of()),
                        1,
                        20,
                        0L);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(1, countKeys(tree.path("review")), "the marker carries only its version key");
        assertEquals(0, tree.path("review").path("version").asInt());
        assertTrue(tree.path("history").isArray());
        assertEquals(0, tree.path("history").size());
    }

    @Test
    void selectionProjectionsSurviveJdbcDriverShapeVariants() {
        var timestamped = selectionEventRow();
        var local = selectionEventRow();
        local.put("verified_observed_at", LocalDateTime.of(2026, 10, 17, 9, 30, 0, 123_000_000));
        local.put("recorded_at", LocalDateTime.of(2026, 10, 17, 10, 0, 0));
        local.put("movement_count", Integer.valueOf(2));
        local.put("review_version", Integer.valueOf(4));
        var legacy = legacySelectionWire(List.of(timestamped, local));
        var typed = SelectionViews.selectionEvents(JSON, List.of(timestamped, local));
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(
                tree.get(0).path("verified_observed_at").asText(),
                tree.get(1).path("verified_observed_at").asText(),
                "Timestamp and LocalDateTime driver shapes both render as the same ISO instant");
    }

    @Test
    void selectionReplyMatchesLegacyMapOfAssembly() {
        var review = reviewRow(false);
        var legacy = legacySelectReply(900L, 946L, 7L, review, DIGEST);
        var typed =
                new SelectionViews.SelectionReply(
                        Long.toString(900L),
                        Long.toString(946L),
                        7L + 1,
                        SelectionViews.reviewState(review),
                        DIGEST);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(5, countKeys(tree));
        assertEquals("19", tree.path("review").path("reviewer_account_id").asText());
        assertEquals("2026-10-18T01:02:03.004Z", tree.path("review").path("reviewed_at").asText());
    }

    @Test
    void evidenceSubjectsMatchLegacyWireWithThreeSegments() {
        var subject = subjectRow();
        var legacyItems =
                List.of(
                        legacySelectionSubject(
                                subject, List.of(decisionRow()), List.of(transferRow()),
                                List.of(repairLinkRow())));
        var legacy =
                Map.of(
                        "items", legacyItems,
                        "requiredDimensions", List.of("CONDITION", "TIME"));
        var typed =
                new SelectionViews.EvidenceSubjectView(
                        List.of(
                                SelectionViews.evidenceSubject(
                                        subject, List.of(decisionRow()), List.of(transferRow()),
                                        List.of(repairLinkRow()))),
                        List.of("CONDITION", "TIME"));
        assertWireEquals(legacy, typed);

        var tree = JSON.readTree(JSON.writeValueAsString(typed)).path("items").get(0);
        assertEquals(16, countKeys(tree), "13 subject columns + decisions + links + repairLinks");
        assertFalse(tree.has("client_event_id"), "the selection query does not join the client event");
        assertTrue(tree.has("recheck_generation"));
        assertEquals("946", tree.path("item_id").asText());
        assertTrue(tree.path("item_id").isTextual());
        assertTrue(tree.path("method").isNull(), "a nullable column stays an explicit null");
        assertTrue(tree.path("reviewed_at").isNull());
        assertEquals(0, tree.path("version").asInt());
        assertEquals(14, countKeys(tree.path("decisions").get(0)));
        assertEquals(6, countKeys(tree.path("links").get(0)));
        assertEquals(5, countKeys(tree.path("repairLinks").get(0)));
    }

    @Test
    void dispositionsMatchLegacyMapOfAssembly() {
        var rows = List.of(dispositionRow(), dispositionRejectedRow());
        var legacy =
                Map.of(
                        "items", legacySelectionWire(rows),
                        "total", 2L,
                        "page", 1,
                        "pageSize", 20);
        var typed =
                new SelectionViews.DispositionPage(
                        SelectionViews.evidenceDispositions(rows), 2L, 1, 20);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(15, countKeys(tree.path("items").get(0)));
        assertEquals("REJECTED", tree.path("items").get(1).path("disposition").asText());
        assertEquals("2026-10-18T01:02:03.004Z",
                tree.path("items").get(1).path("recorded_at").asText());
    }

    @Test
    void evidenceMutationRepliesMatchLegacyLinkedHashMapMerge() {
        var task = taskRow();
        var review = reviewRow(false);
        var decidePayload =
                Map.<String, Object>of(
                        "observationId", Long.toString(31L), "method", "SUPPLEMENTAL", "closed", 2);
        var legacyDecide = legacyFinishEvidence(task, 946L, 7L, decidePayload);
        var typedDecide =
                new SelectionViews.EvidenceDecisionReply(
                        Long.toString(31L), "SUPPLEMENTAL", 2,
                        task.get("id").toString(), Long.toString(946L), 7L + 1);
        assertWireEquals(legacyDecide, typedDecide);

        var acceptPayload = Map.<String, Object>of("review", legacySelectionWire(review));
        var legacyAccept = legacyFinishEvidence(task, 946L, 7L, acceptPayload);
        var typedAccept =
                new SelectionViews.EvidenceAcceptanceReply(
                        SelectionViews.reviewState(review), task.get("id").toString(),
                        Long.toString(946L), 7L + 1);
        assertWireEquals(legacyAccept, typedAccept);

        var tree = JSON.readTree(JSON.writeValueAsString(typedDecide));
        assertEquals(6, countKeys(tree));
        assertEquals("ACCEPTED", JSON.readTree(JSON.writeValueAsString(typedAccept))
                .path("review").path("review_status").asText());
    }

    // ================= difference exits =================

    @Test
    void differenceSubjectsMatchLegacyWire() {
        var subject = differenceSubjectRow();
        var values =
                new ArrayList<Object>();
        values.add(
                legacyDifferenceWire(
                        withSegments(
                                subject, List.of(decisionRow()), List.of(transferRow()),
                                List.of(repairLinkRow()))));
        var legacy = Map.of("items", values, "page", 1, "pageSize", 20, "total", 1L);
        var typed =
                new SelectionViews.DifferencePage(
                        List.of(
                                SelectionViews.differenceSubject(
                                        subject, List.of(decisionRow()), List.of(transferRow()),
                                        List.of(repairLinkRow()))),
                        1,
                        20,
                        1L);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed)).path("items").get(0);
        assertEquals(17, countKeys(tree), "13 subject columns + client_event_id + 3 segments");
        assertEquals("c1a2b3d4-0000-4000-8000-000000000001", tree.path("client_event_id").asText());
        assertFalse(tree.path("client_event_id").isNumber(), "a CHAR(36) id never became a number");
        assertEquals("31", tree.path("observation_id").asText());
        assertEquals("2026-10-18T01:02:03.004Z", tree.path("reviewed_at").asText());
        assertEquals(0, tree.path("recheck_generation").asInt(), "not an id column, stays numeric");
    }

    @Test
    void differencePrepareReplyMatchesLegacyFinish() {
        var task = taskRow();
        var legacy =
                legacyDifferenceFinish(
                        task, 7L, Map.<String, Object>of("created", 3), true);
        var typed =
                new SelectionViews.DifferencePrepareReply(
                        3, task.get("id").toString(), versionAfter(7L, true));
        assertWireEquals(legacy, typed);
        assertEquals(3, countKeys(JSON.readTree(JSON.writeValueAsString(typed))));

        var unchanged =
                legacyDifferenceFinish(task, 7L, Map.<String, Object>of("created", 0), false);
        var unchangedTyped =
                new SelectionViews.DifferencePrepareReply(
                        0, task.get("id").toString(), versionAfter(7L, false));
        assertWireEquals(unchanged, unchangedTyped);
        assertEquals(
                7,
                JSON.readTree(JSON.writeValueAsString(unchangedTyped)).path("version").asLong(),
                "a no-op preparation must not bump the task version");
    }

    @Test
    void differenceDecisionItemsCoverBothConditionalKeyStates() {
        var task = taskRow();
        var transferItem =
                Map.<String, Object>of(
                        "observationId", "31",
                        "dimension", "LOCATION",
                        "version", 4L,
                        "status", "PROCESSING",
                        "linkedRequestId", "77");
        var repairItem =
                Map.<String, Object>of(
                        "observationId", Long.toString(31L),
                        "dimension", "CONDITION",
                        "version", 4L,
                        "status", "PROCESSING",
                        "linkedRequestId", "",
                        "linkedRepairId", "501",
                        "repairStatus", "CLAIMED");
        assertWireEquals(
                legacyDifferenceFinish(
                        task, 7L, Map.<String, Object>of("items", List.of(transferItem)), true),
                new SelectionViews.DifferenceDecisionReply(
                        List.of(
                                new SelectionViews.DifferenceDecisionItem(
                                        "31", "LOCATION", 4L, "PROCESSING", "77", null, null)),
                        task.get("id").toString(),
                        versionAfter(7L, true)));
        assertWireEquals(
                legacyDifferenceFinish(
                        task, 7L, Map.<String, Object>of("items", List.of(repairItem)), true),
                new SelectionViews.DifferenceDecisionReply(
                        List.of(
                                new SelectionViews.DifferenceDecisionItem(
                                        Long.toString(31L), "CONDITION", 4L, "PROCESSING", "", "501",
                                        "CLAIMED")),
                        task.get("id").toString(),
                        versionAfter(7L, true)));

        var tree = JSON.readTree(JSON.writeValueAsString(
                new SelectionViews.DifferenceDecisionReply(
                        List.of(
                                new SelectionViews.DifferenceDecisionItem(
                                        "31", "LOCATION", 4L, "PROCESSING", "", null, null)),
                        "900",
                        8L)));
        var item = tree.path("items").get(0);
        assertEquals(5, countKeys(item), "the transfer branch keeps its five keys");
        assertFalse(item.has("linkedRepairId"));
        assertFalse(item.has("repairStatus"));
        assertTrue(item.has("linkedRequestId"), "the empty linkedRequestId key was always put");
    }

    @Test
    void repairContextMatchesLegacyAssembly() {
        var task = taskRow();
        var subject = subjectRow();
        subject.put("subject_status", "PROCESSING");
        subject.put("version", 3L);
        var observation = observationRow();
        var legacy =
                legacyRepairContext(
                        900L, task, 31L, subject, "42", observation, assetContext(),
                        List.of(repairLinkRow()));
        var typed =
                new SelectionViews.DifferenceRepairContext(
                        Long.toString(900L),
                        number(task, "version"),
                        Long.toString(31L),
                        number(subject, "version"),
                        (String) subject.get("subject_status"),
                        Long.toString(42L),
                        JSON.readTree(assetContext().snapshotJson()),
                        SelectionViews.timeValue(observation.get("raw_observed_at")),
                        JSON.readTree(observation.get("facts_json").toString()),
                        SelectionViews.repairLinks(List.of(repairLinkRow())));
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(10, countKeys(tree));
        assertEquals("2026-10-17T08:00:00Z", tree.path("originalObservedAt").asText());
        assertEquals("DAMAGED", tree.path("originalFacts").path("actual").path("condition").asText());
        assertEquals("501", tree.path("repairLinks").get(0).path("repair_id").asText());
        assertEquals(
                3,
                tree.path("repairLinks").get(0).path("decision_version").asInt(),
                "decision_version is not an id column and stays numeric");
        assertEquals("IN_USE", tree.path("currentAsset").path("lifecycle").asText());
    }

    // ================= readiness exits =================

    @Test
    void readinessTaskAndItemRowsMatchLegacyWire() {
        var task = taskRow();
        var orphan = readinessItemRow(false);
        orphan.put("id", 947L);
        orphan.put("asset_id", 79L);
        var items = List.of(readinessItemRow(true), orphan);
        var contexts = Map.of(77L, assetContext());
        // reason 62 has no loss report yet: report must stay an explicit null and canSubmit a key.
        var pending = unobservedReasonRow();
        pending.put("id", 62L);
        pending.put("reason_kind", "UNABLE_TO_OBSERVE");
        var reasons = List.of(unobservedReasonRow(), pending);
        // REPORTED is neither transferred() nor canRetry(): the loss is open but not resubmittable.
        var progress =
                Map.of(
                        61L,
                        new InventoryLossPort.Progress(
                                "L-9", "REPORTED", "21", LocalDate.of(2026, 11, 1)));
        var legacy =
                legacyReadinessDetail(
                        task, items, contexts, reasons, progress, 2L, 0L, 1L, 1, 20);
        var typed = typedReadinessDetail(task, items, contexts, reasons, progress, 2L, 0L, 1L, 1, 20);
        assertWireEquals(legacy, typed);

        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(7, countKeys(tree));
        var taskTree = tree.path("task");
        assertEquals(21, countKeys(taskTree), "every fams_inventory_task column stays a key");
        assertTrue(taskTree.path("copied_from_task_id").isNull());
        assertTrue(taskTree.path("started_at").isNull());
        assertEquals(7, taskTree.path("version").asInt());
        assertEquals(
                7,
                taskTree.path("range_json").path("usingOrganizationId").asInt(),
                "the camel *Id keys of range_json were never stringified");
        var row = tree.path("items").get(0);
        assertEquals(6, countKeys(row));
        assertEquals("946", row.path("id").asText());
        assertEquals(17, countKeys(row.path("item")));
        assertEquals("900", row.path("item").path("task_id").asText());
        assertEquals("A-1", row.path("item").path("snapshot_json").path("assetCode").asText());
        assertEquals(
                12,
                row.path("item").path("snapshot_json").path("version").asInt(),
                "the snapshot version integer survived the legacy wire recursion unchanged");
        assertEquals("77", row.path("item").path("asset_id").asText());
        assertEquals(3, row.path("item").path("valid_raw_count").asInt());
        assertTrue(row.path("current").path("lifecycle").isTextual());
        assertFalse(row.path("canRecord").asBoolean(), "an existing review row blocks recording");
        assertTrue(row.path("physicalMissingOpen").asBoolean());
        // The second item has no asset context, so current becomes an explicit null.
        var orphanRow = tree.path("items").get(1);
        assertTrue(orphanRow.has("current"));
        assertTrue(orphanRow.path("current").isNull());
        assertFalse(orphanRow.path("canRecord").asBoolean());
        var loss = row.path("missingLosses").get(0);
        assertEquals(5, countKeys(loss));
        assertEquals(2, loss.path("generation").asInt());
        assertEquals("L-9", loss.path("report").path("reportId").asText());
        assertFalse(loss.path("canSubmit").asBoolean());
        assertEquals(4, countKeys(loss.path("report")));
        var pendingLoss = row.path("missingLosses").get(1);
        assertTrue(pendingLoss.has("report"), "an absent loss report stays an explicit null key");
        assertTrue(pendingLoss.path("report").isNull());
        assertTrue(pendingLoss.path("canSubmit").asBoolean(), "no report means the loss can be filed");
    }

    @Test
    void readinessNullableJoinColumnsStayExplicitNulls() {
        var task = taskRow();
        task.put("published_at", null);
        task.put("timezone_id", null);
        var items = List.of(readinessItemRow(false));
        var contexts = Map.of(77L, assetContext());
        var legacy =
                legacyReadinessDetail(
                        task, items, contexts, List.of(), Map.of(), 0L, 0L, 1L, 1, 20);
        var typed =
                typedReadinessDetail(task, items, contexts, List.of(), Map.of(), 0L, 0L, 1L, 1, 20);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        var row = tree.path("items").get(0).path("item");
        for (String key :
                List.of(
                        "review_status",
                        "review_version",
                        "reason_kind",
                        "reason",
                        "evidence",
                        "actor_username",
                        "recorded_at")) {
            assertTrue(row.has(key), "LEFT JOIN column " + key + " must stay present");
            assertTrue(row.path(key).isNull(), "LEFT JOIN column " + key + " must stay null");
        }
        assertTrue(tree.path("items").get(0).path("current").path("version").isNumber());
        assertTrue(tree.path("task").path("published_at").isNull());
        assertTrue(tree.path("task").path("timezone_id").isNull());
        assertTrue(tree.path("items").get(0).path("canRecord").asBoolean());
        assertFalse(tree.path("items").get(0).path("physicalMissingOpen").asBoolean());
        assertEquals(0, tree.path("items").get(0).path("missingLosses").size());
    }

    @Test
    void readinessHistoryMatchesLegacyWire() {
        var rows = List.of(unobservedReasonRow(), unobservedReasonRow());
        var legacy =
                Map.of(
                        "items",
                        rows.stream()
                                .map(row -> legacyReadinessWire(row))
                                .toList(),
                        "total",
                        2L,
                        "page",
                        1,
                        "pageSize",
                        20);
        var typed =
                new SelectionViews.UnobservedHistoryPage(
                        SelectionViews.unobservedReasons(rows), 2L, 1, 20);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(11, countKeys(tree.path("items").get(0)));
        assertEquals("61", tree.path("items").get(0).path("id").asText());
        assertEquals("946", tree.path("items").get(0).path("item_id").asText());
        assertEquals(2, tree.path("total").asLong());
    }

    @Test
    void readinessMutationRepliesMatchLegacyMapOfAssemblies() {
        assertWireEquals(
                legacyUnobservedReply(900L, 946L, 61L, 7L, "ASSET_NOT_FOUND"),
                new SelectionViews.UnobservedReply(
                        Long.toString(900L),
                        Long.toString(946L),
                        Long.toString(61L),
                        7L + 1,
                        1,
                        "NOT_OBSERVED",
                        "ASSET_NOT_FOUND".equals("ASSET_NOT_FOUND")));
        assertWireEquals(
                legacyMissingLossReply(900L, 946L, 61L, "L-9", 7L),
                new SelectionViews.MissingLossReply(
                        Long.toString(900L),
                        Long.toString(946L),
                        Long.toString(61L),
                        "L-9",
                        7L + 1));
        assertWireEquals(
                legacyWaitingReply(900L, 7L, 4L),
                new SelectionViews.WaitingReply(Long.toString(900L), 7L + 1, "WAITING_REVIEW", 4L));

        var unobserved =
                JSON.readTree(
                        JSON.writeValueAsString(
                                new SelectionViews.UnobservedReply("900", "946", "61", 8L, 1L,
                                        "NOT_OBSERVED", false)));
        assertEquals(7, countKeys(unobserved));
        assertTrue(unobserved.path("physicalMissingOpen").isBoolean());
        assertEquals(5,
                countKeys(JSON.readTree(JSON.writeValueAsString(
                        new SelectionViews.MissingLossReply("900", "946", "61", "L-9", 8L)))));
        assertEquals(4,
                countKeys(JSON.readTree(JSON.writeValueAsString(
                        new SelectionViews.WaitingReply("900", 8L, "WAITING_REVIEW", 0L)))));
    }

    // ================= idempotency replays =================

    @Test
    void everyStoredReceiptShapeReplaysIntoItsTypedExit() {
        var task = taskRow();
        var review = reviewRow(false);
        var cases = new LinkedHashMap<String, Object>();
        cases.put(
                "SELECT_EVIDENCE",
                legacySelectReply(900L, 946L, 7L, review, DIGEST));
        cases.put(
                "DECIDE_EVIDENCE",
                legacyFinishEvidence(
                        task,
                        946L,
                        7L,
                        Map.<String, Object>of(
                                "observationId", "31", "method", "VERIFY_SELECTED", "closed", 0)));
        cases.put(
                "ACCEPT_SELECTED",
                legacyFinishEvidence(
                        task, 946L, 7L, Map.<String, Object>of("review", legacySelectionWire(review))));
        cases.put(
                "PREPARE_DIFFERENCES",
                legacyDifferenceFinish(task, 7L, Map.<String, Object>of("created", 2), true));
        cases.put(
                "DECIDE_DIFFERENCES_TRANSFER",
                legacyDifferenceFinish(
                        task,
                        7L,
                        Map.<String, Object>of(
                                "items",
                                List.of(
                                        Map.<String, Object>of(
                                                "observationId", "31",
                                                "dimension", "USAGE_MODE",
                                                "version", 4L,
                                                "status", "PROCESSING",
                                                "linkedRequestId", ""))),
                        true));
        cases.put(
                "DECIDE_DIFFERENCES_REPAIR",
                legacyDifferenceFinish(
                        task,
                        7L,
                        Map.<String, Object>of(
                                "items",
                                List.of(
                                        Map.<String, Object>of(
                                                "observationId", "31",
                                                "dimension", "CONDITION",
                                                "version", 4L,
                                                "status", "PROCESSING",
                                                "linkedRequestId", "",
                                                "linkedRepairId", "501",
                                                "repairStatus", "COMPLETED"))),
                        true));
        cases.put("UNOBSERVED:946", legacyUnobservedReply(900L, 946L, 61L, 7L, "EVIDENCE_UNAVAILABLE"));
        cases.put("MISSING_LOSS:61", legacyMissingLossReply(900L, 946L, 61L, "L-9", 7L));
        cases.put("WAITING_REVIEW", legacyWaitingReply(900L, 7L, 3L));

        assertEquals(
                SelectionViews.SelectionReply.class,
                replayStored(cases.get("SELECT_EVIDENCE"), SelectionViews.SelectionReply.class)
                        .getClass());
        replayStored(
                cases.get("DECIDE_EVIDENCE"), SelectionViews.EvidenceDecisionReply.class);
        replayStored(cases.get("ACCEPT_SELECTED"), SelectionViews.EvidenceAcceptanceReply.class);
        replayStored(cases.get("PREPARE_DIFFERENCES"), SelectionViews.DifferencePrepareReply.class);
        replayStored(
                cases.get("DECIDE_DIFFERENCES_TRANSFER"),
                SelectionViews.DifferenceDecisionReply.class);
        var repair =
                replayStored(
                        cases.get("DECIDE_DIFFERENCES_REPAIR"),
                        SelectionViews.DifferenceDecisionReply.class);
        assertEquals("501", repair.items().getFirst().linkedRepairId());
        assertEquals("COMPLETED", repair.items().getFirst().repairStatus());
        replayStored(cases.get("UNOBSERVED:946"), SelectionViews.UnobservedReply.class);
        replayStored(cases.get("MISSING_LOSS:61"), SelectionViews.MissingLossReply.class);
        replayStored(cases.get("WAITING_REVIEW"), SelectionViews.WaitingReply.class);
    }

    @Test
    void typedStoredShapesRoundTripIdempotently() {
        var review = reviewRow(true);
        var selection =
                new SelectionViews.SelectionReply(
                        "900", "946", 8L, SelectionViews.reviewState(review), DIGEST);
        assertStoredRoundTrip(selection, SelectionViews.SelectionReply.class);
        var decide =
                new SelectionViews.EvidenceDecisionReply(
                        "31", "REJECT_EVIDENCE", 3L, "900", "946", 8L);
        assertStoredRoundTrip(decide, SelectionViews.EvidenceDecisionReply.class);
        var accept =
                new SelectionViews.EvidenceAcceptanceReply(
                        SelectionViews.reviewState(review), "900", "946", 8L);
        assertStoredRoundTrip(accept, SelectionViews.EvidenceAcceptanceReply.class);
        assertStoredRoundTrip(
                new SelectionViews.DifferencePrepareReply(0, "900", 7L),
                SelectionViews.DifferencePrepareReply.class);
        assertStoredRoundTrip(
                new SelectionViews.DifferenceDecisionReply(
                        List.of(
                                new SelectionViews.DifferenceDecisionItem(
                                        "31", "CONDITION", 4L, "CLOSED", "", "501", "CANCELLED"),
                                new SelectionViews.DifferenceDecisionItem(
                                        "32", "LOCATION", 2L, "CLOSED", "77", null, null)),
                        "900",
                        8L),
                SelectionViews.DifferenceDecisionReply.class);
        assertStoredRoundTrip(
                new SelectionViews.UnobservedReply(
                        "900", "946", "61", 8L, 1L, "NOT_OBSERVED", true),
                SelectionViews.UnobservedReply.class);
        assertStoredRoundTrip(
                new SelectionViews.MissingLossReply("900", "946", "61", "L-9", 8L),
                SelectionViews.MissingLossReply.class);
        assertStoredRoundTrip(
                new SelectionViews.WaitingReply("900", 8L, "WAITING_REVIEW", 0L),
                SelectionViews.WaitingReply.class);
    }

    // ================= JAVA-17b consumption freeze =================

    @Test
    void sealEvidenceConsumptionSurfaceStaysUnchanged() throws Exception {
        // 17b calls these two public methods; their signatures are frozen word for word.
        assertEquals(
                void.class,
                InventorySealEvidence.class
                        .getMethod(
                                "validate",
                                Map.class,
                                Map.class,
                                Map.class,
                                AssetInventoryReviewPort.Context.class)
                        .getReturnType());
        assertEquals(void.class, InventorySealEvidence.class.getMethod("subjects", Map.class, Map.class).getReturnType());
        assertEquals(
                List.of(JdbcTemplate.class, ObjectMapper.class, InventoryDifferenceService.class),
                List.of(InventorySealEvidence.class.getConstructors()[0].getParameterTypes()));
        // The one DifferenceService value SealEvidence reads is the internal-row Set, not a Map exit.
        assertEquals(
                java.util.Set.class,
                InventoryDifferenceService.class
                        .getMethod("requiredDimensions", Map.class, Map.class)
                        .getReturnType());
        // The three controllers expose no Map return type any more (HTTP exits are all typed).
        var exits = new ArrayList<java.lang.reflect.Method>();
        exits.addAll(List.of(InventorySelectionController.class.getDeclaredMethods()));
        exits.addAll(List.of(InventoryDifferenceController.class.getDeclaredMethods()));
        exits.addAll(List.of(InventoryReadinessController.class.getDeclaredMethods()));
        for (var method : exits) {
            assertFalse(
                    Map.class.equals(method.getReturnType()),
                    method.getName() + " still returns a raw Map envelope");
        }
    }

    @Test
    void rowMapsFeedingSealEvidenceAreNeverMutatedByTheTypedProjection() {
        var task = taskRow();
        var observation = observationRow();
        var review = reviewRow(true);
        var item = inventoryItemRow();
        var taskBefore = JSON.readTree(JSON.writeValueAsString(task));
        var observationBefore = JSON.readTree(JSON.writeValueAsString(observation));
        var reviewBefore = JSON.readTree(JSON.writeValueAsString(review));
        var itemBefore = JSON.readTree(JSON.writeValueAsString(item));

        SelectionViews.reviewChip(review);
        SelectionViews.reviewState(review);
        SelectionViews.readinessItemRow(JSON, readinessItemRow(true));
        SelectionViews.taskRow(JSON, task);
        SelectionViews.evidenceSubject(subjectRow(), List.of(), List.of(), List.of());
        SelectionViews.differenceSubject(differenceSubjectRow(), List.of(), List.of(), List.of());
        SelectionViews.evidenceDispositions(List.of(dispositionRow()));
        SelectionViews.unobservedReasons(List.of(unobservedReasonRow()));
        SelectionViews.selectionEvents(JSON, List.of(selectionEventRow()));
        // requiredDimensions keeps reading these very row Maps (SealEvidence passes them through).
        assertEquals("900", task.get("id").toString());
        assertEquals("DAMAGED", JSON.readTree(observation.get("facts_json").toString())
                .path("actual").path("condition").asText());
        assertEquals("NOT_OBSERVED", review.get("review_status"));
        assertEquals(0L, ((Number) item.get("recheck_generation")).longValue());

        assertEquals(taskBefore, JSON.readTree(JSON.writeValueAsString(task)));
        assertEquals(observationBefore, JSON.readTree(JSON.writeValueAsString(observation)));
        assertEquals(reviewBefore, JSON.readTree(JSON.writeValueAsString(review)));
        assertEquals(itemBefore, JSON.readTree(JSON.writeValueAsString(item)));
    }

    // ================= typed assembly under test =================

    private static final String DIGEST = "b".repeat(64);

    private static SelectionViews.ReadinessView typedReadinessDetail(
            Map<String, Object> task,
            List<Map<String, Object>> items,
            Map<Long, AssetInventoryReviewPort.Context> contexts,
            List<Map<String, Object>> missingReasons,
            Map<Long, InventoryLossPort.Progress> progress,
            long total,
            long missingCount,
            long notObservedCount,
            int page,
            int size) {
        var byItem = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (var reason : missingReasons) {
            byItem.computeIfAbsent(number(reason, "item_id"), key -> new ArrayList<>()).add(reason);
        }
        var rows =
                items.stream()
                        .map(
                                row -> {
                                    var context = contexts.get(number(row, "asset_id"));
                                    var reasons =
                                            byItem.getOrDefault(number(row, "id"), List.of());
                                    return new SelectionViews.ReadinessItem(
                                            row.get("id").toString(),
                                            SelectionViews.readinessItemRow(JSON, row),
                                            context == null
                                                    ? null
                                                    : JSON.readTree(context.snapshotJson()),
                                            context != null
                                                    && "IN_PROGRESS".equals(task.get("task_status"))
                                                    && row.get("review_status") == null
                                                    && number(row, "valid_raw_count") == 0,
                                            reasons.stream()
                                                    .anyMatch(
                                                            reason -> {
                                                                var report =
                                                                        progress.get(
                                                                                number(reason, "id"));
                                                                return report == null
                                                                        || !report.transferred();
                                                            }),
                                            reasons.stream()
                                                    .map(
                                                            reason -> {
                                                                var report =
                                                                        progress.get(
                                                                                number(reason, "id"));
                                                                return new SelectionViews
                                                                        .MissingLossRow(
                                                                        reason.get("id").toString(),
                                                                        number(
                                                                                reason,
                                                                                "recheck_generation"),
                                                                        (String) reason.get("reason"),
                                                                        report,
                                                                        context != null
                                                                                && List.of(
                                                                                                "IN_PROGRESS",
                                                                                                "WAITING_REVIEW")
                                                                                        .contains(
                                                                                                task.get(
                                                                                                        "task_status"))
                                                                                && (report == null
                                                                                        || report
                                                                                                .canRetry()));
                                                            })
                                                    .toList());
                                })
                        .toList();
        return new SelectionViews.ReadinessView(
                SelectionViews.taskRow(JSON, task),
                rows,
                total,
                page,
                size,
                missingCount,
                notObservedCount);
    }

    // ================= legacy oracles (verbatim pre-JAVA-17a) =================

    /** Verbatim pre-JAVA-17a InventorySelectionService.wire. */
    private static Object legacySelectionWire(Object value) {
        if (value instanceof Map<?, ?> row) {
            var result = new LinkedHashMap<String, Object>();
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
                                        : legacySelectionWire(data));
                    });
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(InventorySelectionWireShapeTest::legacySelectionWire).toList();
        }
        if (value instanceof Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toString();
        }
        return value;
    }

    /** Verbatim pre-JAVA-17a InventoryDifferenceService.wire. */
    private static Object legacyDifferenceWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (key, child) ->
                            result.put(
                                    key.toString(),
                                    child instanceof Number
                                                    && (key.toString().equals("id")
                                                            || key.toString().endsWith("_id"))
                                            ? child.toString()
                                            : legacyDifferenceWire(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InventorySelectionWireShapeTest::legacyDifferenceWire).toList();
        }
        if (value instanceof Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toString();
        }
        return value;
    }

    /** Verbatim pre-JAVA-17a InventoryReadinessService.wire. */
    private static Object legacyReadinessWire(Object value) {
        if (value instanceof Map<?, ?> row) {
            var result = new LinkedHashMap<String, Object>();
            row.forEach(
                    (key, item) -> {
                        String name = key.toString();
                        Object content =
                                name.endsWith("_json") && item instanceof String text
                                        ? JSON.readValue(text, Object.class)
                                        : item;
                        result.put(
                                name,
                                item != null && (name.equals("id") || name.endsWith("_id"))
                                        ? item.toString()
                                        : legacyReadinessWire(content));
                    });
            return result;
        }
        if (value instanceof Timestamp || value instanceof LocalDateTime) {
            return legacyInstant(value).toString();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacySelectionSubject(
            Map<String, Object> row,
            List<Map<String, Object>> decisionRows,
            List<Map<String, Object>> linkRows,
            List<Map<String, Object>> repairRows) {
        var value = new LinkedHashMap<String, Object>(row);
        value.put("decisions", decisionRows);
        value.put("links", linkRows);
        value.put("repairLinks", repairRows);
        return (Map<String, Object>) legacySelectionWire(value);
    }

    private static Map<String, Object> withSegments(
            Map<String, Object> row,
            List<Map<String, Object>> decisionRows,
            List<Map<String, Object>> linkRows,
            List<Map<String, Object>> repairRows) {
        var value = new LinkedHashMap<String, Object>(row);
        value.put("decisions", decisionRows);
        value.put("links", linkRows);
        value.put("repairLinks", repairRows);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacySelectionDetail(
            long taskId,
            Map<String, Object> task,
            long itemId,
            Map<String, Object> item,
            Map<String, Object> currentReview,
            String digest,
            AssetInventoryReviewPort.Context context,
            List<Map<String, Object>> historyRows,
            int page,
            int size,
            Long total) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", task.get("version"));
        result.put("itemId", Long.toString(itemId));
        result.put("recheckGeneration", item.get("recheck_generation"));
        result.put("review", legacySelectionWire(currentReview.isEmpty() ? Map.of("version", 0) : currentReview));
        result.put("contextDigest", digest);
        result.put("movementEvents", context.movements());
        result.put("history", legacySelectionWire(historyRows));
        result.put("page", page);
        result.put("pageSize", size);
        result.put("total", total);
        return result;
    }

    private static Map<String, Object> legacySelectReply(
            long taskId, long itemId, long version, Map<String, Object> reviewRow, String digest) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "itemId",
                Long.toString(itemId),
                "version",
                version + 1,
                "review",
                legacySelectionWire(reviewRow),
                "contextDigest",
                digest);
    }

    private static Map<String, Object> legacyFinishEvidence(
            Map<String, Object> task, long itemId, long version, Map<String, Object> payload) {
        var response = new LinkedHashMap<String, Object>(payload);
        response.put("taskId", task.get("id").toString());
        response.put("itemId", Long.toString(itemId));
        response.put("version", version + 1);
        return response;
    }

    private static Map<String, Object> legacyDifferenceFinish(
            Map<String, Object> task,
            long version,
            Map<String, Object> payload,
            boolean changed) {
        long after = version + (changed ? 1 : 0);
        var result = new LinkedHashMap<>(payload);
        result.put("taskId", task.get("id").toString());
        result.put("version", after);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacyRepairContext(
            long taskId,
            Map<String, Object> task,
            long observationId,
            Map<String, Object> subject,
            String asset,
            Map<String, Object> observation,
            AssetInventoryReviewPort.Context context,
            List<Map<String, Object>> repairRows) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", legacyNumber(task, "version"));
        result.put("observationId", Long.toString(observationId));
        result.put("subjectVersion", legacyNumber(subject, "version"));
        result.put("subjectStatus", subject.get("subject_status"));
        result.put("assetId", asset);
        result.put("currentAsset", JSON.readTree(context.snapshotJson()));
        result.put("originalObservedAt", legacyDifferenceWire(observation.get("raw_observed_at")));
        result.put("originalFacts", JSON.readTree(observation.get("facts_json").toString()));
        result.put("repairLinks", legacyDifferenceWire(repairRows));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacyReadinessDetail(
            Map<String, Object> task,
            List<Map<String, Object>> items,
            Map<Long, AssetInventoryReviewPort.Context> contexts,
            List<Map<String, Object>> missingReasons,
            Map<Long, InventoryLossPort.Progress> progress,
            long total,
            long missingCount,
            long notObservedCount,
            int page,
            int size) {
        var byItem = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (var reason : missingReasons) {
            byItem.computeIfAbsent(legacyNumber(reason, "item_id"), key -> new ArrayList<>()).add(reason);
        }
        var rows =
                items.stream()
                        .map(
                                row -> {
                                    var value = new LinkedHashMap<String, Object>();
                                    value.put("id", row.get("id").toString());
                                    value.put("item", legacyReadinessWire(row));
                                    var context = contexts.get(legacyNumber(row, "asset_id"));
                                    value.put(
                                            "current",
                                            context == null
                                                    ? null
                                                    : JSON.readValue(
                                                            context.snapshotJson(), Object.class));
                                    value.put(
                                            "canRecord",
                                            context != null
                                                    && "IN_PROGRESS".equals(task.get("task_status"))
                                                    && row.get("review_status") == null
                                                    && legacyNumber(row, "valid_raw_count") == 0);
                                    var reasons =
                                            byItem.getOrDefault(legacyNumber(row, "id"), List.of());
                                    value.put(
                                            "physicalMissingOpen",
                                            reasons.stream()
                                                    .anyMatch(
                                                            reason -> {
                                                                var report =
                                                                        progress.get(
                                                                                legacyNumber(
                                                                                        reason,
                                                                                        "id"));
                                                                return report == null
                                                                        || !report.transferred();
                                                            }));
                                    value.put(
                                            "missingLosses",
                                            reasons.stream()
                                                    .map(
                                                            reason -> {
                                                                var report =
                                                                        progress.get(
                                                                                legacyNumber(
                                                                                        reason,
                                                                                        "id"));
                                                                var entry =
                                                                        new LinkedHashMap<String,
                                                                                Object>();
                                                                entry.put(
                                                                        "reasonId",
                                                                        reason.get("id")
                                                                                .toString());
                                                                entry.put(
                                                                        "generation",
                                                                        reason.get(
                                                                                "recheck_generation"));
                                                                entry.put(
                                                                        "reason",
                                                                        reason.get("reason"));
                                                                entry.put("report", report);
                                                                entry.put(
                                                                        "canSubmit",
                                                                        context != null
                                                                                && List.of(
                                                                                                "IN_PROGRESS",
                                                                                                "WAITING_REVIEW")
                                                                                        .contains(
                                                                                                task.get(
                                                                                                        "task_status"))
                                                                                && (report == null
                                                                                        || report
                                                                                                .canRetry()));
                                                                return entry;
                                                            })
                                                    .toList());
                                    return value;
                                })
                        .toList();
        return Map.of(
                "task",
                legacyReadinessWire(task),
                "items",
                rows,
                "total",
                total,
                "page",
                page,
                "pageSize",
                size,
                "missingCount",
                missingCount,
                "notObservedCount",
                notObservedCount);
    }

    private static Map<String, Object> legacyUnobservedReply(
            long taskId, long itemId, long reasonId, long version, String kind) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "itemId",
                Long.toString(itemId),
                "reasonId",
                Long.toString(reasonId),
                "version",
                version + 1,
                "reviewVersion",
                1,
                "status",
                "NOT_OBSERVED",
                "physicalMissingOpen",
                "ASSET_NOT_FOUND".equals(kind));
    }

    private static Map<String, Object> legacyMissingLossReply(
            long taskId, long itemId, long reasonId, String reportId, long version) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "itemId",
                Long.toString(itemId),
                "reasonId",
                Long.toString(reasonId),
                "reportId",
                reportId,
                "version",
                version + 1);
    }

    private static Map<String, Object> legacyWaitingReply(
            long taskId, long version, long notObservedCount) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "version",
                version + 1,
                "status",
                "WAITING_REVIEW",
                "notObservedCount",
                notObservedCount);
    }

    // ================= fixtures =================

    private static Map<String, Object> taskRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 900L);
        row.put("name", "年度盘点");
        row.put("task_status", "IN_PROGRESS");
        row.put(
                "range_json",
                "{\"lifecycle\":\"IN_USE\",\"usingOrganizationId\":7,\"owningOrganizationId\":8,"
                        + "\"categoryId\":null,\"warehouseId\":null,\"locationId\":null,"
                        + "\"responsibleEmployeeId\":null,\"exception\":null}");
        row.put("starts_at", Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        row.put("due_at", Timestamp.from(Instant.parse("2026-10-31T00:00:00Z")));
        row.put("timezone_id", "Asia/Shanghai");
        row.put("parameter_version_id", 3L);
        row.put("expected_count", 2L);
        row.put("captured_result_count", 1L);
        row.put("assignment_version", 4L);
        row.put("version", 7L);
        row.put("creator_account_id", 11L);
        row.put("publisher_account_id", 12L);
        row.put("copied_from_task_id", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-09-20T02:00:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-10-18T02:00:00Z")));
        row.put("published_at", Timestamp.from(Instant.parse("2026-09-30T23:59:59.123Z")));
        row.put("started_at", null);
        row.put("ended_at", null);
        row.put("end_reason", null);
        return row;
    }

    private static Map<String, Object> inventoryItemRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 946L);
        row.put("task_id", 900L);
        row.put("asset_id", 77L);
        row.put("asset_version", 12L);
        row.put("recheck_generation", 0L);
        return row;
    }

    private static Map<String, Object> reviewRow(boolean accepted) {
        var row = new LinkedHashMap<String, Object>();
        row.put("item_id", 946L);
        row.put("recheck_generation", 0L);
        row.put("task_id", 900L);
        row.put("observation_id", accepted ? null : 31L);
        row.put("review_status", accepted ? "NOT_OBSERVED" : "ACCEPTED");
        row.put("verified_observed_at", accepted ? null : Timestamp.from(Instant.parse("2026-10-17T09:30:00.123Z")));
        row.put("reviewed_asset_version", 12L);
        row.put("reason", "现场核实");
        row.put("evidence", "复核依据：与使用人确认");
        row.put("reviewer_account_id", 19L);
        row.put("reviewer_username", "admin");
        row.put("reviewed_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        row.put("version", 4L);
        return row;
    }

    private static Map<String, Object> selectionEventRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("item_id", 946L);
        row.put("recheck_generation", 0L);
        row.put("review_version", 4L);
        row.put("task_id", 900L);
        row.put("observation_id", 31L);
        row.put("review_status", "SELECTED");
        row.put("verified_observed_at", Timestamp.from(Instant.parse("2026-10-17T09:30:00.123Z")));
        row.put("time_source", "CALIBRATED");
        row.put("reviewed_asset_version", 12L);
        row.put(
                "physical_snapshot_json",
                "{\"custodyType\":\"SHARED\",\"departmentResponsibleEmployeeId\":\"61\","
                        + "\"exception\":\"NORMAL\",\"locationId\":\"41\",\"lifecycle\":\"IN_USE\","
                        + "\"owningOrganizationId\":\"8\",\"responsibleEmployeeId\":null,"
                        + "\"usingOrganizationId\":\"7\",\"warehouseId\":null}");
        row.put("movement_digest", DIGEST);
        row.put("movement_count", 2L);
        row.put("reason", "选择该证据");
        row.put("evidence", "证据依据：现场照片一致");
        row.put("actor_account_id", 19L);
        row.put("actor_username", "admin");
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        return row;
    }

    private static Map<String, Object> subjectRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 31L);
        row.put("dimension", "TIME");
        row.put("task_id", 900L);
        row.put("item_id", 946L);
        row.put("recheck_generation", 0L);
        row.put("subject_status", "OPEN");
        row.put("method", null);
        row.put("reason", null);
        row.put("evidence", null);
        row.put("reviewer_account_id", null);
        row.put("reviewer_username", null);
        row.put("reviewed_at", null);
        row.put("version", 0L);
        return row;
    }

    private static Map<String, Object> differenceSubjectRow() {
        var row = subjectRow();
        row.put("dimension", "LOCATION");
        row.put("subject_status", "PROCESSING");
        row.put("method", "BOOK_CORRECT");
        row.put("reason", "账面更正");
        row.put("evidence", "依据：与仓库台账核对一致");
        row.put("reviewer_account_id", 19L);
        row.put("reviewer_username", "admin");
        row.put("reviewed_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        row.put("version", 3L);
        row.put("client_event_id", "c1a2b3d4-0000-4000-8000-000000000001");
        return row;
    }

    private static Map<String, Object> decisionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 71L);
        row.put("task_id", 900L);
        row.put("observation_id", 31L);
        row.put("dimension", "TIME");
        row.put("before_version", 2L);
        row.put("after_version", 3L);
        row.put("method", "VERIFY_SELECTED");
        row.put("subject_status", "CLOSED");
        row.put("reason", "采信所选证据");
        row.put("evidence", "依据：校准链完整可复核");
        row.put("reviewed_asset_version", 12L);
        row.put("actor_account_id", 19L);
        row.put("actor_username", "admin");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        return row;
    }

    private static Map<String, Object> transferRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 31L);
        row.put("dimension", "LOCATION");
        row.put("decision_version", 3L);
        row.put("transfer_id", 77L);
        row.put("transfer_item_id", 78L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        return row;
    }

    private static Map<String, Object> repairLinkRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 31L);
        row.put("dimension", "CONDITION");
        row.put("decision_version", 3L);
        row.put("repair_id", 501L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        return row;
    }

    private static Map<String, Object> dispositionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 32L);
        row.put("task_id", 900L);
        row.put("item_id", 946L);
        row.put("recheck_generation", 0L);
        row.put("counter_observation_id", 31L);
        row.put("counter_review_version", 4L);
        row.put("disposition", "SUPPLEMENTAL");
        row.put("reviewed_asset_version", 12L);
        row.put("context_digest", DIGEST);
        row.put("reason", "补充证据");
        row.put("evidence", "依据：补充照片已归档");
        row.put("actor_account_id", 19L);
        row.put("actor_username", "admin");
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        row.put("version", 1L);
        return row;
    }

    private static Map<String, Object> dispositionRejectedRow() {
        var row = dispositionRow();
        row.put("observation_id", 33L);
        row.put("disposition", "REJECTED");
        row.put(
                "recorded_at",
                LocalDateTime.of(2026, 10, 18, 1, 2, 3, 4_000_000));
        return row;
    }

    private static Map<String, Object> unobservedReasonRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 61L);
        row.put("task_id", 900L);
        row.put("item_id", 946L);
        row.put("recheck_generation", 2L);
        row.put("reason_kind", "ASSET_NOT_FOUND");
        row.put("reason", "现场找不到实物");
        row.put("evidence", "依据：三方查找记录在案");
        row.put("reviewed_asset_version", 12L);
        row.put("actor_account_id", 19L);
        row.put("actor_username", "admin");
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")));
        return row;
    }

    private static Map<String, Object> readinessItemRow(boolean reviewed) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 946L);
        row.put("task_id", 900L);
        row.put("asset_id", 77L);
        row.put("asset_version", 12L);
        row.put(
                "snapshot_json",
                "{\"assetId\":\"77\",\"assetCode\":\"A-1\",\"name\":\"现场甲\",\"version\":12,"
                        + "\"categoryId\":\"5\",\"lifecycle\":\"IN_USE\",\"exception\":\"NORMAL\","
                        + "\"custodyType\":\"SHARED\",\"warehouseId\":null,\"locationId\":\"41\","
                        + "\"responsibleEmployeeId\":\"61\"}");
        row.put("executor_account_id", 21L);
        row.put("assignment_version", 4L);
        row.put("result_count", 1L);
        row.put("recheck_generation", 2L);
        row.put("review_status", reviewed ? "NOT_OBSERVED" : null);
        row.put("review_version", reviewed ? 1L : null);
        row.put("reason_kind", reviewed ? "ASSET_NOT_FOUND" : null);
        row.put("reason", reviewed ? "现场找不到实物" : null);
        row.put("evidence", reviewed ? "依据：三方查找记录在案" : null);
        row.put("actor_username", reviewed ? "admin" : null);
        row.put(
                "recorded_at",
                reviewed ? Timestamp.from(Instant.parse("2026-10-18T01:02:03.004Z")) : null);
        row.put("valid_raw_count", reviewed ? 3L : 0L);
        return row;
    }

    private static Map<String, Object> observationRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 31L);
        row.put("task_id", 900L);
        row.put("item_id", 946L);
        row.put("client_event_id", "c1a2b3d4-0000-4000-8000-000000000001");
        row.put("outcome", "DIFFERENCE_REVIEW");
        row.put("raw_observed_at", Timestamp.from(Instant.parse("2026-10-17T08:00:00Z")));
        row.put(
                "facts_json",
                "{\"actual\":{\"condition\":\"DAMAGED\",\"locationId\":\"41\","
                        + "\"custodyType\":\"SHARED\",\"unknownReason\":null},"
                        + "\"photoIds\":[\"c1a2b3d4-0000-4000-8000-000000000002\"]}");
        row.put(
                "receipt_json",
                "{\"differences\":[\"LOCATION\",\"CONDITION\"],\"acceptedAt\":"
                        + "\"2026-10-17T08:00:01Z\"}");
        return row;
    }

    private static AssetInventoryReviewPort.Context assetContext() {
        return new AssetInventoryReviewPort.Context(
                "{\"assetId\":\"77\",\"assetCode\":\"A-1\",\"name\":\"现场甲\",\"version\":12,"
                        + "\"lifecycle\":\"IN_USE\",\"exception\":\"NORMAL\","
                        + "\"custodyType\":\"SHARED\",\"locationId\":\"41\","
                        + "\"responsibleEmployeeId\":\"61\",\"warehouseId\":null}",
                List.of(
                        new AssetInventoryReviewPort.Movement(
                                "T-1", "TRANSFER", Instant.parse("2026-10-10T00:00:00Z"))));
    }

    // ================= helpers =================

    private static <T> T replayStored(Object legacyDocument, Class<T> type) {
        var stored = JSON.writeValueAsString(legacyDocument);
        var replayed = JSON.readValue(stored, type);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(replayed)),
                "stored receipt JSON must replay into an identical tree for " + type.getSimpleName());
        return replayed;
    }

    private static <T> void assertStoredRoundTrip(T typed, Class<T> type) {
        var stored = JSON.writeValueAsString(typed);
        assertEquals(JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static long versionAfter(long version, boolean changed) {
        return version + (changed ? 1 : 0);
    }

    private static int countKeys(JsonNode node) {
        return node.size();
    }

    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private static long legacyNumber(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private static Instant legacyInstant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
