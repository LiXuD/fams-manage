package com.fams.auth;

import java.time.Instant;

record AuthIdempotencyRecord(
        long accountId,
        String operationId,
        String idempotencyKey,
        String payloadFingerprint,
        long resultAuthEpoch,
        Instant createdAt,
        Instant expiresAt) {
}
