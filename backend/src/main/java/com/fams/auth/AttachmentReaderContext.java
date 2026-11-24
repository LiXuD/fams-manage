package com.fams.auth;

import java.time.Instant;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Read-side identity context for attachment evidence. Like the write side it revalidates the
 * captured session with locks held, but accepts both warehouse operators and asset admins.
 */
@Component
@Profile("fams-product")
public final class AttachmentReaderContext {
    /** Reader snapshot: account identity, effective roles and the session authority versions. */
    public record Reader(long id, Set<Role> roles, long authEpoch, long authorizationVersion, Instant expiresAt) {
        /** Defensively copies the role set so a reader snapshot cannot be mutated afterwards. */
        public Reader { roles = Set.copyOf(roles); }
    }
    private final RequisitionActorContext identities;
    AttachmentReaderContext(RequisitionActorContext identities) {
        this.identities = identities;
    }
    /**
     * Captures the current identity and revalidates it with row locks held before any attachment
     * read is served.
     *
     * @return reader snapshot of the revalidated identity
     * @throws AuthOperationException when the session is no longer valid or the account holds
     *         neither the warehouse-operator nor the asset-admin role
     */
    public Reader requireReaderLocked() {
        var source = identities.capture();
        var current = identities.revalidateLocked(source);
        if (!current.has(Role.WAREHOUSE_OPERATOR) && !current.has(Role.ASSET_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        return new Reader(current.id(), current.roles(), source.authEpoch(), source.authorizationVersion(),
                source.expiresAt());
    }
}
