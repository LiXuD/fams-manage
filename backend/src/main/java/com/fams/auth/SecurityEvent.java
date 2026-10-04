package com.fams.auth;

import java.time.Instant;
import java.util.UUID;

record SecurityEvent(
        Long accountId,
        SecurityEventType eventType,
        UUID requestId,
        Instant occurredAt,
        String detail) {
}
