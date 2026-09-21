package com.fams.auth;

final class AuthOperationException extends RuntimeException {

    private final String code;
    private final int status;

    AuthOperationException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }

    static AuthOperationException authenticationFailed() {
        return new AuthOperationException("AUTHENTICATION_FAILED", "Authentication failed.", 401);
    }

    static AuthOperationException currentPasswordInvalid() {
        return new AuthOperationException(
                "AUTH_CURRENT_PASSWORD_INVALID",
                "The current password is invalid.",
                400);
    }

    static AuthOperationException passwordPolicyViolation(String message) {
        return new AuthOperationException("AUTH_NEW_PASSWORD_POLICY_VIOLATION", message, 400);
    }

    static AuthOperationException initialPasswordChangeNotRequired() {
        return new AuthOperationException(
                "AUTH_INITIAL_PASSWORD_CHANGE_NOT_REQUIRED",
                "The initial password change is not required.",
                409);
    }

    static AuthOperationException sessionInvalid() {
        return new AuthOperationException("AUTH_SESSION_INVALID", "The session is invalid.", 401);
    }

    static AuthOperationException csrfInvalid() {
        return new AuthOperationException("AUTH_CSRF_INVALID", "The CSRF token is invalid.", 403);
    }

    static AuthOperationException idempotencyConflict() {
        return new AuthOperationException(
                "AUTH_IDEMPOTENCY_CONFLICT",
                "The idempotency key was already used for another request.",
                409);
    }
}
