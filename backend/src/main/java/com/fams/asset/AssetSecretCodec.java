package com.fams.asset;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("fams-product")
@Component
final class AssetSecretCodec {
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom random = new SecureRandom();

    AssetSecretCodec(@Value("${fams.security.employee-phone-key}") String encryptionSecret,
            @Value("${fams.security.idempotency-hmac-key}") String hashSecret) {
        if (encryptionSecret.length() < 32 || hashSecret.length() < 32) {
            throw new IllegalArgumentException("Asset encryption and hash keys must be at least 32 characters.");
        }
        encryptionKey = new SecretKeySpec(encryptionSecret.substring(0, 32)
                .getBytes(StandardCharsets.UTF_8), "AES");
        hashKey = new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    byte[] encrypt(String value) {
        if (value == null) { return null; }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] payload = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + payload.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(payload, 0, result, iv.length, payload.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Asset encryption failed.", exception);
        }
    }

    String decrypt(byte[] bytes) {
        if (bytes == null) { return null; }
        try {
            byte[] iv = java.util.Arrays.copyOfRange(bytes, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Asset decryption failed.", exception);
        }
    }

    String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Asset hashing failed.", exception);
        }
    }
}
