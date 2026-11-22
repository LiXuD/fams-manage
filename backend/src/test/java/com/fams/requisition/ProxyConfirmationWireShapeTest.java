package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.attachment.AttachmentFormat;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentStorage;
import com.fams.attachment.MalwareScanner;
import com.fams.proxy.ProxyViews;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JAVA-14b3 wire equivalence for the proxy confirmation receipt HTTP exits. Comparison
 * round-trips through JSON text so integral node widths normalize and object key order (never
 * a contract, the legacy envelopes used Map.of) is ignored, while key presence, explicit nulls
 * and value shapes all count. The confirmation envelope was typed in JAVA-04; its pre-batch
 * wire() Map projection is re-frozen here as the domain-side oracle for every exit
 * (list/get/create/submit/decision/execute). The attachment exits (list/upload/correct) and the
 * mini download grant are newly typed in this batch against their verbatim legacy assemblies.
 */
class ProxyConfirmationWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBJECT_ID = "6d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";
    private static final Instant INSTANT = Instant.parse("2026-11-01T02:00:00.123Z");

    // --- confirmation exits: list / get / create / submit / decision / execute ---

    @Test
    void confirmationDetailMatchesLegacyWireProjectionInBothConditionalStates() {
        var draft = confirmationRow("DRAFT");
        assertWireEquals(legacyWire(draft), ProxyViews.confirmation(JSON, draft));
        // previous_confirmation_id and the whole decision tail flip between explicit null and
        // valued; both states of every nullable key are emitted (global mapper ALWAYS).
        assertTrue(JSON.readTree(JSON.writeValueAsString(ProxyViews.confirmation(JSON, draft)))
                .hasNonNull("id"));
        JsonNode draftNode = JSON.readTree(JSON.writeValueAsString(ProxyViews.confirmation(JSON, draft)));
        assertEquals(31, draftNode.size());
        assertTrue(draftNode.get("previousConfirmationId").isNull());
        var linked = new LinkedHashMap<>(draft);
        linked.put("previous_confirmation_id", 11L);
        JsonNode linkedNode =
                JSON.readTree(JSON.writeValueAsString(ProxyViews.confirmation(JSON, linked)));
        assertWireEquals(legacyWire(linked), linkedNode);
        assertEquals("11", linkedNode.get("previousConfirmationId").asText());
    }

    @Test
    void confirmationPageMatchesLegacyListAssembly() {
        var row = confirmationRow("SUBMITTED");
        var legacy = Map.<String, Object>of(
                "items", List.of(legacyWire(row)),
                "page", 2, "pageSize", 20, "total", 41L, "canApprove", true, "canExecute", false);
        assertWireEquals(
                legacy,
                new ProxyViews.ConfirmationPage(
                        List.of(ProxyViews.confirmation(JSON, row)), 2, 20, 41, true, false));
    }

    @Test
    void decisionAndExecuteReceiptsMatchLegacyWireProjection() {
        for (String status : List.of("APPROVED", "REJECTED", "EXECUTED")) {
            var row = confirmationRow(status);
            assertWireEquals(legacyWire(row), ProxyViews.confirmation(JSON, row));
        }
    }

    @Test
    void contextSnapshotReplaysStoredLegacyBusinessSnapshot() {
        // create()/execute() compare the live ProxySnapshot against the stored JSON tree;
        // snapshots persisted before JAVA-04 (raw legacy Map projection) must still deserialize
        // through the discriminator and re-serialize identically.
        var stored = JSON.writeValueAsString(legacyRequisitionSnapshot());
        var replayed = JSON.readValue(stored, ProxyViews.ProxySnapshot.class);
        assertWireEquals(JSON.readTree(stored), replayed);
        assertTrue(replayed instanceof ProxyViews.RequisitionSnapshot);
        assertEquals("REQUISITION", replayed.businessType());
    }

    // --- idempotent replay: legacy response_body_json and payload digests ---

    @Test
    void storedConfirmationReceiptsReplayThroughTheGenericReplayPath() {
        for (String status : List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "EXECUTED")) {
            // Pre-batch storage was the raw wire() Map serialized by finish(); replay reads it
            // back into ProxyViews.Confirmation and re-serializes on the second hit.
            String stored = JSON.writeValueAsString(legacyWire(confirmationRow(status)));
            var replayed = JSON.readValue(stored, ProxyViews.Confirmation.class);
            assertWireEquals(JSON.readTree(stored), replayed);
            assertEquals("10", replayed.id());
        }
    }

    @Test
    void idempotencyDigestAcceptsLegacyMapOfKeyPermutations() {
        // submit/execute payloads were 2-key and decision payloads 3-key Map.of tuples whose
        // iteration order was JVM-random when fams_idempotency_record.payload_sha256 was written.
        var current = Map.of("id", 7L, "version", 2L);
        assertTrue(ProxyReceiptPayloads.matches(JSON, current, legacyHash(JSON.writeValueAsString(current))));
        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("version", 2L);
        reversed.put("id", 7L);
        assertTrue(ProxyReceiptPayloads.matches(JSON, current, legacyHash(JSON.writeValueAsString(reversed))));
        var decision = new LinkedHashMap<String, Object>();
        decision.put("decision", Map.of("approved", true, "reason", "ok",
                "physicalEvidenceChecked", true, "noDisputeChecked", true));
        decision.put("version", 2L);
        decision.put("id", 7L);
        var currentDecision = Map.of("id", 7L, "version", 2L, "decision", decision.get("decision"));
        assertTrue(ProxyReceiptPayloads.matches(
                JSON, currentDecision, legacyHash(JSON.writeValueAsString(decision))));
        // A different value must never match.
        assertFalse(ProxyReceiptPayloads.matches(
                JSON, Map.of("id", 8L, "version", 2L), legacyHash(JSON.writeValueAsString(reversed))));
    }

    // --- attachment list / upload / correct exits ---

    @Test
    void attachmentListMatchesLegacyEnvelopeAcrossDriverAndRevisionShapes() {
        for (Object createdAt : List.of(
                Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var plain = attachmentRow(createdAt, false);
            var revised = attachmentRow(createdAt, true);
            var legacy = Map.<String, Object>of("items", List.of(plain, revised));
            var typed = new ProxyConfirmationViews.AttachmentListPage(
                    List.of(
                            ProxyConfirmationViews.attachmentEvidence(plain),
                            ProxyConfirmationViews.attachmentEvidence(revised)));
            assertWireEquals(legacy, typed);
            JsonNode first = JSON.readTree(JSON.writeValueAsString(typed)).get("items").get(0);
            // Pre-batch queryForList maps always carried all 11 alias keys, revision columns
            // included as explicit nulls. First row is the superseded original: no own revision
            // keys but a non-null nextObjectId pointer.
            assertEquals(11, first.size());
            assertTrue(first.get("previousObjectId").isNull());
            assertTrue(first.get("correctionReason").isNull());
            assertEquals("1c9e2fbb-0000-4000-8000-000000000002", first.get("nextObjectId").asText());
            JsonNode second = JSON.readTree(JSON.writeValueAsString(typed)).get("items").get(1);
            assertEquals(11, second.size());
            assertTrue(second.get("nextObjectId").isNull());
            assertEquals("1c9e2faa-0000-4000-8000-000000000001", second.get("previousObjectId").asText());
            assertEquals("blurry", second.get("correctionReason").asText());
        }
        // Integral widths normalize: Integer-valued JDBC rows stay equivalent.
        var narrow = attachmentRow(Timestamp.from(INSTANT), false);
        narrow.put("sizeBytes", 1024);
        narrow.put("businessVersion", 0);
        narrow.put("revisionNumber", 1);
        assertWireEquals(narrow, ProxyConfirmationViews.attachmentEvidence(narrow));
        // Envelope never holds null items; defensive copy keeps the record immutable.
        var rows = List.of(attachmentRow(Timestamp.from(INSTANT), false));
        var page = new ProxyConfirmationViews.AttachmentListPage(
                new java.util.ArrayList<>(rows.stream().map(ProxyConfirmationViews::attachmentEvidence).toList()));
        assertWireEquals(Map.of("items", rows), page);
    }

    @Test
    void uploadAndCorrectReceiptsMatchLegacySixKeyAssembly() {
        var id = UUID.fromString(OBJECT_ID);
        var result = new AttachmentIntake.QuarantinedAttachment(
                new AttachmentStorage.StoredFile(id, 2048L, "ab".repeat(32)),
                AttachmentFormat.PNG,
                MalwareScanner.Result.CLEAN);
        var legacy = Map.<String, Object>of(
                "objectId", id.toString(),
                "filename", "photo.png",
                "contentType", "image/png",
                "sizeBytes", 2048L,
                "scanStatus", "CLEAN",
                "businessVersion", 3L);
        assertWireEquals(legacy, ProxyConfirmationViews.receipt(result, "photo.png", 2));
        JsonNode node =
                JSON.readTree(JSON.writeValueAsString(ProxyConfirmationViews.receipt(result, "photo.png", 2)));
        assertEquals(6, node.size());
        assertEquals(3L, node.get("businessVersion").asLong());
    }

    @Test
    void miniGrantMatchesLegacyTwoKeyAssembly() {
        long expiresAtEpochSecond = 1790000000L;
        var grantResult = Map.<String, Object>of(
                "url", "/api/v1/attachments/" + OBJECT_ID + "/content?grant=tok3n",
                "expiresAtEpochSecond", expiresAtEpochSecond);
        // Frozen pre-batch controller assembly, path rewrite included.
        var legacy = Map.of(
                "url", grantResult.get("url").toString()
                        .replace("/api/v1/attachments/", "/api/v1/mini/attachments/"),
                "expiresAtEpochSecond", grantResult.get("expiresAtEpochSecond"));
        var typed = new ProxyConfirmationViews.DownloadGrant(
                grantResult.get("url").toString()
                        .replace("/api/v1/attachments/", "/api/v1/mini/attachments/"),
                ((Number) grantResult.get("expiresAtEpochSecond")).longValue());
        assertWireEquals(legacy, typed);
        JsonNode node = JSON.readTree(JSON.writeValueAsString(typed));
        assertEquals(2, node.size());
        assertEquals("/api/v1/mini/attachments/" + OBJECT_ID + "/content?grant=tok3n",
                node.get("url").asText());
    }

    // --- helpers ---

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static String legacyHash(String json) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Map<String, Object> attachmentRow(Object createdAt, boolean revised) {
        var row = new LinkedHashMap<String, Object>();
        row.put("objectId", OBJECT_ID);
        row.put("filename", "photo.png");
        row.put("contentType", "image/png");
        row.put("sizeBytes", 1024L);
        row.put("scanStatus", "CLEAN");
        row.put("businessVersion", 2L);
        row.put("createdAt", createdAt);
        row.put("revisionNumber", revised ? 2 : 1);
        row.put("previousObjectId", revised ? "1c9e2faa-0000-4000-8000-000000000001" : null);
        row.put("correctionReason", revised ? "blurry" : null);
        row.put("nextObjectId", revised ? null : "1c9e2fbb-0000-4000-8000-000000000002");
        return row;
    }

    private static Map<String, Object> confirmationRow(String status) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 10L);
        row.put("business_type", "REQUISITION");
        row.put("business_id", 2L);
        row.put("item_id", 5L);
        row.put("business_snapshot_json", JSON.writeValueAsString(legacyRequisitionSnapshot()));
        row.put("evidence_metadata_json", "{\"actualSigner\":\"ann\",\"delegatedReceipt\":false}");
        row.put("confirmation_status", status);
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
        row.put("evidence_snapshot_json",
                "SUBMITTED".equals(status) || "EXECUTED".equals(status)
                        ? "[{\"objectId\":\"o-1\",\"sizeBytes\":\"10\"}]"
                        : null);
        row.put("creator_account_id", 12L);
        row.put("submitted_at", "SUBMITTED".equals(status)
                ? Timestamp.from(Instant.parse("2026-11-01T03:00:00Z")) : null);
        row.put("approver_account_id", "APPROVED".equals(status) || "EXECUTED".equals(status) ? 11L : null);
        row.put("decision_reason", "REJECTED".equals(status) ? "no" : null);
        row.put("decided_at", "DRAFT".equals(status) ? null : LocalDateTime.of(2026, 11, 1, 4, 0, 0));
        row.put("executor_account_id", "EXECUTED".equals(status) ? 12L : null);
        row.put("executed_at", "EXECUTED".equals(status)
                ? Timestamp.from(Instant.parse("2026-11-01T05:00:00Z")) : null);
        row.put("execution_result_json", "EXECUTED".equals(status)
                ? "{\"result\":\"RECEIVED\",\"targetEmployeeId\":\"3\"}" : null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-01T02:30:00Z")));
        row.put("updated_at", LocalDateTime.of(2026, 11, 1, 2, 30, 0));
        return row;
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
