package com.fams.attachment;

import java.util.Set;
import com.fams.auth.Role;

/** The business module owns authorization, lifecycle and version updates. */
public interface AttachmentBusinessPort {
    String businessType();
    void requireReaderLocked(long actorId, Set<Role> roles, long businessId);
    void requireUploadAccess(long actorId, long businessId);
    void requireWritable(long actorId, long businessId, long expectedVersion, boolean lock);
    void registered(long actorId, long businessId, long expectedVersion);
}
