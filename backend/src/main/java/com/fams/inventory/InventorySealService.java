package com.fams.inventory;

import com.fams.asset.AssetInventoryReviewPort;
import com.fams.asset.AssetInventorySealPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Task lock freezes reports, accepted dates and original receipts in one transaction. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventorySealService {
    private record Evaluation(
            List<Map<String, Object>> items,
            List<Map<String, Object>> reviews,
            Map<Long, AssetInventoryReviewPort.Context> contexts,
            List<Map<String, Object>> observations,
            List<Map<String, Object>> synchronization,
            String synchronizationDigest,
            List<Map<String, String>> blockers) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final AssetInventorySealPort dates;
    private final InventorySealEvidence evidence;
    private final InventorySynchronizationService synchronization;
    private final Clock clock;

    public InventorySealService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            AssetInventorySealPort dates,
            InventorySealEvidence evidence,
            InventorySynchronizationService synchronization,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.dates = dates;
        this.evidence = evidence;
        this.synchronization = synchronization;
        this.clock = clock;
    }

    public Map<String, Object> preflight(Actor actor, long taskId) {
        admin(actor);
        var task = task(taskId);
        if ("COMPLETED".equals(task.get("task_status"))) {
            return Map.of("task", wire(task), "sealed", true, "report", report(actor, taskId));
        }
        var evaluation = evaluate(actor, task);
        long missing =
                evaluation.synchronization().stream()
                        .filter(row -> !Boolean.TRUE.equals(row.get("current")))
                        .count();
        return Map.of(
                "task",
                wire(task),
                "sealed",
                false,
                "blockers",
                evaluation.blockers().stream().limit(200).toList(),
                "blockerCount",
                evaluation.blockers().size(),
                "synchronization",
                evaluation.synchronization(),
                "synchronizationDigest",
                evaluation.synchronizationDigest(),
                "missingSynchronizationCount",
                missing,
                "declarationOnly",
                true,
                "canSealWithoutWaiver",
                evaluation.blockers().isEmpty() && missing == 0);
    }

    public Map<String, Object> seal(
            Actor actor, long taskId, long version, UUID key, InventorySealInput input) {
        var current = admin(actor);
        var task = task(taskId);
        String payload = hash(version + "\n" + json.writeValueAsString(input));
        var prior =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation='SEAL' AND request_id=?",
                        taskId,
                        current.id(),
                        key.toString());
        if (!prior.isEmpty()) {
            if (!payload.equals(prior.getFirst().get("payload_sha256"))) {
                conflict(actor, current, task, "INVENTORY_SEAL_IDEMPOTENCY_CONFLICT");
            }
            return json.readValue(
                    prior.getFirst().get("response").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        if (number(task, "version") != version
                || !"WAITING_REVIEW".equals(task.get("task_status"))) {
            conflict(actor, current, task, "INVENTORY_SEAL_TASK_CHANGED");
        }
        if (input.reason().strip().length() < 3) {
            throw new InventoryFailure(400, "INVENTORY_SEAL_REASON_REQUIRED");
        }
        var evaluation = evaluate(actor, task);
        if (!evaluation.blockers().isEmpty()) {
            throw new InventoryFailure(409, evaluation.blockers().getFirst().get("code"));
        }
        if (!evaluation.synchronizationDigest().equals(input.synchronizationDigest())) {
            conflict(actor, current, task, "INVENTORY_SEAL_SYNCHRONIZATION_CHANGED");
        }
        long missing =
                evaluation.synchronization().stream()
                        .filter(row -> !Boolean.TRUE.equals(row.get("current")))
                        .count();
        String waiver =
                input.missingSynchronizationReason() == null
                        ? ""
                        : input.missingSynchronizationReason().strip();
        if (missing > 0 && waiver.length() < 10) {
            throw new InventoryFailure(409, "INVENTORY_SEAL_SYNCHRONIZATION_REQUIRED");
        }
        if (missing == 0 && !waiver.isEmpty()) {
            throw new InventoryFailure(400, "INVENTORY_SEAL_UNNECESSARY_WAIVER");
        }
        ZoneId zone = ZoneId.of(task.get("timezone_id").toString());
        var changes = new LinkedHashMap<Long, AssetInventorySealPort.DateChange>();
        var accepted =
                evaluation.reviews().stream()
                        .filter(row -> "ACCEPTED".equals(row.get("review_status")))
                        .map(
                                row ->
                                        new AssetInventorySealPort.AcceptedObservation(
                                                number(row, "asset_id"),
                                                number(row, "observation_id"),
                                                instant(row.get("verified_observed_at"))))
                        .toList();
        for (int offset = 0; offset < accepted.size(); offset += 200) {
            for (var change :
                    dates.advance(
                            accepted.subList(offset, Math.min(offset + 200, accepted.size())),
                            zone)) {
                changes.put(change.assetId(), change);
            }
        }
        var frozenItems = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < evaluation.items().size(); index++) {
            var item = evaluation.items().get(index);
            var packet = new LinkedHashMap<String, Object>();
            packet.put("item", wire(item));
            packet.put("review", wire(evaluation.reviews().get(index)));
            packet.put(
                    "current",
                    json.readValue(
                            evaluation.contexts().get(number(item, "asset_id")).snapshotJson(),
                            Object.class));
            packet.put(
                    "selectionHistory",
                    wire(
                            db.queryForList(
                                    "SELECT * FROM fams_inventory_selection_event WHERE item_id=?"
                                            + " ORDER BY recheck_generation,review_version",
                                    item.get("id"))));
            packet.put(
                    "unobservedHistory",
                    wire(
                            db.queryForList(
                                    "SELECT * FROM fams_inventory_unobserved_reason WHERE item_id=?"
                                            + " ORDER BY recheck_generation",
                                    item.get("id"))));
            var change = changes.get(number(item, "asset_id"));
            packet.put("dateChange", change == null ? null : dateChange(change));
            frozenItems.add(packet);
        }
        var frozenEvidence = evaluation.observations().stream().map(this::evidencePacket).toList();
        var frozenTask = new LinkedHashMap<>(task);
        frozenTask.put("task_status", "COMPLETED");
        frozenTask.put("version", version + 1);
        var now = now();
        var header =
                Map.of(
                        "task",
                        wire(frozenTask),
                        "sealedAt",
                        now.toInstant().toString(),
                        "processedBy",
                        current.username(),
                        "processedByAccountId",
                        Long.toString(current.id()),
                        "reason",
                        input.reason().strip(),
                        "missingSynchronizationReason",
                        waiver,
                        "synchronizationDigest",
                        evaluation.synchronizationDigest(),
                        "declarationOnly",
                        true);
        String headerText = json.writeValueAsString(header);
        var itemTexts = frozenItems.stream().map(json::writeValueAsString).toList();
        var evidenceTexts = frozenEvidence.stream().map(json::writeValueAsString).toList();
        var syncTexts =
                evaluation.synchronization().stream().map(json::writeValueAsString).toList();
        String checksum =
                hash(
                        headerText
                                + "\n"
                                + String.join("\n", itemTexts)
                                + "\n"
                                + String.join("\n", evidenceTexts)
                                + "\n"
                                + String.join("\n", syncTexts));
        db.update(
                "INSERT INTO"
                    + " fams_inventory_sealed_report(task_id,task_version,timezone_id,expected_count,accepted_count,"
                    + "not_observed_count,captured_count,actor_account_id,actor_username,seal_reason,missing_sync_reason,sync_digest,report_sha256,snapshot_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                taskId,
                version + 1,
                zone.getId(),
                task.get("expected_count"),
                accepted.size(),
                evaluation.items().size() - accepted.size(),
                task.get("captured_result_count"),
                current.id(),
                current.username(),
                input.reason().strip(),
                waiver.isEmpty() ? null : waiver,
                evaluation.synchronizationDigest(),
                checksum,
                headerText,
                now);
        for (int index = 0; index < itemTexts.size(); index++) {
            var item = evaluation.items().get(index);
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_sealed_item(task_id,item_id,asset_id,recheck_generation,snapshot_json,snapshot_sha256)"
                        + " VALUES (?,?,?,?,?,?)",
                    taskId,
                    item.get("id"),
                    item.get("asset_id"),
                    item.get("recheck_generation"),
                    itemTexts.get(index),
                    hash(itemTexts.get(index)));
        }
        for (int index = 0; index < evidenceTexts.size(); index++) {
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_sealed_evidence(task_id,observation_id,snapshot_json,snapshot_sha256)"
                        + " VALUES (?,?,?,?)",
                    taskId,
                    evaluation.observations().get(index).get("id"),
                    evidenceTexts.get(index),
                    hash(evidenceTexts.get(index)));
        }
        for (int index = 0; index < syncTexts.size(); index++) {
            db.update(
                    "INSERT INTO"
                        + " fams_inventory_sealed_sync(task_id,account_id,snapshot_json,snapshot_sha256)"
                        + " VALUES (?,?,?,?)",
                    taskId,
                    evaluation.synchronization().get(index).get("accountId"),
                    syncTexts.get(index),
                    hash(syncTexts.get(index)));
        }
        db.update(
                "UPDATE fams_inventory_task SET"
                        + " task_status='COMPLETED',version=version+1,updated_at=? WHERE id=?",
                now,
                taskId);
        var response =
                Map.<String, Object>of(
                        "taskId",
                        Long.toString(taskId),
                        "version",
                        version + 1,
                        "status",
                        "COMPLETED",
                        "reportSha256",
                        checksum,
                        "acceptedCount",
                        accepted.size(),
                        "notObservedCount",
                        evaluation.items().size() - accepted.size(),
                        "missingSynchronizationCount",
                        missing);
        audit(actor, current, task, "INVENTORY_SEALED", version + 1, response);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,'SEAL',?,?,?,?)",
                taskId,
                current.id(),
                key.toString(),
                payload,
                json.writeValueAsString(response),
                now);
        return response;
    }

    public Map<String, Object> report(Actor actor, long taskId) {
        admin(actor);
        task(taskId);
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_sealed_report WHERE task_id=? FOR SHARE",
                        taskId);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_SEALED_REPORT_NOT_FOUND");
        }
        return cast(wire(rows.getFirst()));
    }

    public Map<String, Object> reportRows(
            Actor actor, long taskId, String section, int page, int size) {
        report(actor, taskId);
        String table =
                switch (section) {
                    case "items" -> "fams_inventory_sealed_item";
                    case "evidence" -> "fams_inventory_sealed_evidence";
                    case "synchronization" -> "fams_inventory_sealed_sync";
                    default -> throw new InventoryFailure(400, "INVENTORY_REPORT_SECTION_INVALID");
                };
        String order =
                switch (section) {
                    case "items" -> "asset_id";
                    case "evidence" -> "observation_id";
                    default -> "account_id";
                };
        var rows =
                db.queryForList(
                        "SELECT snapshot_json,snapshot_sha256 FROM "
                                + table
                                + " WHERE task_id=? ORDER BY "
                                + order
                                + " LIMIT ? OFFSET ? FOR SHARE",
                        taskId,
                        size,
                        (page - 1) * size);
        return Map.of(
                "items",
                wire(rows),
                "total",
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM " + table + " WHERE task_id=? FOR SHARE",
                                Long.class,
                                taskId)),
                "page",
                page,
                "pageSize",
                size);
    }

    private Evaluation evaluate(Actor actor, Map<String, Object> task) {
        var blockers = new ArrayList<Map<String, String>>();
        if (!"WAITING_REVIEW".equals(task.get("task_status"))) {
            blockers.add(Map.of("code", "INVENTORY_SEAL_WAITING_REVIEW_REQUIRED"));
        }
        long missingHistory =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fams_inventory_unobserved_reason WHERE"
                                        + " task_id=? AND reason_kind='ASSET_NOT_FOUND'",
                                Long.class,
                                task.get("id")));
        if (missingHistory > 0) {
            blockers.add(Map.of("code", "INVENTORY_SEAL_PHYSICAL_MISSING_OPEN"));
        }
        var items =
                db.queryForList(
                        "SELECT * FROM fams_inventory_item WHERE task_id=? ORDER BY asset_id FOR"
                                + " SHARE",
                        task.get("id"));
        if (items.isEmpty() || items.size() != number(task, "expected_count")) {
            blockers.add(Map.of("code", "INVENTORY_SEAL_FROZEN_RANGE_MISMATCH"));
        }
        var contexts = new LinkedHashMap<Long, AssetInventoryReviewPort.Context>();
        for (int offset = 0; offset < items.size(); offset += 200) {
            var ids =
                    items.subList(offset, Math.min(offset + 200, items.size())).stream()
                            .map(row -> number(row, "asset_id"))
                            .toList();
            if (dates.lock(ids)) {
                contexts.putAll(assets.contexts(ids, instant(task.get("published_at"))));
            } else {
                blockers.add(Map.of("code", "INVENTORY_SEAL_ASSET_UNAVAILABLE"));
            }
        }
        var reviews = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            var rows =
                    db.queryForList(
                            "SELECT * FROM fams_inventory_item_review WHERE item_id=? AND"
                                    + " recheck_generation=? FOR SHARE",
                            item.get("id"),
                            item.get("recheck_generation"));
            var review =
                    rows.isEmpty()
                            ? new LinkedHashMap<String, Object>()
                            : new LinkedHashMap<>(rows.getFirst());
            review.put("asset_id", item.get("asset_id"));
            reviews.add(review);
            try {
                evidence.validate(
                        task,
                        item,
                        rows.isEmpty() ? Map.of() : rows.getFirst(),
                        contexts.get(number(item, "asset_id")));
            } catch (InventoryFailure failure) {
                blockers.add(
                        Map.of("code", failure.getMessage(), "itemId", item.get("id").toString()));
            }
        }
        var observations =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? ORDER BY id FOR"
                                + " SHARE",
                        task.get("id"));
        for (var raw : observations) {
            try {
                evidence.subjects(task, raw);
            } catch (InventoryFailure failure) {
                blockers.add(
                        Map.of(
                                "code",
                                failure.getMessage(),
                                "observationId",
                                raw.get("id").toString()));
            }
        }
        var roster = new ArrayList<Map<String, Object>>();
        int page = 1;
        while (true) {
            var result = synchronization.status(actor, number(task, "id"), page++, 100);
            Object rows = result.get("items");
            if (!(rows instanceof List<?> list)) {
                throw new IllegalStateException("Inventory synchronization result invalid");
            }
            for (Object row : list) {
                roster.add(cast(row));
            }
            if (roster.size() >= number(result, "total")) {
                break;
            }
            if (list.isEmpty()) {
                throw new IllegalStateException("Inventory synchronization page incomplete");
            }
        }
        return new Evaluation(
                items,
                reviews,
                contexts,
                observations,
                roster,
                hash(json.writeValueAsString(roster)),
                blockers);
    }

    private Map<String, Object> evidencePacket(Map<String, Object> observation) {
        var result = new LinkedHashMap<String, Object>();
        result.put("observation", wire(observation));
        for (var relation :
                Map.of(
                                "subjects",
                                "fams_inventory_review_subject",
                                "decisions",
                                "fams_inventory_review_decision",
                                "transfers",
                                "fams_inventory_review_transfer",
                                "disposition",
                                "fams_inventory_evidence_disposition",
                                "findingLinks",
                                "fams_inventory_finding_link",
                                "recollections",
                                "fams_inventory_finding_recollection")
                        .entrySet()) {
            result.put(
                    relation.getKey(),
                    wire(
                            db.queryForList(
                                    "SELECT * FROM "
                                            + relation.getValue()
                                            + " WHERE observation_id=?",
                                    observation.get("id"))));
        }
        return result;
    }

    private Map<String, Object> dateChange(AssetInventorySealPort.DateChange change) {
        var result = new LinkedHashMap<String, Object>();
        result.put("assetId", Long.toString(change.assetId()));
        result.put("observationId", Long.toString(change.observationId()));
        result.put("observedAt", change.observedAt().toString());
        result.put("businessDate", change.businessDate().toString());
        result.put(
                "previousDate",
                change.previousDate() == null ? null : change.previousDate().toString());
        result.put("effectiveDate", change.effectiveDate().toString());
        return result;
    }

    private Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_REVIEW_ADMIN_REQUIRED");
        }
        return current;
    }

    private Map<String, Object> task(long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_task WHERE id=? AND task_status NOT IN"
                                + " ('DRAFT','DELETED') FOR UPDATE",
                        id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_REVIEW_TASK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void conflict(Actor actor, Current current, Map<String, Object> task, String code) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put("result", task.get("task_status").toString());
        fields.put("readOnlyUrl", InventoryReviewLinks.task(actor, number(task, "id")));
        var reports =
                db.queryForList(
                        "SELECT actor_username,created_at FROM fams_inventory_sealed_report WHERE"
                                + " task_id=? FOR SHARE",
                        task.get("id"));
        if (!reports.isEmpty()) {
            fields.put("processedBy", reports.getFirst().get("actor_username").toString());
            fields.put("processedAt", instant(reports.getFirst().get("created_at")).toString());
            fields.put("method", "SEAL");
        }
        audit(
                actor,
                current,
                task,
                "INVENTORY_SEAL_CONFLICT_REJECTED",
                number(task, "version"),
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String action,
            long after,
            Object detail) {
        String value = json.writeValueAsString(detail);
        var time = now();
        db.update(
                "INSERT INTO"
                    + " fams_inventory_task_event(task_id,actor_account_id,action,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?)",
                task.get("id"),
                current.id(),
                action,
                value,
                time);
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,'ASSET_ADMIN',?,'INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                action,
                task.get("id"),
                task.get("version"),
                after,
                time,
                value);
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> source) {
            var result = new LinkedHashMap<String, Object>();
            source.forEach(
                    (key, item) -> {
                        String field = key.toString();
                        Object content =
                                field.endsWith("_json") && item instanceof String text
                                        ? json.readValue(text, Object.class)
                                        : item;
                        result.put(
                                field,
                                item != null && (field.equals("id") || field.endsWith("_id"))
                                        ? item.toString()
                                        : wire(content));
                    });
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(this::wire).toList();
        }
        if (value instanceof Timestamp || value instanceof java.time.LocalDateTime) {
            return instant(value).toString();
        }
        return value;
    }

    private Map<String, Object> cast(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalStateException("Inventory seal map expected");
        }
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> result.put(key.toString(), item));
        return result;
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
