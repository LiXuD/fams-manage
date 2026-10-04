package com.fams.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AuthPayloadFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    AuthPayloadFingerprint(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("The idempotency HMAC key must contain at least 32 characters.");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    String initialPasswordChange(String currentPassword, String newPassword) {
        return fingerprint(currentPassword, newPassword);
    }

    String fingerprint(String... values) {
        StringBuilder canonicalPayload = new StringBuilder();
        for (String value : values) {
            canonicalPayload.append(lengthPrefixed(value));
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(
                    canonicalPayload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    private String lengthPrefixed(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue.length() + ":" + safeValue;
    }
}
