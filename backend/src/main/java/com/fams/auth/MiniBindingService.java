package com.fams.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class MiniBindingService {
    private final JdbcTemplate jdbc;
    private final MiniEmployeeIdentityPort employees;
    private final MiniSessionService sessions;
    private final AuthApplicationService auth;
    private final AuthPayloadFingerprint fingerprint;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    MiniBindingService(JdbcTemplate jdbc, MiniEmployeeIdentityPort employees, MiniSessionService sessions,
            AuthApplicationService auth, AuthPayloadFingerprint fingerprint, Clock clock) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.sessions = sessions;
        this.auth = auth;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    // Rejections commit counters and safe audit, rather than rolling back brute-force protection.
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = AuthOperationException.class)
    public Delivery prepare(String employeeNo, String phone, String deviceId, String action,
            MiniWechatIdentityPort.Identity identity, UUID requestId) {
        String device = fingerprint.fingerprint("mini-device/v1", deviceId);
        takeQuota(device, null, requestId);
        Long employeeId = employees.findEmployee(employeeNo).orElse(null);
        if (employeeId == null) {
            audit(null, "MINI_BINDING_REJECTED", "IDENTITY_MISMATCH", requestId);
            throw rejected();
        }
        List<Long> accounts = jdbc.queryForList("SELECT id FROM fams_system_account WHERE employee_id = ?",
                Long.class, employeeId);
        if (accounts.isEmpty()) {
            audit(null, "MINI_BINDING_REJECTED", "IDENTITY_MISMATCH", requestId);
            throw rejected();
        }
        long accountId = accounts.getFirst();
        lockAccount(accountId);
        takeQuota(employeeLimit(accountId), accountId, requestId);
        Long version = employees.verify(employeeId, phone).orElse(null);
        if (version == null || auth.requireCurrentAccount(accountId).status() != AccountStatus.ENABLED) {
            audit(accountId, "MINI_BINDING_REJECTED", "IDENTITY_MISMATCH", requestId);
            throw rejected();
        }
        String identityHash = sessions.identityHash(identity);
        checkIdentityOwner(accountId, identityHash, requestId);
        var current = activeBinding(accountId);
        if (current != null && "BIND".equals(action)) {
            audit(accountId, "MINI_BINDING_REJECTED", "ALREADY_BOUND", requestId);
            throw conflict();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = challengeHash(token);
        String otp = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant expires = now().plusSeconds(300);
        jdbc.update("""
                INSERT INTO fams_mini_binding_challenge
                (challenge_hash, account_id, employee_version, identity_hash, device_hash, otp_hash,
                 action, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, hash, accountId, version, identityHash, device, otpHash(hash, otp), action,
                Timestamp.from(now()), Timestamp.from(expires));
        audit(accountId, "MINI_BINDING_CODE_REQUESTED", action, requestId);
        return new Delivery(token, phone.strip(), otp, expires);
    }

    /** Delivery runs outside a DB transaction. Failed or ambiguous sends never activate a challenge. */
    @Transactional
    public void deliveryResult(String token, boolean delivered, UUID requestId) {
        jdbc.update("UPDATE fams_mini_binding_challenge SET state = ? WHERE challenge_hash = ? AND state = 'PENDING'",
                delivered ? "SENT" : "FAILED", challengeHash(token));
        if (!delivered) {
            Long accountId = jdbc.queryForObject("SELECT account_id FROM fams_mini_binding_challenge "
                    + "WHERE challenge_hash = ?", Long.class, challengeHash(token));
            audit(accountId, "MINI_BINDING_SMS_FAILED", "DELIVERY_UNAVAILABLE", requestId);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = AuthOperationException.class)
    public Result bind(String token, String deviceId, String otp, String idempotencyKey,
            MiniWechatIdentityPort.Identity identity, UUID requestId) {
        String hash = challengeHash(token);
        List<Long> candidates = jdbc.queryForList("SELECT account_id FROM fams_mini_binding_challenge "
                + "WHERE challenge_hash = ?", Long.class, hash);
        if (candidates.isEmpty()) {
            audit(null, "MINI_BINDING_REJECTED", "UNKNOWN_CHALLENGE", requestId);
            throw rejected();
        }
        long accountId = candidates.getFirst();
        long employeeId = lockAccount(accountId);
        Challenge challenge = jdbc.queryForObject("""
                SELECT employee_version, identity_hash, device_hash, otp_hash, action, state,
                       expires_at, completed_binding_id, idempotency_key
                FROM fams_mini_binding_challenge WHERE challenge_hash = ? FOR UPDATE
                """, (row, ignored) -> new Challenge(row.getLong(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), row.getString(6), row.getTimestamp(7).toInstant(),
                row.getObject(8, Long.class), row.getString(9)), hash);
        if (challenge == null) {
            throw rejected();
        }
        Limit limit = limit(employeeLimit(accountId));
        if (limit.lockedUntil() != null && now().isBefore(limit.lockedUntil())) {
            audit(accountId, "MINI_BINDING_REJECTED", "LOCKED", requestId);
            throw limited();
        }
        boolean proof = challenge.identityHash().equals(sessions.identityHash(identity))
                && challenge.deviceHash().equals(fingerprint.fingerprint("mini-device/v1", deviceId))
                && MessageDigest.isEqual(challenge.otpHash().getBytes(StandardCharsets.US_ASCII),
                        otpHash(hash, otp).getBytes(StandardCharsets.US_ASCII));
        boolean current = employees.isCurrent(employeeId, challenge.employeeVersion())
                && auth.requireCurrentAccount(accountId).status() == AccountStatus.ENABLED;
        if (!proof || !current || !now().isBefore(challenge.expiresAt())
                || !("SENT".equals(challenge.state()) || "COMPLETED".equals(challenge.state()))) {
            fail(accountId, limit, requestId);
            throw rejected();
        }
        if ("COMPLETED".equals(challenge.state())) {
            Binding binding = activeBinding(accountId);
            if (!idempotencyKey.equals(challenge.idempotencyKey()) || binding == null
                    || binding.id() != challenge.completedId()) {
                throw conflict();
            }
            return new Result(Long.toString(accountId), Long.toString(binding.id()), binding.version());
        }
        checkIdentityOwner(accountId, challenge.identityHash(), requestId);
        Binding previous = activeBinding(accountId);
        if (previous != null && "BIND".equals(challenge.action())) {
            audit(accountId, "MINI_BINDING_REJECTED", "ALREADY_BOUND", requestId);
            throw conflict();
        }
        if (previous != null) {
            revoke(previous.id());
        }
        jdbc.update("INSERT INTO fams_mini_binding (account_id, identity_hash, created_at) VALUES (?, ?, ?)",
                accountId, challenge.identityHash(), Timestamp.from(now()));
        Binding binding = activeBinding(accountId);
        if (binding == null) {
            throw new IllegalStateException("Binding insert failed");
        }
        jdbc.update("UPDATE fams_mini_binding_challenge SET state = 'COMPLETED', completed_binding_id = ?, "
                + "idempotency_key = ? WHERE challenge_hash = ?", binding.id(), idempotencyKey, hash);
        jdbc.update("UPDATE fams_mini_binding_challenge SET state = 'FAILED' WHERE account_id = ? "
                + "AND challenge_hash <> ? AND state IN ('PENDING', 'SENT')", accountId, hash);
        jdbc.update("UPDATE fams_mini_binding_limit SET failures = 0, locked_until = NULL WHERE subject_hash = ?",
                employeeLimit(accountId));
        audit(accountId, "REBIND".equals(challenge.action()) ? "MINI_BINDING_REPLACED" : "MINI_BINDING_CREATED",
                challenge.action(), requestId);
        return new Result(Long.toString(accountId), Long.toString(binding.id()), binding.version());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unbind(long actorId, long accountId, Long expectedBindingId, long version,
            String key, String reason, String verificationMethod, String miniToken, AuthSession pcSession,
            UUID requestId) {
        // The actor is reauthorized within the write transaction; Cookie snapshots are not sufficient.
        lockAccount(Math.min(actorId, accountId));
        if (actorId != accountId) {
            lockAccount(Math.max(actorId, accountId));
        }
        AuthAccount actor = auth.requireCurrentAccount(actorId);
        if (miniToken != null) {
            var current = sessions.requireCurrent(miniToken);
            if (current.account().id() != actorId || expectedBindingId == null
                    || Long.parseLong(current.bindingId()) != expectedBindingId) {
                throw AuthOperationException.sessionInvalid();
            }
        } else if (pcSession == null || pcSession.accountId() != actorId
                || !AuthSessionValidator.isCurrent(actor, pcSession.authEpoch(), pcSession.authorizationVersion(),
                        pcSession.absoluteExpiresAt(), clock.instant())) {
            throw AuthOperationException.sessionInvalid();
        }
        if (actor.status() != AccountStatus.ENABLED || (actorId != accountId
                && !actor.roles().contains(Role.SYSTEM_ADMIN))) {
            throw AuthOperationException.forbidden();
        }
        String digest = fingerprint.fingerprint("mini-unbind/v1", Long.toString(accountId),
                String.valueOf(expectedBindingId), Long.toString(version), reason, verificationMethod);
        List<String> replay = jdbc.queryForList("SELECT payload_hash FROM fams_mini_unbind_receipt "
                + "WHERE actor_id = ? AND idempotency_key = ? FOR UPDATE", String.class, actorId, key);
        if (!replay.isEmpty()) {
            if (!replay.getFirst().equals(digest)) {
                throw AuthOperationException.idempotencyConflict();
            }
            return;
        }
        Binding binding = activeBinding(accountId);
        if (binding == null || binding.version() != version
                || (expectedBindingId != null && binding.id() != expectedBindingId)) {
            throw AuthOperationException.versionConflict();
        }
        revoke(binding.id());
        jdbc.update("UPDATE fams_mini_binding_challenge SET state = 'FAILED' WHERE account_id = ? "
                + "AND state IN ('PENDING', 'SENT')", accountId);
        jdbc.update("INSERT INTO fams_mini_unbind_receipt (actor_id, idempotency_key, payload_hash, created_at) "
                + "VALUES (?, ?, ?, ?)", actorId, key, digest, Timestamp.from(now()));
        audit(accountId, "MINI_BINDING_REVOKED", "actor=" + actorId + ";method=" + verificationMethod
                + ";reason=" + reason, requestId);
    }

    private void revoke(long bindingId) {
        jdbc.update("UPDATE fams_mini_binding SET revoked_at = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(now()), bindingId);
        jdbc.update("UPDATE fams_mini_session SET revoked_at = ? WHERE binding_id = ? AND revoked_at IS NULL",
                Timestamp.from(now()), bindingId);
    }

    private long lockAccount(long accountId) {
        List<Long> rows = jdbc.queryForList("SELECT employee_id FROM fams_system_account WHERE id = ? FOR UPDATE",
                Long.class, accountId);
        if (rows.isEmpty()) {
            throw rejected();
        }
        return rows.getFirst();
    }

    private Binding activeBinding(long accountId) {
        List<Binding> rows = jdbc.query("SELECT id, version FROM fams_mini_binding "
                + "WHERE account_id = ? AND revoked_at IS NULL FOR UPDATE",
                (row, ignored) -> new Binding(row.getLong(1), row.getLong(2)), accountId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void checkIdentityOwner(long accountId, String identityHash, UUID requestId) {
        List<Long> owners = jdbc.queryForList("SELECT account_id FROM fams_mini_binding "
                + "WHERE identity_hash = ? AND revoked_at IS NULL", Long.class, identityHash);
        if (!owners.isEmpty() && owners.getFirst() != accountId) {
            audit(accountId, "MINI_BINDING_REJECTED", "IDENTITY_IN_USE", requestId);
            throw conflict();
        }
    }

    private void takeQuota(String subject, Long accountId, UUID requestId) {
        Limit limit = limit(subject);
        if ((limit.lockedUntil() != null && now().isBefore(limit.lockedUntil()))
                || limit.requests() >= 10 || (limit.lastRequest() != null
                && now().isBefore(limit.lastRequest().plusSeconds(60)))) {
            audit(accountId, "MINI_BINDING_RATE_LIMITED", "RATE_LIMIT", requestId);
            throw limited();
        }
        jdbc.update("UPDATE fams_mini_binding_limit SET requests = requests + 1, last_requested_at = ? "
                + "WHERE subject_hash = ?", Timestamp.from(now()), subject);
    }

    private Limit limit(String subject) {
        var day = now().atOffset(ZoneOffset.UTC).toLocalDate();
        jdbc.update("INSERT INTO fams_mini_binding_limit (subject_hash, quota_day) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE subject_hash = subject_hash", subject, day);
        jdbc.queryForObject("SELECT subject_hash FROM fams_mini_binding_limit WHERE subject_hash = ? FOR UPDATE",
                String.class, subject);
        jdbc.update("UPDATE fams_mini_binding_limit SET requests = 0, quota_day = ? "
                + "WHERE subject_hash = ? AND quota_day <> ?", day, subject, day);
        return jdbc.queryForObject("SELECT requests, last_requested_at, failures, locked_until "
                + "FROM fams_mini_binding_limit WHERE subject_hash = ?", (row, ignored) -> new Limit(
                row.getInt(1), instant(row.getTimestamp(2)), row.getInt(3), instant(row.getTimestamp(4))), subject);
    }

    private void fail(long accountId, Limit limit, UUID requestId) {
        int count = limit.lockedUntil() != null && !now().isBefore(limit.lockedUntil()) ? 1 : limit.failures() + 1;
        jdbc.update("UPDATE fams_mini_binding_limit SET failures = ?, locked_until = ? WHERE subject_hash = ?",
                count, count >= 5 ? Timestamp.from(now().plusSeconds(1800)) : null, employeeLimit(accountId));
        audit(accountId, count >= 5 ? "MINI_BINDING_LOCKED" : "MINI_BINDING_REJECTED", "PROOF_INVALID", requestId);
    }

    private String employeeLimit(long accountId) {
        return fingerprint.fingerprint("mini-employee-limit/v1", Long.toString(accountId));
    }

    private String challengeHash(String token) {
        return fingerprint.fingerprint("mini-challenge/v1", token);
    }

    private String otpHash(String hash, String otp) {
        return fingerprint.fingerprint("mini-otp/v1", hash, otp);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private void audit(Long accountId, String type, String detail, UUID requestId) {
        // Encode detail with the JSON mapper rather than accepting user JSON or logging OTP/phone.
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("reason", detail));
        jdbc.update("INSERT INTO fams_security_event (account_id, event_type, request_id, occurred_at, detail_json) "
                + "VALUES (?, ?, ?, ?, ?)", accountId, type, requestId.toString(), Timestamp.from(now()), json);
    }

    private AuthOperationException rejected() {
        return new AuthOperationException("MINI_BINDING_PROOF_INVALID", "Binding verification failed.", 400);
    }

    private AuthOperationException limited() {
        return new AuthOperationException("MINI_BINDING_RATE_LIMITED", "Try again later.", 429);
    }

    private AuthOperationException conflict() {
        return new AuthOperationException("MINI_BINDING_CONFLICT", "The binding changed or is already in use.", 409);
    }

    record Delivery(String challengeToken, String phone, String otp, Instant expiresAt) { }
    record Result(String accountId, String bindingId, long bindingVersion) { }
    private record Binding(long id, long version) { }
    private record Limit(int requests, Instant lastRequest, int failures, Instant lockedUntil) { }
    private record Challenge(long employeeVersion, String identityHash, String deviceHash, String otpHash,
            String action, String state, Instant expiresAt, Long completedId, String idempotencyKey) { }
}
