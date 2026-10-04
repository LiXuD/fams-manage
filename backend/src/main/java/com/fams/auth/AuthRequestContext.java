package com.fams.auth;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

final class AuthRequestContext {

    static final String REQUEST_ID_ATTRIBUTE = "fams.request.id";
    static final String ACCOUNT_ATTRIBUTE = "fams.auth.account";
    static final String SESSION_ATTRIBUTE = "fams.auth.current-session";

    private AuthRequestContext() {
    }

    static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (value instanceof UUID requestId) {
            return requestId;
        }
        UUID requestId = UUID.randomUUID();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }

    static AuthAccount account(HttpServletRequest request) {
        Object value = request.getAttribute(ACCOUNT_ATTRIBUTE);
        if (value instanceof AuthAccount account) {
            return account;
        }
        throw AuthOperationException.sessionInvalid();
    }

    static AuthSession session(HttpServletRequest request) {
        Object value = request.getAttribute(SESSION_ATTRIBUTE);
        if (value instanceof AuthSession session) {
            return session;
        }
        throw AuthOperationException.sessionInvalid();
    }
}
