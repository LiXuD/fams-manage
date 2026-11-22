package com.fams.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.attachment.AttachmentFormat;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentStorage;
import com.fams.attachment.MalwareScanner;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JAVA-15 wire equivalence: every inbound typed view must serialize exactly like the frozen
 * pre-refactor row projections (the legacyXxx methods below are verbatim copies of the
 * pre-JAVA-15 InboundService/InboundReassignmentService/attachment Map assemblies, kept as the
 * reference oracle). Comparison is on JSON text round-trip trees, so key presence, explicit
 * nulls and value shapes all count; object key order never was contractual (the legacy maps
 * were HashMap/Map.of with unstable order). Coverage: all receipt states (conditional key
 * both ways), the accept triple, the reassignment snapshot, the unavailable receipt, every
 * attachment exit, each idempotency-stored shape replayed from its pre-batch JSON, and the
 * {@code FactoryReplacementInboundPort.fromView} consumption through the wire Map wrappers.
 */
class InboundWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    // ---- receipt document (get/create/update/submit/cancel/decision/claim/reassign body) ----

    @Test
    void completedReceiptMatchesLegacyAssembly() {
        var row = completedReceiptRow();
        var legacyItems = List.of(
                legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L), acceptanceRow(70L, "late label", 9L)),
                legacyItem(itemRow(6L, "PENDING"), lockRow(6L), null));
        var typedItems = List.of(
                InboundViews.item(itemRow(5L, "ACCEPTED"), lockRow(5L), acceptanceRow(70L, "late label", 9L)),
                InboundViews.item(itemRow(6L, "PENDING"), lockRow(6L), null));
        var legacy = legacyReceipt(row, legacyItems, originRow(), decisionRow("ok note"),
                List.of(scopeRow(5L), scopeRow(6L)), ownerRow(null));
        var typed = InboundViews.receipt(row, typedItems, originRow(), decisionRow("ok note"),
                List.of(scopeRow(5L), scopeRow(6L)), ownerRow(null));
        assertWireEquals(legacy, typed);

        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        // Every conditional key of the populated document is present.
        for (String key : List.of("factoryReplacement", "submittedAt", "adminDecisionDueAt",
                "warehouseClaimDueAt", "warehouseFirstExecutionDueAt", "completedAt", "notes",
                "decision", "currentProcessor")) {
            assertTrue(tree.has(key), "expected key " + key);
        }
        assertFalse(tree.has("cancelledAt"), "absent putTime key must not be emitted");
        assertFalse(tree.has("cancellationReason"), "absent putOptional key must not be emitted");
        assertFalse(tree.path("currentProcessor").has("archivedAt"));
        assertTrue(tree.path("decision").has("comment"), "populated putOptional key stays");
        assertTrue(tree.path("items").get(0).has("businessLock"));
        assertTrue(tree.path("items").get(0).has("acceptance"));
        assertFalse(tree.path("items").get(0).path("acceptance").has("notes")
                && tree.path("items").get(0).path("acceptance").path("notes").isNull());
        assertFalse(tree.path("items").get(1).has("acceptance"));
        // Reply.version() must not leak into the wire tree.
        assertTrue(tree.has("version"));
    }

    @Test
    void draftReceiptMatchesLegacyAssemblyWithoutConditionalKeys() {
        var row = draftReceiptRow();
        var legacyItems = List.of(legacyItem(itemRow(5L, "PENDING"), null, null));
        var typedItems = List.of(InboundViews.item(itemRow(5L, "PENDING"), null, null));
        var legacy = legacyReceipt(row, legacyItems, null, null, List.of(), null);
        var typed = InboundViews.receipt(row, typedItems, null, null, List.of(), null);
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        for (String key : List.of("factoryReplacement", "submittedAt", "adminDecisionDueAt",
                "warehouseClaimDueAt", "warehouseFirstExecutionDueAt", "cancelledAt", "completedAt",
                "notes", "cancellationReason", "decision", "currentProcessor")) {
            assertFalse(tree.has(key), "conditional key " + key + " must be absent");
        }
        assertFalse(tree.path("items").get(0).has("businessLock"));
        for (String key : List.of("id", "receiptNumber", "status", "businessDate", "createdBy",
                "createdAt", "version", "parameterVersionId", "snapshot", "items")) {
            assertTrue(tree.has(key), "always-present key " + key + " must stay");
        }
        // Always-present nullable columns stay explicitly present (JsonConfiguration ALWAYS).
        assertTrue(tree.path("createdBy").has("username"));
    }

    @Test
    void jdbcDriverShapeVariantsProjectIdentically() {
        var wide = draftReceiptRow();
        wide.put("version", Integer.valueOf(2));
        wide.put("business_date", LocalDate.of(2026, 9, 24));
        wide.put("created_at", LocalDateTime.of(2026, 9, 24, 1, 2, 3));
        var items = List.of(legacyItem(itemRow(5L, "PENDING"), null, null));
        var typedItems = List.of(InboundViews.item(itemRow(5L, "PENDING"), null, null));
        assertWireEquals(legacyReceipt(wide, items, null, null, List.of(), null),
                InboundViews.receipt(wide, typedItems, null, null, List.of(), null));

        var instanted = draftReceiptRow();
        instanted.put("created_at", Instant.parse("2026-09-24T01:02:03Z"));
        assertWireEquals(legacyReceipt(instanted, items, null, null, List.of(), null),
                InboundViews.receipt(instanted, typedItems, null, null, List.of(), null));

        var textual = draftReceiptRow();
        textual.put("created_at", "2026-09-24T01:02:03Z");
        assertWireEquals(legacyReceipt(textual, items, null, null, List.of(), null),
                InboundViews.receipt(textual, typedItems, null, null, List.of(), null));
    }

    @Test
    void cancelledAndRejectedStatesMatchLegacyAssembly() {
        var cancelled = completedReceiptRow();
        cancelled.put("receipt_status", "CANCELLED");
        cancelled.put("cancelled_at", Timestamp.from(Instant.parse("2026-09-25T00:00:00Z")));
        cancelled.put("completed_at", null);
        cancelled.put("cancellation_reason", "no longer needed");
        var items = List.of(legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, null, 9L)));
        var typedItems = List.of(InboundViews.item(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, null, 9L)));
        assertWireEquals(legacyReceipt(cancelled, items, null, decisionRow(null),
                List.of(scopeRow(5L)), ownerRow(Timestamp.from(Instant.parse("2026-09-25T00:00:00Z")))),
                InboundViews.receipt(cancelled, typedItems, null, decisionRow(null),
                        List.of(scopeRow(5L)),
                        ownerRow(Timestamp.from(Instant.parse("2026-09-25T00:00:00Z")))));
    }

    @Test
    void acceptedAssetMatchesLegacyProjection() {
        var asset = assetRow();
        var warehouse = Map.<String, Object>of("version", 3L);
        var location = Map.<String, Object>of("version", 1L);
        assertWireEquals(legacyAcceptedAsset(asset, warehouse, location, 41L),
                InboundViews.acceptedAsset(asset, warehouse, location, 41L));
    }

    @Test
    void acceptResultMatchesLegacyThreeKeyAssembly() {
        var row = completedReceiptRow();
        var legacyItems = List.of(legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, "note", 9L)));
        var typedItems = List.of(InboundViews.item(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, "note", 9L)));
        var legacy = new HashMap<String, Object>();
        legacy.put("receipt", legacyReceipt(row, legacyItems, originRow(), decisionRow("c"),
                List.of(scopeRow(5L)), ownerRow(null)));
        legacy.put("item", legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L), acceptanceRow(70L, "note", 9L)));
        legacy.put("asset", legacyAcceptedAsset(assetRow(),
                Map.<String, Object>of("version", 3L), Map.<String, Object>of("version", 1L), 41L));
        var typed = new InboundViews.AcceptResult(
                InboundViews.receipt(row, typedItems, originRow(), decisionRow("c"),
                        List.of(scopeRow(5L)), ownerRow(null)),
                InboundViews.item(itemRow(5L, "ACCEPTED"), lockRow(5L), acceptanceRow(70L, "note", 9L)),
                InboundViews.acceptedAsset(assetRow(),
                        Map.<String, Object>of("version", 3L), Map.<String, Object>of("version", 1L), 41L));
        assertWireEquals(legacy, typed);
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertFalse(tree.has("version"), "Reply.version() accessor must not serialize");
        // The Reply bound still resolves the receipt version for the sealed interface contract.
        assertEquals(typed.receipt().version(), typed.version());
    }

    @Test
    void listPageMatchesLegacyMapOfAssembly() {
        var row = draftReceiptRow();
        var legacy = Map.of("page", 1, "pageSize", 50, "total", 1L,
                "items", List.of(legacyReceipt(row, List.of(legacyItem(itemRow(5L, "PENDING"),
                        null, null)), null, null, List.of(), null)));
        var typed = new InboundViews.Page<>(List.of(InboundViews.receipt(row,
                List.of(InboundViews.item(itemRow(5L, "PENDING"), null, null)), null, null,
                List.of(), null)), 1L, 1, 50);
        assertWireEquals(legacy, typed);
    }

    // ---- reassignment exits ----

    @Test
    void reassignmentInspectMatchesLegacyBothDeclarationStates() {
        var row = new LinkedHashMap<String, Object>();
        row.put("version", 6L);
        var owner = new LinkedHashMap<String, Object>();
        owner.put("ownership_version", 2L);
        owner.put("processor_account_id", 5L);
        owner.put("unavailable_reason", null);
        var checks = List.of(
                checkRow(5L, 1L, "PENDING", 4L, 0L, "FA-1"),
                checkRow(6L, 0L, "ACCEPTED", 5L, -1L, "FA-2"));
        var operators = List.of(operatorRow(11L, "wh-a"), operatorRow(12L, "wh-b"));
        var legacy = legacyInspect(88L, row, owner, operators, checks, false);
        var typed = typedInspect(88L, row, owner, operators, checks, false);
        assertWireEquals(legacy, typed);
        assertFalse(JSON.readTree(JSON.writeValueAsString(typed)).has("unavailableReason"));

        owner.put("unavailable_reason", "sick leave");
        var declaredOwner = Map.<String, Object>of("ownership_version", 2L,
                "processor_account_id", 5L, "unavailable_reason", "sick leave");
        assertWireEquals(legacyInspect(88L, row, declaredOwner, operators, checks, true),
                typedInspect(88L, row, declaredOwner, operators, checks, true));
        assertTrue(JSON.readTree(JSON.writeValueAsString(
                typedInspect(88L, row, declaredOwner, operators, checks, true)))
                .path("unavailableReason").asText().equals("sick leave"));

        // JDBC integral-width variant: an Integer version projects to the same JSON number.
        var wideRow = new LinkedHashMap<>(row);
        wideRow.put("version", Integer.valueOf(6));
        assertWireEquals(legacyInspect(88L, wideRow, owner, operators, checks, false),
                typedInspect(88L, wideRow, owner, operators, checks, false));
    }

    @Test
    void declareUnavailableMatchesLegacyTwoKeyReceipt() {
        var legacy = Map.<String, Object>of("receiptId", Long.toString(88L), "recorded", true);
        assertWireEquals(legacy, new InboundViews.UnavailabilityResult(Long.toString(88L), true));
    }

    // ---- attachment exits ----

    @Test
    void uploadAndCorrectionReceiptMatchesLegacyMapOfSixKeys() {
        var id = UUID.fromString("3f0f9b2a-2f0e-4d3c-9a8a-6a1f3b8d4c11");
        var result = new AttachmentIntake.QuarantinedAttachment(
                new AttachmentStorage.StoredFile(id, 2048L, "ab".repeat(32)),
                AttachmentFormat.PNG,
                MalwareScanner.Result.CLEAN);
        var legacy = Map.<String, Object>of("objectId", id.toString(),
                "filename", "photo.png", "contentType", "image/png",
                "sizeBytes", 2048L, "scanStatus", "CLEAN", "businessVersion", 3L);
        assertWireEquals(legacy, InboundViews.attachmentReceipt(result, "photo.png", 2));
    }

    @Test
    void attachmentListMatchesLegacyRowPassthrough() {
        var rows = List.of(attachmentRow(1L, Timestamp.from(Instant.parse("2026-09-24T01:02:03Z")), true),
                attachmentRow(2L, LocalDateTime.of(2026, 9, 25, 8, 0, 0), false));
        var legacy = Map.of("items", rows);
        var typed = new InboundViews.AttachmentListPage(
                rows.stream().map(InboundViews::attachmentEvidence).toList());
        assertWireEquals(legacy, typed);
        // createdAt keeps the identical raw JDBC instance (no silent normalisation).
        assertSame(rows.get(0).get("createdAt"), typed.items().get(0).createdAt());
        assertSame(rows.get(1).get("createdAt"), typed.items().get(1).createdAt());
        var tree = JSON.readTree(JSON.writeValueAsString(typed));
        assertTrue(tree.path("items").get(0).has("correctionReason"));
        assertFalse(tree.path("items").get(1).has("correctionReason")
                && !tree.path("items").get(1).path("correctionReason").isNull());
    }

    @Test
    void downloadGrantMatchesLegacyMapOfTwoKeys() {
        var objectId = UUID.fromString("3f0f9b2a-2f0e-4d3c-9a8a-6a1f3b8d4c11");
        var legacy = Map.<String, Object>of("url",
                "/api/v1/attachments/" + objectId + "/content?grant=tok",
                "expiresAtEpochSecond", 1790000000L);
        var typed = new InboundViews.DownloadGrant((String) legacy.get("url"),
                ((Number) legacy.get("expiresAtEpochSecond")).longValue());
        assertWireEquals(legacy, typed);
    }

    // ---- idempotency-stored shape replays (response_body_json across versions) ----

    @Test
    void preBatchStoredReceiptTreeStillReplaysIntoTypedRecords() {
        var row = completedReceiptRow();
        var items = List.of(legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, "note", 9L)));
        var stored = JSON.writeValueAsString(legacyReceipt(row, items, originRow(),
                decisionRow("c"), List.of(scopeRow(5L)), ownerRow(null)));
        var replayed = JSON.readValue(stored, InboundViews.Receipt.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));

        var draft = draftReceiptRow();
        var draftStored = JSON.writeValueAsString(legacyReceipt(draft,
                List.of(legacyItem(itemRow(5L, "PENDING"), null, null)), null, null, List.of(), null));
        var draftReplayed = JSON.readValue(draftStored, InboundViews.Receipt.class);
        assertEquals(JSON.readTree(draftStored), JSON.readTree(JSON.writeValueAsString(draftReplayed)));
    }

    @Test
    void preBatchStoredAcceptResultAndUnavailableReceiptStillReplay() {
        var row = completedReceiptRow();
        var legacy = new HashMap<String, Object>();
        legacy.put("receipt", legacyReceipt(row,
                List.of(legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L), acceptanceRow(70L, null, 9L))),
                null, decisionRow(null), List.of(scopeRow(5L)), ownerRow(null)));
        legacy.put("item", legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L), acceptanceRow(70L, null, 9L)));
        legacy.put("asset", legacyAcceptedAsset(assetRow(),
                Map.<String, Object>of("version", 3L), Map.<String, Object>of("version", 1L), 41L));
        var stored = JSON.writeValueAsString(legacy);
        var replayed = JSON.readValue(stored, InboundViews.AcceptResult.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));

        var unavailableStored = JSON.writeValueAsString(
                Map.<String, Object>of("receiptId", "88", "recorded", true));
        var unavailable = JSON.readValue(unavailableStored, InboundViews.UnavailabilityResult.class);
        assertEquals(JSON.readTree(unavailableStored), JSON.readTree(JSON.writeValueAsString(unavailable)));
    }

    @Test
    void typedStoredShapesRoundTripIdempotently() {
        var row = completedReceiptRow();
        var items = List.of(InboundViews.item(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, "note", 9L)));
        var receipt = InboundViews.receipt(row, items, originRow(), decisionRow("c"),
                List.of(scopeRow(5L)), ownerRow(null));
        var stored = JSON.writeValueAsString(receipt);
        assertEquals(JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, InboundViews.Receipt.class))));
    }

    // ---- FactoryReplacementInboundPort wire-Map consumption (JAVA-14b1 wrapper precedent) ----

    @Test
    void wireMapWrapperEqualsLegacyMapAndPortProjectionsStayEquivalent() {
        var row = completedReceiptRow();
        var legacyItems = List.of(legacyItem(itemRow(5L, "ACCEPTED"), lockRow(5L),
                acceptanceRow(70L, "note", 9L)));
        var legacy = legacyReceipt(row, legacyItems, originRow(), decisionRow("c"),
                List.of(scopeRow(5L)), ownerRow(null));
        var typed = InboundViews.receipt(row, List.of(InboundViews.item(itemRow(5L, "ACCEPTED"),
                lockRow(5L), acceptanceRow(70L, "note", 9L))), originRow(), decisionRow("c"),
                List.of(scopeRow(5L)), ownerRow(null));

        // create/get wrappers: json.convertValue(typed) is tree-identical to the legacy map.
        Map<String, Object> wire = JSON.convertValue(typed, MAP_TYPE);
        assertWireEquals(legacy, wire);

        // Receipt.fromView (inbound_receipt_id binding of the repair side).
        assertEquals(FactoryReplacementInboundPort.Receipt.fromView(legacy).id(),
                FactoryReplacementInboundPort.Receipt.fromView(wire).id());
        assertEquals(legacy.get("id"), FactoryReplacementInboundPort.Receipt.fromView(wire).id());

        // Context.fromView (the JAVA-10c frozen four-key projection embedded by repair HTTP).
        var legacyContext = FactoryReplacementInboundPort.Context.fromView(legacy);
        var wireContext = FactoryReplacementInboundPort.Context.fromView(wire);
        assertWireEquals(Map.of("id", legacy.get("id"), "version", legacy.get("version"),
                "status", legacy.get("status"), "receiptNumber", legacy.get("receiptNumber")),
                legacyContext);
        assertWireEquals(legacyContext, wireContext);

        // After a stored-receipt replay through the typed record the port still reads the same
        // four values (this is the reassign/declareUnavailable idempotency path end to end).
        var replayed = JSON.readValue(JSON.writeValueAsString(legacy), InboundViews.Receipt.class);
        assertWireEquals(legacyContext,
                FactoryReplacementInboundPort.Context.fromView(JSON.convertValue(replayed, MAP_TYPE)));

        // The full legacy document keeps every nested key in the wrapper mirror as well.
        JsonNode legacyTree = JSON.readTree(JSON.writeValueAsString(legacy));
        JsonNode wireTree = JSON.readTree(JSON.writeValueAsString(wire));
        assertEquals(legacyTree, wireTree);
        assertNotNull(wireTree.path("snapshot").path("timezone"));
    }

    // ---- fixtures ----

    private static Map<String, Object> completedReceiptRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 88L);
        row.put("receipt_no", "IN-0088");
        row.put("receipt_status", "COMPLETED");
        row.put("business_date", java.sql.Date.valueOf("2026-09-24"));
        row.put("creator_account_id", 11L);
        row.put("creator_username_snapshot", "creator");
        row.put("created_at", Timestamp.from(Instant.parse("2026-09-24T01:02:03Z")));
        row.put("version", 6L);
        row.put("parameter_version_id", 7L);
        row.put("timezone_snapshot", "Asia/Shanghai");
        row.put("admin_decision_deadline_workdays", 2L);
        row.put("warehouse_claim_deadline_workdays", 3L);
        row.put("warehouse_first_execution_deadline_workdays", 4L);
        row.put("submitted_at", Timestamp.from(Instant.parse("2026-09-24T02:00:00Z")));
        row.put("admin_decision_due_at", Timestamp.from(Instant.parse("2026-09-26T06:00:00Z")));
        row.put("warehouse_claim_due_at", Timestamp.from(Instant.parse("2026-09-29T06:00:00Z")));
        row.put("warehouse_first_execution_due_at", Timestamp.from(Instant.parse("2026-10-01T06:00:00Z")));
        row.put("cancelled_at", null);
        row.put("completed_at", Timestamp.from(Instant.parse("2026-09-30T06:00:00Z")));
        row.put("notes", "desk batch");
        row.put("cancellation_reason", null);
        return row;
    }

    private static Map<String, Object> draftReceiptRow() {
        var row = completedReceiptRow();
        row.put("receipt_status", "DRAFT");
        row.put("version", 0L);
        row.put("submitted_at", null);
        row.put("admin_decision_due_at", null);
        row.put("warehouse_claim_due_at", null);
        row.put("warehouse_first_execution_due_at", null);
        row.put("completed_at", null);
        row.put("notes", null);
        return row;
    }

    private static Map<String, Object> itemRow(long id, String executionStatus) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("execution_status", executionStatus);
        row.put("asset_id", 40L + id);
        row.put("asset_version_snapshot", 3L);
        row.put("asset_code_snapshot", "FA-" + id);
        row.put("asset_name_snapshot", "laptop " + id);
        row.put("target_warehouse_id", 2L);
        row.put("warehouse_version_snapshot", 1L);
        row.put("warehouse_code_snapshot", "WH-2");
        row.put("warehouse_name_snapshot", "main");
        row.put("target_location_id", 9L);
        row.put("location_version_snapshot", 2L);
        row.put("location_code_snapshot", "BIN-9");
        row.put("location_name_snapshot", "bin a");
        return row;
    }

    private static Map<String, Object> lockRow(long itemId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("asset_id", 40L + itemId);
        row.put("business_id", 88L);
        row.put("business_item_id", itemId);
        row.put("lock_version", 0L);
        row.put("acquired_at", Timestamp.from(Instant.parse("2026-09-24T02:00:00Z")));
        return row;
    }

    private static Map<String, Object> acceptanceRow(long id, String notes, Long lockVersion) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("receipt_item_id", 5L);
        row.put("asset_id", 45L);
        row.put("accepted_by_account_id", 12L);
        row.put("accepted_by_username_snapshot", "wh1");
        row.put("accepted_at", Timestamp.from(Instant.parse("2026-09-24T03:00:00Z")));
        row.put("business_date", java.sql.Date.valueOf("2026-09-24"));
        row.put("receipt_version_before", 5L);
        row.put("asset_version_before", 4L);
        row.put("asset_version_after", 5L);
        row.put("lock_version", lockVersion);
        row.put("observed_asset_code", "FA-5");
        row.put("storage_warehouse_id", 2L);
        row.put("warehouse_version_at_acceptance", 1L);
        row.put("storage_location_id", 9L);
        row.put("location_version_at_acceptance", 2L);
        row.put("notes", notes);
        return row;
    }

    private static Map<String, Object> originRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("source_id", "3f0f9b2a-2f0e-4d3c-9a8a-6a1f3b8d4c11");
        row.put("original_asset_id", 41L);
        row.put("asset_id", 42L);
        row.put("repair_id", 7L);
        row.put("round_id", 2L);
        return row;
    }

    private static Map<String, Object> decisionRow(String comment) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 61L);
        row.put("decision_result", comment == null ? "REJECTED" : "APPROVED");
        row.put("receipt_version_before", 1L);
        row.put("decided_by_account_id", 13L);
        row.put("decided_by_username_snapshot", "admin");
        row.put("decided_at", Timestamp.from(Instant.parse("2026-09-24T02:30:00Z")));
        row.put("comment_text", comment);
        return row;
    }

    private static Map<String, Object> scopeRow(long itemId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("receipt_item_id", itemId);
        row.put("approved_asset_id", 40L + itemId);
        row.put("approved_asset_version", 3L);
        row.put("approved_warehouse_id", 2L);
        row.put("approved_warehouse_version", 1L);
        row.put("approved_location_id", 9L);
        row.put("approved_location_version", 2L);
        return row;
    }

    private static Map<String, Object> ownerRow(Timestamp archivedAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("processor_account_id", 12L);
        row.put("processor_username_snapshot", "wh1");
        row.put("acquired_at", Timestamp.from(Instant.parse("2026-09-24T04:00:00Z")));
        row.put("ownership_version", 2L);
        row.put("archived_at", archivedAt);
        return row;
    }

    private static Map<String, Object> assetRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("asset_code", "FA-41");
        row.put("lifecycle_status", "IN_STOCK");
        row.put("exception_status", "NORMAL");
        row.put("card_stage", "ACTIVE");
        row.put("version", 5L);
        row.put("storage_warehouse_id", 2L);
        row.put("current_location_id", 9L);
        row.put("inbound_business_date", java.sql.Date.valueOf("2026-09-24"));
        return row;
    }

    /** One frozen checks() row exactly as InboundReassignmentService.checks assembles it. */
    private static Map<String, Object> checkRow(long id, long version, String status,
            long assetVersion, long lockVersion, String assetCode) {
        return Map.of("id", Long.toString(id), "version", version, "status", status,
                "assetVersion", assetVersion, "lockVersion", lockVersion, "assetCode", assetCode,
                "warehouseId", Long.toString(2L), "locationId", Long.toString(9L));
    }

    private static Map<String, Object> operatorRow(long id, String name) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    private static Map<String, Object> attachmentRow(long ordinal, Object createdAt, boolean corrected) {
        var row = new LinkedHashMap<String, Object>();
        row.put("objectId", "obj-" + ordinal);
        row.put("filename", "photo-" + ordinal + ".png");
        row.put("contentType", "image/png");
        row.put("sizeBytes", 2048L);
        row.put("scanStatus", "CLEAN");
        row.put("businessVersion", 2L);
        row.put("createdAt", createdAt);
        row.put("revisionNumber", corrected ? 2L : 1L);
        row.put("previousObjectId", corrected ? "obj-" + (ordinal - 1) : null);
        row.put("correctionReason", corrected ? "blurry" : null);
        row.put("nextObjectId", corrected ? null : "obj-" + (ordinal + 1));
        return row;
    }

    private static InboundViews.ReassignmentSnapshot typedInspect(long id, Map<String, Object> row,
            Map<String, Object> owner, List<Map<String, Object>> operators,
            List<Map<String, Object>> checks, boolean declared) {
        return new InboundViews.ReassignmentSnapshot(
                Long.toString(id),
                ((Number) row.get("version")).longValue(),
                ((Number) owner.get("ownership_version")).longValue(),
                owner.get("processor_account_id").toString(),
                checks.stream().map(InboundViews::reassignmentItem).toList(),
                operators.stream().map(InboundViews::operator).toList(),
                declared ? (String) owner.get("unavailable_reason") : null);
    }

    // ---- frozen pre-JAVA-15 oracles (verbatim copies) ----

    /** Pre-JAVA-15 InboundService.receipt assembly. */
    private static Map<String, Object> legacyReceipt(Map<String, Object> row,
            List<Map<String, Object>> items, Map<String, Object> origin,
            Map<String, Object> decision, List<Map<String, Object>> scopeRows,
            Map<String, Object> owner) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.toString(legacyId(row)));
        result.put("receiptNumber", legacyText(row, "receipt_no"));
        result.put("status", legacyText(row, "receipt_status"));
        result.put("businessDate", legacyLocalDate(row.get("business_date")).toString());
        result.put("createdBy", legacyIdentity(legacyNumber(row, "creator_account_id"),
                legacyText(row, "creator_username_snapshot")));
        result.put("createdAt", legacyInstant(row.get("created_at")).toString());
        result.put("version", legacyNumber(row, "version"));
        result.put("parameterVersionId", Long.toString(legacyNumber(row, "parameter_version_id")));
        result.put("snapshot", Map.of("parameterVersionId", result.get("parameterVersionId"),
                "businessDate", result.get("businessDate"), "timezone", legacyText(row, "timezone_snapshot"),
                "adminDecisionDeadlineWorkdays", legacyNumber(row, "admin_decision_deadline_workdays"),
                "warehouseClaimDeadlineWorkdays", legacyNumber(row, "warehouse_claim_deadline_workdays"),
                "warehouseFirstExecutionDeadlineWorkdays",
                legacyNumber(row, "warehouse_first_execution_deadline_workdays")));
        result.put("items", items);
        if (origin != null) {
            result.put("factoryReplacement", Map.of("sourceId", origin.get("source_id"),
                    "originalAssetId", origin.get("original_asset_id").toString(),
                    "replacementAssetId", origin.get("asset_id").toString(),
                    "repairId", origin.get("repair_id").toString(),
                    "roundId", origin.get("round_id").toString()));
        }
        legacyPutTime(result, row, "submittedAt", "submitted_at");
        legacyPutTime(result, row, "adminDecisionDueAt", "admin_decision_due_at");
        legacyPutTime(result, row, "warehouseClaimDueAt", "warehouse_claim_due_at");
        legacyPutTime(result, row, "warehouseFirstExecutionDueAt", "warehouse_first_execution_due_at");
        legacyPutTime(result, row, "cancelledAt", "cancelled_at");
        legacyPutTime(result, row, "completedAt", "completed_at");
        legacyPutOptional(result, row, "notes", "notes");
        legacyPutOptional(result, row, "cancellationReason", "cancellation_reason");
        if (decision != null) {
            Map<String, Object> decisionView = new HashMap<>();
            decisionView.put("id", Long.toString(legacyId(decision)));
            decisionView.put("result", legacyText(decision, "decision_result"));
            decisionView.put("decidedReceiptVersion", legacyNumber(decision, "receipt_version_before"));
            decisionView.put("decidedBy", legacyIdentity(legacyNumber(decision, "decided_by_account_id"),
                    legacyText(decision, "decided_by_username_snapshot")));
            decisionView.put("decidedAt", legacyInstant(decision.get("decided_at")).toString());
            legacyPutOptional(decisionView, decision, "comment", "comment_text");
            decisionView.put("scope", Map.of("receiptVersion", legacyNumber(decision, "receipt_version_before"),
                    "items", scopeRows.stream().map(InboundWireShapeTest::legacyScope).toList()));
            result.put("decision", decisionView);
        }
        if (owner != null) {
            Map<String, Object> view = new HashMap<>();
            view.put("processor", legacyIdentity(legacyNumber(owner, "processor_account_id"),
                    legacyText(owner, "processor_username_snapshot")));
            view.put("claimedAt", legacyInstant(owner.get("acquired_at")).toString());
            view.put("ownershipVersion", legacyNumber(owner, "ownership_version"));
            legacyPutTime(view, owner, "archivedAt", "archived_at");
            result.put("currentProcessor", view);
        }
        return result;
    }

    /** Pre-JAVA-15 InboundService.item assembly (lock/acceptance rows pre-resolved). */
    private static Map<String, Object> legacyItem(Map<String, Object> row, Map<String, Object> lock,
            Map<String, Object> acceptance) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.toString(legacyId(row)));
        result.put("executionStatus", legacyText(row, "execution_status"));
        result.put("asset", legacyRef(legacyNumber(row, "asset_id"), legacyNumber(row, "asset_version_snapshot")));
        result.put("assetSnapshot", Map.of("assetCode", legacyText(row, "asset_code_snapshot"),
                "assetName", legacyText(row, "asset_name_snapshot")));
        result.put("targetWarehouse", legacyRef(legacyNumber(row, "target_warehouse_id"),
                legacyNumber(row, "warehouse_version_snapshot")));
        result.put("targetLocation", legacyRef(legacyNumber(row, "target_location_id"),
                legacyNumber(row, "location_version_snapshot")));
        result.put("warehouseSnapshot", Map.of("code", legacyText(row, "warehouse_code_snapshot"),
                "name", legacyText(row, "warehouse_name_snapshot")));
        result.put("locationSnapshot", Map.of("code", legacyText(row, "location_code_snapshot"),
                "name", legacyText(row, "location_name_snapshot")));
        if (lock != null) {
            result.put("businessLock", Map.of("assetId", Long.toString(legacyNumber(lock, "asset_id")),
                    "businessType", "INBOUND", "businessId", Long.toString(legacyNumber(lock, "business_id")),
                    "businessItemId", Long.toString(legacyNumber(lock, "business_item_id")),
                    "lockVersion", legacyNumber(lock, "lock_version"),
                    "acquiredAt", legacyInstant(lock.get("acquired_at")).toString()));
        }
        if (acceptance != null) {
            result.put("acceptance", legacyAcceptance(acceptance));
        }
        return result;
    }

    /** Pre-JAVA-15 InboundService.scope projection. */
    private static Map<String, Object> legacyScope(Map<String, Object> row) {
        return Map.of("receiptItemId", Long.toString(legacyNumber(row, "receipt_item_id")),
                "asset", legacyRef(legacyNumber(row, "approved_asset_id"), legacyNumber(row, "approved_asset_version")),
                "targetWarehouse", legacyRef(legacyNumber(row, "approved_warehouse_id"),
                        legacyNumber(row, "approved_warehouse_version")),
                "targetLocation", legacyRef(legacyNumber(row, "approved_location_id"),
                        legacyNumber(row, "approved_location_version")));
    }

    /** Pre-JAVA-15 InboundService.acceptance projection (camel-to-snake loop included). */
    private static Map<String, Object> legacyAcceptance(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", Long.toString(legacyId(row)));
        result.put("receiptItemId", Long.toString(legacyNumber(row, "receipt_item_id")));
        result.put("assetId", Long.toString(legacyNumber(row, "asset_id")));
        result.put("acceptedBy", legacyIdentity(legacyNumber(row, "accepted_by_account_id"),
                legacyText(row, "accepted_by_username_snapshot")));
        result.put("acceptedAt", legacyInstant(row.get("accepted_at")).toString());
        result.put("businessDate", legacyLocalDate(row.get("business_date")).toString());
        for (String field : List.of("receiptVersionBefore", "assetVersionBefore",
                "assetVersionAfter", "lockVersion")) {
            result.put(field, legacyNumber(row, field.replaceAll("([A-Z])", "_$1").toLowerCase()));
        }
        result.put("observedAssetCode", legacyText(row, "observed_asset_code"));
        result.put("storageCustody", legacyStorage(legacyNumber(row, "storage_warehouse_id"),
                legacyNumber(row, "warehouse_version_at_acceptance"),
                legacyNumber(row, "storage_location_id"), legacyNumber(row, "location_version_at_acceptance")));
        legacyPutOptional(result, row, "notes", "notes");
        return result;
    }

    /** Pre-JAVA-15 InboundService.acceptedAsset projection. */
    private static Map<String, Object> legacyAcceptedAsset(Map<String, Object> asset,
            Map<String, Object> warehouse, Map<String, Object> location, long assetId) {
        return Map.of("id", Long.toString(assetId), "assetCode", legacyText(asset, "asset_code"),
                "lifecycle", legacyText(asset, "lifecycle_status"),
                "exception", legacyText(asset, "exception_status"),
                "cardStage", legacyText(asset, "card_stage"), "version", legacyNumber(asset, "version"),
                "currentCustody", legacyStorage(legacyNumber(asset, "storage_warehouse_id"),
                        legacyNumber(warehouse, "version"), legacyNumber(asset, "current_location_id"),
                        legacyNumber(location, "version")),
                "inboundBusinessDate", legacyLocalDate(asset.get("inbound_business_date")).toString());
    }

    /** Pre-JAVA-15 InboundReassignmentService.inspect assembly. */
    private static Map<String, Object> legacyInspect(long id, Map<String, Object> row,
            Map<String, Object> owner, List<Map<String, Object>> operators,
            List<Map<String, Object>> checks, boolean declared) {
        var result = new HashMap<String, Object>(Map.of("id", Long.toString(id), "version", row.get("version"),
                "ownershipVersion", owner.get("ownership_version"),
                "processorAccountId", owner.get("processor_account_id").toString(),
                "items", checks, "operators", operators.stream().map(o -> Map.of(
                        "id", o.get("id").toString(), "name", o.get("name"))).toList()));
        if (declared) {
            result.put("unavailableReason", owner.get("unavailable_reason"));
        }
        return result;
    }

    private static Map<String, Object> legacyIdentity(long accountId, String username) {
        return Map.of("id", Long.toString(accountId), "username", username);
    }

    private static Map<String, Object> legacyRef(long id, long version) {
        return Map.of("id", Long.toString(id), "version", version);
    }

    private static Map<String, Object> legacyStorage(long warehouse, long warehouseVersion,
            long location, long locationVersion) {
        return Map.of("custodyType", "STORAGE", "warehouse", legacyRef(warehouse, warehouseVersion),
                "location", legacyRef(location, locationVersion));
    }

    private static void legacyPutTime(Map<String, Object> target, Map<String, Object> row,
            String jsonKey, String column) {
        if (row.get(column) != null) {
            target.put(jsonKey, legacyInstant(row.get(column)).toString());
        }
    }

    private static void legacyPutOptional(Map<String, Object> target, Map<String, Object> row,
            String jsonKey, String column) {
        if (row.get(column) != null) {
            target.put(jsonKey, legacyText(row, column));
        }
    }

    private static long legacyId(Map<String, Object> row) {
        return legacyNumber(row, "id");
    }

    private static long legacyNumber(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String legacyText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static LocalDate legacyLocalDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(value.toString());
    }

    private static Instant legacyInstant(Object value) {
        if (value instanceof Timestamp stamp) {
            return stamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(value.toString());
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the HashMap/Map.of assemblies.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
