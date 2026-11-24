package com.fams.auth;

/**
 * Lifecycle state of a system account. Only {@code ENABLED} accounts may authenticate or perform
 * sensitive operations; {@code DISABLED} and {@code LOCKED} accounts are rejected by the auth
 * validators.
 */
public enum AccountStatus {
    ENABLED,
    DISABLED,
    LOCKED
}
