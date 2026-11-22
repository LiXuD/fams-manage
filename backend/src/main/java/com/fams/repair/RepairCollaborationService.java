package com.fams.repair;

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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An explicit invitation opens minimal repair evidence, never staff or warehouse authority. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = RepairConflict.class)
public class RepairCollaborationService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final RepairService repairs;
    private final ObjectProvider<AttachmentEvidencePort> evidence;
    private final ObjectProvider<AttachmentRegistry> attachments;

    public RepairCollaborationService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            RepairService repairs,
            ObjectProvider<AttachmentEvidencePort> evidence,
            ObjectProvider<AttachmentRegistry> attachments) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.repairs = repairs;
        this.evidence = evidence;
        this.attachments = attachments;
    }

    public RepairViews.CollaborationBoard staffBoard(Actor actor, long repair) {
        var current = actors.revalidateLocked(actor);
        repairs.getDetail(actor, repair);
        var row = header(repair);
        return new RepairViews.CollaborationBoard(
                Long.toString(repair),
                n(row, "version"),
                mayInvite(current, row),
                RepairViews.collaborationInvitations(
                        db.queryForList(
                                "SELECT i.id,i.technician_account_id,a.username AS"
                                    + " technician_username,i.inviter_account_id,i.invitation_status,i.reason,i.version,i.invited_at,i.ended_at,i.end_reason"
                                    + " FROM fams_repair_invitation i JOIN fams_system_account a ON"
                                    + " a.id=i.technician_account_id WHERE i.repair_id=? ORDER BY"
                                    + " i.id DESC LIMIT 100",
                                repair)),
                count("SELECT COUNT(*) FROM fams_repair_invitation WHERE repair_id=?", repair),
                opinions(repair),
                count("SELECT COUNT(*) FROM fams_repair_opinion WHERE repair_id=?", repair));
    }

    public RepairViews.Page<RepairViews.CollaborationTechnician> technicians(
            Actor actor, long repair, int page, int size) {
        var current = actors.revalidateLocked(actor);
        var row = header(repair);
        requireInviter(current, row);
        String where =
                " FROM fams_system_account a JOIN fams_employee e ON e.id=a.employee_id JOIN"
                    + " fams_organization o ON o.id=e.organization_id WHERE a.status='ENABLED' AND"
                    + " a.initial_password_required=FALSE AND e.status='ACTIVE' AND"
                    + " o.status='ACTIVE' AND EXISTS(SELECT 1 FROM fams_account_role r WHERE"
                    + " r.account_id=a.id AND r.role_code='TECH_ASSISTANT')";
        var rows =
                db.queryForList(
                        "SELECT a.id,a.username,e.name" + where + " ORDER BY a.id LIMIT ? OFFSET ?",
                        size,
                        ((long) page - 1) * size);
        return new RepairViews.Page<>(
                RepairViews.collaborationTechnicians(rows),
                count("SELECT COUNT(*)" + where),
                page,
                size);
    }

    public RepairViews.CollaborationRequestReceipt invite(
            Actor actor,
            long repair,
            long version,
            UUID key,
            RepairCollaborationInput.Invitation input) {
        var current = actors.revalidateLocked(actor);
        var row = header(repair);
        requireInviter(current, row);
        String operation = "TECH_INVITE:" + repair, fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        current,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.CollaborationRequestReceipt.class);
        if (prior != null) {
            return prior;
        }
        version(actor, current, row, version);
        var targets =
                db.queryForList(
                        "SELECT a.id FROM fams_system_account a JOIN fams_employee e ON"
                                + " e.id=a.employee_id JOIN fams_organization o ON"
                                + " o.id=e.organization_id WHERE a.id=? AND a.status='ENABLED' AND"
                                + " a.initial_password_required=FALSE AND e.status='ACTIVE' AND"
                                + " o.status='ACTIVE' AND EXISTS(SELECT 1 FROM fams_account_role r"
                                + " WHERE r.account_id=a.id AND r.role_code='TECH_ASSISTANT') FOR"
                                + " SHARE",
                        input.technicianAccountId());
        if (targets.isEmpty()) {
            throw new RepairFailure(409, "REPAIR_TECHNICIAN_NOT_ELIGIBLE");
        }
        if (count(
                        "SELECT COUNT(*) FROM fams_repair_invitation WHERE repair_id=? AND"
                                + " technician_account_id=? AND invitation_status='ACTIVE'",
                        repair,
                        input.technicianAccountId())
                != 0) {
            reject(actor, current, row, "REPAIR_TECH_ALREADY_INVITED");
        }
        db.update(
                "INSERT INTO"
                    + " fams_repair_invitation(repair_id,technician_account_id,inviter_account_id,reason,invited_at)"
                    + " VALUES (?,?,?,?,?)",
                repair,
                input.technicianAccountId(),
                current.id(),
                input.reason().strip(),
                now());
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES ('TECH_ASSISTANT',?,'REPAIR_TECH',?,'OPINION','OPEN',?)",
                input.technicianAccountId(),
                id,
                now());
        advance(row);
        audit(
                actor,
                current,
                row,
                "REPAIR_TECH_INVITED",
                version,
                version + 1,
                Map.of(
                        "invitationId",
                        Long.toString(id),
                        "technicianAccountId",
                        Long.toString(input.technicianAccountId()),
                        "reason",
                        input.reason()),
                inviterRole(current, row));
        return remember(
                current,
                operation,
                key,
                fingerprint,
                repair,
                new RepairViews.CollaborationRequestReceipt(
                        Long.toString(repair), Long.toString(id), version + 1));
    }

    public RepairViews.CollaborationRequestReceipt revoke(
            Actor actor, long invitation, long version, UUID key, RepairInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        var pair = aggregate(invitation);
        var row = pair.repair();
        var invite = pair.invitation();
        requireInviter(current, row);
        String operation = "TECH_REVOKE:" + invitation,
                fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        current,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.CollaborationRequestReceipt.class);
        if (prior != null) {
            return prior;
        }
        version(actor, current, row, version);
        if (!"ACTIVE".equals(invite.get("invitation_status"))) {
            reject(actor, current, row, "REPAIR_INVITATION_CHANGED");
        }
        db.update(
                "UPDATE fams_repair_invitation SET"
                    + " invitation_status='REVOKED',ended_at=?,end_reason=?,version=version+1 WHERE"
                    + " id=?",
                now(),
                input.reason().strip(),
                invitation);
        closeTodo(invitation);
        advance(row);
        audit(
                actor,
                current,
                row,
                "REPAIR_TECH_REVOKED",
                version,
                version + 1,
                Map.of("invitationId", Long.toString(invitation), "reason", input.reason()),
                inviterRole(current, row));
        return remember(
                current,
                operation,
                key,
                fingerprint,
                n(row, "id"),
                new RepairViews.CollaborationRequestReceipt(
                        row.get("id").toString(), Long.toString(invitation), version + 1));
    }

    public RepairViews.Page<RepairViews.CollaborationBrief> mine(
            Actor actor, int page, int size) {
        var current = participant(actor);
        String where =
                " FROM fams_repair_invitation i JOIN fams_repair_request r ON r.id=i.repair_id"
                    + " WHERE i.technician_account_id=? AND i.invitation_status='ACTIVE' AND"
                    + " r.repair_status IN"
                    + " ('PENDING_ADMIN_DECISION','WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING')";
        var rows =
                db.queryForList(
                        "SELECT i.id,i.version,i.invited_at,r.id"
                            + " repair_id,r.fault,r.impact,r.repair_status,r.source_snapshot_json,r.occurred_at"
                                + where
                                + " ORDER BY i.id DESC LIMIT ? OFFSET ?",
                        current.id(),
                        size,
                        ((long) page - 1) * size);
        return new RepairViews.Page<>(
                rows.stream().map(this::minimal).toList(),
                count("SELECT COUNT(*)" + where, current.id()),
                page,
                size);
    }

    public RepairViews.CollaborationInvitationDetail get(Actor actor, long invitation) {
        var current = participant(actor);
        var pair = aggregate(invitation);
        requireParticipant(current, pair);
        var brief = minimalJoined(pair);
        long repair = n(pair.repair(), "id");
        var port = evidence.getIfAvailable();
        return new RepairViews.CollaborationInvitationDetail(
                brief.invitationId(),
                brief.repairId(),
                brief.version(),
                brief.invitedAt(),
                brief.asset(),
                brief.fault(),
                brief.impact(),
                brief.faultOccurredAt(),
                brief.status(),
                opinions(repair),
                count("SELECT COUNT(*) FROM fams_repair_opinion WHERE repair_id=?", repair),
                port != null,
                port == null ? List.of() : photoProjection(port.list("REPAIR", repair)),
                port == null ? List.of() : photoProjection(port.list("REPAIR_TECH", invitation)));
    }

    public RepairViews.CollaborationOpinionReceipt opinion(
            Actor actor,
            long invitation,
            long version,
            UUID key,
            RepairCollaborationInput.Opinion input) {
        var current = participant(actor);
        var pair = aggregate(invitation);
        requireParticipant(current, pair);
        var row = pair.repair();
        var invite = pair.invitation();
        String operation = "TECH_OPINION:" + invitation,
                fingerprint = hash(operation, version, input);
        var prior =
                replay(
                        current,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.CollaborationOpinionReceipt.class);
        if (prior != null) {
            return prior;
        }
        if (n(invite, "version") != version) {
            reject(actor, current, row, "REPAIR_INVITATION_CHANGED");
        }
        if (!List.of("RESTORED", "FACTORY_SUGGESTED", "SCRAP_SUGGESTED", "NEEDS_WORK")
                        .contains(input.result())
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))
                || input.occurredAt().isAfter(clock.instant().plusSeconds(2))
                || input.attachmentIds().stream().distinct().count()
                        != input.attachmentIds().size()) {
            throw new RepairFailure(400, "REPAIR_OPINION_INVALID");
        }
        var port = evidence.getIfAvailable();
        for (var object : input.attachmentIds()) {
            if (port == null || !port.clean("REPAIR_TECH", invitation, current.id(), object)) {
                throw new RepairFailure(409, "REPAIR_OPINION_ATTACHMENT_INVALID");
            }
        }
        db.update(
                "INSERT INTO"
                    + " fams_repair_opinion(invitation_id,repair_id,before_version,judgement,method,opinion_result,occurred_at,attachment_ids_json,actor_account_id,actor_username,recorded_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                invitation,
                n(row, "id"),
                version,
                input.judgement().strip(),
                input.method().strip(),
                input.result(),
                input.occurredAt(),
                json.writeValueAsString(input.attachmentIds()),
                current.id(),
                current.username(),
                now());
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update("UPDATE fams_repair_invitation SET version=version+1 WHERE id=?", invitation);
        advance(row);
        closeTodo(invitation);
        for (long recipient :
                java.util.stream.LongStream.of(
                                n(invite, "inviter_account_id"),
                                row.get("processor_account_id") == null
                                        ? n(invite, "inviter_account_id")
                                        : n(row, "processor_account_id"))
                        .distinct()
                        .toArray()) {
            db.update(
                    "INSERT INTO"
                        + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES"
                        + " ('REPAIR_REVIEW',?,'REPAIR_OPINION',?,'OPINION_RECORDED','OPEN',?)",
                    recipient,
                    id,
                    now());
        }
        audit(
                actor,
                current,
                row,
                "REPAIR_TECH_OPINION_ADDED",
                n(row, "version"),
                n(row, "version") + 1,
                Map.of(
                        "invitationId",
                        Long.toString(invitation),
                        "opinionId",
                        Long.toString(id),
                        "result",
                        input.result()),
                Role.TECH_ASSISTANT.name());
        return remember(
                current,
                operation,
                key,
                fingerprint,
                n(row, "id"),
                new RepairViews.CollaborationOpinionReceipt(
                        row.get("id").toString(),
                        Long.toString(invitation),
                        Long.toString(id),
                        version + 1));
    }

    public void uploader(Actor actor, long invitation) {
        var current = participant(actor);
        requireParticipant(current, aggregate(invitation));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RepairConflict.class)
    public RepairViews.CollaborationAttachmentReceipt registerAttachment(
            Actor actor,
            long invitation,
            long version,
            UUID key,
            String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        var current = participant(actor);
        var pair = aggregate(invitation);
        requireParticipant(current, pair);
        var row = pair.repair();
        String operation = "TECH_ATTACHMENT:" + invitation,
                fingerprint =
                        hash(
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
        var prior =
                replay(
                        current,
                        operation,
                        key,
                        fingerprint,
                        RepairViews.CollaborationAttachmentReceipt.class);
        if (prior != null) {
            return prior;
        }
        if (n(pair.invitation(), "version") != version) {
            reject(actor, current, row, "REPAIR_INVITATION_CHANGED");
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new RepairFailure(
                    attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE ? 503 : 422,
                    "REPAIR_TECH_ATTACHMENT_NOT_CLEAN");
        }
        var registry = attachments.getIfAvailable();
        if (registry == null) {
            throw new RepairFailure(503, "REPAIR_PHOTOS_UNAVAILABLE");
        }
        registry.register("REPAIR_TECH", invitation, version, current.id(), filename, attachment);
        db.update(
                "INSERT INTO"
                    + " fams_repair_collaboration_attachment(object_id,invitation_id,actor_account_id,content_sha256,recorded_at)"
                    + " VALUES (?,?,?,?,?)",
                attachment.file().id().toString(),
                invitation,
                current.id(),
                attachment.file().sha256(),
                now());
        db.update("UPDATE fams_repair_invitation SET version=version+1 WHERE id=?", invitation);
        advance(row);
        audit(
                actor,
                current,
                row,
                "REPAIR_TECH_ATTACHMENT_ADDED",
                n(row, "version"),
                n(row, "version") + 1,
                Map.of(
                        "invitationId",
                        Long.toString(invitation),
                        "objectId",
                        attachment.file().id().toString()),
                Role.TECH_ASSISTANT.name());
        return remember(
                current,
                operation,
                key,
                fingerprint,
                n(row, "id"),
                new RepairViews.CollaborationAttachmentReceipt(
                        row.get("id").toString(),
                        Long.toString(invitation),
                        attachment.file().id().toString(),
                        version + 1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long photoScope(Actor actor, long invitation, boolean fault) {
        var current = participant(actor);
        var pair = aggregate(invitation);
        requireParticipant(current, pair);
        return fault ? n(pair.repair(), "id") : invitation;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long staffAttachmentScope(Actor actor, long invitation, UUID object) {
        actors.revalidateLocked(actor);
        var pair = aggregate(invitation);
        repairs.getDetail(actor, n(pair.repair(), "id"));
        if (count(
                        "SELECT COUNT(*) FROM fams_repair_opinion WHERE invitation_id=? AND"
                            + " JSON_CONTAINS(attachment_ids_json,JSON_QUOTE(?))",
                        invitation,
                        object.toString())
                == 0) {
            throw new RepairFailure(404, "REPAIR_EVIDENCE_NOT_FOUND");
        }
        return invitation;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long opinionAttachmentScope(Actor actor, long invitation, UUID object) {
        var current = participant(actor);
        var pair = aggregate(invitation);
        requireParticipant(current, pair);
        var rows =
                db.queryForList(
                        "SELECT a.invitation_id FROM fams_repair_collaboration_attachment a JOIN"
                                + " fams_repair_invitation i ON i.id=a.invitation_id WHERE"
                                + " a.object_id=? AND i.repair_id=? AND EXISTS(SELECT 1 FROM"
                                + " fams_repair_opinion o WHERE o.invitation_id=i.id AND"
                                + " JSON_CONTAINS(o.attachment_ids_json,JSON_QUOTE(?))) FOR SHARE",
                        object.toString(),
                        n(pair.repair(), "id"),
                        object.toString());
        if (rows.size() != 1) {
            throw new RepairFailure(404, "REPAIR_EVIDENCE_NOT_FOUND");
        }
        return n(rows.getFirst(), "invitation_id");
    }

    private Current participant(Actor actor) {
        var current = actors.revalidateRepairParticipantLocked(actor);
        if (!current.has(Role.TECH_ASSISTANT)) {
            throw new RepairFailure(403, "REPAIR_TECHNICIAN_REQUIRED");
        }
        return current;
    }

    private void requireParticipant(Current current, Pair pair) {
        if (n(pair.invitation(), "technician_account_id") != current.id()
                || !"ACTIVE".equals(pair.invitation().get("invitation_status"))
                || !List.of(
                                "PENDING_ADMIN_DECISION",
                                "WAITING_WAREHOUSE_CLAIM",
                                "WAREHOUSE_PROCESSING")
                        .contains(pair.repair().get("repair_status"))) {
            throw new RepairFailure(403, "REPAIR_INVITATION_NOT_ACTIVE");
        }
    }

    private boolean mayInvite(Current current, Map<String, Object> row) {
        return "PENDING_ADMIN_DECISION".equals(row.get("repair_status"))
                        && current.has(Role.ASSET_ADMIN)
                || "WAREHOUSE_PROCESSING".equals(row.get("repair_status"))
                        && current.has(Role.WAREHOUSE_OPERATOR)
                        && Objects.equals(current.id(), row.get("processor_account_id"));
    }

    private void requireInviter(Current current, Map<String, Object> row) {
        if (!mayInvite(current, row)) {
            throw new RepairFailure(403, "REPAIR_INVITER_REQUIRED");
        }
    }

    private String inviterRole(Current current, Map<String, Object> row) {
        return "PENDING_ADMIN_DECISION".equals(row.get("repair_status"))
                ? Role.ASSET_ADMIN.name()
                : Role.WAREHOUSE_OPERATOR.name();
    }

    private record Pair(Map<String, Object> repair, Map<String, Object> invitation) {}

    private Pair aggregate(long invitation) {
        var reference =
                db.queryForList(
                        "SELECT repair_id FROM fams_repair_invitation WHERE id=?", invitation);
        if (reference.isEmpty()) {
            throw new RepairFailure(404, "REPAIR_INVITATION_NOT_FOUND");
        }
        var row = header(n(reference.getFirst(), "repair_id"));
        var invite =
                db.queryForMap(
                        "SELECT * FROM fams_repair_invitation WHERE id=? FOR UPDATE", invitation);
        return new Pair(row, invite);
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_repair_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new RepairFailure(404, "REPAIR_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private RepairViews.CollaborationBrief minimalJoined(Pair pair) {
        var row = new LinkedHashMap<>(pair.repair());
        row.put("repair_id", row.get("id"));
        row.put("id", pair.invitation().get("id"));
        row.put("version", pair.invitation().get("version"));
        row.put("invited_at", pair.invitation().get("invited_at"));
        return minimal(row);
    }

    private RepairViews.CollaborationBrief minimal(Map<String, Object> row) {
        var source = json.readTree(row.get("source_snapshot_json").toString());
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

    private List<RepairViews.CollaborationOpinionRow> opinions(long repair) {
        return RepairViews.collaborationOpinions(
                json,
                db.queryForList(
                        "SELECT"
                            + " id,invitation_id,judgement,method,opinion_result,occurred_at,attachment_ids_json,actor_username,recorded_at"
                            + " FROM fams_repair_opinion WHERE repair_id=? ORDER BY id DESC LIMIT"
                            + " 100",
                        repair));
    }

    /**
     * Frozen five-key projection of the attachment list rows (faultPhotos/attachments):
     * businessVersion and createdAt never reach this wire.
     */
    public record Photo(
            String objectId, String filename, String contentType, long sizeBytes, String scanStatus) {
    }

    public static List<Photo> photoProjection(List<AttachmentEvidencePort.Metadata> photos) {
        return photos.stream()
                .map(
                        row ->
                                new Photo(
                                        row.objectId(),
                                        row.filename(),
                                        row.contentType(),
                                        row.sizeBytes(),
                                        row.scanStatus()))
                .toList();
    }

    private void advance(Map<String, Object> row) {
        db.update(
                "UPDATE fams_repair_request SET version=version+1,updated_at=? WHERE id=?",
                now(),
                row.get("id"));
    }

    private void closeTodo(long invitation) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='REPAIR_TECH' AND business_id=? AND closed_at IS NULL",
                now(),
                invitation);
    }

    private void version(Actor actor, Current current, Map<String, Object> row, long expected) {
        if (n(row, "version") != expected) {
            reject(actor, current, row, "REPAIR_VERSION_CONFLICT");
        }
    }

    private void reject(Actor actor, Current current, Map<String, Object> row, String code) {
        var fields =
                Map.of(
                        "version",
                        row.get("version").toString(),
                        "result",
                        row.get("repair_status").toString(),
                        "readOnlyUrl",
                        "/repairs/" + row.get("id"));
        audit(
                actor,
                current,
                row,
                "REPAIR_CONFLICT_REJECTED",
                n(row, "version"),
                n(row, "version"),
                Map.of("code", code),
                current.has(Role.TECH_ASSISTANT)
                        ? Role.TECH_ASSISTANT.name()
                        : inviterRole(current, row));
        throw new RepairConflict(code, fields);
    }

    private <T> T replay(
            Current current, String operation, UUID key, String fingerprint, Class<T> type) {
        var rows =
                db.queryForList(
                        "SELECT payload_sha256,response_json FROM fams_repair_receipt WHERE"
                                + " actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        if (!fingerprint.equals(rows.getFirst().get("payload_sha256"))) {
            throw new RepairFailure(409, "REPAIR_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(rows.getFirst().get("response_json").toString(), type);
    }

    private <T> T remember(
            Current current,
            String operation,
            UUID key,
            String fingerprint,
            long repair,
            T result) {
        db.update(
                "INSERT INTO"
                    + " fams_repair_receipt(actor_account_id,operation,request_id,repair_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                current.id(),
                operation,
                key.toString(),
                repair,
                fingerprint,
                json.writeValueAsString(result),
                now());
        return result;
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            long before,
            long after,
            Object detail,
            String role) {
        String body = json.writeValueAsString(detail);
        var time = now();
        db.update(
                "INSERT INTO"
                    + " fams_repair_event(repair_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                action,
                before,
                after,
                current.id(),
                current.username(),
                role,
                body,
                time);
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'REPAIR',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role,
                action,
                row.get("id"),
                before,
                after,
                time,
                body);
    }

    private String hash(String operation, long version, Object body) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (operation
                                                            + "\n"
                                                            + version
                                                            + "\n"
                                                            + json.writeValueAsString(body))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(db.queryForObject(sql, Long.class, args));
    }
}
