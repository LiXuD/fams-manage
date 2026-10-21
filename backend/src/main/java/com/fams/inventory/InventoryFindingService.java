package com.fams.inventory;

import com.fams.asset.AssetInventoryReadPort;
import com.fams.asset.AssetInventoryReviewPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.inbound.InventoryInboundPort;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finding closure follows authoritative cards and actual inbound acceptance, never free-text
 * progress.
 */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = InventoryReviewConflict.class)
public class InventoryFindingService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetInventoryReviewPort assets;
    private final AssetInventoryReadPort cards;
    private final InventoryInboundPort inbounds;
    private final Clock clock;

    public InventoryFindingService(
            JdbcTemplate db,
            ObjectMapper json,
            RequisitionActorContext actors,
            AssetInventoryReviewPort assets,
            AssetInventoryReadPort cards,
            InventoryInboundPort inbounds,
            Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.cards = cards;
        this.inbounds = inbounds;
        this.clock = clock;
    }

    public Map<String, Object> targets(
            Actor actor, long taskId, String assetCode, String receiptNumber) {
        admin(actor);
        task(taskId);
        if (receiptNumber != null && !receiptNumber.isBlank()) {
            var row = inbounds.find(actor, receiptNumber.strip(), assetCode.strip());
            return Map.of(
                    "items",
                    row == null
                            ? List.of()
                            : List.of(
                                    Map.of(
                                            "kind",
                                            "FORMAL_INBOUND",
                                            "assetId",
                                            row.assetId(),
                                            "assetCode",
                                            row.assetCode(),
                                            "ownerVersion",
                                            row.version(),
                                            "inboundReceiptId",
                                            row.receiptId(),
                                            "receiptNumber",
                                            receiptNumber.strip(),
                                            "status",
                                            row.status())));
        }
        var rows = cards.identify("CODE", assetCode.strip());
        if (rows.isEmpty()) {
            return Map.of("items", List.of());
        }
        String id = rows.getFirst().get("id").toString();
        String snapshot = cards.currentSnapshot(Long.parseLong(id));
        if (snapshot == null) {
            return Map.of("items", List.of());
        }
        var row = json.readTree(snapshot);
        return Map.of(
                "items",
                List.of(
                        Map.of(
                                "kind",
                                "DUPLICATE_ASSET",
                                "assetId",
                                id,
                                "assetCode",
                                row.path("assetCode").asText(),
                                "assetName",
                                row.path("name").asText(),
                                "ownerVersion",
                                row.path("version").asLong())));
    }

    public Map<String, Object> detail(Actor actor, long taskId, long observationId) {
        admin(actor);
        var task = task(taskId);
        var observation = observation(taskId, observationId);
        var subject =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='FINDING'",
                        observationId);
        var history =
                db.queryForList(
                        "SELECT d.*,CAST(l.asset_id AS CHAR) AS assetId, CAST(l.inbound_receipt_id"
                            + " AS CHAR) AS inboundReceiptId,l.link_kind,l.owner_version FROM"
                            + " fams_inventory_review_decision d LEFT JOIN"
                            + " fams_inventory_finding_link l ON l.observation_id=d.observation_id"
                            + " AND l.dimension=d.dimension AND l.decision_version=d.after_version"
                            + " WHERE d.observation_id=? AND d.dimension='FINDING' ORDER BY"
                            + " d.after_version DESC LIMIT 100",
                        observationId);
        return Map.of(
                "timeSubject",
                db
                        .queryForList(
                                "SELECT * FROM fams_inventory_review_subject WHERE observation_id=?"
                                        + " AND dimension='TIME'",
                                observationId)
                        .stream()
                        .map(this::wire)
                        .toList(),
                "recollections",
                db
                        .queryForList(
                                "SELECT d.*,CAST(l.counter_observation_id AS CHAR) AS"
                                    + " counterObservationId,l.counter_observed_at FROM"
                                    + " fams_inventory_finding_recollection l JOIN"
                                    + " fams_inventory_review_decision d ON"
                                    + " d.observation_id=l.observation_id AND"
                                    + " d.dimension=l.dimension AND"
                                    + " d.after_version=l.decision_version WHERE l.observation_id=?"
                                    + " ORDER BY l.decision_version DESC LIMIT 100",
                                observationId)
                        .stream()
                        .map(this::wire)
                        .toList(),
                "taskVersion",
                task.get("version"),
                "observation",
                wire(observation),
                "subject",
                subject.isEmpty() ? Map.of() : wire(subject.getFirst()),
                "history",
                history.stream().map(this::wire).toList(),
                "historyTotal",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_inventory_review_decision WHERE"
                                + " observation_id=? AND dimension='FINDING'",
                        Long.class,
                        observationId));
    }

    public Map<String, Object> decide(
            Actor actor,
            long taskId,
            long observationId,
            long version,
            UUID key,
            InventoryFindingInput.Decision input) {
        var current = admin(actor);
        var task = task(taskId);
        observation(taskId, observationId);
        String operation = "FINDING:" + observationId;
        String hash = hash(version, input);
        var prior =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                        taskId,
                        current.id(),
                        operation,
                        key.toString());
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
            }
            return json.readValue(
                    prior.getFirst().get("response").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        var subjects =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='FINDING' FOR UPDATE",
                        observationId);
        if (subjects.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_PREPARATION_REQUIRED");
        }
        var subject = subjects.getFirst();
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", subject);
        }
        if (number(subject, "version") != input.subjectVersion()
                || List.of("CLOSED", "TRANSFERRED").contains(subject.get("subject_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", subject);
        }
        var previous =
                db.queryForList(
                        "SELECT * FROM fams_inventory_finding_link WHERE observation_id=? ORDER BY"
                                + " decision_version DESC LIMIT 1 FOR UPDATE",
                        observationId);
        if ("PROCESSING".equals(subject.get("subject_status"))
                && !previous.isEmpty()
                && !"REFRESH_INBOUND".equals(input.method())) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_PROGRESS_REQUIRED");
        }
        String status, kind = null;
        Long asset = input.assetId(),
                receipt = input.inboundReceiptId(),
                ownerVersion = input.ownerVersion();
        long assetVersion = 0;
        switch (input.method()) {
            case "NON_ENTERPRISE" -> {
                if (asset != null || receipt != null || ownerVersion != null) {
                    throw new InventoryFailure(400, "INVENTORY_FINDING_TARGET_INVALID");
                }
                status = "CLOSED";
            }
            case "DUPLICATE_ASSET" -> {
                if (asset == null || receipt != null || ownerVersion == null) {
                    throw new InventoryFailure(400, "INVENTORY_FINDING_TARGET_INVALID");
                }
                if (!assets.matchesVersion(asset, ownerVersion)
                        || cards.currentSnapshot(asset) == null) {
                    throw new InventoryFailure(409, "INVENTORY_REVIEW_ASSET_CHANGED");
                }
                assetVersion = ownerVersion;
                kind = "DUPLICATE_ASSET";
                status = "CLOSED";
            }
            case "LINK_INBOUND", "REFRESH_INBOUND" -> {
                if (asset == null || receipt == null || ownerVersion == null) {
                    throw new InventoryFailure(400, "INVENTORY_FINDING_TARGET_INVALID");
                }
                if ("REFRESH_INBOUND".equals(input.method())
                        && (previous.isEmpty()
                                || !"FORMAL_INBOUND".equals(previous.getFirst().get("link_kind"))
                                || number(previous.getFirst(), "asset_id") != asset
                                || number(previous.getFirst(), "inbound_receipt_id") != receipt)) {
                    throw new InventoryFailure(409, "INVENTORY_FINDING_LINK_REQUIRED");
                }
                var context = inbounds.context(actor, receipt, asset);
                if (context == null || context.version() != ownerVersion) {
                    throw new InventoryFailure(409, "INVENTORY_FINDING_OWNER_CHANGED");
                }
                if (!db.queryForList(
                                "SELECT observation_id FROM fams_inventory_finding_link WHERE"
                                        + " asset_id=? AND observation_id<>? LIMIT 1 FOR UPDATE",
                                asset,
                                observationId)
                        .isEmpty()) {
                    throw new InventoryFailure(409, "INVENTORY_FINDING_ALREADY_LINKED");
                }
                if (List.of("CANCELLED", "REJECTED").contains(context.status())) {
                    if (!"REFRESH_INBOUND".equals(input.method())) {
                        throw new InventoryFailure(409, "INVENTORY_FINDING_OWNER_CHANGED");
                    }
                    status = "OPEN";
                } else if (context.accepted()
                        && "ACCEPTED".equals(context.itemStatus())
                        && "ACTIVE".equals(context.cardStage())) {
                    status = "CLOSED";
                } else {
                    status = "PROCESSING";
                }
                assetVersion = context.assetVersion();
                kind = "FORMAL_INBOUND";
            }
            default -> throw new InventoryFailure(400, "INVENTORY_REVIEW_METHOD_INVALID");
        }
        long after = input.subjectVersion() + 1;
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "UPDATE fams_inventory_review_subject SET subject_status=?,method=?,reason=?,"
                    + " evidence=?,reviewer_account_id=?,reviewer_username=?,reviewed_at=?,version=?"
                    + " WHERE observation_id=? AND dimension='FINDING'",
                status,
                input.method(),
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now,
                after,
                observationId);
        db.update(
                "INSERT INTO fams_inventory_review_decision(task_id,observation_id,dimension,"
                        + " before_version,after_version,method,subject_status,reason,evidence,"
                        + " reviewed_asset_version,actor_account_id,actor_username,occurred_at)"
                        + " VALUES (?,?,'FINDING',?,?,?,?,?,?,?,?,?,?)",
                taskId,
                observationId,
                input.subjectVersion(),
                after,
                input.method(),
                status,
                input.reason().strip(),
                input.evidence().strip(),
                assetVersion,
                current.id(),
                current.username(),
                now);
        if (kind != null) {
            db.update(
                    "INSERT INTO fams_inventory_finding_link(observation_id,dimension,"
                        + " decision_version,task_id,link_kind,asset_id,inbound_receipt_id,owner_version,"
                        + " created_at) VALUES (?,'FINDING',?,?,?,?,?,?,?)",
                    observationId,
                    after,
                    taskId,
                    kind,
                    asset,
                    receipt,
                    ownerVersion,
                    now);
        }
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now,
                taskId);
        var result = new LinkedHashMap<String, Object>();
        result.put("taskId", Long.toString(taskId));
        result.put("observationId", Long.toString(observationId));
        result.put("version", version + 1);
        result.put("subjectVersion", after);
        result.put("status", status);
        audit(actor, current, task, "INVENTORY_FINDING_DECIDED", result);
        db.update(
                "INSERT INTO fams_inventory_review_receipt(task_id,actor_account_id,operation,"
                    + " request_id,payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                taskId,
                current.id(),
                operation,
                key.toString(),
                hash,
                json.writeValueAsString(result),
                now);
        return result;
    }

    public Map<String, Object> recollectionTargets(
            Actor actor, long taskId, long observationId, int page, int size) {
        admin(actor);
        task(taskId);
        var original = observation(taskId, observationId);
        if (!"TIME_REVIEW".equals(original.get("outcome"))) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_TIME_REQUIRED");
        }
        String where =
                " FROM fams_inventory_observation WHERE task_id=? AND item_id IS NULL AND id>? AND"
                    + " outcome='FINDING_REVIEW' AND calibrated_observed_at IS NOT NULL AND"
                    + " calibrated_persisted_at IS NOT NULL AND calibrated_observed_at>=?";
        return Map.of(
                "items",
                db
                        .queryForList(
                                "SELECT *" + where + " ORDER BY id LIMIT ? OFFSET ? FOR SHARE",
                                taskId,
                                observationId,
                                original.get("received_at"),
                                size,
                                ((long) page - 1) * size)
                        .stream()
                        .map(this::wire)
                        .toList(),
                "total",
                java.util.Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COUNT(*)" + where,
                                Long.class,
                                taskId,
                                observationId,
                                original.get("received_at"))),
                "page",
                page,
                "pageSize",
                size);
    }

    /** Recollection resolves uncertainty without assigning a new timestamp to the original fact. */
    public Map<String, Object> recollect(
            Actor actor,
            long taskId,
            long observationId,
            long version,
            UUID key,
            InventoryFindingInput.Recollection input) {
        var current = admin(actor);
        var task = task(taskId);
        var observation = observation(taskId, observationId);
        String operation = "FINDING_TIME:" + observationId;
        String fingerprint = hash(version, input);
        var prior =
                db.queryForList(
                        "SELECT payload_sha256,CAST(response_json AS CHAR) AS response FROM"
                                + " fams_inventory_review_receipt WHERE task_id=? AND"
                                + " actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                        taskId,
                        current.id(),
                        operation,
                        key.toString());
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.getFirst().get("payload_sha256"))) {
                reject(actor, current, task, "INVENTORY_REVIEW_IDEMPOTENCY_CONFLICT", Map.of());
            }
            return json.readValue(
                    prior.getFirst().get("response").toString(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        if (!"TIME_REVIEW".equals(observation.get("outcome"))) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_TIME_REQUIRED");
        }
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_review_subject WHERE"
                                + " observation_id=? AND dimension='TIME' FOR UPDATE",
                        observationId);
        if (rows.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_PREPARATION_REQUIRED");
        }
        var subject = rows.getFirst();
        if (number(task, "version") != version
                || !List.of("IN_PROGRESS", "WAITING_REVIEW").contains(task.get("task_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_TASK_CHANGED", subject);
        }
        if (number(subject, "version") != input.subjectVersion()
                || !List.of("OPEN", "PROCESSING").contains(subject.get("subject_status"))) {
            reject(actor, current, task, "INVENTORY_REVIEW_VERSION_CONFLICT", subject);
        }
        if (input.counterObservationId() == observationId) {
            throw new InventoryFailure(400, "INVENTORY_FINDING_RECOLLECTION_INVALID");
        }
        var counters =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? AND"
                                + " item_id IS NULL AND outcome='FINDING_REVIEW' FOR SHARE",
                        taskId,
                        input.counterObservationId());
        if (counters.isEmpty()) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_RECOLLECTION_INVALID");
        }
        var counter = counters.getFirst();
        if (counter.get("calibrated_observed_at") == null
                || counter.get("calibrated_persisted_at") == null) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        var observed = instant(counter.get("calibrated_observed_at"));
        var persisted = instant(counter.get("calibrated_persisted_at"));
        var received = instant(counter.get("received_at"));
        if (observed.isBefore(instant(observation.get("received_at")))) {
            throw new InventoryFailure(409, "INVENTORY_FINDING_RECOLLECTION_INVALID");
        }
        if (observed.isBefore(instant(task.get("published_at")))
                || observed.isAfter(persisted.plusSeconds(2))
                || persisted.isAfter(received.plusSeconds(2))
                || !received.isBefore(persisted.plus(java.time.Duration.ofHours(72)))) {
            throw new InventoryFailure(409, "INVENTORY_REVIEW_RECOLLECTION_REQUIRED");
        }
        long after = input.subjectVersion() + 1;
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "UPDATE fams_inventory_review_subject SET"
                    + " subject_status='CLOSED',method='RECOLLECTED',"
                    + " reason=?,evidence=?,reviewer_account_id=?,reviewer_username=?,reviewed_at=?,version=?"
                    + " WHERE observation_id=? AND dimension='TIME'",
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now,
                after,
                observationId);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_decision(task_id,observation_id,dimension,before_version,"
                    + "after_version,method,subject_status,reason,evidence,reviewed_asset_version,actor_account_id,actor_username,occurred_at)"
                    + " VALUES (?,?,'TIME',?,?,'RECOLLECTED','CLOSED',?,?,0,?,?,?)",
                taskId,
                observationId,
                input.subjectVersion(),
                after,
                input.reason().strip(),
                input.evidence().strip(),
                current.id(),
                current.username(),
                now);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_finding_recollection(observation_id,dimension,decision_version,task_id,counter_observation_id,counter_observed_at,created_at)"
                    + " VALUES (?,'TIME',?,?,?,?,?)",
                observationId,
                after,
                taskId,
                input.counterObservationId(),
                observed,
                now);
        db.update(
                "UPDATE fams_inventory_task SET version=version+1,updated_at=? WHERE id=?",
                now,
                taskId);
        var result =
                Map.<String, Object>of(
                        "taskId",
                        Long.toString(taskId),
                        "observationId",
                        Long.toString(observationId),
                        "version",
                        version + 1,
                        "subjectVersion",
                        after,
                        "status",
                        "CLOSED",
                        "method",
                        "RECOLLECTED",
                        "counterObservationId",
                        Long.toString(input.counterObservationId()),
                        "originalTimeConfidence",
                        "UNVERIFIED");
        audit(actor, current, task, "INVENTORY_FINDING_TIME_RECOLLECTED", result);
        db.update(
                "INSERT INTO"
                    + " fams_inventory_review_receipt(task_id,actor_account_id,operation,request_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                taskId,
                current.id(),
                operation,
                key.toString(),
                fingerprint,
                json.writeValueAsString(result),
                now);
        return result;
    }

    private Instant instant(Object value) {
        return value instanceof java.sql.Timestamp time
                ? time.toInstant()
                : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) {
            throw new InventoryFailure(403, "INVENTORY_REVIEW_ADMIN_REQUIRED");
        }
        return current;
    }

    private Map<String, Object> task(long id) {
        var rows = db.queryForList("SELECT * FROM fams_inventory_task WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()
                || List.of("DRAFT", "DELETED").contains(rows.getFirst().get("task_status"))) {
            throw new InventoryFailure(404, "INVENTORY_REVIEW_TASK_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private Map<String, Object> observation(long task, long id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_inventory_observation WHERE task_id=? AND id=? AND"
                            + " item_id IS NULL AND outcome IN ('FINDING_REVIEW','TIME_REVIEW') FOR"
                            + " SHARE",
                        task,
                        id);
        if (rows.isEmpty()) {
            throw new InventoryFailure(404, "INVENTORY_FINDING_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void reject(
            Actor actor,
            Current current,
            Map<String, Object> task,
            String code,
            Map<String, Object> subject) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("taskVersion", task.get("version").toString());
        fields.put(
                "result",
                subject.getOrDefault("subject_status", task.get("task_status")).toString());
        if (subject.get("reviewer_username") != null) {
            fields.put("processedBy", subject.get("reviewer_username").toString());
            fields.put("processedAt", wire(subject).get("reviewed_at").toString());
        }
        fields.put(
                "readOnlyUrl", InventoryReviewLinks.task(actor, number(task, "id")) + "/findings");
        audit(
                actor,
                current,
                task,
                "INVENTORY_FINDING_CONFLICT_REJECTED",
                Map.of("code", code, "fields", fields));
        throw new InventoryReviewConflict(code, fields);
    }

    private void audit(
            Actor actor, Current current, Map<String, Object> task, String action, Object detail) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String body = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO fams_inventory_task_event(task_id,actor_account_id,action,detail_json,"
                        + " occurred_at) VALUES (?,?,?,?,?)",
                task.get("id"),
                current.id(),
                action,
                body,
                now);
        db.update(
                "INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                    + " resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,'ASSET_ADMIN',?,'INVENTORY_TASK',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                action,
                task.get("id"),
                task.get("version"),
                db.queryForObject(
                        "SELECT version FROM fams_inventory_task WHERE id=?",
                        Long.class,
                        task.get("id")),
                now,
                body);
    }

    private String hash(long version, Object input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (version + "\n" + json.writeValueAsString(input))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Map<String, Object> wire(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach(
                (key, value) ->
                        result.put(
                                key,
                                value != null
                                                && (key.equals("facts_json")
                                                        || key.equals("receipt_json"))
                                        ? json.readValue(value.toString(), Object.class)
                                        : value instanceof java.time.LocalDateTime time
                                                ? time.toInstant(java.time.ZoneOffset.UTC)
                                                        .toString()
                                                : value instanceof java.sql.Timestamp time
                                                        ? time.toInstant().toString()
                                                        : value instanceof Number
                                                                        && (key.equals("id")
                                                                                || key.endsWith(
                                                                                        "_id"))
                                                                ? value.toString()
                                                                : value));
        return result;
    }
}
