package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.inbound.FactoryReplacementInboundPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-16b wire equivalence for the repair factory HTTP exits. Every typed RepairViews factory
 * shape must serialize exactly like the frozen pre-refactor RepairFactoryService projections:
 * legacyWire/legacyFields/legacyInstant are the deleted RepairFactoryService.wire/fields/instant
 * value projections verbatim, and legacyScopeView/legacyRoundView/legacyReplyAssembly/
 * legacyGetAssembly/legacyRoundExit/legacyHistoryAssembly/legacyPartsAssembly/
 * legacyReplacementsAssembly/legacyCostSummary mirror the pre-refactor Map assemblies.
 * Comparison round-trips through JSON text so integral node widths normalize and object key
 * order (never stable in the Map.of responses) is ignored, while key presence, explicit nulls
 * and value shapes all count.
 */
class RepairFactoryWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- scope / round row projections ----

    @Test
    void scopeViewMatchesLegacyFieldsProjectionInBothStates() {
        var pending = scopePendingRow();
        assertWireEquals(legacyScopeView(pending), RepairViews.factoryScope(JSON, pending));

        var approved = scopeApprovedRow();
        assertWireEquals(legacyScopeView(approved), RepairViews.factoryScope(JSON, approved));

        // Conditional-null discrimination: the PENDING row keeps its always-keyed decision
        // segment as an explicit null, while cost_limit UNLIMITED stays explicit null too.
        var unlimited = new LinkedHashMap<>(approved);
        unlimited.put("cost_policy", "UNLIMITED");
        unlimited.put("cost_limit", null);
        assertWireEquals(legacyScopeView(unlimited), RepairViews.factoryScope(JSON, unlimited));
        var node = JSON.readTree(JSON.writeValueAsString(RepairViews.factoryScope(JSON, pending)));
        assertTrue(node.has("decisionBasis") && node.get("decisionBasis").isNull());
        assertTrue(node.has("decidedBy") && node.get("decidedBy").isNull());
        assertTrue(node.get("requestedBy").isTextual());
        assertTrue(node.get("costLimit").isTextual());
    }

    @Test
    void roundViewMatchesLegacyFieldsProjectionAcrossLifecycleStates() {
        var created = roundPendingRow();
        assertWireEquals(
                legacyRoundView(created, List.of(), null),
                RepairViews.factoryRound(JSON, created, List.of(), null));
        var createdNode =
                JSON.readTree(
                        JSON.writeValueAsString(
                                RepairViews.factoryRound(JSON, created, List.of(), null)));
        // costFact/quotes/originalIdentity were always-keyed; dispatch segments are explicit
        // nulls; the list item must not leak the detail-only actions/evidence/files keys.
        assertTrue(createdNode.has("costFact") && createdNode.get("costFact").isNull());
        assertTrue(createdNode.has("dispatchScopeId") && createdNode.get("dispatchScopeId").isNull());
        assertFalse(createdNode.has("actions"));
        assertFalse(createdNode.has("evidence"));
        assertFalse(createdNode.has("files"));

        var dispatched = roundDispatchedRow();
        assertWireEquals(
                legacyRoundView(dispatched, List.of(quoteRow()), null),
                RepairViews.factoryRound(JSON, dispatched, List.of(quoteRow()), null));

        var received = roundReceivedRow();
        assertWireEquals(
                legacyRoundView(received, List.of(quoteRow(), quoteRow2()), costRow()),
                RepairViews.factoryRound(
                        JSON, received, List.of(quoteRow(), quoteRow2()), costRow()));
    }

    // ---- detail exits ----

    @Test
    void roundDetailExitMatchesLegacyAssembly() {
        var row = roundReceivedRow();
        var quotes = List.of(quoteRow(), quoteRow2());
        var files = List.of(evidenceFile());
        var legacy = new LinkedHashMap<>(legacyRoundView(row, quotes, costRow()));
        legacy.put("actions", legacyRoundActions(true, true));
        legacy.put("evidence", legacyWire(List.of(evidenceRow())));
        legacy.put("files", files);
        var typed =
                RepairViews.factoryRound(JSON, row, quotes, costRow())
                        .withRoundDetail(
                                new RepairViews.FactoryRoundActions(true, false, false),
                                RepairViews.factoryEvidences(List.of(evidenceRow())),
                                files);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(node.path("evidence").get(0).has("object_id"));
        assertTrue(node.path("evidence").get(0).get("uploader_account_id").isNumber());
    }

    @Test
    void historyPageMatchesLegacyWireAssemblyWithSnakeCaseNumericIds() {
        var legacy =
                Map.<String, Object>of(
                        "items", legacyWire(List.of(factoryEventRow())),
                        "total", 1L,
                        "page", 1,
                        "pageSize", 20);
        var typed =
                new RepairViews.Page<>(
                        RepairViews.factoryEvents(JSON, List.of(factoryEventRow())), 1, 1, 20);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        // The factory history wire never stringified ids and kept the snake_case keys.
        assertTrue(node.get("items").get(0).has("repair_id"));
        assertTrue(node.get("items").get(0).get("id").isNumber());
        assertTrue(node.get("items").get(0).has("detail_json"));
        var bare = new LinkedHashMap<>(factoryEventRow());
        bare.put("scope_id", null);
        bare.put("round_id", null);
        assertWireEquals(
                Map.<String, Object>of(
                        "items", legacyWire(List.of(bare)), "total", 0L, "page", 1, "pageSize", 20),
                new RepairViews.Page<>(RepairViews.factoryEvents(JSON, List.of(bare)), 0, 1, 20));
    }

    @Test
    void getDetailExitMatchesLegacyAssemblyInBothStates() {
        var row = headerRow();
        var pending = scopePendingRow();
        var approved = scopeApprovedRow();
        var active = roundDispatchedRow();
        var guardMap = factorySummaryMap(true, true);
        var cost = legacyCostSummary();
        var legacy =
                legacyGetAssembly(
                        row,
                        7L,
                        legacyScopeView(pending),
                        legacyRoundView(active, List.of(quoteRow()), null),
                        legacyWire(guardMap),
                        true,
                        cost,
                        legacyGetActions(true, true));
        var typed =
                RepairViews.factoryDetail(
                        row,
                        7L,
                        RepairViews.factoryScope(JSON, pending),
                        null,
                        RepairViews.factoryRound(JSON, active, List.of(quoteRow()), null),
                        RepairViews.factorySummary(guardMap),
                        true,
                        typedCostSummary(),
                        new RepairViews.FactoryActions(true, false, false, false, true, true, false, true, true));
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(node.has("pendingScope"));
        assertTrue(node.has("approvedScope") && node.get("approvedScope").isNull());
        assertTrue(node.has("summary") && node.get("summary").has("scope"));
        assertTrue(node.has("activeRound") && node.get("activeRound").has("quotes"));
        assertFalse(node.path("activeRound").has("actions"));

        var lean =
                legacyGetAssembly(row, null, null, null, null, false, cost, legacyGetActions(false, false));
        var leanTyped =
                RepairViews.factoryDetail(
                        row,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        typedCostSummary(),
                        new RepairViews.FactoryActions(false, false, false, false, false, false, false, false, false));
        assertWireEquals(lean, leanTyped);
        var leanNode = JSON.readTree(JSON.writeValueAsString(leanTyped));
        // The get exit always emitted its null chips (they were plain puts, not conditional).
        assertTrue(leanNode.has("pendingScope") && leanNode.get("pendingScope").isNull());
        assertTrue(leanNode.has("summary") && leanNode.get("summary").isNull());
        assertTrue(leanNode.has("lockVersion") && leanNode.get("lockVersion").isNull());
    }

    // ---- command receipts ----

    @Test
    void scopeCommandReceiptMatchesLegacyReplyAssembly() {
        var legacy = legacyReplyAssembly(headerRow(), 7L, legacyScopeView(scopeApprovedRow()), null);
        var typed =
                RepairViews.factoryReply(
                        headerRow(),
                        7L,
                        RepairViews.factoryScope(JSON, scopeApprovedRow()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        // Conditional-key discrimination on the stored receipt shape.
        assertTrue(node.has("scope"));
        assertFalse(node.has("round"));
        assertFalse(node.has("partsChangeId"));
        assertFalse(node.has("objectId"));
    }

    @Test
    void roundCommandReceiptMatchesLegacyReplyAssembly() {
        var legacy =
                legacyReplyAssembly(
                        headerRow(),
                        null,
                        legacyScopeView(scopeApprovedRow()),
                        legacyRoundView(roundReceivedRow(), List.of(quoteRow()), costRow()));
        var typed =
                RepairViews.factoryReply(
                        headerRow(),
                        null,
                        RepairViews.factoryScope(JSON, scopeApprovedRow()),
                        RepairViews.factoryRound(
                                JSON, roundReceivedRow(), List.of(quoteRow()), costRow()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(node.has("lockVersion") && node.get("lockVersion").isNull());
        assertTrue(node.path("round").has("costFact"));
        assertFalse(node.path("round").has("files"));
    }

    @Test
    void partsReplacementAndEvidenceReceiptsMatchLegacyAppendedAssemblies() {
        var scope = legacyScopeView(scopeApprovedRow());
        var round = legacyRoundView(roundReceivedRow(), List.of(), null);
        var partsLegacy = legacyReplyAssembly(headerRow(), 7L, scope, round);
        partsLegacy.put("partsChangeId", "110");
        assertWireEquals(
                partsLegacy,
                RepairViews.factoryReply(
                        headerRow(),
                        7L,
                        RepairViews.factoryScope(JSON, scopeApprovedRow()),
                        RepairViews.factoryRound(JSON, roundReceivedRow(), List.of(), null),
                        "110",
                        null,
                        null,
                        null,
                        null,
                        null));

        var replacementLegacy = legacyReplyAssembly(headerRow(), 7L, scope, round);
        replacementLegacy.put("replacementId", "120");
        replacementLegacy.put("sourceId", "0f8fad5b-d9cb-469f-a165-70867728950e");
        replacementLegacy.put("replacementAssetId", "41");
        replacementLegacy.put("inboundReceiptId", "55");
        assertWireEquals(
                replacementLegacy,
                RepairViews.factoryReply(
                        headerRow(),
                        7L,
                        RepairViews.factoryScope(JSON, scopeApprovedRow()),
                        RepairViews.factoryRound(JSON, roundReceivedRow(), List.of(), null),
                        null,
                        "120",
                        "0f8fad5b-d9cb-469f-a165-70867728950e",
                        "41",
                        "55",
                        null));

        var evidenceLegacy = legacyReplyAssembly(headerRow(), 7L, scope, round);
        evidenceLegacy.put("objectId", "1f8fad5b-d9cb-469f-a165-70867728950e");
        assertWireEquals(
                evidenceLegacy,
                RepairViews.factoryReply(
                        headerRow(),
                        7L,
                        RepairViews.factoryScope(JSON, scopeApprovedRow()),
                        RepairViews.factoryRound(JSON, roundReceivedRow(), List.of(), null),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "1f8fad5b-d9cb-469f-a165-70867728950e"));
    }

    // ---- parts / replacements / cost exits ----

    @Test
    void partsPageMatchesLegacyMapOfAssembly() {
        var row = partRow();
        var legacy =
                Map.<String, Object>of(
                        "page",
                        1,
                        "pageSize",
                        20,
                        "total",
                        1L,
                        "items",
                        List.of(legacyReplacementlessPartView(row, 70L)),
                        "canRecord",
                        true);
        var typed =
                new RepairViews.FactoryPartsPage(
                        1, 20, 1, List.of(RepairViews.factoryPart(JSON, row, 70L)), true);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(node.get("items").get(0).get("id").isTextual());
        assertTrue(node.get("items").get(0).get("recordedBy").isTextual());
    }

    @Test
    void replacementsPageMatchesLegacyAssemblyInBothInboundStates() {
        var row = replacementRow();
        var withInbound = new LinkedHashMap<>(legacyReplacementView(row, inboundContext()));
        var legacy =
                Map.<String, Object>of(
                        "items", List.of(withInbound), "canRecord", true, "canRequestScrap", false);
        var typed =
                new RepairViews.FactoryReplacementsPage(
                        List.of(RepairViews.factoryReplacement(JSON, row, inboundContext())),
                        true,
                        false);
        assertWireEquals(legacy, typed);

        var legacyBare =
                Map.<String, Object>of(
                        "items",
                        List.of(legacyReplacementView(row, null)),
                        "canRecord",
                        false,
                        "canRequestScrap",
                        false);
        var typedBare =
                new RepairViews.FactoryReplacementsPage(
                        List.of(RepairViews.factoryReplacement(JSON, row, null)), false, false);
        assertWireEquals(legacyBare, typedBare);
        var node = JSON.readTree(JSON.writeValueAsString(typedBare));
        assertTrue(node.get("items").get(0).has("inbound") && node.get("items").get(0).get("inbound").isNull());
        assertTrue(
                node.get("items").get(0).has("replacementAssetId")
                        && node.get("items").get(0).get("replacementAssetId").isNull());
    }

    @Test
    void costSummaryMatchesLegacyLinkedHashMap() {
        assertWireEquals(legacyCostSummary(), typedCostSummary());
        var node = JSON.readTree(JSON.writeValueAsString(typedCostSummary()));
        assertEquals(8, size(node));
        assertTrue(node.get("costLimit").isTextual());
        assertTrue(node.get("unassessedCount").isNumber());
    }

    // ---- Guard Map coupling: the 16a FactorySummary consumption shape stays frozen ----

    @Test
    void factorySummaryChipKeepsTheFrozenConsumedShape() throws Exception {
        // RepairFactoryGuard.summary is untouched by 16b and RepairService (16a) plus the
        // factory get exit both project it through RepairViews.factorySummary; the guard Map
        // key shape and the FactorySummary tree must not drift.
        var full = factorySummaryMap(true, true);
        assertWireEquals(legacyWire(full), RepairViews.factorySummary(full));
        var node = JSON.readTree(JSON.writeValueAsString(RepairViews.factorySummary(full)));
        assertEquals(
                List.of("actualDispatchCount", "followupOpen", "round", "scope"),
                sortedKeys(node));
        assertEquals(
                List.of("id", "scopeStatus", "version"), sortedKeys(node.path("scope")));
        assertEquals(
                List.of(
                        "dispatchedAt",
                        "expectedReturnDate",
                        "id",
                        "receivedAt",
                        "roundNumber",
                        "roundStatus",
                        "version"),
                sortedKeys(node.path("round")));
        assertTrue(node.path("scope").get("id").isTextual());
        assertTrue(node.path("scope").get("version").isNumber());

        var bare = factorySummaryMap(false, false);
        assertWireEquals(legacyWire(bare), RepairViews.factorySummary(bare));
        // 16a RepairDetail.factorySummary consumption: same chip, same replay path.
        var replayed =
                JSON.readValue(
                        JSON.writeValueAsString(legacyWire(bare)), RepairViews.FactorySummary.class);
        assertWireEquals(legacyWire(bare), replayed);

        // Anti-degradation freeze of the 16a coupling registered in JAVA-16a §6-3: the detail
        // exit keeps consuming exactly this chip record under the same conditional-key semantics.
        var detail = RepairViews.RepairDetail.class;
        var component =
                java.util.Arrays.stream(detail.getRecordComponents())
                        .filter(c -> c.getName().equals("factorySummary"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(RepairViews.FactorySummary.class, component.getType());
        var accessor = detail.getMethod("factorySummary");
        var inclusion =
                accessor.getAnnotation(com.fasterxml.jackson.annotation.JsonInclude.class)
                        != null
                ? accessor.getAnnotation(com.fasterxml.jackson.annotation.JsonInclude.class)
                : detail.getDeclaredField("factorySummary")
                        .getAnnotation(com.fasterxml.jackson.annotation.JsonInclude.class);
        assertEquals(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                inclusion.value());
    }

    // ---- idempotency stored-shape replays (fams_repair_receipt.response_json) ----

    @Test
    void legacyStoredReceiptsOfEveryFactoryShapeStillDeserialize() {
        var scope = legacyScopeView(scopeApprovedRow());
        var round = legacyRoundView(roundReceivedRow(), List.of(quoteRow()), costRow());
        var storeds =
                List.of(
                        // FACTORY_SCOPE_REQUEST/DECIDE/WITHDRAW stored the scope-only reply.
                        JSON.writeValueAsString(legacyReplyAssembly(headerRow(), 7L, scope, null)),
                        // FACTORY_ROUND_CREATE/CANCEL/DISPATCH/RECEIVE/QUOTE/COST stored both.
                        JSON.writeValueAsString(
                                legacyReplyAssembly(headerRow(), 7L, scope, round)),
                        // FACTORY_PARTS appended partsChangeId.
                        withExtra(
                                legacyReplyAssembly(headerRow(), null, scope, round),
                                "partsChangeId",
                                "110"),
                        // FACTORY_REPLACEMENT appended the four replacement keys.
                        withExtra(
                                legacyReplyAssembly(headerRow(), 7L, scope, round),
                                "replacementId",
                                "120",
                                "sourceId",
                                "0f8fad5b-d9cb-469f-a165-70867728950e",
                                "replacementAssetId",
                                "41",
                                "inboundReceiptId",
                                "55"),
                        // FACTORY_EVIDENCE appended objectId.
                        withExtra(
                                legacyReplyAssembly(headerRow(), 7L, scope, round),
                                "objectId",
                                "1f8fad5b-d9cb-469f-a165-70867728950e"));
        for (var stored : storeds) {
            var replayed = JSON.readValue(stored, RepairViews.FactoryReply.class);
            assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
        }
    }

    @Test
    void typedFactoryViewsRoundTripToTheirOwnStoredShape() {
        assertRoundTrip(
                RepairViews.factoryScope(JSON, scopeApprovedRow()),
                RepairViews.FactoryScopeView.class);
        assertRoundTrip(
                RepairViews.factoryRound(JSON, roundReceivedRow(), List.of(quoteRow()), costRow()),
                RepairViews.FactoryRoundView.class);
        assertRoundTrip(
                RepairViews.factoryReply(
                        headerRow(),
                        7L,
                        RepairViews.factoryScope(JSON, scopeApprovedRow()),
                        RepairViews.factoryRound(
                                JSON, roundReceivedRow(), List.of(quoteRow()), costRow()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                RepairViews.FactoryReply.class);
        assertRoundTrip(
                RepairViews.factoryEvents(JSON, List.of(factoryEventRow())).get(0),
                RepairViews.FactoryEventRow.class);
        assertRoundTrip(
                RepairViews.factoryPart(JSON, partRow(), 70L), RepairViews.FactoryPartRow.class);
        assertRoundTrip(
                RepairViews.factoryReplacement(JSON, replacementRow(), inboundContext()),
                RepairViews.FactoryReplacementRow.class);
        assertRoundTrip(typedCostSummary(), RepairViews.FactoryCostSummary.class);
    }

    // ---- helpers: fixtures ----

    private static Map<String, Object> headerRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L);
        row.put("asset_id", 40L);
        row.put("version", 5L);
        row.put("asset_version", 4L);
        row.put("ownership_version", 2L);
        row.put("repair_status", "WAREHOUSE_PROCESSING");
        row.put("processor_account_id", 13L);
        return row;
    }

    private static Map<String, Object> scopePendingRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 80L);
        row.put("repair_id", 7L);
        row.put("scope_status", "PENDING");
        row.put("allowed_method", "FACTORY_COST_ALLOWED");
        row.put("cost_policy", "LIMITED");
        row.put("cost_limit", new BigDecimal("500.00"));
        row.put("request_reason", "屏幕维修");
        row.put("requested_by", 13L);
        row.put("requested_at", Timestamp.from(Instant.parse("2026-11-23T01:00:00Z")));
        row.put("decision_reason", null);
        row.put("decided_by", null);
        row.put("decided_at", null);
        row.put("version", 0L);
        row.put(
                "request_snapshot_json",
                "{\"repairId\":\"7\",\"requestVersion\":5,\"lockVersion\":7,"
                        + "\"costBasis\":{\"confirmedTotal\":\"0.00\",\"unassessedCount\":0}}");
        row.put("decision_snapshot_json", null);
        return row;
    }

    private static Map<String, Object> scopeApprovedRow() {
        var row = new LinkedHashMap<>(scopePendingRow());
        row.put("scope_status", "APPROVED");
        row.put("decision_reason", "同意");
        row.put("decided_by", 11L);
        row.put("decided_at", Timestamp.from(Instant.parse("2026-11-24T01:00:00Z")));
        row.put("version", 1L);
        row.put(
                "decision_snapshot_json",
                "{\"request\":{\"repairId\":\"7\"},\"decision\":{\"result\":\"APPROVE\"}}");
        return row;
    }

    private static Map<String, Object> roundPendingRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 70L);
        row.put("repair_id", 7L);
        row.put("asset_id", 40L);
        row.put("scope_id", 80L);
        row.put("dispatch_scope_id", null);
        row.put("round_number", 1);
        row.put("round_status", "PENDING_DISPATCH");
        row.put("provider_name", "厂商A");
        row.put("service_contact", "李四");
        row.put("service_phone", "13800000000");
        row.put("expected_return_date", java.sql.Date.valueOf("2026-12-05"));
        row.put("fault", "屏幕不亮");
        row.put(
                "original_identity_json",
                "{\"id\":\"40\",\"assetCode\":\"FA-1\",\"assetSerial\":\"SN-1\"}");
        row.put("created_by", 13L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-24T02:00:00Z")));
        row.put("dispatched_at", null);
        row.put("dispatched_by", null);
        row.put("dispatch_evidence", null);
        row.put("received_at", null);
        row.put("received_by", null);
        row.put("receive_location_id", null);
        row.put("receive_location_version", null);
        row.put("condition_result", null);
        row.put("treatment_result", null);
        row.put("actual_cost", null);
        row.put("cancel_reason", null);
        row.put("version", 0L);
        return row;
    }

    private static Map<String, Object> roundDispatchedRow() {
        var row = new LinkedHashMap<>(roundPendingRow());
        row.put("round_status", "DISPATCHED");
        row.put("dispatch_scope_id", 80L);
        row.put("dispatched_at", Timestamp.from(Instant.parse("2026-11-25T02:00:00Z")));
        row.put("dispatched_by", 13L);
        row.put("dispatch_evidence", "已寄出");
        row.put("version", 1L);
        return row;
    }

    private static Map<String, Object> roundReceivedRow() {
        var row = new LinkedHashMap<>(roundDispatchedRow());
        row.put("round_status", "RECEIVED");
        row.put("received_at", Timestamp.from(Instant.parse("2026-11-27T02:00:00Z")));
        row.put("received_by", 13L);
        row.put("receive_location_id", 9L);
        row.put("receive_location_version", 4L);
        row.put("condition_result", "RESTORED");
        row.put("treatment_result", "换屏");
        row.put("actual_cost", new BigDecimal("120.00"));
        row.put("version", 2L);
        return row;
    }

    private static Map<String, Object> quoteRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 90L);
        row.put("round_id", 70L);
        row.put("amount", new BigDecimal("110.00"));
        row.put("evidence_ids_json", "[\"0f8fad5b-d9cb-469f-a165-70867728950e\"]");
        row.put("snapshot_json", "{\"input\":{\"amount\":\"110.00\"}}");
        row.put("created_by", 13L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-26T01:00:00Z")));
        return row;
    }

    private static Map<String, Object> quoteRow2() {
        var row = new LinkedHashMap<>(quoteRow());
        row.put("id", 91L);
        row.put("amount", new BigDecimal("115.50"));
        return row;
    }

    private static Map<String, Object> costRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("round_id", 70L);
        row.put("amount", new BigDecimal("120.00"));
        row.put(
                "evidence_ids_json",
                "[\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"1f8fad5b-d9cb-469f-a165-70867728950e\"]");
        row.put("snapshot_json", "{\"input\":{\"amount\":\"120.00\"}}");
        row.put("recorded_by", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-27T02:30:00Z")));
        return row;
    }

    private static Map<String, Object> factoryEventRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 100L);
        row.put("repair_id", 7L);
        row.put("scope_id", 80L);
        row.put("round_id", 70L);
        row.put("action", "FACTORY_ROUND_DISPATCHED");
        row.put("before_version", 5L);
        row.put("after_version", 6L);
        row.put("actor_account_id", 13L);
        row.put("actor_username", "wh1");
        row.put("actor_role", "WAREHOUSE_OPERATOR");
        row.put("detail_json", "{\"input\":{\"evidence\":\"已寄出\"}}");
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-25T02:00:01Z")));
        return row;
    }

    private static Map<String, Object> evidenceRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("object_id", "0f8fad5b-d9cb-469f-a165-70867728950e");
        row.put("purpose", "FAULT");
        row.put("content_sha256", "a".repeat(64));
        row.put("uploader_account_id", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-24T03:00:00Z")));
        return row;
    }

    private static Map<String, Object> partRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 110L);
        row.put("repair_id", 7L);
        row.put("round_id", 70L);
        row.put("asset_id", 40L);
        row.put("part_name", "屏幕");
        row.put("before_info", "碎裂");
        row.put("after_info", "已更换");
        row.put("observed_at", Timestamp.from(Instant.parse("2026-11-27T03:00:00Z")));
        row.put(
                "evidence_ids_json",
                "[\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"1f8fad5b-d9cb-469f-a165-70867728950e\"]");
        row.put("snapshot_json", "{\"partName\":\"屏幕\"}");
        row.put("recorded_by", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-27T03:00:01Z")));
        return row;
    }

    private static Map<String, Object> replacementRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 120L);
        row.put("source_id", "0f8fad5b-d9cb-469f-a165-70867728950e");
        row.put("repair_id", 7L);
        row.put("round_id", 70L);
        row.put("original_asset_id", 40L);
        row.put("replacement_asset_id", 41L);
        row.put("inbound_receipt_id", 55L);
        row.put("original_disposition", "FACTORY_RETAINED");
        row.put("observed_at", Timestamp.from(Instant.parse("2026-11-26T04:00:00Z")));
        row.put("evidence_ids_json", "[\"2f8fad5b-d9cb-469f-a165-70867728950e\"]");
        row.put("snapshot_json", "{\"originalDisposition\":\"FACTORY_RETAINED\"}");
        row.put("recorded_by", 13L);
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-26T04:00:01Z")));
        return row;
    }

    private static FactoryReplacementInboundPort.Context inboundContext() {
        return new FactoryReplacementInboundPort.Context(
                "55", 3, "COMPLETED", "IN-2026-1126-001");
    }

    private static RepairFactoryService.EvidenceFile evidenceFile() {
        return new RepairFactoryService.EvidenceFile(
                "0f8fad5b-d9cb-469f-a165-70867728950e",
                "quote.png",
                "image/png",
                2048L,
                "CLEAN",
                1L,
                "2026-11-24T03:00:00Z");
    }

    /** Guard-style RepairFactoryGuard.summary output (16b keeps the Map; consumed shape frozen). */
    private static Map<String, Object> factorySummaryMap(boolean withScope, boolean withRound) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "scope",
                withScope
                        ? Map.of("id", "80", "scopeStatus", "APPROVED", "version", 1L)
                        : null);
        Map<String, Object> round = null;
        if (withRound) {
            round = new LinkedHashMap<>();
            round.put("id", "70");
            round.put("roundNumber", 1L);
            round.put("roundStatus", "DISPATCHED");
            round.put("expectedReturnDate", "2026-12-05");
            round.put("dispatchedAt", Timestamp.from(Instant.parse("2026-11-25T02:00:00Z")));
            round.put("receivedAt", null);
            round.put("version", 1L);
        }
        result.put("round", round);
        result.put("actualDispatchCount", 1L);
        result.put("followupOpen", true);
        return result;
    }

    // ---- helpers: typed cost summary twins of the legacy values ----

    private static Map<String, Object> legacyCostSummary() {
        var result = new LinkedHashMap<String, Object>();
        result.put("confirmedTotal", "120.00");
        result.put("unassessedCount", 0L);
        result.put("confirmedRoundCount", 1);
        result.put("latestQuoteId", "91");
        result.put("costPolicy", "LIMITED");
        result.put("costLimit", "500.00");
        result.put("overrun", false);
        result.put("followupOpen", false);
        return result;
    }

    private static RepairViews.FactoryCostSummary typedCostSummary() {
        return new RepairViews.FactoryCostSummary(
                "120.00", 0, 1, "91", "LIMITED", "500.00", false, false);
    }

    // ---- helpers: legacy assemblies (verbatim pre-JAVA-16b RepairFactoryService code) ----

    private static Map<String, Object> legacyScopeView(Map<String, Object> row) {
        var result =
                legacyFields(
                        row,
                        "id",
                        "repair_id",
                        "scope_status",
                        "allowed_method",
                        "cost_policy",
                        "cost_limit",
                        "request_reason",
                        "requested_by",
                        "requested_at",
                        "decision_reason",
                        "decided_by",
                        "decided_at",
                        "version");
        result.put("requestBasis", JSON.readTree(row.get("request_snapshot_json").toString()));
        result.put(
                "decisionBasis",
                row.get("decision_snapshot_json") == null
                        ? null
                        : JSON.readTree(row.get("decision_snapshot_json").toString()));
        return result;
    }

    private static Map<String, Object> legacyRoundView(
            Map<String, Object> row, List<Map<String, Object>> quoteRows, Map<String, Object> cost) {
        var result =
                legacyFields(
                        row,
                        "id",
                        "repair_id",
                        "asset_id",
                        "scope_id",
                        "dispatch_scope_id",
                        "round_number",
                        "round_status",
                        "provider_name",
                        "service_contact",
                        "service_phone",
                        "expected_return_date",
                        "fault",
                        "created_by",
                        "created_at",
                        "dispatched_at",
                        "dispatched_by",
                        "dispatch_evidence",
                        "received_at",
                        "received_by",
                        "receive_location_id",
                        "receive_location_version",
                        "condition_result",
                        "treatment_result",
                        "actual_cost",
                        "cancel_reason",
                        "version");
        result.put(
                "quotes",
                quoteRows.stream()
                        .map(
                                q -> {
                                    var value =
                                            legacyFields(
                                                    q,
                                                    "id",
                                                    "round_id",
                                                    "amount",
                                                    "created_by",
                                                    "created_at");
                                    value.put(
                                            "evidenceIds",
                                            JSON.readTree(q.get("evidence_ids_json").toString()));
                                    return value;
                                })
                        .toList());
        Map<String, Object> fact = null;
        if (cost != null) {
            fact = legacyFields(cost, "round_id", "amount", "recorded_by", "recorded_at");
            fact.put("evidenceIds", JSON.readTree(cost.get("evidence_ids_json").toString()));
        }
        result.put("costFact", fact);
        result.put("originalIdentity", JSON.readTree(row.get("original_identity_json").toString()));
        return result;
    }

    private static Map<String, Object> legacyReplyAssembly(
            Map<String, Object> row,
            Object lockVersion,
            Map<String, Object> scopeSegment,
            Map<String, Object> roundSegment) {
        var result = new LinkedHashMap<String, Object>();
        result.put("repairId", row.get("id").toString());
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("status", row.get("repair_status"));
        result.put(
                "processorAccountId",
                row.get("processor_account_id") == null
                        ? null
                        : row.get("processor_account_id").toString());
        result.put("lockVersion", lockVersion);
        if (scopeSegment != null) {
            result.put("scope", scopeSegment);
        }
        if (roundSegment != null) {
            result.put("round", roundSegment);
        }
        return result;
    }

    private static Map<String, Object> legacyGetAssembly(
            Map<String, Object> row,
            Object lockVersion,
            Map<String, Object> pendingScope,
            Map<String, Object> activeRound,
            Object summary,
            boolean photosAvailable,
            Map<String, Object> costSummary,
            Map<String, Object> actions) {
        var result = legacyReplyAssembly(row, lockVersion, null, null);
        result.put("pendingScope", pendingScope);
        // The get exit's approvedScope/round-less state keeps the same always-keyed puts.
        result.put("approvedScope", null);
        result.put("activeRound", activeRound);
        result.put("summary", summary);
        result.put("photosAvailable", photosAvailable);
        result.put("costSummary", costSummary);
        result.put("actions", actions);
        return result;
    }

    private static Map<String, Object> legacyGetActions(boolean on, boolean manage) {
        return Map.of(
                "requestScope", on && manage,
                "decideScope", false,
                "withdrawScope", false,
                "createRound", false,
                "dispatch", manage,
                "cancelRound", manage,
                "receive", false,
                "uploadEvidence", on,
                "requestCostScope", on);
    }

    private static Map<String, Object> legacyRoundActions(boolean writable, boolean recorded) {
        return Map.of(
                "uploadFinancialEvidence", writable,
                "recordQuote", writable && !recorded,
                "recordCost", writable && !recorded);
    }

    private static Map<String, Object> legacyReplacementlessPartView(
            Map<String, Object> item, long roundId) {
        return Map.<String, Object>of(
                "id",
                item.get("id").toString(),
                "roundId",
                Long.toString(roundId),
                "assetId",
                item.get("asset_id").toString(),
                "partName",
                item.get("part_name"),
                "beforeInfo",
                item.get("before_info"),
                "afterInfo",
                item.get("after_info"),
                "observedAt",
                legacyInstant(item.get("observed_at")).toString(),
                "recordedAt",
                legacyInstant(item.get("recorded_at")).toString(),
                "evidenceIds",
                JSON.readTree(item.get("evidence_ids_json").toString()),
                "recordedBy",
                item.get("recorded_by").toString());
    }

    private static Map<String, Object> legacyReplacementView(
            Map<String, Object> item, FactoryReplacementInboundPort.Context inbound) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", item.get("id").toString());
        result.put("sourceId", item.get("source_id"));
        result.put("originalAssetId", item.get("original_asset_id").toString());
        result.put("originalDisposition", item.get("original_disposition"));
        result.put("observedAt", legacyInstant(item.get("observed_at")).toString());
        result.put("recordedAt", legacyInstant(item.get("recorded_at")).toString());
        result.put("evidenceIds", JSON.readTree(item.get("evidence_ids_json").toString()));
        result.put("inbound", inbound);
        result.put("replacementAssetId", inbound == null
                ? null
                : item.get("replacement_asset_id").toString());
        return result;
    }

    /** Frozen pre-JAVA-16b RepairFactoryService.fields projection; reference oracle only. */
    private static Map<String, Object> legacyFields(Map<String, Object> row, String... names) {
        var result = new LinkedHashMap<String, Object>();
        for (var name : names) {
            String[] words = name.split("_");
            var label = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) {
                label.append(Character.toUpperCase(words[i].charAt(0)))
                        .append(words[i].substring(1));
            }
            Object value = row.get(name);
            result.put(
                    label.toString(),
                    value instanceof Number
                                    && (name.equals("id")
                                            || name.endsWith("_id")
                                            || name.endsWith("_by"))
                            ? value.toString()
                            : legacyWire(value));
        }
        return result;
    }

    /** Frozen pre-JAVA-16b RepairFactoryService.wire projection; reference oracle only. */
    private static Object legacyWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().endsWith("_json") && v instanceof String text
                                            ? JSON.readTree(text)
                                            : legacyWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RepairFactoryWireShapeTest::legacyWire).toList();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Timestamp
                || value instanceof LocalDateTime
                || value instanceof Instant) {
            return legacyInstant(value).toString();
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value;
    }

    /** Frozen pre-JAVA-16b RepairFactoryService.instant; reference oracle only. */
    private static Instant legacyInstant(Object value) {
        if (value instanceof Timestamp time) {
            return time.toInstant();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC);
        }
        return value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    // ---- helpers: assertions ----

    private static String withExtra(Map<String, Object> base, Object... keysAndValues) {
        var copy = new LinkedHashMap<>(base);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            copy.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return JSON.writeValueAsString(copy);
    }

    private static void assertRoundTrip(Object view, Class<?> type) {
        var stored = JSON.writeValueAsString(view);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static List<String> sortedKeys(JsonNode node) {
        var keys = new java.util.ArrayList<String>();
        node.propertyNames().forEach(keys::add);
        java.util.Collections.sort(keys);
        return keys;
    }

    private static int size(JsonNode node) {
        int count = 0;
        for (var ignored : node.propertyNames()) {
            count++;
        }
        return count;
    }
}
