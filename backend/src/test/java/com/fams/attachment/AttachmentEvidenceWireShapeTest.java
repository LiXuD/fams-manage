package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fams.inventory.InventoryPhotoReadService;
import com.fams.loss.LossService;
import com.fams.repair.RepairCollaborationService;
import com.fams.repair.RepairFactoryService;
import com.fams.repair.RepairService;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-06 wire equivalence: AttachmentEvidencePort.list rows became the typed Metadata
 * envelope, and every domain consumes them through a named projection. Each legacy*
 * helper below is the pre-refactor projection algorithm verbatim (LossService.wire,
 * RepairService.wire, RepairFactoryService.wire + instant(),
 * RepairCollaborationService.photoProjection, the InventoryPhotoController.list item
 * conversion, and the deleted ScrapViews.attachmentMetadata boundary). Comparison
 * round-trips through JSON text so integral node widths normalize and object key order
 * (never a contract) is ignored, while key presence, explicit nulls and value shapes all
 * count. createdAt is the highest red line: the port envelope and the pass-through wires
 * (scrap list, inventory repair evidence list) keep the raw JDBC value, while the
 * converting wires (loss/repair/factory/inventory photo) rewrite Timestamp/LocalDateTime
 * to ISO instant strings exactly as before.
 */
class AttachmentEvidenceWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBJECT_ID = "6d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";
    private static final Instant INSTANT = Instant.parse("2026-11-01T02:00:00.123Z");

    @Test
    void portMappingMirrorsAliasRowAndPassesCreatedAtThroughRaw() {
        for (Object createdAt : List.of(Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var row = attachmentRow(createdAt);
            var typed = AttachmentEvidencePort.fromRow(row);
            assertWireEquals(row, typed);
            // The envelope never converts the DATETIME(3) driver shape.
            assertSame(createdAt, typed.createdAt());
        }
        // Integral widths normalize on the wire: Integer-valued JDBC rows stay equivalent.
        var narrow = attachmentRow(Timestamp.from(INSTANT));
        narrow.put("sizeBytes", 1024);
        narrow.put("businessVersion", 0);
        assertWireEquals(narrow, AttachmentEvidencePort.fromRow(narrow));
    }

    @Test
    void scrapAttachmentListMatchesPreRefactorBoundaryProjection() {
        for (Object createdAt : List.of(Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var rows = List.of(attachmentRow(createdAt), attachmentRow(createdAt));
            var legacy = rows.stream().map(AttachmentEvidenceWireShapeTest::legacyScrapMetadata).toList();
            var typed = rows.stream().map(AttachmentEvidencePort::fromRow).toList();
            assertWireEquals(legacy, typed);
        }
    }

    @Test
    void lossPhotosMatchFrozenWireProjectionIncludingApprovalSnapshotFreeze() {
        for (Object createdAt : List.of(Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var rows = List.of(attachmentRow(createdAt));
            // decide() freezes the same projection into approval_snapshot_json; text equality
            // covers both the get() response and the stored shape.
            assertWireEquals(legacyLossWire(rows), LossService.photoProjection(typed(rows)));
        }
    }

    @Test
    void repairPhotosMatchFrozenWireProjection() {
        for (Object createdAt : List.of(Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var rows = List.of(attachmentRow(createdAt));
            assertWireEquals(legacyRepairWire(rows), RepairService.photoProjection(typed(rows)));
        }
    }

    @Test
    void factoryFilesMatchFrozenWireProjectionAcrossTemporalShapes() {
        for (Object createdAt : List.of(
                Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000), INSTANT)) {
            var rows = List.of(attachmentRow(createdAt));
            assertWireEquals(legacyFactoryWire(rows), RepairFactoryService.fileProjection(typed(rows)));
        }
    }

    @Test
    void collaborationPhotosKeepOnlyTheFiveLegacyKeys() {
        var rows = List.of(attachmentRow(Timestamp.from(INSTANT)));
        var typed = RepairCollaborationService.photoProjection(typed(rows));
        assertWireEquals(legacyCollaborationProjection(rows), typed);
        JsonNode item = JSON.readTree(JSON.writeValueAsString(typed)).get(0);
        assertFalse(item.has("businessVersion"));
        assertFalse(item.has("createdAt"));
        assertEquals(5, item.size());
    }

    @Test
    void inventoryPhotoItemsMatchFrozenControllerProjection() {
        for (Object createdAt : List.of(
                Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000), "2026-11-01T02:00:00.123Z")) {
            var rows = List.of(attachmentRow(createdAt));
            var legacy = Map.of("items", rows.stream().map(AttachmentEvidenceWireShapeTest::legacyInventoryPhotoItem).toList());
            var typed = Map.of("items", typed(rows).stream().map(InventoryPhotoReadService::photo).toList());
            assertWireEquals(legacy, typed);
        }
    }

    @Test
    void inventoryRepairEvidenceListPassesRawRowsThroughWithoutConversion() {
        for (Object createdAt : List.of(Timestamp.from(INSTANT), LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000))) {
            var rows = List.of(attachmentRow(createdAt));
            // The controller wraps the service list unchanged; createdAt must stay raw.
            assertWireEquals(Map.of("items", rows), Map.of("items", typed(rows)));
        }
    }

    @Test
    void convertingProjectionsNormalizeBothDriverShapesToTheSameInstantText() {
        var timestampRow = List.of(attachmentRow(Timestamp.from(INSTANT)));
        var localDateTimeRow = List.of(attachmentRow(LocalDateTime.of(2026, 11, 1, 2, 0, 0, 123_000_000)));
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(LossService.photoProjection(typed(timestampRow)))),
                JSON.readTree(JSON.writeValueAsString(LossService.photoProjection(typed(localDateTimeRow)))));
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(RepairService.photoProjection(typed(timestampRow)))),
                JSON.readTree(JSON.writeValueAsString(RepairService.photoProjection(typed(localDateTimeRow)))));
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(RepairFactoryService.fileProjection(typed(timestampRow)))),
                JSON.readTree(JSON.writeValueAsString(RepairFactoryService.fileProjection(typed(localDateTimeRow)))));
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(typed(timestampRow).stream().map(InventoryPhotoReadService::photo).toList())),
                JSON.readTree(JSON.writeValueAsString(typed(localDateTimeRow).stream().map(InventoryPhotoReadService::photo).toList())));
    }

    private static List<AttachmentEvidencePort.Metadata> typed(List<Map<String, Object>> rows) {
        return rows.stream().map(AttachmentEvidencePort::fromRow).toList();
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

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** Frozen pre-JAVA-06 ScrapViews.attachmentMetadata boundary; reference oracle only. */
    private static Map<String, Object> legacyScrapMetadata(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("objectId", (String) row.get("objectId"));
        result.put("filename", (String) row.get("filename"));
        result.put("contentType", (String) row.get("contentType"));
        result.put("sizeBytes", ((Number) row.get("sizeBytes")).longValue());
        result.put("scanStatus", (String) row.get("scanStatus"));
        result.put("businessVersion", ((Number) row.get("businessVersion")).longValue());
        result.put("createdAt", row.get("createdAt"));
        return result;
    }

    /** Frozen pre-JAVA-06 LossService.wire; reference oracle only. */
    private static Object legacyLossWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : legacyLossWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AttachmentEvidenceWireShapeTest::legacyLossWire).toList();
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return value;
    }

    /** Frozen pre-JAVA-06 RepairService.wire; reference oracle only. */
    private static Object legacyRepairWire(Object value) {
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
                                                    : legacyRepairWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AttachmentEvidenceWireShapeTest::legacyRepairWire).toList();
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        return value;
    }

    /** Frozen pre-JAVA-06 RepairFactoryService.wire; reference oracle only. */
    private static Object legacyFactoryWire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().endsWith("_json") && v instanceof String text
                                            ? JSON.readTree(text)
                                            : legacyFactoryWire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AttachmentEvidenceWireShapeTest::legacyFactoryWire).toList();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp
                || value instanceof java.time.LocalDateTime
                || value instanceof Instant) {
            return legacyFactoryInstant(value).toString();
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value;
    }

    /** Frozen pre-JAVA-06 RepairFactoryService.instant; reference oracle only. */
    private static Instant legacyFactoryInstant(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC);
        }
        return value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    /** Frozen pre-JAVA-06 RepairCollaborationService.photoProjection; reference oracle only. */
    private static Object legacyCollaborationProjection(List<Map<String, Object>> photos) {
        return photos.stream()
                .map(
                        row -> {
                            var result = new LinkedHashMap<String, Object>();
                            for (String field :
                                    List.of(
                                            "objectId",
                                            "filename",
                                            "contentType",
                                            "sizeBytes",
                                            "scanStatus")) {
                                result.put(field, row.get(field));
                            }
                            return result;
                        })
                .toList();
    }

    /** Frozen pre-JAVA-06 InventoryPhotoController.list item conversion; reference oracle only. */
    private static Map<String, Object> legacyInventoryPhotoItem(Map<String, Object> row) {
        var item = new LinkedHashMap<String, Object>(row);
        Object created = item.get("createdAt");
        if (created instanceof java.sql.Timestamp time) {
            item.put("createdAt", time.toInstant().toString());
        } else if (created instanceof java.time.LocalDateTime time) {
            item.put("createdAt", time.toInstant(java.time.ZoneOffset.UTC).toString());
        }
        return item;
    }
}
