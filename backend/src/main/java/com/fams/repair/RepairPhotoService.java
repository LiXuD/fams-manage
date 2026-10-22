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

    public Map<String, Object> upload(
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
        var result =
                writer.write(
                        filename,
                        mime,
                        bytes,
                        attachment -> {
                            received.set(attachment.file().id());
                            try {
                                return repairs.registerPhoto(
                                        actor, id, version, key, filename, attachment);
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
            if (result.get("conflictFields") instanceof Map<?, ?> source) {
                source.forEach((k, v) -> fields.put(k.toString(), v.toString()));
            }
            throw new RepairConflict(code, fields);
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
