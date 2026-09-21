package com.fams.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class CsrfTokenValidator {

    private CsrfTokenValidator() {
    }

    static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
