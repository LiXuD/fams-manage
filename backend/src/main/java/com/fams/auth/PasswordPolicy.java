package com.fams.auth;

final class PasswordPolicy {

    private static final int MIN_LENGTH = 12;

    private PasswordPolicy() {
    }

    static void validateInitialPassword(String password) {
        validateInitialPassword(password, null);
    }

    static void validateInitialPassword(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new AuthValidationException("Password must contain at least 12 characters.");
        }
        if (username != null && !username.isBlank()
                && password.toLowerCase().contains(username.toLowerCase())) {
            throw new AuthValidationException("Password must not contain the username.");
        }
    }
}
