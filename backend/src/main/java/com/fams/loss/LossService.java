package com.fams.loss;

import com.fams.asset.AssetLossPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.MalwareScanner;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Independent loss reporting. Approval authorizes verification; it never confirms a loss. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class LossService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetLossPort assets;
    private final ObjectProvider<AttachmentRegistry> attachments;
    private final ObjectProvider<AttachmentEvidencePort> evidence;

    public LossService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetLossPort assets,
            ObjectProvider<AttachmentRegistry> attachments,
            ObjectProvider<AttachmentEvidencePort> evidence) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.attachments = attachments;
        this.evidence = evidence;
    }

    public Map<String, Object> eligible(Actor actor, int page, int size) {
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    public Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_loss_report WHERE (? OR creator_account_id=? OR"
                    + " responsible_employee_id=? OR shared_responsible_employee_id=? OR (? AND"
                    + " using_organization_id=?) OR (? AND approval_snapshot_json IS NOT NULL))";
        Object[] scope = {
            current.has(Role.ASSET_ADMIN),
            current.id(),
            current.employeeId(),
            current.employeeId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER),
            current.organizationId(),
            current.has(Role.WAREHOUSE_OPERATOR)
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        return Map.of(
                "items",
                db
                        .queryForList(
                                "SELECT *" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(this::view)
                        .toList(),
                "total",
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        var result = new LinkedHashMap<>(view(row));
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='LOST_REPORT' AND business_id=? AND released_at IS"
                            + " NULL",
                        row.get("asset_id"),
                        id);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        var photos = evidence.getIfAvailable();
        result.put("photosAvailable", photos != null);
        result.put("photos", photos == null ? List.of() : wire(photos.list("LOST_REPORT", id)));
        result.put(
                "history",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_loss_event WHERE report_id=? ORDER BY id DESC"
                                    + " LIMIT 100",
                                id)));
        result.put(
                "historyTotal",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_event WHERE report_id=?", Long.class, id));
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("report_status"));
        result.put(
                "actions",
                Map.of(
                        "decide",
                        pending && current.has(Role.ASSET_ADMIN),
                        "cancel",
                        pending
                                && (current.has(Role.ASSET_ADMIN)
                                        || current.id() == number(row, "creator_account_id")),
                        "addPhoto",
                        pending
                                && photos != null
                                && current.id() == number(row, "creator_account_id"),
                        "claim",
                        "WAITING_WAREHOUSE_CLAIM".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR),
                        "search",
                        "VERIFYING".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id"))));
        return result;
    }

    public Map<String, Object> submit(Actor actor, UUID key, LossInput.Submission input) {
        var current = actors.revalidateLocked(actor);
        String hash = LossPayloads.fingerprint(json, "SUBMIT", 0, input);
        var prior = replay(current, "SUBMIT", key, hash);
        if (prior != null) {
            return prior;
        }
        validTime(input.lastSeenAt());
        String source = assets.submission(current, input.assetId(), input.assetVersion());
        var snapshot = json.readTree(source);
        db.update(
                "INSERT INTO"
                    + " fams_loss_report(asset_id,creator_account_id,using_organization_id,responsible_employee_id,shared_responsible_employee_id,asset_version,source_snapshot_json,last_seen_at,last_location,circumstances,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                input.assetId(),
                current.id(),
                reference(snapshot, "usingOrganizationId"),
                reference(snapshot, "responsibleEmployeeId"),
                reference(snapshot, "departmentResponsibleEmployeeId"),
                input.assetVersion(),
                source,
                input.lastSeenAt(),
                input.lastLocation().strip(),
                input.circumstances().strip(),
                now(),
                now());
        long id =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (?,'LOST_REPORT',?,?,?)",
                input.assetId(),
                id,
                id,
                now());
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, header(id), "LOSS_REPORTED", 0, input);
        return remember(current, "SUBMIT", key, hash, header(id));
    }

    public Map<String, Object> decide(
            Actor actor, long id, long version, UUID key, LossInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "DECIDE:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        if (!List.of("APPROVE", "REJECT").contains(input.result())) {
            throw new LossFailure(400, "LOSS_DECISION_INVALID");
        }
        boolean approve = "APPROVE".equals(input.result());
        var photos = evidence.getIfAvailable();
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("report", view(row));
        frozen.put("reason", input.reason());
        frozen.put("photos", photos == null ? List.of() : wire(photos.list("LOST_REPORT", id)));
        if (approve) {
            assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"));
            if (photos == null || photos.list("LOST_REPORT", id).isEmpty()) {
                throw new LossFailure(409, "LOSS_PHOTO_REQUIRED");
            }
        }
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                approve ? json.writeValueAsString(frozen) : null,
                now(),
                id);
        closeTodos(id);
        if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null);
        } else {
            release(row, "LOSS_REJECTED");
        }
        audit(actor, current, row, "LOSS_DECIDED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> cancel(
            Actor actor, long id, long version, UUID key, LossInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (current.id() != number(row, "creator_account_id") && !current.has(Role.ASSET_ADMIN)) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        String operation = "CANCEL:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        release(row, "LOSS_CANCELLED");
        closeTodos(id);
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
                now(),
                id);
        audit(actor, current, row, "LOSS_CANCELLED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> claim(Actor actor, long id, long version, UUID key) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "CLAIM:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, Map.of());
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "WAITING_WAREHOUSE_CLAIM");
        lock(row, null);
        assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"));
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='VERIFYING',processor_account_id=?,ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                id);
        closeTodos(id);
        todo(id, "VERIFY", "WAREHOUSE_OPERATOR", current.id());
        audit(
                actor,
                current,
                row,
                "LOSS_CLAIMED",
                version + 1,
                Map.of("processorAccountId", Long.toString(current.id())));
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> search(
            Actor actor, long id, long version, UUID key, LossInput.Search input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "SEARCH:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "VERIFYING");
        if (!java.util.Objects.equals(current.id(), row.get("processor_account_id"))
                || input.ownershipVersion() != number(row, "ownership_version")
                || input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        lock(row, input.lockVersion());
        validTime(input.searchedAt());
        if (input.searchedAt().isBefore(instant(row.get("last_seen_at")))
                || input.nextReviewDate().isBefore(today())) {
            throw new LossFailure(400, "LOSS_SEARCH_DATE_INVALID");
        }
        long assetVersion =
                assets.searching(
                        number(row, "asset_id"), input.assetVersion(), id, current.id(), now());
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='SEARCHING',asset_version=?,next_review_date=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                assetVersion,
                input.nextReviewDate(),
                now(),
                id);
        closeTodos(id);
        todo(id, "SEARCH", "WAREHOUSE_OPERATOR", current.id());
        audit(actor, current, row, "LOSS_SEARCH_STARTED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public void photoUploader(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        if (current.id() != number(header(id), "creator_account_id")) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void photoAccess(Actor actor, long id) {
        visible(actors.revalidateLocked(actor), header(id));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> registerPhoto(
            Actor actor,
            long id,
            long version,
            UUID key,
            String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (current.id() != number(row, "creator_account_id")) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        String operation = "PHOTO:" + id,
                hash =
                        LossPayloads.fingerprint(
                                json,
                                operation,
                                version,
                                Map.of(
                                        "filename",
                                        filename,
                                        "sha256",
                                        attachment.file().sha256(),
                                        "mime",
                                        attachment.format().mime(),
                                        "size",
                                        attachment.file().size()));
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        if (attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE) {
            throw new LossFailure(503, "LOSS_PHOTO_SCAN_UNAVAILABLE");
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new LossFailure(422, "LOSS_PHOTO_REJECTED");
        }
        if (!attachment.format().image()) {
            throw new LossFailure(400, "LOSS_PHOTO_IMAGE_REQUIRED");
        }
        var registry = attachments.getIfAvailable();
        if (registry == null) {
            throw new LossFailure(503, "LOSS_PHOTOS_UNAVAILABLE");
        }
        registry.register("LOST_REPORT", id, version, current.id(), filename, attachment);
        db.update(
                "UPDATE fams_loss_report SET version=version+1,updated_at=? WHERE id=?", now(), id);
        var result =
                Map.<String, Object>of(
                        "id",
                        Long.toString(id),
                        "objectId",
                        attachment.file().id().toString(),
                        "version",
                        version + 1);
        audit(
                actor,
                current,
                row,
                "LOSS_PHOTO_ADDED",
                version + 1,
                Map.of(
                        "objectId",
                        attachment.file().id().toString(),
                        "sha256",
                        attachment.file().sha256()));
        saveReceipt(current, operation, key, hash, id, result);
        return result;
    }

    private Map<String, Object> replay(Current current, String operation, UUID key, String hash) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_loss_receipt WHERE actor_account_id=? AND operation=?"
                            + " AND request_id=? FOR UPDATE",
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        visible(current, header(number(row, "report_id")));
        if (!hash.equals(row.get("payload_sha256"))) {
            throw new LossFailure(409, "LOSS_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(
                row.get("response_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> remember(
            Current current, String operation, UUID key, String hash, Map<String, Object> row) {
        var result = view(row);
        saveReceipt(current, operation, key, hash, number(row, "id"), result);
        return result;
    }

    private void saveReceipt(
            Current current,
            String operation,
            UUID key,
            String hash,
            long id,
            Map<String, Object> result) {
        db.update(
                "INSERT INTO"
                    + " fams_loss_receipt(actor_account_id,operation,request_id,report_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                current.id(),
                operation,
                key.toString(),
                id,
                hash,
                json.writeValueAsString(result),
                now());
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loss_report WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void visible(Current current, Map<String, Object> row) {
        if (!(current.has(Role.ASSET_ADMIN)
                || current.id() == number(row, "creator_account_id")
                || java.util.Objects.equals(
                        current.employeeId(), row.get("responsible_employee_id"))
                || java.util.Objects.equals(
                        current.employeeId(), row.get("shared_responsible_employee_id"))
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && java.util.Objects.equals(
                                current.organizationId(), row.get("using_organization_id"))
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && row.get("approval_snapshot_json") != null)) {
            throw new LossFailure(403, "LOSS_NOT_VISIBLE");
        }
    }

    private void require(Current current, Role role) {
        if (!current.has(role)) {
            throw new LossFailure(403, "LOSS_ROLE_REQUIRED");
        }
    }

    private void guard(Map<String, Object> row, long version, String status) {
        if (version != number(row, "version") || !status.equals(row.get("report_status"))) {
            throw new LossFailure(409, "LOSS_REPORT_CHANGED");
        }
    }

    private void lock(Map<String, Object> row, Long version) {
        var rows =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='LOST_REPORT' AND business_id=? AND released_at IS"
                            + " NULL FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"));
        if (rows.size() != 1
                || version != null
                        && version.longValue() != number(rows.getFirst(), "lock_version")) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void release(Map<String, Object> row, String reason) {
        if (db.update(
                        "UPDATE fams_asset_business_lock SET"
                            + " released_at=?,release_reason=?,lock_version=lock_version+1 WHERE"
                            + " asset_id=? AND business_type='LOST_REPORT' AND business_id=? AND"
                            + " released_at IS NULL",
                        now(),
                        reason,
                        row.get("asset_id"),
                        row.get("id"))
                != 1) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void todo(long id, String kind, String pool, Long owner) {
        db.update(
                "INSERT INTO"
                    + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'LOST_REPORT',?,?,'OPEN',?)",
                pool,
                owner,
                id,
                kind,
                now());
    }

    private void closeTodos(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                    + " business_type='LOST_REPORT' AND business_id=? AND closed_at IS NULL",
                now(),
                id);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            long after,
            Object detail) {
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        String body = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_loss_event(report_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                action,
                row.get("version"),
                after,
                current.id(),
                current.username(),
                role,
                body,
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'LOST_REPORT',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role,
                action,
                row.get("id"),
                row.get("version"),
                after,
                now(),
                body);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_id", "assetId"),
                                Map.entry("creator_account_id", "creatorAccountId"),
                                Map.entry("version", "version"),
                                Map.entry("asset_version", "assetVersion"),
                                Map.entry("report_status", "status"),
                                Map.entry("last_seen_at", "lastSeenAt"),
                                Map.entry("last_location", "lastLocation"),
                                Map.entry("circumstances", "circumstances"),
                                Map.entry("processor_account_id", "processorAccountId"),
                                Map.entry("ownership_version", "ownershipVersion"),
                                Map.entry("next_review_date", "nextReviewDate"))
                        .entrySet()) {
            var value = row.get(field.getKey());
            result.put(
                    field.getValue(),
                    field.getKey().equals("id") || field.getKey().endsWith("_id")
                            ? value == null ? null : value.toString()
                            : wire(value));
        }
        result.put("source", json.readTree(row.get("source_snapshot_json").toString()));
        return result;
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : wire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::wire).toList();
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

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        return ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private void validTime(Instant time) {
        if (!time.equals(time.truncatedTo(ChronoUnit.MILLIS))
                || time.isAfter(clock.instant().plusSeconds(2))) {
            throw new LossFailure(400, "LOSS_TIME_INVALID");
        }
    }

    private LocalDate today() {
        String snapshot =
                db.queryForObject(
                        "SELECT v.snapshot_json FROM fams_business_parameter_state s JOIN"
                            + " fams_business_parameter_version v ON v.id=s.current_version_id"
                            + " WHERE s.singleton_id=1 AND v.status='ACTIVE' FOR SHARE",
                        String.class);
        return LocalDate.now(
                clock.withZone(ZoneId.of(json.readTree(snapshot).path("timezone").asText())));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Long reference(tools.jackson.databind.JsonNode row, String key) {
        return row.path(key).isNull() ? null : row.path(key).asLong();
    }
}
