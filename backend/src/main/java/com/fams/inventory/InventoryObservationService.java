package com.fams.inventory;

import com.fams.asset.AssetInventoryReadPort;
import com.fams.auth.InventoryIdentityPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only observations; task locking is shared with publication, assignment and termination.
 */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class InventoryObservationService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final InventoryIdentityPort identities;
    private final AssetInventoryReadPort assets;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<
                    com.fams.attachment.AttachmentEvidencePort>
            attachments;

    public InventoryObservationService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            InventoryIdentityPort identities,
            AssetInventoryReadPort assets,
            Clock clock,
            org.springframework.beans.factory.ObjectProvider<
                            com.fams.attachment.AttachmentEvidencePort>
                    attachments) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.identities = identities;
        this.assets = assets;
        this.clock = clock;
        this.attachments = attachments;
    }

    public Map<String, Object> calibrate(
            RequisitionActorContext.Actor actor, InventoryObservationInput.Calibration input) {
        var current = actors.revalidateLocked(actor);
        Long binding = identities.bindingId(actor);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        requireTime(input.clientTime());
        String id = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO"
                    + " fams_inventory_clock_calibration(id,actor_account_id,binding_id,device_id,client_time,server_time)"
                    + " VALUES (?,?,?,?,?,?)",
                id,
                current.id(),
                binding,
                input.deviceId().toString(),
                Timestamp.from(input.clientTime()),
                Timestamp.from(now));
        return Map.of(
                "calibrationId",
                id,
                "serverTime",
                now.toString(),
                "clientTime",
                input.clientTime().toString());
    }

    public Map<String, Object> identify(
            RequisitionActorContext.Actor actor, long taskId, String source, String value) {
        var current = actors.revalidateLocked(actor);
        task(current, taskId);
        if (!List.of("SCAN", "CODE").contains(source) || value.isBlank() || value.length() > 256) {
            throw new InventoryFailure(400, "INVENTORY_LOOKUP_INVALID");
        }
        if ("SCAN".equals(source) && !value.matches("[A-Za-z0-9_-]{43}")) {
            return Map.of("kind", "INVALID_TAG", "message", "无效标签，请按资产编码或名称查询并填写手工原因；不认定盘盈。");
        }
        var found = assets.identify(source, value);
        if (found.isEmpty()) {
            return Map.of("kind", "UNVERIFIED", "message", "未核实标签或编码；可记录待核实线索，不自动判盘盈。");
        }
        var asset = found.getFirst();
        var rows =
                db.queryForList(
                        "SELECT CAST(id AS CHAR) AS"
                            + " id,executor_account_id,assignment_version,recheck_generation,asset_version,CAST(snapshot_json"
                            + " AS CHAR) AS snapshot FROM fams_inventory_item WHERE task_id=? AND"
                            + " asset_id=?",
                        taskId,
                        asset.get("id"));
        if (rows.isEmpty()) {
            return Map.of(
                    "kind",
                    "TASK_OUTSIDE",
                    "assetCode",
                    asset.get("asset_code"),
                    "name",
                    asset.get("name"));
        }
        var item = rows.getFirst();
        if (item.get("executor_account_id") == null
                || number(item, "executor_account_id") != current.id()) {
            return Map.of("kind", "NOT_ASSIGNED", "message", "该资产未分配给当前执行人。");
        }
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
                json.readTree(item.get("snapshot").toString()));
    }

    public Map<String, Object> submit(
            RequisitionActorContext.Actor actor,
            long taskId,
            InventoryObservationInput.Observation input) {
        var current = actors.revalidateLocked(actor);
        Long binding = identities.bindingId(actor);
        var task =
                task(
                        current,
                        taskId); // Identity and safety relationship precede even an old receipt.
        if (db.queryForList(
                        "SELECT account_id FROM fams_inventory_participant WHERE task_id=? AND"
                                + " account_id=? AND revoked_at IS NULL",
                        taskId,
                        current.id())
                .isEmpty()) {
            throw new InventoryFailure(403, "INVENTORY_PARTICIPATION_REQUIRED");
        }
        String payload = sha(json.writeValueAsString(input));
        var prior =
                db.queryForList(
                        "SELECT actor_account_id,binding_id,payload_sha256,CAST(receipt_json AS"
                            + " CHAR) AS receipt FROM fams_inventory_observation WHERE task_id=?"
                            + " AND client_event_id=?",
                        taskId,
                        input.eventId().toString());
        if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (number(row, "actor_account_id") != current.id()
                    || !Objects.equals(nullable(row, "binding_id"), binding)
                    || !payload.equals(row.get("payload_sha256"))) {
                throw new InventoryFailure(409, "INVENTORY_EVENT_CONFLICT");
            }
            return json.readValue(
                    row.get("receipt").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        Instant received = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        requireTime(input.rawObservedAt());
        if (input.rawScannedAt() != null) {
            requireTime(input.rawScannedAt());
        }
        requireTime(input.firstPersistedAt());
        requireTime(input.expiresAt());
        var time = time(current.id(), binding, input, received);
        validateFacts(taskId, current, binding, input, !"EXPIRED".equals(time.decision()));
        Map<String, Object> item = null;
        if (input.itemId() != null) {
            var rows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_item WHERE task_id=? AND id=? FOR UPDATE",
                            taskId,
                            input.itemId());
            if (rows.isEmpty()) {
                throw new InventoryFailure(404, "INVENTORY_ITEM_NOT_FOUND");
            }
            item = rows.getFirst();
        }
        String status = task.get("task_status").toString();
        if (List.of("DRAFT", "READY", "DELETED").contains(status)) {
            throw new InventoryFailure(409, "INVENTORY_NOT_COLLECTING");
        }
        String outcome;
        var differences = new java.util.ArrayList<String>();
        String fact = sha(json.writeValueAsString(input.actual()));
        boolean terminal = List.of("COMPLETED", "CANCELLED", "TERMINATED").contains(status);
        if ("TIME_REVIEW".equals(time.decision())) {
            outcome = "TIME_REVIEW";
        } else if ("EXPIRED".equals(time.decision())) {
            outcome = "EXPIRED";
        } else if (terminal) {
            outcome = "LATE_REVIEW";
        } else if (item == null) {
            outcome = "FINDING_REVIEW";
        } else if (item.get("executor_account_id") == null
                || number(item, "executor_account_id") != current.id()
                || number(item, "assignment_version") != input.assignmentVersion()
                || number(item, "recheck_generation") != input.recheckGeneration()
                || !Objects.equals(nullable(item, "asset_version"), input.assetVersion())) {
            outcome = "OLD_ASSIGNMENT_REVIEW";
        } else {
            var existing =
                    db.queryForList(
                            "SELECT fact_sha256 FROM fams_inventory_round_result WHERE item_id=?"
                                    + " AND recheck_generation=?",
                            input.itemId(),
                            input.recheckGeneration());
            if (!existing.isEmpty()) {
                outcome =
                        fact.equals(existing.getFirst().get("fact_sha256"))
                                ? "DUPLICATE"
                                : "CONFLICT_REVIEW";
            } else {
                differences = differences(item, input);
                outcome = differences.isEmpty() ? "ACCEPTED" : "DIFFERENCE_REVIEW";
            }
        }
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("eventId", input.eventId().toString());
        receipt.put("outcome", outcome);
        receipt.put("counted", List.of("ACCEPTED", "DIFFERENCE_REVIEW").contains(outcome));
        receipt.put("differences", differences);
        receipt.put("receivedAt", received.toString());
        receipt.put("recollectionRequired", "EXPIRED".equals(outcome));
        // Expiry retains only minimal refusal metadata, never a new late-evidence body.
        String facts = "EXPIRED".equals(outcome) ? "{}" : json.writeValueAsString(input);
        if ("EXPIRED".equals(outcome)) {
            var scopes =
                    db.queryForList(
                            "SELECT id FROM fams_inventory_upload_scope WHERE task_id=? AND"
                                    + " client_event_id=?",
                            taskId,
                            input.eventId().toString());
            var evidence = attachments.getIfAvailable();
            if (evidence != null && !scopes.isEmpty()) {
                evidence.discard(
                        "INVENTORY_EVENT", number(scopes.getFirst(), "id"), "EVENT_EXPIRED");
                db.update(
                        "UPDATE fams_inventory_upload_scope SET discarded_at=? WHERE id=?",
                        timestamp(received),
                        number(scopes.getFirst(), "id"));
            }
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_observation(task_id,client_event_id,actor_account_id,binding_id,device_id,item_id,assignment_version,recheck_generation,snapshot_asset_version,source,asset_code,raw_observed_at,first_persisted_at,client_expires_at,calibrated_observed_at,calibrated_persisted_at,calibration_id,received_at,facts_json,payload_sha256,fact_sha256,outcome,receipt_json)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                taskId,
                input.eventId().toString(),
                current.id(),
                binding,
                input.deviceId().toString(),
                input.itemId(),
                input.assignmentVersion(),
                input.recheckGeneration(),
                input.assetVersion(),
                input.source(),
                "EXPIRED".equals(outcome) ? null : input.assetCode(),
                timestamp(input.rawObservedAt()),
                timestamp(input.firstPersistedAt()),
                timestamp(input.expiresAt()),
                timestamp(time.observedAt()),
                timestamp(time.persistedAt()),
                input.calibrationId() == null ? null : input.calibrationId().toString(),
                timestamp(received),
                facts,
                payload,
                fact,
                outcome,
                json.writeValueAsString(receipt));
        db.update(
                "UPDATE fams_inventory_upload_scope SET submitted_at=? WHERE task_id=? AND"
                    + " client_event_id=?",
                timestamp(received),
                taskId,
                input.eventId().toString());
        Long observation =
                db.queryForObject(
                        "SELECT id FROM fams_inventory_observation WHERE task_id=? AND"
                                + " client_event_id=?",
                        Long.class,
                        taskId,
                        input.eventId().toString());
        if (Boolean.TRUE.equals(receipt.get("counted"))) {
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_round_result(item_id,recheck_generation,observation_id,fact_sha256)"
                        + " VALUES (?,?,?,?)",
                    input.itemId(),
                    input.recheckGeneration(),
                    observation,
                    fact);
            db.update(
                    "UPDATE fams_inventory_item SET result_count=result_count+1 WHERE id=?",
                    input.itemId());
        }
        if (!terminal && !"EXPIRED".equals(outcome)) {
            db.update(
                    "UPDATE fams_inventory_task SET"
                        + " captured_result_count=captured_result_count+1,version=version+1,updated_at=?"
                        + " WHERE id=?",
                    timestamp(received),
                    taskId);
        }
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,'INVENTORY_OBSERVED',?,?)",
                taskId,
                current.id(),
                json.writeValueAsString(receipt),
                timestamp(received));
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,'INVENTORY_OBSERVED','INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow(),
                taskId,
                number(task, "version"),
                number(task, "version") + (!terminal && !"EXPIRED".equals(outcome) ? 1 : 0),
                timestamp(received),
                json.writeValueAsString(receipt));
        return receipt;
    }

    private Map<String, Object> task(RequisitionActorContext.Current current, long id) {
        var rows = db.queryForList("SELECT * FROM fams_inventory_task WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_TASK_NOT_FOUND");
        }
        var participant =
                db.queryForList(
                        "SELECT revoked_at FROM fams_inventory_participant WHERE task_id=? AND"
                                + " account_id=?",
                        id,
                        current.id());
        if (!participant.isEmpty() && participant.getFirst().get("revoked_at") != null) {
            throw new InventoryFailure(403, "INVENTORY_PARTICIPATION_REVOKED");
        }
        if (!current.has(Role.ASSET_ADMIN) && participant.isEmpty()) {
            throw new InventoryFailure(403, "INVENTORY_PARTICIPATION_REQUIRED");
        }
        return rows.getFirst();
    }

    private InventoryObservationTime.Result time(
            long actor, Long binding, InventoryObservationInput.Observation input, Instant now) {
        if (input.calibrationId() == null) {
            return InventoryObservationTime.evaluate(input, null, null, now);
        }
        var rows =
                db.queryForList(
                        "SELECT client_time,server_time,actor_account_id,binding_id,device_id FROM"
                                + " fams_inventory_clock_calibration WHERE id=?",
                        input.calibrationId().toString());
        if (rows.isEmpty()) {
            throw new InventoryFailure(400, "INVENTORY_CALIBRATION_INVALID");
        }
        var row = rows.getFirst();
        if (number(row, "actor_account_id") != actor
                || !Objects.equals(binding, nullable(row, "binding_id"))
                || !input.deviceId().toString().equals(row.get("device_id"))) {
            throw new InventoryFailure(403, "INVENTORY_CALIBRATION_IDENTITY");
        }
        return InventoryObservationTime.evaluate(
                input, instant(row.get("client_time")), instant(row.get("server_time")), now);
    }

    private void validateFacts(
            long task,
            RequisitionActorContext.Current actor,
            Long binding,
            InventoryObservationInput.Observation input,
            boolean inspectPhotos) {
        if (!List.of("SCAN", "CODE", "MANUAL").contains(input.source())
                || !List.of("GOOD", "DAMAGED", "UNKNOWN").contains(input.actual().condition())) {
            throw new InventoryFailure(400, "INVENTORY_FACT_INVALID");
        }
        if (!"SCAN".equals(input.source())
                && (input.manualReason() == null || input.manualReason().isBlank())) {
            throw new InventoryFailure(400, "INVENTORY_MANUAL_REASON_REQUIRED");
        }
        if (input.actual().custodyType() != null
                && !List.of("STORAGE", "PERSONAL", "SHARED")
                        .contains(input.actual().custodyType())) {
            throw new InventoryFailure(400, "INVENTORY_CUSTODY_INVALID");
        }
        if (input.itemId() != null && input.itemId() <= 0
                || input.actual().locationId() != null && input.actual().locationId() <= 0
                || input.actual().responsibleEmployeeId() != null
                        && input.actual().responsibleEmployeeId() <= 0
                || input.assetVersion() != null && input.assetVersion() < 0) {
            throw new InventoryFailure(400, "INVENTORY_IDENTIFIER_INVALID");
        }
        if ((input.itemId() == null || "DAMAGED".equals(input.actual().condition()))
                && input.photoIds().isEmpty()) {
            throw new InventoryFailure(400, "INVENTORY_PHOTO_REQUIRED");
        }
        if (input.itemId() == null
                && (input.actual().description() == null
                        || input.actual().description().isBlank())) {
            throw new InventoryFailure(400, "INVENTORY_FINDING_DESCRIPTION_REQUIRED");
        }
        if (input.actual().responsibleEmployeeId() != null) {
            Long count =
                    db.queryForObject(
                            "SELECT COUNT(*) FROM fams_inventory_item WHERE task_id=? AND"
                                + " (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.responsibleEmployeeId'))=?"
                                + " OR JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.departmentResponsibleEmployeeId'))=?)",
                            Long.class,
                            task,
                            input.actual().responsibleEmployeeId().toString(),
                            input.actual().responsibleEmployeeId().toString());
            if (count == null || count == 0) {
                throw new InventoryFailure(403, "INVENTORY_PERSON_OUTSIDE_TASK");
            }
        }
        if ((input.actual().locationId() == null
                        || input.actual().custodyType() == null
                        || !"STORAGE".equals(input.actual().custodyType())
                                && input.actual().responsibleEmployeeId() == null
                        || "UNKNOWN".equals(input.actual().condition()))
                && (input.actual().unknownReason() == null
                        || input.actual().unknownReason().isBlank())) {
            throw new InventoryFailure(400, "INVENTORY_UNKNOWN_REASON_REQUIRED");
        }
        if (input.photoIds().stream().distinct().count() != input.photoIds().size()) {
            throw new InventoryFailure(400, "INVENTORY_PHOTO_DUPLICATE");
        }
        // Expiry skips reading disposed bytes, never authorization to dispose another scope.
        var scopes =
                db.queryForList(
                        "SELECT id,actor_account_id,binding_id,device_id FROM"
                                + " fams_inventory_upload_scope WHERE task_id=? AND"
                                + " client_event_id=?",
                        task,
                        input.eventId().toString());
        if (scopes.isEmpty()) {
            if (inspectPhotos && !input.photoIds().isEmpty()) {
                throw new InventoryFailure(403, "INVENTORY_PHOTO_NOT_AUTHORIZED");
            }
            return;
        }
        if (number(scopes.getFirst(), "actor_account_id") != actor.id()
                || !Objects.equals(nullable(scopes.getFirst(), "binding_id"), binding)
                || !input.deviceId().toString().equals(scopes.getFirst().get("device_id"))) {
            throw new InventoryFailure(403, "INVENTORY_PHOTO_NOT_AUTHORIZED");
        }
        if (inspectPhotos) {
            var evidence = attachments.getIfAvailable();
            if (evidence == null) {
                throw new InventoryFailure(403, "INVENTORY_PHOTO_NOT_AUTHORIZED");
            }
            for (UUID photo : input.photoIds()) {
                if (!evidence.clean(
                        "INVENTORY_EVENT", number(scopes.getFirst(), "id"), actor.id(), photo)) {
                    throw new InventoryFailure(403, "INVENTORY_PHOTO_NOT_AUTHORIZED");
                }
            }
        }
    }

    private java.util.ArrayList<String> differences(
            Map<String, Object> item, InventoryObservationInput.Observation input) {
        var result = new java.util.ArrayList<String>();
        var snapshot = json.readTree(item.get("snapshot_json").toString());
        if (!Objects.equals(
                snapshot.path("locationId").asText(null),
                input.actual().locationId() == null
                        ? null
                        : input.actual().locationId().toString())) {
            result.add("LOCATION");
        }
        if (!Objects.equals(
                snapshot.path("custodyType").asText(null), input.actual().custodyType())) {
            result.add("USAGE_MODE");
        }
        String person = snapshot.path("responsibleEmployeeId").asText(null);
        if (person == null) {
            person = snapshot.path("departmentResponsibleEmployeeId").asText(null);
        }
        if (!Objects.equals(
                        person,
                        input.actual().responsibleEmployeeId() == null
                                ? null
                                : input.actual().responsibleEmployeeId().toString())
                || input.actual().responsibilityHint() != null
                        && !input.actual().responsibilityHint().isBlank()) {
            result.add("RESPONSIBILITY");
        }
        if (!"GOOD".equals(input.actual().condition())) {
            result.add("CONDITION");
        }
        if (input.actual().unknownReason() != null && !input.actual().unknownReason().isBlank()) {
            result.add("UNKNOWN");
        }
        String current = assets.currentSnapshot(number(item, "asset_id"));
        if (current == null) {
            result.add("PERIOD_MOVEMENT");
        } else {
            var latest = json.readTree(current);
            for (String field :
                    List.of(
                            "locationId",
                            "custodyType",
                            "responsibleEmployeeId",
                            "departmentResponsibleEmployeeId",
                            "usingOrganizationId",
                            "lifecycle",
                            "exception",
                            "warehouseId")) {
                if (!Objects.equals(snapshot.path(field), latest.path(field))) {
                    result.add("PERIOD_MOVEMENT");
                    break;
                }
            }
        }
        return result;
    }

    private void requireTime(Instant time) {
        if (time.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                || time.isAfter(Instant.parse("2100-01-01T00:00:00Z"))
                || !time.equals(time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))) {
            throw new InventoryFailure(400, "INVENTORY_TIME_INVALID");
        }
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private Long nullable(Map<String, Object> row, String field) {
        return row.get(field) == null ? null : number(row, field);
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp timestamp
                ? timestamp.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
