package com.fams.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Profile("fams-product")
class MiniSessionService {

    private final JdbcTemplate jdbc;
    private final AuthApplicationService auth;
    private final AuthPayloadFingerprint fingerprint;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    MiniSessionService(JdbcTemplate jdbc, AuthApplicationService auth,
            AuthPayloadFingerprint fingerprint, Clock clock) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    String identityHash(MiniWechatIdentityPort.Identity identity) {
        return fingerprint.fingerprint("mini-identity/v1", identity.appId(), identity.openId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Grant start(MiniWechatIdentityPort.Identity identity, UUID requestId) {
        String identityHash = identityHash(identity);
        List<Binding> bindings = bindings("identity_hash = ? AND revoked_at IS NULL", identityHash);
        if (bindings.isEmpty()) {
            return new Grant("UNBOUND", null, null, null, null, null);
        }
        Binding candidate = bindings.getFirst();
        lockAccount(candidate.accountId());
        Binding binding = requireBinding(candidate.id());
        if (!binding.identityHash().equals(identityHash)) {
            throw AuthOperationException.sessionInvalid();
        }
        AuthAccount account = auth.requireCurrentAccount(binding.accountId());
        if (account.status() != AccountStatus.ENABLED) {
            throw AuthOperationException.sessionInvalid();
        }
        Instant now = now();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant idle = now.plusSeconds(1800);
        Instant absolute = now.plusSeconds(8 * 60 * 60);
        jdbc.update("""
                INSERT INTO fams_mini_session
                (token_hash, binding_id, binding_version, account_id, auth_epoch,
                 authorization_version, idle_expires_at, absolute_expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tokenHash(token), binding.id(), binding.version(), account.id(), account.authEpoch(),
                account.authorizationVersion(), Timestamp.from(idle), Timestamp.from(absolute), Timestamp.from(now));
        audit(account.id(), "MINI_SESSION_CREATED", requestId);
        return new Grant("BOUND", token, Long.toString(account.id()), Long.toString(binding.id()), idle, absolute);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Current requireCurrent(String token) {
        String hash = checkedTokenHash(token);
        Session candidate = findSession(hash, false);
        lockAccount(candidate.accountId());
        Binding binding = requireBinding(candidate.bindingId());
        Session session = findSession(hash, true);
        AuthAccount account = auth.requireCurrentAccount(session.accountId());
        Instant now = now();
        if (binding.accountId() != account.id() || binding.version() != session.bindingVersion()
                || session.revoked() || !now.isBefore(session.idleExpiresAt())
                || !AuthSessionValidator.isCurrent(account, session.authEpoch(),
                        session.authorizationVersion(), session.absoluteExpiresAt(), now)) {
            throw AuthOperationException.sessionInvalid();
        }
        Instant idle = now.plusSeconds(1800);
        if (idle.isAfter(session.absoluteExpiresAt())) {
            idle = session.absoluteExpiresAt();
        }
        jdbc.update("UPDATE fams_mini_session SET idle_expires_at = ? WHERE token_hash = ?",
                Timestamp.from(idle), hash);
        return new Current(account, Long.toString(binding.id()), binding.version(), idle, session.absoluteExpiresAt());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void logout(String token, UUID requestId) {
        Current current = requireCurrent(token);
        jdbc.update("UPDATE fams_mini_session SET revoked_at = ? WHERE token_hash = ?",
                Timestamp.from(now()), checkedTokenHash(token));
        audit(current.account().id(), "MINI_SESSION_REVOKED", requestId);
    }

    private void lockAccount(long id) {
        if (jdbc.queryForList("SELECT id FROM fams_system_account WHERE id = ? FOR UPDATE", Long.class, id).isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
    }

    private Binding requireBinding(long id) {
        List<Binding> rows = bindings("id = ? AND revoked_at IS NULL FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        return rows.getFirst();
    }

    private List<Binding> bindings(String predicate, Object argument) {
        return jdbc.query("SELECT id, account_id, identity_hash, version FROM fams_mini_binding WHERE " + predicate,
                (row, ignored) -> new Binding(row.getLong(1), row.getLong(2), row.getString(3), row.getLong(4)), argument);
    }

    private Session findSession(String hash, boolean lock) {
        List<Session> rows = jdbc.query("""
                SELECT account_id, binding_id, binding_version, auth_epoch, authorization_version,
                       idle_expires_at, absolute_expires_at, revoked_at
                FROM fams_mini_session WHERE token_hash = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new Session(
                row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5),
                row.getTimestamp(6).toInstant(), row.getTimestamp(7).toInstant(), row.getTimestamp(8) != null), hash);
        if (rows.isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        return rows.getFirst();
    }

    private String checkedTokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw AuthOperationException.sessionInvalid();
        }
        return tokenHash(token);
    }

    private String tokenHash(String token) {
        return fingerprint.fingerprint("mini-session/v1", token);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void audit(long accountId, String event, UUID requestId) {
        jdbc.update("""
                INSERT INTO fams_security_event (account_id, event_type, request_id, occurred_at, detail_json)
                VALUES (?, ?, ?, ?, '{}')
                """, accountId, event, requestId.toString(), Timestamp.from(now()));
    }

    private record Binding(long id, long accountId, String identityHash, long version) { }

    private record Session(long accountId, long bindingId, long bindingVersion, long authEpoch,
            long authorizationVersion, Instant idleExpiresAt, Instant absoluteExpiresAt, boolean revoked) { }

    record Grant(String bindingStatus, String sessionToken, String accountId, String bindingId,
            Instant idleExpiresAt, Instant absoluteExpiresAt) { }

    record Current(AuthAccount account, String bindingId, long bindingVersion,
            Instant idleExpiresAt, Instant absoluteExpiresAt) { }
}
