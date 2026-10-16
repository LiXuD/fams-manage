package com.fams.inventory;

import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.AttachmentStorage;
import com.fams.attachment.AttachmentWriter;
import com.fams.auth.InventoryIdentityPort;
import com.fams.auth.RequisitionActorContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Intake occurs outside transactions; task, identity and metadata register in the writer callback.
 */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class InventoryPhotoService {
    private final AttachmentWriter writer;
    private final AttachmentRegistry registry;
    private final AttachmentStorage storage;
    private final RequisitionActorContext actors;
    private final InventoryIdentityPort identities;
    private final JdbcTemplate db;
    private final Clock clock;
    private final ObjectMapper json;

    public InventoryPhotoService(
            AttachmentWriter writer,
            AttachmentRegistry registry,
            AttachmentStorage storage,
            RequisitionActorContext actors,
            InventoryIdentityPort identities,
            JdbcTemplate db,
            Clock clock,
            ObjectMapper json) {
        this.writer = writer;
        this.registry = registry;
        this.storage = storage;
        this.actors = actors;
        this.identities = identities;
        this.db = db;
        this.clock = clock;
        this.json = json;
    }

    public Map<String, Object> upload(
            RequisitionActorContext.Actor actor,
            long taskId,
            UUID eventId,
            UUID photoId,
            UUID deviceId,
            String filename,
            String mime,
            InputStream source)
            throws IOException {
        var newlyReceived = new AtomicReference<UUID>();
        var result =
                writer.write(
                        filename,
                        mime,
                        source,
                        attachment -> {
                            newlyReceived.set(attachment.file().id());
                            var current = actors.revalidateLocked(actor);
                            Long binding = identities.bindingId(actor);
                            var tasks =
                                    db.queryForList(
                                            "SELECT version,task_status FROM fams_inventory_task"
                                                    + " WHERE id=? FOR UPDATE",
                                            taskId);
                            if (tasks.isEmpty()) {
                                throw new InventoryFailure(404, "INVENTORY_TASK_NOT_FOUND");
                            }
                            if (db.queryForList(
                                            "SELECT account_id FROM fams_inventory_participant"
                                                + " WHERE task_id=? AND account_id=? AND revoked_at"
                                                + " IS NULL",
                                            taskId,
                                            current.id())
                                    .isEmpty()) {
                                throw new InventoryFailure(403, "INVENTORY_PARTICIPATION_REQUIRED");
                            }
                            if (java.util.List.of("DRAFT", "READY", "DELETED")
                                    .contains(tasks.getFirst().get("task_status").toString())) {
                                throw new InventoryFailure(409, "INVENTORY_NOT_COLLECTING");
                            }
                            if (attachment.scanResult()
                                    == com.fams.attachment.MalwareScanner.Result.UNAVAILABLE) {
                                throw new InventoryFailure(503, "INVENTORY_PHOTO_SCAN_UNAVAILABLE");
                            }
                            if (attachment.scanResult()
                                    != com.fams.attachment.MalwareScanner.Result.CLEAN) {
                                throw new InventoryFailure(422, "INVENTORY_PHOTO_REJECTED");
                            }
                            if (!attachment.format().image()) {
                                throw new InventoryFailure(400, "INVENTORY_PHOTO_IMAGE_REQUIRED");
                            }
                            var scopes =
                                    db.queryForList(
                                            "SELECT"
                                                + " id,actor_account_id,binding_id,device_id,discarded_at"
                                                + " FROM fams_inventory_upload_scope WHERE"
                                                + " task_id=? AND client_event_id=? FOR UPDATE",
                                            taskId,
                                            eventId.toString());
                            if (scopes.isEmpty()) {
                                db.update(
                                        "INSERT INTO"
                                            + " fams_inventory_upload_scope(task_id,client_event_id,actor_account_id,binding_id,device_id,created_at)"
                                            + " VALUES (?,?,?,?,?,?)",
                                        taskId,
                                        eventId.toString(),
                                        current.id(),
                                        binding,
                                        deviceId.toString(),
                                        clock.instant());
                                scopes =
                                        db.queryForList(
                                                "SELECT"
                                                    + " id,actor_account_id,binding_id,device_id,discarded_at"
                                                    + " FROM fams_inventory_upload_scope WHERE"
                                                    + " task_id=? AND client_event_id=?",
                                                taskId,
                                                eventId.toString());
                            }
                            var scope = scopes.getFirst();
                            if (scope.get("discarded_at") != null) {
                                throw new InventoryFailure(410, "INVENTORY_PHOTO_EXPIRED");
                            }
                            long id = ((Number) scope.get("id")).longValue();
                            Long originalBinding =
                                    scope.get("binding_id") == null
                                            ? null
                                            : ((Number) scope.get("binding_id")).longValue();
                            if (((Number) scope.get("actor_account_id")).longValue() != current.id()
                                    || !Objects.equals(binding, originalBinding)
                                    || !deviceId.toString().equals(scope.get("device_id"))) {
                                throw new InventoryFailure(403, "INVENTORY_PHOTO_IDENTITY");
                            }
                            var prior =
                                    db.queryForList(
                                            "SELECT object_id,content_sha256,original_filename FROM"
                                                + " fams_inventory_photo_receipt WHERE scope_id=?"
                                                + " AND client_photo_id=?",
                                            id,
                                            photoId.toString());
                            if (!prior.isEmpty()) {
                                if (!attachment
                                                .file()
                                                .sha256()
                                                .equals(prior.getFirst().get("content_sha256"))
                                        || !filename.equals(
                                                prior.getFirst().get("original_filename"))) {
                                    throw new InventoryFailure(409, "INVENTORY_PHOTO_KEY_CONFLICT");
                                }
                                return Map.<String, Object>of(
                                        "objectId",
                                        prior.getFirst().get("object_id"),
                                        "photoId",
                                        photoId.toString());
                            }
                            if (!db.queryForList(
                                            "SELECT id FROM fams_inventory_observation WHERE"
                                                    + " task_id=? AND client_event_id=?",
                                            taskId,
                                            eventId.toString())
                                    .isEmpty()) {
                                throw new InventoryFailure(
                                        409, "INVENTORY_EVENT_ALREADY_SUBMITTED");
                            }
                            registry.register(
                                    "INVENTORY_EVENT", id, 0, current.id(), filename, attachment);
                            db.update(
                                    "INSERT INTO"
                                        + " fams_inventory_photo_receipt(scope_id,client_photo_id,object_id,content_sha256,original_filename)"
                                        + " VALUES (?,?,?,?,?)",
                                    id,
                                    photoId.toString(),
                                    attachment.file().id().toString(),
                                    attachment.file().sha256(),
                                    filename);
                            db.update(
                                    "INSERT INTO"
                                        + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                                        + " VALUES"
                                        + " (?,?,?,'INVENTORY_PHOTO_UPLOADED','INVENTORY_TASK',?,?,?,?,?)",
                                    actor.requestId().toString(),
                                    current.id(),
                                    current.roles().stream()
                                            .map(Enum::name)
                                            .sorted()
                                            .findFirst()
                                            .orElseThrow(),
                                    taskId,
                                    ((Number) tasks.getFirst().get("version")).longValue(),
                                    ((Number) tasks.getFirst().get("version")).longValue(),
                                    clock.instant(),
                                    json.writeValueAsString(
                                            Map.of(
                                                    "eventId",
                                                    eventId.toString(),
                                                    "objectId",
                                                    attachment.file().id().toString(),
                                                    "sha256",
                                                    attachment.file().sha256())));
                            return Map.<String, Object>of(
                                    "objectId",
                                    attachment.file().id().toString(),
                                    "photoId",
                                    photoId.toString());
                        });
        // An exact replay registered no new metadata; delete only this attempt's new quarantined
        // bytes.
        if (!newlyReceived.get().toString().equals(result.get("objectId"))) {
            storage.delete(newlyReceived.get());
        }
        return result;
    }
}
