package com.fams.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-17b wire equivalence for the inventory seal/synchronization/observation HTTP exits. Every
 * typed SealViews shape must serialize exactly like the frozen pre-refactor Map assemblies:
 * {@code legacyWire}/{@code legacyCast} are the deleted {@code InventorySealService.wire}/{@code
 * cast} projections verbatim; {@code legacyPreflightOpen}/{@code legacyPreflightSealed}/
 * {@code legacySealReceipt}/{@code legacyBlocker}/{@code legacySnapshotPage} mirror
 * InventorySealService; {@code legacyDevice}/{@code legacyParticipant}/{@code legacyStatus}/
 * {@code legacyOwn}/{@code legacyDeclarationReceipt} mirror InventorySynchronizationService; and
 * {@code legacyCalibration}/{@code legacyIdentify*}/{@code legacyObservationReceipt} mirror
 * InventoryObservationService.
 *
 * <p>Most comparisons round-trip through JSON text so integral node widths normalize and the key
 * order of the legacy {@code Map.of} envelopes (never stable across JVM versions) is ignored,
 * while key presence, explicit nulls and value shapes all count. The three assemblies whose text
 * is a fingerprint input or an ordered stored payload — the synchronization roster behind
 * {@code sync_digest} (echoed back by the client across a request boundary), each
 * {@code fams_inventory_sealed_sync.snapshot_json} row and the observation {@code receipt_json} —
 * are additionally compared byte for byte.
 */
class InventorySealWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<SealViews.SyncParticipant>> ROSTER =
            new TypeReference<>() {};

    private static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String EVENT = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
    private static final String RECEIVED = "2026-11-05T02:00:00.123Z";
    private static final String DIGEST = "b".repeat(64);
    private static final String CHECKSUM = "f".repeat(64);

    // ---- fams_inventory_task projection ----

    @Test
    void taskRowMatchesLegacyWireAcrossDriverShapes() {
        var row = taskRow(true);
        assertWireEquals(legacyWire(row), SealViews.taskRow(JSON, row));

        // LocalDateTime is the second driver shape the legacy wire() converted through instant().
        var local = new LinkedHashMap<>(row);
        local.put("starts_at", LocalDateTime.of(2026, 11, 1, 0, 0, 0));
        local.put("updated_at", LocalDateTime.of(2026, 11, 5, 2, 0, 0, 123_000_000));
        // Integral width variant: an INT-backed count must still serialize by value.
        local.put("expected_count", Integer.valueOf(2));
        assertWireEquals(legacyWire(local), SealViews.taskRow(JSON, local));

        var lean = taskRow(false);
        assertWireEquals(legacyWire(lean), SealViews.taskRow(JSON, lean));
        var node = toJson(SealViews.taskRow(JSON, lean));
        // The legacy wire() emitted every column key: nulls stay explicit under ALWAYS inclusion.
        assertTrue(node.path("publisher_account_id").isNull());
        assertTrue(node.has("ended_at") && node.get("ended_at").isNull());
        assertTrue(node.has("end_reason") && node.get("end_reason").isNull());
        // timezone_id matched the legacy *_id rule even though the column is a VARCHAR.
        assertEquals("Asia/Shanghai", node.path("timezone_id").asString());
        assertEquals(2, node.path("range_json").size());
        assertEquals("7", node.path("range_json").path("categoryId").asString());
    }

    // ---- seal preflight ----

    @Test
    void openPreflightMatchesLegacyMapOfAssembly() {
        var row = taskRow(true);
        var blockers =
                List.of(
                        legacyBlocker("INVENTORY_SEAL_WAITING_REVIEW_REQUIRED", null, null),
                        legacyBlocker("INVENTORY_SEAL_REVIEW_REQUIRED", "948001", null));
        var roster =
                List.of(
                        legacyParticipant("947002", "张三", "DECLARED", List.of(legacyDevice(true))),
                        legacyParticipant("947003", "身份待核实", "BINDING_MISSING", List.of()));
        assertWireEquals(
                legacyPreflightOpen(row, blockers, roster, DIGEST, 1, false),
                new SealViews.Preflight(
                        SealViews.taskRow(JSON, row),
                        false,
                        null,
                        blockers.stream().map(InventorySealWireShapeTest::typedBlocker).toList(),
                        blockers.size(),
                        List.of(
                                typedParticipant("947002", "张三", "DECLARED",
                                        List.of(typedDevice(true))),
                                typedParticipant("947003", "身份待核实", "BINDING_MISSING", List.of())),
                        DIGEST,
                        1L,
                        true,
                        false));

        var clean =
                toJson(
                        new SealViews.Preflight(
                                SealViews.taskRow(JSON, row),
                                false,
                                null,
                                List.of(),
                                0,
                                List.of(),
                                DIGEST,
                                0L,
                                true,
                                true));
        // Zero-valued keys stayed present; only the sealed-branch report key is conditional.
        assertTrue(clean.has("blockerCount") && clean.path("blockerCount").asInt() == 0);
        assertTrue(clean.has("missingSynchronizationCount"));
        assertTrue(clean.path("canSealWithoutWaiver").asBoolean());
        assertTrue(clean.path("blockers").isArray());
        assertFalse(clean.has("report"));
    }

    @Test
    void sealedPreflightMatchesLegacyThreeKeyAssembly() {
        var row = taskRow(true);
        var report = legacySealedReportRow();
        var typed =
                new SealViews.Preflight(
                        SealViews.taskRow(JSON, row),
                        true,
                        SealViews.sealedReportRow(JSON, report),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertWireEquals(legacyPreflightSealed(row, legacyCast(legacyWire(report))), typed);
        var node = toJson(typed);
        // The seven open-branch keys were never put on the sealed branch.
        assertFalse(node.has("blockers"));
        assertFalse(node.has("blockerCount"));
        assertFalse(node.has("synchronization"));
        assertFalse(node.has("synchronizationDigest"));
        assertFalse(node.has("missingSynchronizationCount"));
        assertFalse(node.has("declarationOnly"));
        assertFalse(node.has("canSealWithoutWaiver"));
        assertTrue(node.path("sealed").asBoolean());
        assertTrue(node.path("report").isObject());
    }

    @Test
    void blockersMatchLegacyMapOfAssembliesPerKind() {
        var bare = legacyBlocker("INVENTORY_SEAL_FROZEN_RANGE_MISMATCH", null, null);
        assertWireEquals(bare, typedBlocker(bare));
        var item = legacyBlocker("INVENTORY_SEAL_REVIEW_REQUIRED", "948001", null);
        assertWireEquals(item, typedBlocker(item));
        var subject =
                legacyBlocker("INVENTORY_SEAL_ITEMLESS_TIME_AND_FINDING_REQUIRED", null, "949007");
        assertWireEquals(subject, typedBlocker(subject));

        var bareNode = toJson(typedBlocker(bare));
        assertEquals(1, bareNode.size());
        assertFalse(bareNode.has("itemId"));
        assertFalse(bareNode.has("observationId"));
        var itemNode = toJson(typedBlocker(item));
        assertEquals(2, itemNode.size());
        assertEquals("948001", itemNode.path("itemId").asString());
        assertFalse(itemNode.has("observationId"));
        var subjectNode = toJson(typedBlocker(subject));
        assertEquals(2, subjectNode.size());
        assertFalse(subjectNode.has("itemId"));
        assertEquals("949007", subjectNode.path("observationId").asString());
    }

    // ---- seal reply ----

    @Test
    void sealReceiptMatchesLegacyMapOfAssembly() {
        assertWireEquals(
                legacySealReceipt("947100", 7, CHECKSUM, 2, 1, 3),
                new SealViews.SealReceipt("947100", 7, "COMPLETED", CHECKSUM, 2, 1, 3));
        var node =
                toJson(new SealViews.SealReceipt("947100", 7, "COMPLETED", CHECKSUM, 0, 2, 0));
        assertEquals(7, node.size());
        assertTrue(node.path("acceptedCount").isNumber());
        assertTrue(node.path("taskId").isString());
    }

    // ---- sealed report exits ----

    @Test
    void sealedReportRowMatchesLegacyCastWire() {
        var row = legacySealedReportRow();
        assertWireEquals(legacyCast(legacyWire(row)), SealViews.sealedReportRow(JSON, row));
        var node = toJson(SealViews.sealedReportRow(JSON, row));
        // task_id/actor_account_id stringified per the legacy *_id rule; counts stay numeric.
        assertEquals("947100", node.path("task_id").asString());
        assertEquals("947009", node.path("actor_account_id").asString());
        assertEquals(7, node.path("task_version").asInt());
        assertEquals(15, node.size());
        assertTrue(node.path("snapshot_json").isObject());
        assertTrue(node.path("missing_sync_reason").isNull());
        assertEquals("2026-11-05T02:00:00.123Z", node.path("created_at").asString());
    }

    @Test
    void snapshotPageMatchesLegacyMapOfAssembly() {
        var rows =
                List.of(
                        snapshotRow("{\"item\":{\"taskId\":\"947100\"},\"dateChange\":null}"),
                        snapshotRow("{\"accountId\":\"947002\",\"current\":true}"));
        var typed =
                new SealViews.Page<>(
                        rows.stream().map(row -> SealViews.snapshotRow(JSON, row)).toList(),
                        41L,
                        2,
                        20);
        assertWireEquals(legacySnapshotPage(rows, 41L, 2, 20), typed);
        var node = toJson(typed);
        assertEquals(4, node.size());
        assertEquals(2, node.path("items").size());
        assertTrue(node.path("items").get(0).path("snapshot_json").isObject());
        assertTrue(node.path("items").get(0).path("snapshot_json").path("dateChange").isNull());
        assertEquals("a".repeat(64),
                node.path("items").get(0).path("snapshot_sha256").asString());
    }

    // ---- synchronization exits ----

    @Test
    void deviceRowMatchesLegacyLinkedHashMapInBothStates() {
        assertWireEquals(legacyDevice(true), typedDevice(true));
        assertWireEquals(legacyDevice(false), typedDevice(false));

        var declared = toJson(typedDevice(true));
        assertEquals(7, declared.size());
        assertTrue(declared.has("declarationId"));
        assertTrue(declared.has("declaredAt"));
        assertTrue(declared.has("unresolvedCount"));
        var missing = toJson(typedDevice(false));
        // bindingId was always put, so an unbound device keeps the key as an explicit null.
        assertTrue(missing.has("bindingId") && missing.get("bindingId").isNull());
        assertFalse(missing.has("declarationId"));
        assertFalse(missing.has("declaredAt"));
        assertFalse(missing.has("unresolvedCount"));
        assertEquals(4, missing.size());
    }

    @Test
    void statusExitMatchesLegacyMapOfAssembly() {
        var legacy =
                legacyStatus(
                        List.of(
                                legacyParticipant(
                                        "947002", "张三", "DECLARED",
                                        List.of(legacyDevice(true))),
                                legacyParticipant(
                                        "947003", "身份待核实", "BINDING_MISSING", List.of())),
                        21L,
                        3,
                        100);
        var typed =
                new SealViews.SynchronizationStatus(
                        List.of(
                                typedParticipant(
                                        "947002", "张三", "DECLARED", List.of(typedDevice(true))),
                                typedParticipant(
                                        "947003", "身份待核实", "BINDING_MISSING", List.of())),
                        21L,
                        3,
                        100,
                        7L,
                        true);
        assertWireEquals(legacy, typed);
        var node = toJson(typed);
        assertEquals(6, node.size());
        assertTrue(node.path("declarationOnly").asBoolean());
        assertTrue(node.path("items").get(1).path("devices").isArray());
        assertEquals(0, node.path("items").get(1).path("devices").size());
        assertEquals(1, node.path("items").get(0).path("devices").size());
    }

    @Test
    void ownExitMatchesLegacyLinkedHashMapAssembly() {
        var legacy =
                legacyOwn(
                        947100L,
                        7L,
                        "IN_PROGRESS",
                        1L,
                        DEVICE,
                        "77",
                        legacyDevice(true));
        var typed =
                new SealViews.OwnDeviceState(
                        "947100",
                        7L,
                        "IN_PROGRESS",
                        1L,
                        DEVICE,
                        "77",
                        typedDevice(true),
                        true);
        assertWireEquals(legacy, typed);
        // observationWatermark is a string in the legacy assembly, not a number.
        assertTrue(toJson(typed).path("observationWatermark").isString());
    }

    @Test
    void declarationReceiptMatchesLegacyAssemblyAndStoredText() {
        var legacy = legacyDeclarationReceipt(512L, 947100L, 8L, DEVICE, RECEIVED);
        var typed =
                new SealViews.SyncDeclarationReceipt(
                        "512", "947100", 8L, DEVICE, RECEIVED, true);
        assertWireEquals(legacy, typed);
        // The same text is written into sync_receipt.response_json, the task event detail_json and
        // the audit detail_json, so the legacy LinkedHashMap key order must stay byte-identical.
        assertEquals(JSON.writeValueAsString(legacy), JSON.writeValueAsString(typed));
    }

    @Test
    void synchronizationRosterAndSealedSyncTextAreByteIdenticalToLegacy() {
        // sync_digest is SHA-256 over this exact roster text, the client echoes it as
        // synchronizationDigest on the later seal request, and every row is also stored into
        // fams_inventory_sealed_sync.snapshot_json.
        var legacyRoster =
                List.of(
                        legacyParticipant(
                                "947002", "张三", "DECLARED", List.of(legacyDevice(true))),
                        legacyParticipant("947003", "身份待核实", "BINDING_MISSING", List.of()));
        var typedRoster =
                List.of(
                        typedParticipant(
                                "947002", "张三", "DECLARED", List.of(typedDevice(true))),
                        typedParticipant("947003", "身份待核实", "BINDING_MISSING", List.of()));
        assertEquals(JSON.writeValueAsString(legacyRoster), JSON.writeValueAsString(typedRoster));
        for (int index = 0; index < legacyRoster.size(); index++) {
            assertEquals(
                    JSON.writeValueAsString(legacyRoster.get(index)),
                    JSON.writeValueAsString(typedRoster.get(index)));
        }
    }

    // ---- observation exits ----

    @Test
    void calibrationResultMatchesLegacyMapOfAssembly() {
        var legacy = legacyCalibration("6f1a4c9e-2f1a-4e2b-9c1a-0d5f7a3b8c21", RECEIVED,
                "2026-11-05T02:00:00.456Z");
        var typed =
                new SealViews.CalibrationResult(
                        "6f1a4c9e-2f1a-4e2b-9c1a-0d5f7a3b8c21", RECEIVED,
                        "2026-11-05T02:00:00.456Z");
        assertWireEquals(legacy, typed);
        assertEquals(3, toJson(typed).size());
    }

    @Test
    void identificationMatchesAllFiveLegacyBranchAssemblies() {
        assertWireEquals(legacyIdentifyInvalidTag(), typedInvalidTag());
        assertWireEquals(legacyIdentifyUnverified(), typedUnverified());
        assertWireEquals(legacyIdentifyTaskOutside(), typedTaskOutside());
        assertWireEquals(legacyIdentifyNotAssigned(), typedNotAssigned());
        var item = assignmentRow();
        assertWireEquals(legacyIdentifyAssigned(item), typedAssigned(item));

        // Each branch only put its own keys, so every other component must stay absent.
        var invalid = toJson(typedInvalidTag());
        assertEquals(2, invalid.size());
        assertEquals("无效标签，请按资产编码或名称查询并填写手工原因；不认定盘盈。", invalid.path("message").asString());
        assertFalse(invalid.has("snapshot"));
        var assigned = toJson(typedAssigned(item));
        assertEquals(6, assigned.size());
        assertTrue(assigned.path("snapshot").isObject());
        assertTrue(assigned.path("itemId").isString());
        assertFalse(assigned.has("message"));
        assertFalse(assigned.has("assetCode"));
        var outside = toJson(typedTaskOutside());
        assertEquals(3, outside.size());
        assertEquals("FA-2026-0007", outside.path("assetCode").asString());
    }

    @Test
    void observationReceiptTextIsByteIdenticalToLegacyAssembly() {
        // The same six-key assembly is written into receipt_json (the replay source), the task
        // event detail_json and the audit detail_json, so key order is part of the stored bytes.
        var accepted =
                legacyObservationReceipt(
                        EVENT, "DIFFERENCE_REVIEW", new ArrayList<>(List.of("LOCATION",
                                "CONDITION")), RECEIVED);
        var typedAccepted =
                new SealViews.ObservationReceipt(
                        EVENT, "DIFFERENCE_REVIEW", true, List.of("LOCATION", "CONDITION"),
                        RECEIVED, false);
        assertEquals(JSON.writeValueAsString(accepted), JSON.writeValueAsString(typedAccepted));

        var expired =
                legacyObservationReceipt(EVENT, "EXPIRED", new ArrayList<>(), RECEIVED);
        var typedExpired =
                new SealViews.ObservationReceipt(
                        EVENT, "EXPIRED", false, List.of(), RECEIVED, true);
        assertEquals(JSON.writeValueAsString(expired), JSON.writeValueAsString(typedExpired));
        var node = toJson(typedExpired);
        assertEquals(6, node.size());
        assertTrue(node.path("differences").isArray());
        assertEquals(0, node.path("differences").size());
        assertTrue(node.path("recollectionRequired").asBoolean());
    }

    // ---- idempotency stored-shape replays ----

    @Test
    void storedLegacySealReceiptStillDeserializes() {
        // fams_inventory_review_receipt keeps response_json for every SEAL request id forever, and
        // the historical text came from a Map.of whose order was never the declaration order.
        var stored = JSON.writeValueAsString(legacySealReceipt("947100", 7, CHECKSUM, 2, 1, 3));
        var replay = JSON.readValue(stored, SealViews.SealReceipt.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replay)));
        assertEquals(7L, replay.version());
        assertEquals(3L, replay.missingSynchronizationCount());

        var zeroed = JSON.writeValueAsString(legacySealReceipt("947100", 1, "0".repeat(64), 0, 0, 0));
        var zeroReplay = JSON.readValue(zeroed, SealViews.SealReceipt.class);
        assertEquals(JSON.readTree(zeroed), JSON.readTree(JSON.writeValueAsString(zeroReplay)));
        assertEquals("COMPLETED", zeroReplay.status());
    }

    @Test
    void storedLegacySyncDeclarationReplyStillDeserializes() {
        var stored = JSON.writeValueAsString(legacyDeclarationReceipt(512L, 947100L, 8L, DEVICE,
                RECEIVED));
        var replay = JSON.readValue(stored, SealViews.SyncDeclarationReceipt.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replay)));
        // Byte identity proves the record re-emits the stored text of an old receipt unchanged.
        assertEquals(stored, JSON.writeValueAsString(replay));
    }

    @Test
    void storedLegacyObservationReceiptStillDeserializes() {
        // fams_inventory_observation.receipt_json is both the replay source and the audit payload.
        var stored = JSON.writeValueAsString(legacyObservationReceipt(EVENT, "ACCEPTED",
                new ArrayList<>(), RECEIVED));
        var replay = JSON.readValue(stored, SealViews.ObservationReceipt.class);
        assertEquals(stored, JSON.writeValueAsString(replay));
        assertTrue(replay.counted());

        var differences =
                JSON.writeValueAsString(
                        legacyObservationReceipt(
                                EVENT, "CONFLICT_REVIEW",
                                new ArrayList<>(List.of("LOCATION")), RECEIVED));
        var replayed = JSON.readValue(differences, SealViews.ObservationReceipt.class);
        assertEquals(differences, JSON.writeValueAsString(replayed));
        assertFalse(replayed.counted());
        assertEquals(List.of("LOCATION"), replayed.differences());
    }

    @Test
    void storedLegacySynchronizationRosterStillDeserializesIntoDigestInput() {
        var stored =
                JSON.writeValueAsString(
                        List.of(
                                legacyParticipant(
                                        "947002", "张三", "DECLARED", List.of(legacyDevice(true))),
                                legacyParticipant(
                                        "947003", "身份待核实", "BINDING_MISSING", List.of())));
        var replayed = JSON.readValue(stored, ROSTER);
        assertEquals(stored, JSON.writeValueAsString(replayed));
        assertTrue(replayed.get(0).current());
        assertEquals("512", replayed.get(0).devices().get(0).declarationId());
        assertEquals(0, replayed.get(1).devices().size());
        assertFalse(replayed.get(1).current());
    }

    @Test
    void typedViewsRoundTripToTheirOwnStoredShape() {
        var row = taskRow(true);
        for (Object typed :
                List.of(
                        SealViews.taskRow(JSON, row),
                        SealViews.sealedReportRow(JSON, legacySealedReportRow()),
                        SealViews.snapshotRow(JSON, snapshotRow("{\"item\":{}}")),
                        typedDevice(true),
                        typedDevice(false),
                        new SealViews.Preflight(
                                SealViews.taskRow(JSON, row), false, null, List.of(), 0, List.of(),
                                DIGEST, 0L, true, true),
                        new SealViews.SyncParticipant("947002", "张三", false, "BINDING_MISSING",
                                List.of()),
                        new SealViews.ObservationReceipt(EVENT, "ACCEPTED", true, List.of(),
                                RECEIVED, false))) {
            var text = JSON.writeValueAsString(typed);
            assertEquals(JSON.readTree(text), JSON.readTree(JSON.writeValueAsString(
                    JSON.readValue(text, typed.getClass()))));
        }
    }

    // ---- fixtures ----

    private static Map<String, Object> taskRow(boolean published) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 947100L);
        row.put("name", "2026 年度资产盘点");
        row.put("task_status", published ? "WAITING_REVIEW" : "DRAFT");
        row.put("range_json", "{\"categoryId\":\"7\",\"lifecycle\":\"IN_USE\"}");
        row.put("starts_at", Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
        row.put("due_at", Timestamp.from(Instant.parse("2026-11-30T00:00:00Z")));
        row.put("timezone_id", "Asia/Shanghai");
        row.put("parameter_version_id", published ? 12L : null);
        row.put("expected_count", 2L);
        row.put("captured_result_count", 2L);
        row.put("assignment_version", 1L);
        row.put("version", 6L);
        row.put("creator_account_id", 947001L);
        row.put("publisher_account_id", published ? 947001L : null);
        row.put("copied_from_task_id", published ? 946000L : null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-10-28T02:00:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse(RECEIVED)));
        row.put("published_at", published ? Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"))
                : null);
        row.put("started_at", published ? Timestamp.from(Instant.parse("2026-11-01T00:05:00Z"))
                : null);
        row.put("ended_at", null);
        row.put("end_reason", null);
        return row;
    }

    private static Map<String, Object> legacySealedReportRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("task_id", 947100L);
        row.put("task_version", 7L);
        row.put("timezone_id", "Asia/Shanghai");
        row.put("expected_count", 2L);
        row.put("accepted_count", 1L);
        row.put("not_observed_count", 1L);
        row.put("captured_count", 3L);
        row.put("actor_account_id", 947009L);
        row.put("actor_username", "admin");
        row.put("seal_reason", "年度盘点封账");
        row.put("missing_sync_reason", null);
        row.put("sync_digest", DIGEST);
        row.put("report_sha256", CHECKSUM);
        row.put(
                "snapshot_json",
                "{\"task\":{\"id\":\"947100\"},\"sealedAt\":\""
                        + RECEIVED
                        + "\",\"processedBy\":\"admin\",\"declarationOnly\":true}");
        row.put("created_at", Timestamp.from(Instant.parse(RECEIVED)));
        return row;
    }

    private static Map<String, Object> snapshotRow(String snapshot) {
        var row = new LinkedHashMap<String, Object>();
        row.put("snapshot_json", snapshot);
        row.put("snapshot_sha256", "a".repeat(64));
        return row;
    }

    /** The fams_inventory_sync_declaration row of the legacy device() query projection. */
    private static Map<String, Object> declarationRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 512L);
        row.put("binding_version", 3L);
        row.put("assignment_version", 1L);
        row.put("observation_watermark", 77L);
        row.put("unresolved_count", Integer.valueOf(0));
        row.put("declared_at", Timestamp.from(Instant.parse(RECEIVED)));
        return row;
    }

    /** The identify() row: CAST(id AS CHAR) plus the CAST(snapshot_json AS CHAR) tree column. */
    private static Map<String, Object> assignmentRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", "948001");
        row.put("assignment_version", 1L);
        row.put("recheck_generation", 0L);
        row.put("asset_version", 14L);
        row.put("snapshot", "{\"assetCode\":\"FA-2026-0007\",\"name\":\"工位显示器\"}");
        return row;
    }

    private static SealViews.Blocker typedBlocker(Map<String, String> legacy) {
        return new SealViews.Blocker(
                legacy.get("code"), legacy.get("itemId"), legacy.get("observationId"));
    }

    private static SealViews.DeviceRow typedDevice(boolean declared) {
        return SealViews.device(
                DEVICE,
                declared ? 31L : null,
                declared ? "DECLARED" : "NO_DECLARATION",
                declared ? declarationRow() : null);
    }

    private static SealViews.SyncParticipant typedParticipant(
            String account, String name, String reason, List<SealViews.DeviceRow> devices) {
        return new SealViews.SyncParticipant(
                account, name, "DECLARED".equals(reason), reason, devices);
    }

    private static SealViews.Identification typedInvalidTag() {
        return new SealViews.Identification(
                "INVALID_TAG", "无效标签，请按资产编码或名称查询并填写手工原因；不认定盘盈。", null, null, null, null,
                null, null, null);
    }

    private static SealViews.Identification typedUnverified() {
        return new SealViews.Identification(
                "UNVERIFIED", "未核实标签或编码；可记录待核实线索，不自动判盘盈。", null, null, null, null, null,
                null, null);
    }

    private static SealViews.Identification typedTaskOutside() {
        return new SealViews.Identification(
                "TASK_OUTSIDE", null, "FA-2026-0007", "工位显示器", null, null, null, null, null);
    }

    private static SealViews.Identification typedNotAssigned() {
        return new SealViews.Identification(
                "NOT_ASSIGNED", "该资产未分配给当前执行人。", null, null, null, null, null, null, null);
    }

    private static SealViews.Identification typedAssigned(Map<String, Object> item) {
        return new SealViews.Identification(
                "ASSIGNED",
                null,
                null,
                null,
                item.get("id").toString(),
                ((Number) item.get("assignment_version")).longValue(),
                ((Number) item.get("recheck_generation")).longValue(),
                ((Number) item.get("asset_version")).longValue(),
                JSON.readTree(item.get("snapshot").toString()));
    }

    // ---- legacy oracle assemblies (verbatim pre-refactor code) ----

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
            return rows.stream().map(InventorySealWireShapeTest::legacyWire).toList();
        }
        if (value instanceof Timestamp || value instanceof LocalDateTime) {
            return legacyInstant(value).toString();
        }
        return value;
    }

    private static Instant legacyInstant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private static Map<String, Object> legacyCast(Object value) {
        var source = (Map<?, ?>) value;
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> result.put(key.toString(), item));
        return result;
    }

    private static Map<String, String> legacyBlocker(
            String code, String itemId, String observationId) {
        if (itemId != null) {
            return Map.of("code", code, "itemId", itemId);
        }
        if (observationId != null) {
            return Map.of("code", code, "observationId", observationId);
        }
        return Map.of("code", code);
    }

    private static Map<String, Object> legacyPreflightOpen(
            Map<String, Object> task,
            List<Map<String, String>> blockers,
            List<Map<String, Object>> synchronization,
            String synchronizationDigest,
            long missing,
            boolean canSealWithoutWaiver) {
        return Map.of(
                "task",
                legacyWire(task),
                "sealed",
                false,
                "blockers",
                blockers.stream().limit(200).toList(),
                "blockerCount",
                blockers.size(),
                "synchronization",
                synchronization,
                "synchronizationDigest",
                synchronizationDigest,
                "missingSynchronizationCount",
                missing,
                "declarationOnly",
                true,
                "canSealWithoutWaiver",
                canSealWithoutWaiver);
    }

    private static Map<String, Object> legacyPreflightSealed(
            Map<String, Object> task, Map<String, Object> report) {
        return Map.of("task", legacyWire(task), "sealed", true, "report", report);
    }

    private static Map<String, Object> legacySealReceipt(
            String taskId,
            long version,
            String reportSha256,
            long accepted,
            long notObserved,
            long missing) {
        return Map.<String, Object>of(
                "taskId",
                taskId,
                "version",
                version,
                "status",
                "COMPLETED",
                "reportSha256",
                reportSha256,
                "acceptedCount",
                accepted,
                "notObservedCount",
                notObserved,
                "missingSynchronizationCount",
                missing);
    }

    private static Map<String, Object> legacySnapshotPage(
            List<Map<String, Object>> rows, long total, int page, int size) {
        return Map.of(
                "items",
                legacyWire(rows),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    private static Map<String, Object> legacyDevice(boolean declared) {
        var result = new LinkedHashMap<String, Object>();
        result.put("deviceId", DEVICE);
        result.put("bindingId", declared ? Long.toString(31L) : null);
        result.put("current", "DECLARED".equals(declared ? "DECLARED" : "NO_DECLARATION"));
        result.put("reason", declared ? "DECLARED" : "NO_DECLARATION");
        if (declared) {
            var rows = declarationRow();
            result.put("declarationId", rows.get("id").toString());
            Object time = rows.get("declared_at");
            result.put(
                    "declaredAt",
                    time instanceof Timestamp timestamp
                            ? timestamp.toInstant().toString()
                            : ((LocalDateTime) time)
                                    .toInstant(java.time.ZoneOffset.UTC)
                                    .toString());
            result.put("unresolvedCount", ((Number) rows.get("unresolved_count")).longValue());
        }
        return result;
    }

    private static Map<String, Object> legacyParticipant(
            String account, String name, String reason, List<Map<String, Object>> declaredDevices) {
        var row = new LinkedHashMap<String, Object>();
        row.put("accountId", account);
        row.put("name", name);
        row.put("current", "DECLARED".equals(reason));
        row.put("reason", reason);
        row.put("devices", declaredDevices);
        return row;
    }

    private static Map<String, Object> legacyStatus(
            List<Map<String, Object>> rows, long total, int page, int size) {
        return Map.of(
                "items",
                rows,
                "total",
                total,
                "page",
                page,
                "pageSize",
                size,
                "taskVersion",
                7L,
                "declarationOnly",
                true);
    }

    private static Map<String, Object> legacyOwn(
            long taskId,
            long taskVersion,
            Object taskStatus,
            long assignmentVersion,
            String deviceId,
            String watermark,
            Map<String, Object> device) {
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("taskVersion", taskVersion);
        result.put("taskStatus", taskStatus);
        result.put("assignmentVersion", assignmentVersion);
        result.put("deviceId", deviceId);
        result.put("observationWatermark", watermark);
        result.put("device", device);
        result.put("declarationOnly", true);
        return result;
    }

    private static Map<String, Object> legacyDeclarationReceipt(
            long id, long taskId, long taskVersion, String deviceId, String declaredAt) {
        var response = new LinkedHashMap<String, Object>();
        response.put("declarationId", Long.toString(id));
        response.put("taskId", Long.toString(taskId));
        response.put("taskVersion", taskVersion);
        response.put("deviceId", deviceId);
        response.put("declaredAt", declaredAt);
        response.put("declarationOnly", true);
        return response;
    }

    private static Map<String, Object> legacyCalibration(
            String id, String serverTime, String clientTime) {
        return Map.of(
                "calibrationId",
                id,
                "serverTime",
                serverTime,
                "clientTime",
                clientTime);
    }

    private static Map<String, Object> legacyIdentifyInvalidTag() {
        return Map.of(
                "kind",
                "INVALID_TAG",
                "message",
                "无效标签，请按资产编码或名称查询并填写手工原因；不认定盘盈。");
    }

    private static Map<String, Object> legacyIdentifyUnverified() {
        return Map.of(
                "kind",
                "UNVERIFIED",
                "message",
                "未核实标签或编码；可记录待核实线索，不自动判盘盈。");
    }

    private static Map<String, Object> legacyIdentifyTaskOutside() {
        return Map.of(
                "kind",
                "TASK_OUTSIDE",
                "assetCode",
                "FA-2026-0007",
                "name",
                "工位显示器");
    }

    private static Map<String, Object> legacyIdentifyNotAssigned() {
        return Map.of("kind", "NOT_ASSIGNED", "message", "该资产未分配给当前执行人。");
    }

    private static Map<String, Object> legacyIdentifyAssigned(Map<String, Object> item) {
        return Map.of(
                "kind",
                "ASSIGNED",
                "itemId",
                item.get("id"),
                "assignmentVersion",
                item.get("assignment_version"),
                "recheckGeneration",
                item.get("recheck_generation"),
                "assetVersion",
                item.get("asset_version"),
                "snapshot",
                JSON.readTree(item.get("snapshot").toString()));
    }

    private static Map<String, Object> legacyObservationReceipt(
            String eventId, String outcome, List<String> differences, String receivedAt) {
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("eventId", eventId);
        receipt.put("outcome", outcome);
        receipt.put("counted", List.of("ACCEPTED", "DIFFERENCE_REVIEW").contains(outcome));
        receipt.put("differences", differences);
        receipt.put("receivedAt", receivedAt);
        receipt.put("recollectionRequired", "EXPIRED".equals(outcome));
        return receipt;
    }

    private static JsonNode toJson(Object typed) {
        return JSON.readTree(JSON.writeValueAsString(typed));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        assertEquals(toJson(legacy), toJson(typed));
    }
}
