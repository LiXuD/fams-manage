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

/** Scanning occurs without a business transaction; registration rechecks current authority. */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class RepairPhotoService {
    private final RepairService repairs;
    private final AttachmentWriter writer;
    private final AttachmentStorage storage;
    private final AttachmentEvidencePort evidence;

    public RepairPhotoService(
            RepairService repairs,
            AttachmentWriter writer,
            AttachmentStorage storage,
            AttachmentEvidencePort evidence) {
        this.repairs = repairs;
        this.writer = writer;
        this.storage = storage;
        this.evidence = evidence;
    }

    /** Internal write-callback envelope: a receipt or the conflict to rethrow after cleanup. */
    private record UploadOutcome(
            RepairViews.PhotoReceipt receipt,
            String conflictCode,
            Map<String, String> conflictFields) {}

    public RepairViews.PhotoReceipt upload(
            Actor actor,
            long id,
            long version,
            UUID key,
            String filename,
            String mime,
            InputStream bytes)
            throws IOException {
        repairs.photoUploader(actor, id);
        var received = new AtomicReference<UUID>();
        var outcome =
                writer.write(
                        filename,
                        mime,
                        bytes,
                        attachment -> {
                            received.set(attachment.file().id());
                            try {
                                return new UploadOutcome(
                                        repairs.registerPhoto(
                                                actor, id, version, key, filename, attachment),
                                        null,
                                        null);
                            } catch (RepairConflict conflict) {
                                return new UploadOutcome(
                                        null, conflict.getMessage(), conflict.fields());
                            }
                        });
        var result = outcome.receipt();
        if (result == null
                || !received.get().toString().equals(result.objectId())) {
            storage.delete(received.get());
        }
        if (outcome.conflictCode() != null) {
            var fields = new java.util.LinkedHashMap<String, String>();
            if (outcome.conflictFields() != null) {
                fields.putAll(outcome.conflictFields());
            }
            throw new RepairConflict(outcome.conflictCode(), fields);
        }
        return result;
    }

    @Transactional
    public AttachmentGrantSigner.Grant grant(Actor actor, long id, UUID object) {
        repairs.photoAccess(actor, id);
        return evidence.grant("REPAIR", id, identity(actor, object));
    }

    @Transactional
    public AttachmentReadService.Content read(Actor actor, long id, UUID object, String token)
            throws IOException {
        repairs.photoAccess(actor, id);
        return evidence.read("REPAIR", id, identity(actor, object), token);
    }

    private AttachmentGrantSigner.Identity identity(Actor actor, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
}
