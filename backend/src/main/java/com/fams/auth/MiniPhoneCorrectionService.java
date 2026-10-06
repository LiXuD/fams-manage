package com.fams.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("fams-product")
class MiniPhoneCorrectionService {
    private final JdbcTemplate jdbc;
    private final MiniPhoneCorrectionPort employees;
    private final AuthApplicationService auth;
    private final AuthPayloadFingerprint fingerprint;
    private final Clock clock;
    private final ObjectMapper json;

    MiniPhoneCorrectionService(JdbcTemplate jdbc, MiniPhoneCorrectionPort employees, AuthApplicationService auth,
            AuthPayloadFingerprint fingerprint, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.auth = auth;
        this.fingerprint = fingerprint;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long correct(long actorId, long employeeId, long version, String phone, String method,
            String reason, String key, AuthSession session, UUID requestId) {
        jdbc.queryForObject("SELECT id FROM fams_system_account WHERE id = ? FOR UPDATE", Long.class, actorId);
        AuthAccount actor = auth.requireCurrentAccount(actorId);
        if (session == null || session.accountId() != actorId
                || !AuthSessionValidator.isCurrent(actor, session.authEpoch(), session.authorizationVersion(),
                        session.absoluteExpiresAt(), clock.instant())) {
            throw AuthOperationException.sessionInvalid();
        }
        if (actor.status() != AccountStatus.ENABLED || !actor.roles().contains(Role.SYSTEM_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        String digest = fingerprint.fingerprint("mini-phone-correction/v1", Long.toString(employeeId),
                Long.toString(version), phone, method, reason);
        List<Receipt> receipts = jdbc.query("SELECT payload_hash, result_version FROM fams_mini_phone_receipt "
                + "WHERE actor_id = ? AND idempotency_key = ? FOR UPDATE",
                (row, ignored) -> new Receipt(row.getString(1), row.getLong(2)), actorId, key);
        if (!receipts.isEmpty()) {
            Receipt receipt = receipts.getFirst();
            if (!receipt.digest().equals(digest)) {
                throw AuthOperationException.idempotencyConflict();
            }
            return receipt.version();
        }
        MiniPhoneCorrectionPort.Change change = employees.correct(employeeId, version, phone, actorId, clock.instant())
                .orElseThrow(AuthOperationException::versionConflict);
        // Bound challenges retain their pre-correction version, even if the old phone is later restored.
        jdbc.update("UPDATE fams_mini_binding_challenge c JOIN fams_system_account a ON a.id = c.account_id "
                + "SET c.state = 'FAILED' WHERE a.employee_id = ? AND c.state IN ('PENDING', 'SENT')", employeeId);
        String detail = json.writeValueAsString(Map.of("employeeId", Long.toString(employeeId),
                "verificationMethod", method, "reason", reason, "beforeEncrypted", change.previousCiphertext(),
                "afterEncrypted", change.updatedCiphertext()));
        jdbc.update("INSERT INTO fams_security_event (account_id, event_type, request_id, occurred_at, detail_json) "
                + "VALUES (?, 'MINI_PHONE_CORRECTED', ?, ?, ?)", actorId, requestId.toString(),
                Timestamp.from(clock.instant()), detail);
        jdbc.update("INSERT INTO fams_mini_phone_receipt (actor_id, idempotency_key, payload_hash, result_version) "
                + "VALUES (?, ?, ?, ?)", actorId, key, digest, change.version());
        return change.version();
    }

    private record Receipt(String digest, long version) { }
}
