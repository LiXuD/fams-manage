package com.fams.masterdata;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("fams-product")
@Component
final class MasterDataFingerprint {

    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    MasterDataFingerprint(@Value("${fams.security.idempotency-hmac-key}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    String of(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            String safe = value == null ? "" : value;
            canonical.append(safe.length()).append(':').append(safe);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Master-data fingerprinting is unavailable.", exception);
        }
    }
}
