package com.fams.attachment;

/** The business module owns authorization, lifecycle and version updates. */
public interface AttachmentBusinessPort {
    String businessType();
    void requireWritable(long actorId, long businessId, long expectedVersion, boolean lock);
    void registered(long actorId, long businessId, long expectedVersion);
}
