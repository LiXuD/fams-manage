package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CsrfTokenValidatorTest {

    @Test
    void requiresAConstantTimeMatch() {
        assertTrue(CsrfTokenValidator.matches("csrf-value-123456", "csrf-value-123456"));
        assertFalse(CsrfTokenValidator.matches("csrf-value-123456", "csrf-value-123457"));
        assertFalse(CsrfTokenValidator.matches("csrf-value-123456", null));
        assertFalse(CsrfTokenValidator.matches(null, "csrf-value-123456"));
    }
}
