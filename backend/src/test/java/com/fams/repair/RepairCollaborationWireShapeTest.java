package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-16c wire equivalence for the repair collaboration HTTP exits. Every typed
 * RepairViews.Collaboration* shape must serialize exactly like the frozen pre-refactor
 * assemblies: legacyWire is the deleted RepairCollaborationService.wire verbatim,
 * legacyStaffBoard/legacyTechniciansPage/legacyMinimal/legacyGetAssembly mirror the pre-refactor
 * Map assemblies of staffBoard()/technicians()/minimal()/get(), and legacy*Receipt mirrors the
 * Map.of bodies remember() stored for TECH_INVITE/TECH_REVOKE/TECH_OPINION/TECH_ATTACHMENT.
 * Comparison round-trips through JSON text so integral node widths normalize and object key order
 * (never stable in the Map.of-based responses) is ignored, while key presence, explicit nulls and
 * value shapes all count. The collaboration surface has no conditional keys: every legacy key was
 * always emitted, so every state keeps the full key set (asserted per test).
 */
class RepairCollaborationWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- sub-table row projections (frozen wire() over the collaboration selects) ----

    @Test
    void invitationRowMatchesLegacyWireAcrossDriverShapes() {
        var row = invitationRow(true);
        assertWireEquals(legacyWire(row), RepairViews.collaborationInvitation(row));

        var local = new LinkedHashMap<>(row);
        local.put("invited_at", LocalDateTime.of(2026, 11, 20, 1, 2, 3, 456_000_000));
        local.put("ended_at", LocalDateTime.of(2026, 11, 25, 9, 0, 0));
        assertWireEquals(legacyWire(local), RepairViews.collaborationInvitation(local));

        // Ended invitation: ended_at/end_reason were always emitted, nulls stay explicit.
        var open = RepairViews.collaborationInvitation(invitationRow(false));
        assertWireEquals(legacyWire(invitationRow(false)), open);
        var node = JSON.readTree(JSON.writeValueAsString(open));
        assertTrue(node.has("ended_at") && node.get("ended_at").isNull());
        assertTrue(node.has("end_reason") && node.get("end_reason").isNull());
        assertTrue(node.get("id").isTextual());
        assertTrue(node.get("technician_account_id").isTextual());
        assertTrue(node.get("inviter_account_id").isTextual());
        assertTrue(node.get("version").isNumber());
    }

    @Test
    void opinionRowMatchesLegacyWireIncludingParsedAttachmentIdsJson() {
        var row = opinionRow();
        assertWireEquals(legacyWire(row), RepairViews.collaborationOpinion(JSON, row));

        var local = new LinkedHashMap<>(row);
        local.put("occurred_at", LocalDateTime.of(2026, 11, 24, 6, 7, 8));
        local.put("recorded_at", LocalDateTime.of(2026, 11, 24, 6, 7, 9));
        assertWireEquals(legacyWire(local), RepairViews.collaborationOpinion(JSON, local));

        // Integral width variant: INT auto-increment ids serialize by value.
        var narrow = new LinkedHashMap<>(row);
        narrow.put("id", Integer.valueOf(9));
        assertWireEquals(legacyWire(narrow), RepairViews.collaborationOpinion(JSON, narrow));
    }

    @Test
    void technicianPageMatchesLegacyMapOfAssembly() {
        var rows = List.of(technicianRow());
        var legacy =
                Map.<String, Object>of(
                        "items", legacyWire(rows),
                        "total", 3L,
                        "page", 1,
                        "pageSize", 20);
        assertWireEquals(
                legacy,
                new RepairViews.Page<>(RepairViews.collaborationTechnicians(rows), 3, 1, 20));
    }

    // ---- staff board exit ----

    @Test
    void boardMatchesLegacyAssemblyInBothInviteStates() {
        var header = repairHeaderRow();
        var invitations = List.of(invitationRow(true), invitationRow(false));
        var opinions = List.of(opinionRow());
        var legacy = legacyStaffBoard(header, invitations, opinions, 2L, 1L);
        var typed =
                new RepairViews.CollaborationBoard(
                        Long.toString(((Number) header.get("id")).longValue()),
                        ((Number) header.get("version")).longValue(),
                        true,
                        RepairViews.collaborationInvitations(invitations),
                        2L,
                        RepairViews.collaborationOpinions(JSON, opinions),
                        1L);
        assertWireEquals(legacy, typed);
        var node = JSON.readTree(JSON.writeValueAsString(typed));
        // No conditional keys on the collaboration board: every key present in both states.
        for (var key :
                List.of(
                        "repairId",
                        "requestVersion",
                        "canInvite",
                        "invitations",
                        "invitationTotal",
                        "opinions",
                        "opinionTotal")) {
            assertTrue(node.has(key), key);
        }
        assertTrue(node.get("repairId").isTextual());
        assertEquals(10, size(node.get("invitations").get(0)));
    }

    // ---- technician-facing brief (mine + get) ----

    @Test
    void briefMatchesLegacyMinimalAcrossDriverShapes() {
        var row = mineRow();
        assertWireEquals(legacyMinimal(row), typedBrief(row));

        var local = new LinkedHashMap<>(row);
        local.put("invited_at", LocalDateTime.of(2026, 11, 21, 4, 5, 6));
        local.put("occurred_at", LocalDateTime.of(2026, 11, 20, 1, 2, 3, 123_000_000));
        assertWireEquals(legacyMinimal(local), typedBrief(local));

        // Integral width variant for the invitation version column.
        var narrow = new LinkedHashMap<>(row);
        narrow.put("version", Integer.valueOf(2));
        assertWireEquals(legacyMinimal(narrow), typedBrief(narrow));
    }

    @Test
    void minePageMatchesLegacyMapOfAssembly() {
        var row = mineRow();
        var legacy =
                Map.<String, Object>of(
                        "items", List.of(legacyMinimal(row)),
                        "total", 5L,
                        "page", 2,
                        "pageSize", 20);
        assertWireEquals(
                legacy,
                new RepairViews.Page<>(List.of(typedBrief(row)), 5, 2, 20));
    }

    @Test
    void getExitMatchesLegacyAssemblyInBothPhotoStates() {
        var row = mineRow();
        var opinions = List.of(opinionRow());
        var photos = List.of(photo());
        var legacy = legacyGetAssembly(row, opinions, 1L, true, photos, photos);
        var brief = typedBrief(row);
        var typed =
                new RepairViews.CollaborationInvitationDetail(
                        brief.invitationId(),
                        brief.repairId(),
                        brief.version(),
                        brief.invitedAt(),
                        brief.asset(),
                        brief.fault(),
                        brief.impact(),
                        brief.faultOccurredAt(),
                        brief.status(),
                        RepairViews.collaborationOpinions(JSON, opinions),
                        1L,
                        true,
                        photos,
                        photos);
        assertWireEquals(legacy, typed);

        // Evidence port unavailable: the legacy exit still emitted empty lists plus false.
        var lean =
                new RepairViews.CollaborationInvitationDetail(
                        brief.invitationId(),
                        brief.repairId(),
                        brief.version(),
                        brief.invitedAt(),
                        brief.asset(),
                        brief.fault(),
                        brief.impact(),
                        brief.faultOccurredAt(),
                        brief.status(),
                        List.of(),
                        0L,
                        false,
                        List.of(),
                        List.of());
        assertWireEquals(legacyGetAssembly(row, List.of(), 0L, false, List.of(), List.of()), lean);
        var node = JSON.readTree(JSON.writeValueAsString(lean));
        assertFalse(node.get("photosAvailable").asBoolean());
        assertTrue(node.get("faultPhotos").isArray() && node.get("faultPhotos").isEmpty());
    }

    // ---- receipts (invite/revoke/opinion/attachment) and the shared grant ----

    @Test
    void receiptsMatchLegacyMapOfAssemblies() {
        assertWireEquals(
                Map.<String, Object>of(
                        "repairId", "7",
                        "invitationId", "88",
                        "requestVersion", 6L),
                new RepairViews.CollaborationRequestReceipt("7", "88", 6));
        assertWireEquals(
                Map.<String, Object>of(
                        "repairId", "7",
                        "invitationId", "88",
                        "opinionId", "99",
                        "invitationVersion", 3L),
                new RepairViews.CollaborationOpinionReceipt("7", "88", "99", 3));
        assertWireEquals(
                Map.<String, Object>of(
                        "repairId", "7",
                        "invitationId", "88",
                        "objectId", "0f8fad5b-d9cb-469f-a165-70867728950e",
                        "invitationVersion", 4L),
                new RepairViews.CollaborationAttachmentReceipt(
                        "7", "88", "0f8fad5b-d9cb-469f-a165-70867728950e", 4));
        assertWireEquals(
                Map.<String, Object>of(
                        "url", "/api/v1/repair-collaborations/88/fault-photos/x/content?grant=t",
                        "expiresAtEpochSecond", 1795200000L),
                new RepairViews.PhotoGrant(
                        "/api/v1/repair-collaborations/88/fault-photos/x/content?grant=t",
                        1795200000L));
    }

    // ---- idempotency stored-shape replays (fams_repair_receipt.response_json) ----

    @Test
    void legacyStoredCollaborationReceiptsStillDeserialize() {
        // TECH_INVITE/TECH_REVOKE stored the three-key Map.of; both replay through the record.
        var storedRequest =
                JSON.writeValueAsString(
                        Map.<String, Object>of(
                                "repairId", "7",
                                "invitationId", "88",
                                "requestVersion", 6L));
        var request =
                JSON.readValue(storedRequest, RepairViews.CollaborationRequestReceipt.class);
        assertEquals(
                JSON.readTree(storedRequest),
                JSON.readTree(JSON.writeValueAsString(request)));

        var storedOpinion =
                JSON.writeValueAsString(
                        Map.<String, Object>of(
                                "repairId", "7",
                                "invitationId", "88",
                                "opinionId", "99",
                                "invitationVersion", 3L));
        var opinion = JSON.readValue(storedOpinion, RepairViews.CollaborationOpinionReceipt.class);
        assertEquals(
                JSON.readTree(storedOpinion),
                JSON.readTree(JSON.writeValueAsString(opinion)));

        var storedAttachment =
                JSON.writeValueAsString(
                        Map.<String, Object>of(
                                "repairId", "7",
                                "invitationId", "88",
                                "objectId", "0f8fad5b-d9cb-469f-a165-70867728950e",
                                "invitationVersion", 4L));
        var attachment =
                JSON.readValue(storedAttachment, RepairViews.CollaborationAttachmentReceipt.class);
        assertEquals(
                JSON.readTree(storedAttachment),
                JSON.readTree(JSON.writeValueAsString(attachment)));
    }

    @Test
    void typedCollaborationShapesRoundTripToTheirOwnStoredShape() {
        assertRoundTrip(
                new RepairViews.CollaborationRequestReceipt("7", "88", 6),
                RepairViews.CollaborationRequestReceipt.class);
        assertRoundTrip(
                new RepairViews.CollaborationOpinionReceipt("7", "88", "99", 3),
                RepairViews.CollaborationOpinionReceipt.class);
        assertRoundTrip(
                new RepairViews.CollaborationAttachmentReceipt(
                        "7", "88", "0f8fad5b-d9cb-469f-a165-70867728950e", 4),
                RepairViews.CollaborationAttachmentReceipt.class);
        assertRoundTrip(
                typedBrief(mineRow()), RepairViews.CollaborationBrief.class);
        var row = mineRow();
        var brief = typedBrief(row);
        assertRoundTrip(
                new RepairViews.CollaborationInvitationDetail(
                        brief.invitationId(),
                        brief.repairId(),
                        brief.version(),
                        brief.invitedAt(),
                        brief.asset(),
                        brief.fault(),
                        brief.impact(),
                        brief.faultOccurredAt(),
                        brief.status(),
                        RepairViews.collaborationOpinions(JSON, List.of(opinionRow())),
                        1L,
                        true,
                        List.of(photo()),
                        List.of()),
                RepairViews.CollaborationInvitationDetail.class);
        assertRoundTrip(
                new RepairViews.CollaborationBoard(
                        "7",
                        5L,
                        true,
                        RepairViews.collaborationInvitations(List.of(invitationRow(true))),
                        1L,
                        RepairViews.collaborationOpinions(JSON, List.of(opinionRow())),
                        1L),
                RepairViews.CollaborationBoard.class);
    }

    // ---- helpers ----

    private static void assertRoundTrip(Object view, Class<?> type) {
        var stored = JSON.writeValueAsString(view);
        assertEquals(
                JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the same
        // bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** The frozen pre-JAVA-16c RepairCollaborationService.wire projection; oracle only. */
    private static Object legacyWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : k.toString().endsWith("_json")
                                                            && v instanceof String text
                                                    ? JSON.readValue(text, Object.class)
                                                    : legacyWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RepairCollaborationWireShapeTest::legacyWire).toList();
        }
        if (value instanceof Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC).toString();
        }
        return value;
    }

    /** The frozen pre-JAVA-16c staffBoard LinkedHashMap assembly; oracle only. */
    private static Map<String, Object> legacyStaffBoard(
            Map<String, Object> header,
            List<Map<String, Object>> invitations,
            List<Map<String, Object>> opinions,
            long invitationTotal,
            long opinionTotal) {
        var result = new LinkedHashMap<String, Object>();
        result.put("repairId", Long.toString(((Number) header.get("id")).longValue()));
        result.put("requestVersion", ((Number) header.get("version")).longValue());
        result.put("canInvite", true);
        result.put("invitations", legacyWire(invitations));
        result.put("invitationTotal", invitationTotal);
        result.put("opinions", legacyWire(opinions));
        result.put("opinionTotal", opinionTotal);
        return result;
    }

    /** The frozen pre-JAVA-16c minimal() Map.of assembly; oracle only. */
    private static Map<String, Object> legacyMinimal(Map<String, Object> row) {
        var source = JSON.readTree(row.get("source_snapshot_json").toString());
        return Map.of(
                "invitationId",
                row.get("id").toString(),
                "repairId",
                row.get("repair_id").toString(),
                "version",
                row.get("version"),
                "invitedAt",
                legacyWire(row.get("invited_at")),
                "asset",
                Map.of(
                        "assetCode",
                        source.path("assetCode").asText(),
                        "name",
                        source.path("name").asText()),
                "fault",
                row.get("fault"),
                "impact",
                row.get("impact"),
                "faultOccurredAt",
                legacyWire(row.get("occurred_at")),
                "status",
                row.get("repair_status"));
    }

    /** The frozen pre-JAVA-16c get() LinkedHashMap assembly; oracle only. */
    private static Map<String, Object> legacyGetAssembly(
            Map<String, Object> row,
            List<Map<String, Object>> opinions,
            long opinionTotal,
            boolean photosAvailable,
            List<RepairCollaborationService.Photo> faultPhotos,
            List<RepairCollaborationService.Photo> attachments) {
        var result = new LinkedHashMap<>(legacyMinimal(row));
        result.put("opinions", legacyWire(opinions));
        result.put("opinionTotal", opinionTotal);
        result.put("photosAvailable", photosAvailable);
        result.put("faultPhotos", faultPhotos);
        result.put("attachments", attachments);
        return result;
    }

    /** Typed mirror of the new minimal(): same extraction the service performs. */
    private static RepairViews.CollaborationBrief typedBrief(Map<String, Object> row) {
        var source = JSON.readTree(row.get("source_snapshot_json").toString());
        return new RepairViews.CollaborationBrief(
                row.get("id").toString(),
                row.get("repair_id").toString(),
                ((Number) row.get("version")).longValue(),
                RepairViews.timeValue(row.get("invited_at")),
                new RepairViews.CollaborationAsset(
                        source.path("assetCode").asText(), source.path("name").asText()),
                (String) row.get("fault"),
                (String) row.get("impact"),
                RepairViews.timeValue(row.get("occurred_at")),
                (String) row.get("repair_status"));
    }

    private static Map<String, Object> repairHeaderRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L);
        row.put("version", 5L);
        return row;
    }

    private static Map<String, Object> invitationRow(boolean ended) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 88L);
        row.put("technician_account_id", 21L);
        row.put("technician_username", "tech01");
        row.put("inviter_account_id", 13L);
        row.put("invitation_status", ended ? "ENDED" : "ACTIVE");
        row.put("reason", "需要外部技师判断");
        row.put("version", 2L);
        row.put("invited_at", Timestamp.from(Instant.parse("2026-11-20T01:02:03.456Z")));
        row.put("ended_at", ended ? Timestamp.from(Instant.parse("2026-11-25T09:00:00Z")) : null);
        row.put("end_reason", ended ? "OPINION_SUBMITTED" : null);
        return row;
    }

    private static Map<String, Object> opinionRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 99L);
        row.put("invitation_id", 88L);
        row.put("judgement", "主板故障");
        row.put("method", "更换主板");
        row.put("opinion_result", "FACTORY_SUGGESTED");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-24T06:07:08Z")));
        row.put(
                "attachment_ids_json",
                "[\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"11111111-d9cb-469f-a165-70867728950e\"]");
        row.put("actor_username", "tech01");
        row.put("recorded_at", Timestamp.from(Instant.parse("2026-11-24T06:07:09Z")));
        return row;
    }

    private static Map<String, Object> technicianRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 21L);
        row.put("username", "tech01");
        row.put("name", "李维修");
        return row;
    }

    /** Mine-query row: invitation id/version/invited_at joined over the repair header columns. */
    private static Map<String, Object> mineRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 88L);
        row.put("version", 2L);
        row.put("invited_at", Timestamp.from(Instant.parse("2026-11-20T01:02:03.456Z")));
        row.put("repair_id", 7L);
        row.put("fault", "屏幕碎裂");
        row.put("impact", "HIGH");
        row.put("repair_status", "WAREHOUSE_PROCESSING");
        row.put(
                "source_snapshot_json",
                "{\"id\":\"40\",\"assetCode\":\"FA-1\",\"name\":\"laptop\",\"version\":3,"
                        + "\"lifecycle\":\"UNDER_REPAIR\"}");
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-19T23:00:00Z")));
        return row;
    }

    private static RepairCollaborationService.Photo photo() {
        return new RepairCollaborationService.Photo(
                "0f8fad5b-d9cb-469f-a165-70867728950e", "a.jpg", "image/jpeg", 10L, "CLEAN");
    }

    private static int size(JsonNode node) {
        int count = 0;
        for (var ignored : node.propertyNames()) {
            count++;
        }
        return count;
    }
}
