package com.fams.auth;

import java.time.Instant;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
public final class AttachmentReaderContext {
    public record Reader(long id, Set<Role> roles, long authEpoch, long authorizationVersion, Instant expiresAt) {
        public Reader { roles = Set.copyOf(roles); }
    }
    private final RequisitionActorContext identities;
    AttachmentReaderContext(RequisitionActorContext identities) {
        this.identities = identities;
    }
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
