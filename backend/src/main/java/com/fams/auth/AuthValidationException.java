package com.fams.auth;

final class AuthValidationException extends RuntimeException {

    AuthValidationException(String message) {
        super(message);
    }
}
