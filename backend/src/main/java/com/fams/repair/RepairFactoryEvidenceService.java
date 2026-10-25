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

/**
 * No scanning transaction; current owner, all versions and original bytes are rebound at commit.
 */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class RepairFactoryEvidenceService {
    private final RepairFactoryService factory;
    private final AttachmentWriter writer;
    private final AttachmentStorage storage;
    private final AttachmentEvidencePort evidence;

    public RepairFactoryEvidenceService(
            RepairFactoryService factory,
            AttachmentWriter writer,
            AttachmentStorage storage,
            AttachmentEvidencePort evidence) {
        this.factory = factory;
        this.writer = writer;
        this.storage = storage;
        this.evidence = evidence;
    }

    public Map<String, Object> upload(
            Actor actor,
            long repair,
            long round,
            long version,
            long roundVersion,
            long assetVersion,
            long ownershipVersion,
            long lockVersion,
            UUID key,
            String purpose,
            String filename,
            String mime,
            InputStream bytes)
            throws IOException {
        factory.uploader(actor, repair, round);
        var received = new AtomicReference<UUID>();
        var result =
                writer.write(
                        filename,
                        mime,
                        bytes,
                        attachment -> {
                            received.set(attachment.file().id());
                            try {
                                return factory.registerEvidence(
                                        actor,
                                        repair,
                                        round,
                                        version,
                                        roundVersion,
                                        key,
                                        assetVersion,
                                        ownershipVersion,
                                        lockVersion,
                                        purpose,
                                        filename,
                                        attachment);
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
    public AttachmentGrantSigner.Grant grant(Actor actor, long repair, long round, UUID object) {
        factory.evidenceAccess(actor, repair, round, object);
        return evidence.grant("REPAIR_FACTORY", round, identity(actor, object));
    }

    @Transactional
    public AttachmentReadService.Content read(
            Actor actor, long repair, long round, UUID object, String token) throws IOException {
        factory.evidenceAccess(actor, repair, round, object);
        return evidence.read("REPAIR_FACTORY", round, identity(actor, object), token);
    }

    private AttachmentGrantSigner.Identity identity(Actor actor, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
}
