package com.fams.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.masterdata.InventoryReferencePort;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * JAVA-17d wire equivalence for the inventory 主档/迟交/迟交损坏 exits. Every {@code legacyXxx} method
 * below is a verbatim copy of the pre-batch Map assembly of InventoryService, InventoryLateService
 * and InventoryLateDamageService and is kept as the reference oracle; the typed {@link InventoryViews}
 * projections must serialize to exactly the same JSON tree. Comparison goes through JSON text, so key
 * presence, explicit nulls and value shapes all count while object key order never was contractual
 * (the legacy envelopes were Map.of with unstable order). The assemblies that were built as a
 * LinkedHashMap are additionally compared byte-for-byte, because that exact text is persisted into
 * fams_idempotency_record.response_body_json and fams_inventory_review_receipt.response_json.
 *
 * <p>Coverage: both legacy row projections (the flat {@code clean} and the recursive {@code wire})
 * with their driver-shape variants and the nested identifier stringification the recursive walk
 * performs; the role-gated task view key sets; every HTTP exit and envelope; each idempotency-stored
 * shape replayed from its pre-batch JSON text; and the consumption points of InventorySchedule
 * (startDue) plus InventoryLateService.decide (copied draft identifier and task version).
 */
class InventoryMainWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Instant NOW = Instant.parse("2026-11-27T06:00:00Z");

    // ============================== 盘点主档 ==============================

    @Test
    void taskRowMatchesLegacyCleanProjection() {
        var row = taskRow(7L, true, false);
        var typed = InventoryViews.inventoryTaskRow(JSON, row);
        var legacy = legacyClean(row);
        assertWireEquals(legacy, typed);
        // The same text is persisted into fams_idempotency_record.response_body_json: the record
        // declaration order is the column order, so newly stored receipts stay byte-identical.
        assertEquals(JSON.writeValueAsString(legacy), JSON.writeValueAsString(typed));
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(
                new TreeSet<>(
                        List.of(
                                "id",
                                "name",
                                "task_status",
                                "range_json",
                                "starts_at",
                                "due_at",
                                "timezone_id",
                                "parameter_version_id",
                                "expected_count",
                                "captured_result_count",
                                "assignment_version",
                                "version",
                                "creator_account_id",
                                "publisher_account_id",
                                "copied_from_task_id",
                                "created_at",
                                "updated_at",
                                "published_at",
                                "started_at",
                                "ended_at",
                                "end_reason")),
                keys(tree));
        assertFalse(tree.has("overdue"), "the write exit body never carried overdue");
        // Identifier columns stringify per value, other numeric columns stay numbers.
        assertTrue(tree.path("creator_account_id").isTextual());
        assertTrue(tree.path("timezone_id").isTextual());
        assertTrue(tree.path("expected_count").isNumber());
        assertTrue(tree.path("version").isNumber());
        // range_json leaves through the Range round-trip: canonical keys, absent filters null.
        assertEquals("933000", tree.path("range_json").path("categoryId").asText());
        assertTrue(tree.path("range_json").path("warehouseId").isNull());
        assertEquals(8, tree.path("range_json").size());
    }

    @Test
    void taskRowDraftNullsAndDriverShapeVariantsMatchLegacyClean() {
        var draft = taskRow(8L, false, false);
        var draftTyped = InventoryViews.inventoryTaskRow(JSON, draft);
        assertWireEquals(legacyClean(draft), draftTyped);
        var draftTree = JSON.readTree(JSON.writeValueAsString(draftTyped));
        assertTrue(draftTree.has("publisher_account_id"), "explicit null column stays a key");
        assertTrue(draftTree.path("publisher_account_id").isNull());
        assertTrue(draftTree.path("end_reason").isNull());
        assertTrue(draftTree.path("timezone_id").isNull());
        assertTrue(draftTree.path("parameter_version_id").isNull());

        var localDateTime = taskRow(9L, true, true);
        localDateTime.put("published_at", LocalDateTime.of(2026, 12, 1, 8, 30, 15));
        localDateTime.put("due_at", LocalDateTime.of(2026, 12, 5, 0, 0, 0));
        var localTyped = InventoryViews.inventoryTaskRow(JSON, localDateTime);
        assertWireEquals(legacyClean(localDateTime), localTyped);
        assertEquals(
                "2026-12-01T08:30:15Z",
                JSON.readTree(JSON.writeValueAsString(localTyped)).path("published_at").asText());

        var narrow = taskRow(10L, true, false);
        narrow.put("expected_count", Integer.valueOf(2));
        narrow.put("version", Short.valueOf((short) 3));
        narrow.put("publisher_account_id", Integer.valueOf(933001));
        assertWireEquals(legacyClean(narrow), InventoryViews.inventoryTaskRow(JSON, narrow));
    }

    @Test
    void adminTaskViewKeepsEveryKeyIncludingExplicitNulls() {
        var row = taskRow(8L, false, false);
        var legacy = legacyTaskView(row, true);
        var typed =
                InventoryViews.inventoryTaskAdminView(
                        InventoryViews.inventoryTaskRow(JSON, row), overdue(row));
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(22, tree.size());
        for (String key :
                List.of(
                        "range_json",
                        "creator_account_id",
                        "publisher_account_id",
                        "end_reason",
                        "expected_count",
                        "captured_result_count")) {
            assertTrue(tree.has(key), "admin view must keep " + key);
        }
        assertTrue(tree.path("publisher_account_id").isNull());
        assertTrue(tree.path("overdue").isBoolean());
    }

    @Test
    void executorTaskViewDropsTheSixAdminOnlyKeys() {
        var row = taskRow(7L, true, false);
        var legacy = legacyTaskView(row, false);
        var typed =
                InventoryViews.inventoryTaskExecutorView(
                        InventoryViews.inventoryTaskRow(JSON, row), overdue(row));
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(16, tree.size());
        for (String key :
                List.of(
                        "range_json",
                        "creator_account_id",
                        "publisher_account_id",
                        "end_reason",
                        "expected_count",
                        "captured_result_count")) {
            assertFalse(tree.has(key), "executor view must not leak " + key);
        }
        assertTrue(tree.has("overdue"));
        assertTrue(tree.path("version").isNumber());
    }

    @Test
    void overdueFlagFollowsTheLegacyClockComparison() {
        var running = taskRow(11L, true, false);
        running.put("task_status", "IN_PROGRESS");
        running.put("due_at", Timestamp.from(NOW.plusSeconds(1)));
        assertFalse(overdue(running));
        assertEquals(
                "false",
                JSON.readTree(JSON.writeValueAsString(legacyTaskView(running, true)))
                        .path("overdue")
                        .asText());
        running.put("due_at", Timestamp.from(NOW.minusSeconds(1)));
        assertTrue(overdue(running));
        assertWireEquals(legacyTaskView(running, true),
                InventoryViews.inventoryTaskAdminView(
                        InventoryViews.inventoryTaskRow(JSON, running), overdue(running)));
        running.put("task_status", "COMPLETED");
        assertFalse(overdue(running), "only an IN_PROGRESS task can be overdue");
    }

    @Test
    void itemRowMatchesLegacyCleanProjection() {
        var row = itemRow(21L, 933010L);
        var typed = InventoryViews.inventoryItemRow(JSON, row);
        assertWireEquals(legacyClean(row), typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(
                List.of(
                        "id",
                        "task_id",
                        "asset_id",
                        "asset_version",
                        "snapshot_json",
                        "executor_account_id",
                        "assignment_version",
                        "result_count",
                        "recheck_generation"),
                tree.properties().stream().map(Map.Entry::getKey).toList());
        assertTrue(tree.path("task_id").isTextual());
        assertTrue(tree.path("asset_version").isNumber());
        assertTrue(tree.path("executor_account_id").isTextual());
        assertFalse(JSON.readTree(JSON.writeValueAsString(InventoryViews.inventoryItemRow(JSON, itemRow(22L, 933011L))))
                .path("executor_account_id")
                .isNumber());
        // The flat clean projection does not recurse: nested values keep their stored shape.
        assertTrue(tree.path("snapshot_json").path("locationId").isTextual());
        assertTrue(tree.path("snapshot_json").path("parameterVersionId").isNumber());
        assertTrue(tree.path("snapshot_json").path("warehouse_id").isNumber());
        assertTrue(tree.path("snapshot_json").path("movements").get(0).path("movement_id").isNumber());
    }

    @Test
    void taskPageAndDetailMatchLegacyEnvelopes() {
        var published = taskRow(7L, true, false);
        var draft = taskRow(8L, false, false);
        var legacyItems = List.of(legacyTaskView(published, true), legacyTaskView(draft, true));
        var typedItems =
                List.<InventoryViews.InventoryTaskView>of(
                        InventoryViews.inventoryTaskAdminView(
                                InventoryViews.inventoryTaskRow(JSON, published), overdue(published)),
                        InventoryViews.inventoryTaskAdminView(
                                InventoryViews.inventoryTaskRow(JSON, draft), overdue(draft)));
        assertWireEquals(
                legacyListPage(legacyItems, 2L, 1, 20, true),
                new InventoryViews.InventoryTaskPage(typedItems, 2L, 1, 20, true));

        var legacyExecutorPage =
                legacyListPage(List.of(legacyTaskView(published, false)), 1L, 3, 7, false);
        var typedExecutorPage =
                new InventoryViews.InventoryTaskPage(
                        List.of(
                                InventoryViews.inventoryTaskExecutorView(
                                        InventoryViews.inventoryTaskRow(JSON, published),
                                        overdue(published))),
                        1L,
                        3,
                        7,
                        false);
        assertWireEquals(legacyExecutorPage, typedExecutorPage);
        var pageTree = JSON.readTree(JSON.writeValueAsString(typedExecutorPage));
        assertEquals(5, pageTree.size());
        for (String key : List.of("items", "total", "page", "pageSize", "canManage")) {
            assertTrue(pageTree.has(key), "list envelope key " + key);
        }
        assertFalse(pageTree.has("canManage") && pageTree.path("canManage").isNull());
        assertFalse(pageTree.path("items").get(0).has("range_json"));

        var legacyDetail =
                legacyTaskDetail(
                        legacyTaskView(published, true),
                        List.of(legacyClean(itemRow(21L, 933010L)), legacyClean(itemRow(22L, 933011L))),
                        2L,
                        1,
                        20,
                        true);
        var typedDetail =
                new InventoryViews.InventoryTaskDetail(
                        InventoryViews.inventoryTaskAdminView(
                                InventoryViews.inventoryTaskRow(JSON, published), overdue(published)),
                        List.of(
                                InventoryViews.inventoryItemRow(JSON, itemRow(21L, 933010L)),
                                InventoryViews.inventoryItemRow(JSON, itemRow(22L, 933011L))),
                        2L,
                        1,
                        20,
                        true);
        assertWireEquals(legacyDetail, typedDetail);
        var detailTree = JSON.readTree(JSON.writeValueAsString(typedDetail));
        assertEquals(6, detailTree.size());
        assertEquals(2, detailTree.path("items").size());
        assertTrue(detailTree.path("task").path("range_json").isObject());
    }

    @Test
    void previewAndCatalogExitsMatchLegacyEnvelopes() {
        assertWireEquals(
                legacyDigest(4L, "aa".repeat(32)),
                new InventoryViews.InventoryPreviewDigest(4L, "aa".repeat(32)));
        var choices =
                List.of(
                        new InventoryReferencePort.Choice("933000", "办公类", "ACTIVE"),
                        new InventoryReferencePort.Choice("933001", null, "DISABLED"));
        assertWireEquals(
                legacyCatalog(choices, 1, 200),
                new InventoryViews.InventoryReferenceChoicePage(choices, 1, 200));
        var tree =
                JSON.readTree(
                        JSON.writeValueAsString(
                                new InventoryViews.InventoryReferenceChoicePage(choices, 1, 200)));
        assertEquals(3, tree.size(), "the catalog envelope never had a total key");
        assertEquals(3, tree.path("items").get(1).size());
        assertTrue(tree.path("items").get(1).has("name"), "null name stays an explicit null");
        assertTrue(tree.path("items").get(1).path("name").isNull());
    }

    // ============================== 迟交补充证据 ==============================

    @Test
    void lateTaskHeaderKeepsTheStoredRangeDocumentUntouched() {
        var row = taskRow(7L, true, false);
        row.put("range_json", "{\"categoryId\":933000,\"extra\":{\"keeper_id\":44}}");
        var typed = InventoryViews.inventoryLateTaskHeader(JSON, row);
        assertWireEquals(legacyWire(row), typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(21, tree.size());
        // Unlike the 主档 clean projection there is no Range round-trip and no key normalization.
        assertEquals(2, tree.path("range_json").size());
        assertTrue(tree.path("range_json").path("categoryId").isNumber());
        assertFalse(tree.path("range_json").has("warehouseId"));
        // The recursive walk does stringify nested identifiers.
        assertTrue(tree.path("range_json").path("extra").path("keeper_id").isTextual());
        assertEquals("44", tree.path("range_json").path("extra").path("keeper_id").asText());
    }

    @Test
    void lateCaseEvidenceWalksTheFrozenSnapshotRecursively() {
        var row = lateCaseRow();
        var typed = InventoryViews.inventoryLateEvidence(JSON, row);
        assertWireEquals(legacyWire(row), typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(9, tree.size());
        assertTrue(tree.path("observation_id").isTextual());
        assertTrue(
                tree.path("accepted_observation_id").isNull(),
                "nullable identifier stays an explicit null");
        assertTrue(tree.path("needs_review").isBoolean());
        var snapshot = tree.path("snapshot_json");
        // camelCase keys of the evidence packet are untouched, numeric values stay numbers.
        assertTrue(snapshot.path("observationId").isNumber());
        assertEquals("7", snapshot.path("taskId").asText());
        // snake_case nested identifiers become text, at any depth and inside arrays.
        assertEquals("11", snapshot.path("frozenItem").path("item_id").asText());
        assertTrue(snapshot.path("frozenItem").path("review").path("observation_id").isTextual());
        assertEquals("5", snapshot.path("sealedItems").get(0).path("asset_id").asText());
        assertEquals("6", snapshot.path("sealedItems").get(1).path("id").asText());
        assertEquals(6, snapshot.path("sealedItems").get(0).path("assetVersion").asInt());
        // A nested *_json string field is expanded and then walked, exactly like the Map domain did.
        assertTrue(snapshot.path("frozenItem").path("review").path("detail_json").isObject());
        assertEquals(
                "13", snapshot.path("frozenItem").path("review").path("detail_json").path("actor_id").asText());
        assertEquals(
                "kept", snapshot.path("frozenItem").path("review").path("detail_json").path("note").asText());
        assertTrue(snapshot.path("acceptedReviewAtTerminal").isNull());
        assertFalse(snapshot.path("reportSha256").isNull());
        assertEquals(
                "DAMAGED", snapshot.path("facts").path("actual").path("condition").asText());
    }

    @Test
    void lateCaseViewPlaceholderMatchesLegacyAssembly() {
        var row = lateCaseRow();
        var legacy = legacyLateView(row, Map.of(), List.of(), true);
        var typed = InventoryViews.inventoryLateCaseView(JSON, row, Map.of(), null, true);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(4, tree.size());
        assertEquals(2, tree.path("review").size());
        assertEquals(0, tree.path("review").path("decision_version").asInt());
        assertEquals("OPEN", tree.path("review").path("result").asText());
        assertTrue(tree.path("damageFollowup").isObject());
        assertEquals(0, tree.path("damageFollowup").size(), "absent damage row stays an empty object");
        assertTrue(tree.path("todoOpen").asBoolean());
    }

    @Test
    void lateCaseViewDecisionAndDamageChipsMatchLegacyAssembly() {
        var row = lateCaseRow();
        row.put("needs_review", false);
        var previous = lateDecisionRow(3L, null);
        var damage = lateDamageRow();
        var legacy = legacyLateView(row, previous, List.of(damage), false);
        var typed = InventoryViews.inventoryLateCaseView(JSON, row, previous, damage, false);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals("REVIEWED", tree.path("review").path("result").asText());
        assertEquals(10, tree.path("review").size());
        assertTrue(tree.path("review").path("decided_at").isTextual());
        assertTrue(tree.path("review").has("linked_task_id"));
        assertTrue(tree.path("review").path("linked_task_id").isNull());
        assertEquals(7, tree.path("damageFollowup").size());
        assertEquals("OPEN", tree.path("damageFollowup").path("damage_status").asText());
        assertTrue(tree.path("damageFollowup").path("repair_id").isNull());
        assertEquals(
                "2026-12-02T09:30:00Z",
                tree.path("damageFollowup").path("updated_at").asText(),
                "LocalDateTime driver shape normalizes like Timestamp");
        assertEquals(
                "INFORMATION",
                JSON.readTree(
                                JSON.writeValueAsString(
                                        InventoryViews.inventoryLateCaseView(
                                                JSON, row, Map.of(), null, false)))
                        .path("review")
                        .path("result")
                        .asText());
    }

    @Test
    void lateCasePageAndDetailMatchLegacyEnvelopes() {
        var task = taskRow(7L, true, false);
        var row = lateCaseRow();
        var previous = lateDecisionRow(3L, 940001L);
        var damage = lateDamageRow();
        var legacyPage =
                legacyLateList(
                        legacyWire(task),
                        List.of(legacyLateView(row, previous, List.of(damage), true)),
                        1,
                        20,
                        4L);
        var typedPage =
                new InventoryViews.InventoryLateCasePage(
                        InventoryViews.inventoryLateTaskHeader(JSON, task),
                        List.of(
                                InventoryViews.inventoryLateCaseView(
                                        JSON, row, previous, damage, true)),
                        1,
                        20,
                        4L,
                        true);
        assertWireEquals(legacyPage, typedPage);
        var tree = JSON.readTree(JSON.writeValueAsString(typedPage));
        assertEquals(6, tree.size());
        assertTrue(tree.path("supplementOnly").asBoolean());
        assertTrue(tree.path("task").path("id").isTextual());
        assertEquals(4L, tree.path("total").asLong());

        var second = lateDecisionRow(4L, null);
        var history =
                new InventoryViews.InventoryLateDecisionPage(
                        InventoryViews.inventoryLateDecisions(List.of(previous, second)), 2, 20, 2L);
        var legacyHistory =
                legacyLateHistory(List.of(legacyWire(previous), legacyWire(second)), 2, 20, 2L);
        assertWireEquals(legacyHistory, history);
        var detail =
                new InventoryViews.InventoryLateCaseDetail(
                        InventoryViews.inventoryLateTaskHeader(JSON, task),
                        InventoryViews.inventoryLateCaseView(JSON, row, previous, damage, false),
                        history,
                        true);
        assertWireEquals(
                legacyLateDetail(
                        legacyWire(task),
                        legacyLateView(row, previous, List.of(damage), false),
                        legacyHistory),
                detail);
        var detailTree = JSON.readTree(JSON.writeValueAsString(detail));
        assertEquals(4, detailTree.size(), "task/case/history/supplementOnly");
        assertTrue(detailTree.has("case"), "'case' keeps its reserved-word key name");
        assertFalse(detailTree.has("lateCase"));
        assertEquals(2, detailTree.path("history").path("items").size());
        assertTrue(detailTree.path("history").path("items").get(1).path("linked_task_id").isNull());
    }

    @Test
    void latePrepareReceiptMatchesLegacyAssemblyAndReplaysStoredJson() {
        var legacy = legacyPrepare(7L, 100, 3L);
        var typed =
                new InventoryViews.InventoryLatePrepareReceipt(
                        Long.toString(7L), 100, true, 3L, true);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(5, tree.size());
        assertTrue(tree.path("taskId").isTextual());
        assertTrue(tree.path("moreMayRemain").asBoolean());
        assertTrue(tree.path("taskVersion").isNumber());
        assertWireEquals(
                legacyPrepare(7L, 1, 3L),
                new InventoryViews.InventoryLatePrepareReceipt("7", 1, false, 3L, true));

        // fams_inventory_review_receipt.response_json replay of a pre-batch body. The legacy body
        // was a Map.of, so its stored key order is hash order; re-serializing normalizes it to the
        // declaration order (JAVA-16a §6-7), which is why this shape asserts tree equality only.
        var stored = JSON.writeValueAsString(legacy);
        var replayed = JSON.readValue(stored, InventoryViews.InventoryLatePrepareReceipt.class);
        assertWireEquals(JSON.readTree(stored), replayed);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(replayed)));
    }

    @Test
    void lateDecisionReceiptMatchesLegacyAssemblyAndReplaysStoredJson() {
        var legacy = legacyDecide(7L, 91L, 2L, "LINK_RECHECK", "FOLLOWUP_OPEN", 940001L);
        var typed =
                new InventoryViews.InventoryLateDecisionReceipt(
                        Long.toString(7L),
                        Long.toString(91L),
                        3L,
                        "FOLLOWUP_OPEN",
                        "LINK_RECHECK",
                        Long.toString(940001L),
                        "inventory.admin",
                        "2026-11-27T06:00:00Z",
                        true,
                        false);
        assertWireEquals(legacy, typed);
        assertEquals(
                JSON.writeValueAsString(legacy),
                JSON.writeValueAsString(typed),
                "stored receipt text keeps the legacy put order");
        var noLink =
                new InventoryViews.InventoryLateDecisionReceipt(
                        "7",
                        "91",
                        1L,
                        "REVIEWED",
                        "NOTE",
                        null,
                        "inventory.admin",
                        "2026-11-27T06:00:00Z",
                        true,
                        false);
        var noLinkTree = JSON.readTree(JSON.writeValueAsString(noLink));
        assertEquals(10, noLinkTree.size());
        assertTrue(noLinkTree.has("linkedTaskId"), "the legacy Map always put this key");
        assertTrue(noLinkTree.path("linkedTaskId").isNull());

        // The late decision body was a LinkedHashMap in the same order, so its stored text is
        // reproduced byte-for-byte by the record.
        var stored = JSON.writeValueAsString(legacyDecide(7L, 91L, 2L, "NOTE", "REVIEWED", null));
        var replayed = JSON.readValue(stored, InventoryViews.InventoryLateDecisionReceipt.class);
        assertWireEquals(JSON.readTree(stored), replayed);
        assertEquals(stored, JSON.writeValueAsString(replayed));
    }

    // ============================== 迟交损坏返修 ==============================

    @Test
    void lateDamageDetailMatchesLegacyAssembly() {
        var task = taskRow(7L, true, false);
        var damage = lateDamageRow();
        var decisions = List.of(lateDamageDecisionRow(1L, 550001L), lateDamageDecisionRow(2L, null));
        var source = lateCaseRow();
        source.put(
                "snapshot_json",
                "{\"assetId\":\"933010\",\"facts\":{\"actual\":{\"condition\":\"DAMAGED\"}}}");
        var currentAsset = JSON.readTree("{\"assetId\":\"933010\",\"version\":6}");
        var originalEvidence = JSON.readTree(source.get("snapshot_json").toString());
        var historyItems =
                decisions.stream().map(InventoryMainWireShapeTest::legacyDamageWire).toList();
        var legacy =
                legacyDamageDetail(
                        7L,
                        legacyNumber(task, "version"),
                        91L,
                        933010L,
                        currentAsset,
                        legacyDamageWire(damage),
                        originalEvidence,
                        legacyDamageHistory(historyItems, 1, 20, 2L),
                        "OPEN",
                        true);
        var typed =
                new InventoryViews.InventoryLateDamageDetail(
                        Long.toString(7L),
                        InventoryViews.inventoryLateTaskHeader(JSON, task).version(),
                        Long.toString(91L),
                        Long.toString(933010L),
                        currentAsset,
                        InventoryViews.inventoryLateDamage(damage),
                        originalEvidence,
                        new InventoryViews.InventoryLateDamageDecisionPage(
                                InventoryViews.inventoryLateDamageDecisions(decisions), 1, 20, 2L),
                        "OPEN",
                        true,
                        true,
                        false);
        assertWireEquals(legacy, typed);
        assertEquals(
                keys(JSON.readTree(JSON.writeValueAsString(legacy))),
                keys(JSON.readTree(JSON.writeValueAsString(typed))),
                "the detail envelope kept the legacy LinkedHashMap key order (its nested history "
                        + "envelope was a Map.of and never had one)");
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(12, tree.size());
        assertTrue(tree.path("taskVersion").isNumber());
        assertTrue(tree.path("assetId").isTextual());
        assertTrue(tree.path("damage").path("observation_id").isTextual());
        assertTrue(tree.path("history").path("items").get(1).path("reviewed_asset_version").isNull());
        assertTrue(tree.path("history").path("items").get(0).path("repair_id").isTextual());
        assertTrue(
                tree.path("originalEvidence").path("facts").path("actual").path("condition")
                        .isTextual());
        assertTrue(tree.path("supplementOnly").asBoolean());
        assertFalse(tree.path("originalReportChanged").asBoolean());

        var noContext =
                new InventoryViews.InventoryLateDamageDetail(
                        "7",
                        3L,
                        "91",
                        "933010",
                        null,
                        InventoryViews.inventoryLateDamage(damage),
                        JSON.readTree("{}"),
                        new InventoryViews.InventoryLateDamageDecisionPage(List.of(), 1, 20, 0L),
                        "OPEN",
                        false,
                        true,
                        false);
        var noContextTree = JSON.readTree(JSON.writeValueAsString(noContext));
        assertTrue(noContextTree.has("currentAsset"), "absent asset context stays an explicit null");
        assertTrue(noContextTree.path("currentAsset").isNull());
        assertEquals(0, noContextTree.path("history").path("items").size());
        assertEquals(0, noContextTree.path("history").path("total").asInt());
    }

    @Test
    void lateDamageDecisionReceiptMatchesLegacyAssemblyAndReplaysStoredJson() {
        var legacy =
                legacyDamageDecide(
                        7L,
                        91L,
                        1L,
                        "FORMAL_REPAIR",
                        "PROCESSING",
                        "550001",
                        "WAREHOUSE_PROCESSING",
                        "OPEN",
                        true);
        var typed =
                new InventoryViews.InventoryLateDamageDecisionReceipt(
                        Long.toString(7L),
                        Long.toString(91L),
                        2L,
                        "FORMAL_REPAIR",
                        "PROCESSING",
                        "550001",
                        "WAREHOUSE_PROCESSING",
                        "OPEN",
                        true,
                        "inventory.admin",
                        "2026-11-27T06:00:00Z",
                        true,
                        false);
        assertWireEquals(legacy, typed);
        assertEquals(JSON.writeValueAsString(legacy), JSON.writeValueAsString(typed));
        assertEquals(13, JSON.readTree(JSON.writeValueAsString(typed)).size());
        assertWireEquals(
                legacyDamageDecide(
                        7L, 91L, 3L, "REFRESH_REPAIR", "CLOSED", "550001", "COMPLETED", "REVIEWED", false),
                new InventoryViews.InventoryLateDamageDecisionReceipt(
                        "7", "91", 4L, "REFRESH_REPAIR", "CLOSED", "550001", "COMPLETED", "REVIEWED",
                        false, "inventory.admin", "2026-11-27T06:00:00Z", true, false));

        var stored = JSON.writeValueAsString(legacy);
        var replayed =
                JSON.readValue(stored, InventoryViews.InventoryLateDamageDecisionReceipt.class);
        assertWireEquals(JSON.readTree(stored), replayed);
        assertEquals(
                stored,
                JSON.writeValueAsString(replayed),
                "the damage receipt body was a LinkedHashMap in declaration order");
    }

    // ============================== 存储回放与消费点 ==============================

    @Test
    void storedTaskRowBodyReplaysThroughTheSameProjectionFactory() {
        var row = taskRow(7L, true, false);
        var stored = JSON.writeValueAsString(legacyClean(row));
        var replayed = InventoryViews.inventoryTaskRow(JSON, JSON.readValue(stored, MAP_TYPE));
        assertWireEquals(JSON.readTree(stored), replayed);
        assertEquals(stored, JSON.writeValueAsString(replayed), "replay must not rewrite the body");
        assertWireEquals(
                InventoryViews.inventoryTaskRow(JSON, row),
                InventoryViews.inventoryTaskRow(JSON, JSON.readValue(stored, MAP_TYPE)));

        // An old receipt whose range_json carries a numeric large identifier (the pre-batch
        // InventoryTaskHttpRuntimeCase shape) still replays as text through the Range round-trip.
        ObjectNode mutated = (ObjectNode) JSON.readTree(stored);
        ((ObjectNode) mutated.path("range_json")).put("categoryId", 9007199254740993L);
        var mutatedText = JSON.writeValueAsString(mutated);
        var replayedMutated =
                InventoryViews.inventoryTaskRow(JSON, JSON.readValue(mutatedText, MAP_TYPE));
        var tree = JSON.readTree(JSON.writeValueAsString(replayedMutated));
        assertTrue(tree.path("range_json").path("categoryId").isTextual());
        assertEquals("9007199254740993", tree.path("range_json").path("categoryId").asText());
        assertEquals(8, tree.path("range_json").size());
    }

    @Test
    void typedReceiptsRoundTripThroughTheirOwnJson() {
        for (Object value :
                List.of(
                        new InventoryViews.InventoryPreviewDigest(2L, "bb".repeat(32)),
                        new InventoryViews.InventoryLatePrepareReceipt("7", 3, false, 1L, true),
                        new InventoryViews.InventoryLateDecisionReceipt(
                                "7", "91", 4L, "REVIEWED", "CONFIRM_RECHECK", "940001", "admin",
                                "2026-11-27T06:00:00Z", true, false),
                        new InventoryViews.InventoryLateDamageDecisionReceipt(
                                "7", "91", 2L, "LINK_REPAIR", "PROCESSING", "550001",
                                "WAREHOUSE_PROCESSING", "REVIEWED", true, "admin",
                                "2026-11-27T06:00:00Z", true, false))) {
            var once = JSON.writeValueAsString(value);
            var back = JSON.readValue(once, value.getClass());
            assertEquals(once, JSON.writeValueAsString(back), value.getClass().getSimpleName());
        }
    }

    @Test
    void consumptionPointsOfTheSchedulerAndTheLateChainKeepTheirValues() throws Exception {
        // InventorySchedule drives its bounded catch-up on this exact boolean contract.
        var startDue = InventoryService.class.getMethod("startDue");
        assertEquals(boolean.class, startDue.getReturnType());

        // finish() reads id/version out of the reply body and InventoryLateService.decide reads the
        // copied draft id; both must yield the values the legacy Map reads produced.
        var row = taskRow(7L, true, false);
        var typed = InventoryViews.inventoryTaskRow(JSON, row);
        var legacy = legacyClean(row);
        assertEquals(Long.parseLong(legacy.get("id").toString()), Long.parseLong(typed.id()));
        assertEquals(((Number) legacy.get("version")).longValue(), typed.version());

        // The evidence port Task projection the late chain feeds stays Map-based and unchanged.
        var task = InventoryLateEvidencePort.Task.fromRow(row);
        assertEquals(7L, task.id());
        assertEquals("COMPLETED", task.taskStatus());
        assertEquals(3L, task.version());
        assertEquals(
                task.version(),
                InventoryViews.inventoryLateTaskHeader(JSON, row).version(),
                "the late header exposes the same task version the prepare guard compares");
        assertEquals(
                Long.valueOf(legacyNumber(lateDecisionRow(3L, 940001L), "linked_task_id")),
                Long.valueOf(
                        InventoryViews.inventoryLateDecision(lateDecisionRow(3L, 940001L))
                                .linkedTaskId()));
    }

    // ============================== fixtures ==============================

    private static Map<String, Object> taskRow(long id, boolean published, boolean ended) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", "Annual inventory");
        row.put("task_status", published ? "COMPLETED" : "DRAFT");
        row.put("range_json", "{\"categoryId\":\"933000\",\"lifecycle\":\"ACTIVE\"}");
        row.put("starts_at", Timestamp.from(Instant.parse("2026-12-01T00:00:00Z")));
        row.put(
                "due_at",
                Timestamp.from(ended ? NOW.minusSeconds(60) : Instant.parse("2026-12-02T00:00:00Z")));
        row.put("timezone_id", published ? "Asia/Shanghai" : null);
        row.put("parameter_version_id", published ? 41L : null);
        row.put("expected_count", published ? 2L : 0L);
        row.put("captured_result_count", published ? 2L : 0L);
        row.put("assignment_version", published ? 1L : 0L);
        row.put("version", published ? 3L : 0L);
        row.put("creator_account_id", 933001L);
        row.put("publisher_account_id", published ? 933001L : null);
        row.put("copied_from_task_id", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-30T10:00:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-11-30T10:05:00Z")));
        row.put("published_at", published ? Timestamp.from(Instant.parse("2026-11-30T10:05:00Z")) : null);
        row.put(
                "started_at",
                published ? Timestamp.from(Instant.parse("2026-11-30T11:00:00Z")) : null);
        row.put("ended_at", ended ? Timestamp.from(Instant.parse("2026-12-01T11:00:00Z")) : null);
        row.put("end_reason", ended ? "Retain all evidence" : null);
        return row;
    }

    private static Map<String, Object> itemRow(long id, long assetId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("task_id", 7L);
        row.put("asset_id", assetId);
        row.put("asset_version", 6L);
        row.put(
                "snapshot_json",
                "{\"assetCode\":\"A-"
                        + assetId
                        + "\",\"locationId\":\"934002\",\"parameterVersionId\":41,"
                        + "\"warehouse_id\":934001,\"movements\":[{\"movement_id\":5}]}");
        row.put("executor_account_id", id % 2 == 0 ? null : 933002L);
        row.put("assignment_version", 1L);
        row.put("result_count", 0L);
        row.put("recheck_generation", 2L);
        return row;
    }

    private static Map<String, Object> lateCaseRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 91L);
        row.put("task_id", 7L);
        row.put("comparison_kind", "FROZEN_CONFLICT");
        row.put("needs_review", Boolean.TRUE);
        row.put("report_sha256", "cc".repeat(32));
        row.put("accepted_observation_id", null);
        row.put(
                "snapshot_json",
                "{\"taskId\":\"7\",\"observationId\":91,\"taskVersion\":3,"
                    + "\"frozenItem\":{\"item_id\":11,\"review\":{\"observation_id\":12,"
                    + "\"review_status\":\"ACCEPTED\",\"detail_json\":\"{\\\"actor_id\\\":13,"
                    + "\\\"note\\\":\\\"kept\\\"}\"}},"
                    + "\"sealedItems\":[{\"asset_id\":5,\"assetVersion\":6},{\"id\":6}],"
                    + "\"acceptedReviewAtTerminal\":null,\"reportSha256\":\""
                    + "cc".repeat(32)
                    + "\",\"facts\":{\"photoIds\":[\"11111111-1111-1111-1111-111111111111\"],"
                    + "\"actual\":{\"condition\":\"DAMAGED\"}}}");
        row.put("snapshot_sha256", "dd".repeat(32));
        row.put("created_at", Timestamp.from(Instant.parse("2026-12-02T09:00:00Z")));
        return row;
    }

    private static Map<String, Object> lateDecisionRow(long version, Long linkedTaskId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 91L);
        row.put("decision_version", version);
        row.put("method", linkedTaskId == null ? "NOTE" : "LINK_RECHECK");
        row.put("result", linkedTaskId == null ? "REVIEWED" : "FOLLOWUP_OPEN");
        row.put("reason", "operator confirmed the late note");
        row.put("evidence", "call log 2026-12-02");
        row.put("linked_task_id", linkedTaskId);
        row.put("actor_account_id", 933001L);
        row.put("actor_username", "inventory.admin");
        row.put("decided_at", Timestamp.from(Instant.parse("2026-12-03T01:02:03Z")));
        return row;
    }

    private static Map<String, Object> lateDamageRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 91L);
        row.put("version", 0L);
        row.put("damage_status", "OPEN");
        row.put("repair_id", null);
        row.put("repair_status", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-12-02T09:00:00Z")));
        row.put("updated_at", LocalDateTime.of(2026, 12, 2, 9, 30, 0));
        return row;
    }

    private static Map<String, Object> lateDamageDecisionRow(
            long afterVersion, Long reviewedAssetVersion) {
        var row = new LinkedHashMap<String, Object>();
        row.put("observation_id", 91L);
        row.put("before_version", afterVersion - 1);
        row.put("after_version", afterVersion);
        row.put("method", reviewedAssetVersion == null ? "REFRESH_REPAIR" : "FORMAL_REPAIR");
        row.put("damage_status", "PROCESSING");
        row.put("repair_id", 550001L);
        row.put("repair_status", "WAREHOUSE_PROCESSING");
        row.put("reviewed_asset_version", reviewedAssetVersion);
        row.put("reason", "screen cracked during the late check");
        row.put("evidence", "photo 11111111-1111-1111-1111-111111111111 reviewed");
        row.put("actor_account_id", 933001L);
        row.put("actor_username", "inventory.admin");
        row.put("decided_at", Timestamp.from(Instant.parse("2026-12-03T02:00:00Z")));
        return row;
    }

    // ============================== legacy oracles ==============================

    /** Verbatim pre-JAVA-17d InventoryService.clean. */
    private static Map<String, Object> legacyClean(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, raw) -> {
                    Object value = raw;
                    if (value != null && key.equals("range_json")) {
                        value =
                                JSON.readTree(
                                        legacyRangeJson(
                                                JSON.readValue(
                                                        value instanceof CharSequence
                                                                ? value.toString()
                                                                : JSON.writeValueAsString(value),
                                                        InventoryInput.Range.class)));
                    } else if (value != null && key.endsWith("_json")) {
                        value = JSON.readTree(value.toString());
                    } else if (value instanceof Timestamp || value instanceof LocalDateTime) {
                        value = legacyInstant(value).toString();
                    } else if (value != null && (key.equals("id") || key.endsWith("_id"))) {
                        value = value.toString();
                    }
                    result.put(key, value);
                });
        return result;
    }

    /** Verbatim pre-JAVA-17d InventoryService.rangeJson plus its identifier helper. */
    private static String legacyRangeJson(InventoryInput.Range range) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("usingOrganizationId", legacyIdentifier(range.usingOrganizationId()));
        snapshot.put("owningOrganizationId", legacyIdentifier(range.owningOrganizationId()));
        snapshot.put("categoryId", legacyIdentifier(range.categoryId()));
        snapshot.put("warehouseId", legacyIdentifier(range.warehouseId()));
        snapshot.put("locationId", legacyIdentifier(range.locationId()));
        snapshot.put("responsibleEmployeeId", legacyIdentifier(range.responsibleEmployeeId()));
        snapshot.put("lifecycle", range.lifecycle());
        snapshot.put("exception", range.exception());
        return JSON.writeValueAsString(snapshot);
    }

    private static String legacyIdentifier(Long id) {
        return id == null ? null : id.toString();
    }

    private static Instant legacyInstant(Object value) {
        return value instanceof Timestamp stamp
                ? stamp.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    /** Verbatim pre-JAVA-17d InventoryService.taskView (the clock comparison is injected). */
    private static Map<String, Object> legacyTaskView(Map<String, Object> row, boolean admin) {
        var result = legacyClean(row);
        if (!admin) {
            for (String field :
                    List.of(
                            "range_json",
                            "creator_account_id",
                            "publisher_account_id",
                            "end_reason",
                            "expected_count",
                            "captured_result_count")) {
                result.remove(field);
            }
        }
        result.put("overdue", overdue(row));
        return result;
    }

    /** The legacy overdue predicate, evaluated against the fixture clock. */
    private static boolean overdue(Map<String, Object> row) {
        Instant due = legacyInstant(row.get("due_at"));
        return "IN_PROGRESS".equals(row.get("task_status")) && !due.isAfter(NOW);
    }

    private static long legacyNumber(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    /** Verbatim pre-JAVA-17d InventoryService.list envelope. */
    private static Map<String, Object> legacyListPage(
            List<Map<String, Object>> items, long total, int page, int size, boolean admin) {
        return Map.of(
                "items", items, "total", total, "page", page, "pageSize", size, "canManage", admin);
    }

    /** Verbatim pre-JAVA-17d InventoryService.detail envelope. */
    private static Map<String, Object> legacyTaskDetail(
            Map<String, Object> task,
            List<Map<String, Object>> items,
            long total,
            int page,
            int size,
            boolean admin) {
        return Map.of(
                "task",
                task,
                "items",
                items,
                "total",
                total,
                "page",
                page,
                "pageSize",
                size,
                "canManage",
                admin);
    }

    /** Verbatim pre-JAVA-17d InventoryService.digest envelope (the /preview body). */
    private static Map<String, Object> legacyDigest(long count, String digest) {
        return Map.of("count", count, "digest", digest);
    }

    /** Verbatim pre-JAVA-17d InventoryService.referenceChoices envelope. */
    private static Map<String, Object> legacyCatalog(
            List<InventoryReferencePort.Choice> items, int page, int size) {
        return Map.of("items", items, "page", page, "pageSize", size);
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.wire. */
    private static Object legacyWire(Object value) {
        if (value instanceof Map<?, ?> source) {
            var result = new LinkedHashMap<String, Object>();
            source.forEach(
                    (key, item) -> {
                        String field = key.toString();
                        Object content =
                                field.endsWith("_json") && item instanceof String text
                                        ? JSON.readValue(text, Object.class)
                                        : item;
                        result.put(
                                field,
                                item != null && (field.equals("id") || field.endsWith("_id"))
                                        ? item.toString()
                                        : legacyWire(content));
                    });
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(InventoryMainWireShapeTest::legacyWire).toList();
        }
        if (value instanceof Timestamp || value instanceof LocalDateTime) {
            return legacyInstant(value).toString();
        }
        return value;
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.view. */
    private static Map<String, Object> legacyLateView(
            Map<String, Object> row,
            Map<String, Object> previous,
            List<Map<String, Object>> damage,
            boolean todoOpen) {
        return Map.of(
                "evidence",
                legacyWire(row),
                "review",
                previous.isEmpty()
                        ? Map.of(
                                "decision_version",
                                0,
                                "result",
                                Boolean.TRUE.equals(row.get("needs_review"))
                                        ? "OPEN"
                                        : "INFORMATION")
                        : legacyWire(previous),
                "damageFollowup",
                damage.isEmpty() ? Map.of() : legacyWire(damage.getFirst()),
                "todoOpen",
                todoOpen);
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.list envelope. */
    private static Map<String, Object> legacyLateList(
            Object task, List<Map<String, Object>> items, int page, int size, long total) {
        return Map.of(
                "task",
                task,
                "items",
                items,
                "page",
                page,
                "pageSize",
                size,
                "total",
                total,
                "supplementOnly",
                true);
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.detail history envelope. */
    private static Map<String, Object> legacyLateHistory(
            List<Object> items, int page, int size, long total) {
        return Map.of("items", items, "page", page, "pageSize", size, "total", total);
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.detail envelope. */
    private static Map<String, Object> legacyLateDetail(
            Object task, Object lateCase, Object history) {
        return Map.of("task", task, "case", lateCase, "history", history, "supplementOnly", true);
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.prepare response. */
    private static Map<String, Object> legacyPrepare(long taskId, int prepared, long taskVersion) {
        return Map.<String, Object>of(
                "taskId",
                Long.toString(taskId),
                "prepared",
                prepared,
                "moreMayRemain",
                prepared == 100,
                "taskVersion",
                taskVersion,
                "supplementOnly",
                true);
    }

    /** Verbatim pre-JAVA-17d InventoryLateService.decide response. */
    private static Map<String, Object> legacyDecide(
            long taskId,
            long observationId,
            long version,
            String method,
            String result,
            Long linkedTaskId) {
        var response = new LinkedHashMap<String, Object>();
        response.put("taskId", Long.toString(taskId));
        response.put("observationId", Long.toString(observationId));
        response.put("version", version + 1);
        response.put("result", result);
        response.put("method", method);
        response.put("linkedTaskId", linkedTaskId == null ? null : linkedTaskId.toString());
        response.put("processedBy", "inventory.admin");
        response.put("processedAt", "2026-11-27T06:00:00Z");
        response.put("supplementOnly", true);
        response.put("originalReportChanged", false);
        return response;
    }

    /** Verbatim pre-JAVA-17d InventoryLateDamageService.wire (no *_json branch at all). */
    private static Object legacyDamageWire(Object value) {
        if (value instanceof Map<?, ?> source) {
            var result = new LinkedHashMap<String, Object>();
            source.forEach(
                    (key, item) ->
                            result.put(
                                    key.toString(),
                                    item != null
                                                    && (key.toString().equals("id")
                                                            || key.toString().endsWith("_id"))
                                            ? item.toString()
                                            : legacyDamageWire(item)));
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(InventoryMainWireShapeTest::legacyDamageWire).toList();
        }
        if (value instanceof Timestamp || value instanceof LocalDateTime) {
            return legacyInstant(value).toString();
        }
        return value;
    }

    /** Verbatim pre-JAVA-17d InventoryLateDamageService.detail envelope. */
    private static Map<String, Object> legacyDamageDetail(
            long taskId,
            long taskVersion,
            long observation,
            long asset,
            Object currentAsset,
            Object damage,
            Object originalEvidence,
            Object history,
            String recheckResult,
            boolean todoOpen) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", taskVersion);
        result.put("observationId", Long.toString(observation));
        result.put("assetId", Long.toString(asset));
        result.put("currentAsset", currentAsset);
        result.put("damage", damage);
        result.put("originalEvidence", originalEvidence);
        result.put("history", history);
        result.put("recheckResult", recheckResult);
        result.put("todoOpen", todoOpen);
        result.put("supplementOnly", true);
        result.put("originalReportChanged", false);
        return result;
    }

    /** Verbatim pre-JAVA-17d InventoryLateDamageService.detail history envelope. */
    private static Map<String, Object> legacyDamageHistory(
            List<Object> items, int page, int size, long total) {
        return Map.of("items", items, "page", page, "pageSize", size, "total", total);
    }

    /** Verbatim pre-JAVA-17d InventoryLateDamageService.decide response. */
    private static Map<String, Object> legacyDamageDecide(
            long taskId,
            long observation,
            long version,
            String method,
            String damageStatus,
            String repairId,
            String repairStatus,
            String recheckResult,
            boolean todoOpen) {
        var response = new LinkedHashMap<String, Object>();
        response.put("taskId", Long.toString(taskId));
        response.put("observationId", Long.toString(observation));
        response.put("version", version + 1);
        response.put("method", method);
        response.put("damageStatus", damageStatus);
        response.put("repairId", repairId);
        response.put("repairStatus", repairStatus);
        response.put("recheckResult", recheckResult);
        response.put("todoOpen", todoOpen);
        response.put("processedBy", "inventory.admin");
        response.put("processedAt", "2026-11-27T06:00:00Z");
        response.put("supplementOnly", true);
        response.put("originalReportChanged", false);
        return response;
    }

    // ============================== helpers ==============================

    private static TreeSet<String> keys(JsonNode node) {
        var result = new TreeSet<String>();
        node.properties().forEach(entry -> result.add(entry.getKey()));
        return result;
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the same
        // bytes, and object key order was never stable in the legacy Map.of assemblies.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
