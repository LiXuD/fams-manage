package com.fams.attachment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Domain-separated, short-lived grant; current session and business checks remain mandatory. */
public final class AttachmentGrantSigner {
    public record Identity(UUID objectId, long accountId, long authEpoch, long authorizationVersion) { }
    public record Grant(String token, long expiresAtEpochSecond) { }
    private final byte[] key;
    private final Clock clock;
    public AttachmentGrantSigner(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) { throw new IllegalArgumentException("Download signing key is too short"); }
        this.key = sign(secret.getBytes(StandardCharsets.UTF_8), "fams-attachment-download-v1");
        this.clock = clock;
    }
    public Grant issue(Identity identity) {
        long expiry = clock.instant().getEpochSecond() + 60;
        String body = identity.objectId() + "." + identity.accountId() + "." + identity.authEpoch()
                + "." + identity.authorizationVersion() + "." + expiry;
        return new Grant(body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(key, body)), expiry);
    }
    public void verify(String token, Identity expected) {
        try {
            if (token == null || token.length() > 256) { throw denied(); }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 6) { throw denied(); }
            String body = String.join(".", java.util.Arrays.copyOf(parts, 5));
            byte[] signature = Base64.getUrlDecoder().decode(parts[5]);
            long expiry = Long.parseLong(parts[4]);
            long now = clock.instant().getEpochSecond();
            if (!MessageDigest.isEqual(sign(key, body), signature) || !expected.objectId().toString().equals(parts[0])
                    || expected.accountId() != Long.parseLong(parts[1]) || expected.authEpoch() != Long.parseLong(parts[2])
                    || expected.authorizationVersion() != Long.parseLong(parts[3]) || expiry <= now || expiry > now + 60) {
                throw denied();
            }
        } catch (IllegalArgumentException error) { throw denied(); }
    }
    private AttachmentAccessFailure denied() { return new AttachmentAccessFailure(403, "ATTACHMENT_GRANT_INVALID"); }
    private byte[] sign(byte[] signingKey, String text) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
}
