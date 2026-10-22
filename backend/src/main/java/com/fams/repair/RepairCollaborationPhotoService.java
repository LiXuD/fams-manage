package com.fams.repair;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentGrantSigner;
import com.fams.attachment.AttachmentReadService;
import com.fams.attachment.AttachmentStorage;
import com.fams.attachment.AttachmentWriter;
import com.fams.auth.RequisitionActorContext.Actor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Invitation authorization precedes every original receipt and signed content read. */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class RepairCollaborationPhotoService {
    private final RepairCollaborationService collaboration;
    private final AttachmentWriter writer;
    private final AttachmentStorage storage;
    private final AttachmentEvidencePort evidence;

    public RepairCollaborationPhotoService(
            RepairCollaborationService collaboration,
            AttachmentWriter writer,
            AttachmentStorage storage,
            AttachmentEvidencePort evidence) {
        this.collaboration = collaboration;
        this.writer = writer;
        this.storage = storage;
        this.evidence = evidence;
    }

    public Map<String, Object> upload(
            Actor actor,
            long invitation,
            long version,
            UUID key,
            String filename,
            String mime,
            InputStream bytes)
            throws IOException {
        collaboration.uploader(actor, invitation);
        var received = new AtomicReference<UUID>();
        var result =
                writer.write(
                        filename,
                        mime,
                        bytes,
                        attachment -> {
                            received.set(attachment.file().id());
                            try {
                                return collaboration.registerAttachment(
                                        actor, invitation, version, key, filename, attachment);
                            } catch (RepairConflict conflict) {
                                return Map.<String, Object>of(
                                        "conflictCode",
                                        conflict.getMessage(),
                                        "conflictFields",
                                        conflict.fields());
                            }
                        });
        if (!received.get().toString().equals(result.get("objectId"))) {
            storage.delete(received.get());
        }
        if (result.get("conflictCode") instanceof String code) {
            var fields = new java.util.LinkedHashMap<String, String>();
            if (result.get("conflictFields") instanceof Map<?, ?> map) {
                map.forEach((k, v) -> fields.put(k.toString(), v.toString()));
            }
            throw new RepairConflict(code, fields);
        }
        return result;
    }

    @Transactional
    public AttachmentGrantSigner.Grant grant(
            Actor actor, long invitation, UUID object, String kind) {
        long scope = scope(actor, invitation, object, kind);
        return evidence.grant(type(kind), scope, identity(actor, object));
    }

    @Transactional
    public AttachmentReadService.Content read(
            Actor actor, long invitation, UUID object, String kind, String token)
            throws IOException {
        long scope = scope(actor, invitation, object, kind);
        return evidence.read(type(kind), scope, identity(actor, object), token);
    }

    private long scope(Actor actor, long invitation, UUID object, String kind) {
        return switch (kind) {
            case "fault-photos" -> collaboration.photoScope(actor, invitation, true);
            case "attachments" -> collaboration.photoScope(actor, invitation, false);
            case "opinion-attachments" ->
                    collaboration.opinionAttachmentScope(actor, invitation, object);
            case "staff-attachments" ->
                    collaboration.staffAttachmentScope(actor, invitation, object);
            default -> throw new RepairFailure(404, "REPAIR_EVIDENCE_NOT_FOUND");
        };
    }

    private String type(String kind) {
        return "fault-photos".equals(kind) ? "REPAIR" : "REPAIR_TECH";
    }

    private AttachmentGrantSigner.Identity identity(Actor actor, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
}
