package com.fams.masterdata;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

final class RequestIds {

    private static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    private RequestIds() {
    }

    static UUID from(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof UUID requestId) {
            return requestId;
        }
        String header = request.getHeader("X-Request-Id");
        UUID requestId;
        try {
            requestId = header == null ? UUID.randomUUID() : UUID.fromString(header);
        } catch (IllegalArgumentException exception) {
            requestId = UUID.randomUUID();
        }
        request.setAttribute(ATTRIBUTE, requestId);
        return requestId;
    }
}
