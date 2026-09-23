package com.fams.masterdata;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("fams-product")
@Component
final class MasterDataPhoneCodec {

    private static final String AES = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final int IV_BYTES = 12;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec lookupKey;
    private final SecureRandom random = new SecureRandom();

    MasterDataPhoneCodec(
            @Value("${fams.security.employee-phone-key}") String encryptionSecret,
            @Value("${fams.security.idempotency-hmac-key}") String lookupSecret) {
        if (encryptionSecret == null || encryptionSecret.length() < 32) {
            throw new IllegalArgumentException("The employee phone key must contain at least 32 characters.");
        }
        this.encryptionKey = new SecretKeySpec(
                encryptionSecret.substring(0, 32).getBytes(StandardCharsets.UTF_8), "AES");
        this.lookupKey = new SecretKeySpec(lookupSecret.getBytes(StandardCharsets.UTF_8), HMAC);
    }

    byte[] encrypt(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(phone.strip().getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Employee phone encryption is unavailable.", exception);
        }
    }

    byte[] lookupHash(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(lookupKey);
            return mac.doFinal(phone.strip().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Employee phone lookup hashing is unavailable.", exception);
        }
    }
}
