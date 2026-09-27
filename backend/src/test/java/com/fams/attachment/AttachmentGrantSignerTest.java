package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttachmentGrantSignerTest {
    private static final String SECRET = "attachment-test-secret-0123456789abcdef";
    private final Clock now = Clock.fixed(Instant.parse("2026-09-27T01:00:00Z"), ZoneOffset.UTC);
    private final AttachmentGrantSigner.Identity identity = new AttachmentGrantSigner.Identity(UUID.randomUUID(), 7, 2, 3);
    @Test
    void grantBindsObjectAccountAndCurrentAuthorizationAndExpiresAtSixtySeconds() {
        var signer = new AttachmentGrantSigner(SECRET, now);
        var grant = signer.issue(identity);
        signer.verify(grant.token(), identity);
        assertThrows(AttachmentAccessFailure.class, () -> signer.verify(grant.token(),
                new AttachmentGrantSigner.Identity(identity.objectId(), 8, 2, 3)));
        assertThrows(AttachmentAccessFailure.class, () -> signer.verify(grant.token(),
                new AttachmentGrantSigner.Identity(UUID.randomUUID(), 7, 2, 3)));
        assertThrows(AttachmentAccessFailure.class, () -> signer.verify(grant.token(),
                new AttachmentGrantSigner.Identity(identity.objectId(), 7, 2, 4)));
        var expired = new AttachmentGrantSigner(SECRET, Clock.offset(now, java.time.Duration.ofSeconds(60)));
        assertThrows(AttachmentAccessFailure.class, () -> expired.verify(grant.token(), identity));
        assertThrows(AttachmentAccessFailure.class, () -> signer.verify(grant.token().replace(".7.", ".9."), identity));
        assertThrows(AttachmentAccessFailure.class, () -> signer.verify("malformed", identity));
    }
}
