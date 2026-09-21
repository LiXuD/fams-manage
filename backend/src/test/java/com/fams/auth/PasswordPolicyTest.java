package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsAnInitialPasswordWithAtLeastTwelveCharacters() {
        assertDoesNotThrow(() -> PasswordPolicy.validateInitialPassword("a-strong-password"));
    }

    @Test
    void rejectsShortInitialPasswords() {
        assertThrows(AuthValidationException.class, () -> PasswordPolicy.validateInitialPassword("short"));
    }

    @Test
    void rejectsPasswordsThatContainTheUsername() {
        assertThrows(
                AuthValidationException.class,
                () -> PasswordPolicy.validateInitialPassword("alice-strong-password", "alice"));
    }
}
