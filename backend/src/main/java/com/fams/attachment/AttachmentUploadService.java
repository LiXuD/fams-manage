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
            return attachment;
        });
    }
}
