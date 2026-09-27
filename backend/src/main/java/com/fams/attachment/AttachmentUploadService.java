package com.fams.attachment;

import java.io.IOException;
import java.io.InputStream;
import com.fams.auth.AttachmentActorContext;

/** Internal upload use case; public transport and idempotency are connected separately. */
public final class AttachmentUploadService {
    private final AttachmentWriter writer;
    private final AttachmentRegistry registry;
    private final AttachmentActorContext actors;
    private final AttachmentBusinessPort business;

    public AttachmentUploadService(AttachmentWriter writer, AttachmentRegistry registry,
            AttachmentActorContext actors, AttachmentBusinessPort business) {
        this.writer = writer; this.registry = registry; this.actors = actors; this.business = business;
    }

    public AttachmentIntake.QuarantinedAttachment upload(long businessId, long version, String filename,
            String mime, InputStream source) throws IOException {
        var actor = actors.requireWarehouse();
        business.requireWritable(actor.id(), businessId, version, false);
        return writer.write(filename, mime, source, attachment -> {
            actors.revalidateLocked(actor);
            business.requireWritable(actor.id(), businessId, version, true);
            registry.register(business.businessType(), businessId, version, actor.id(), filename, attachment);
            business.registered(actor.id(), businessId, version);
            registry.auditUpload(actor.id(), actor.requestId(), business.businessType(), businessId, version, attachment);
            return attachment;
        });
    }

    public AttachmentIntake.QuarantinedAttachment uploadIdempotent(AttachmentUploadKeys keys, String key,
            long businessId, long version, String filename, String mime, InputStream source) throws IOException {
        if (key == null || !key.matches("[\\x21-\\x7e]{16,128}") || version < 0 || businessId <= 0
                || filename == null || filename.isBlank() || filename.length() > 255 || mime == null) {
            throw new IllegalArgumentException("Invalid attachment upload request");
        }
        var actor = actors.requireWarehouse();
        // Version comparison belongs after locked replay lookup; an upload may have just advanced it.
        if (!keys.exists(actor.id(), key)) { business.requireUploadAccess(actor.id(), businessId); }
        try {
            return writer.write(filename, mime, source, attachment -> {
                actors.revalidateLocked(actor);
                keys.replay(actor.id(), key, business.businessType(), businessId, version, filename, mime, attachment);
                business.requireWritable(actor.id(), businessId, version, true);
                registry.register(business.businessType(), businessId, version, actor.id(), filename, attachment);
                business.registered(actor.id(), businessId, version);
                registry.auditUpload(actor.id(), actor.requestId(), business.businessType(), businessId, version, attachment);
                keys.save(actor.id(), key, version, attachment.file().id());
                return attachment;
            });
        } catch (AttachmentUploadKeys.Replay replay) { return replay.attachment(); }
    }
}
