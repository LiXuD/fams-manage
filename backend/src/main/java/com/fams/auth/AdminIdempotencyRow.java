package com.fams.auth;

record AdminIdempotencyRow(
        String payloadFingerprint,
        int httpStatus,
        String responseBodyJson,
        Long resourceId,
        Long resourceVersion) {
}
