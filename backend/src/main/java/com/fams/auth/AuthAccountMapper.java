package com.fams.auth;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AuthAccountMapper {

    @Select("""
            SELECT account.id, account.username, account.password_hash,
                   CASE WHEN employee.status = 'ACTIVE' THEN account.status ELSE 'DISABLED' END AS status,
                   account.initial_password_required, account.failed_attempts, account.locked_until,
                   account.auth_epoch, account.authorization_version
            FROM fams_system_account account
            JOIN fams_employee employee ON employee.id = account.employee_id
            WHERE account.username_normalized = #{normalizedUsername}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "username", javaType = String.class),
        @Arg(column = "password_hash", javaType = String.class),
        @Arg(column = "status", javaType = AccountStatus.class),
        @Arg(column = "initial_password_required", javaType = boolean.class),
        @Arg(column = "failed_attempts", javaType = int.class),
        @Arg(column = "locked_until", javaType = Instant.class),
        @Arg(column = "auth_epoch", javaType = long.class),
        @Arg(column = "authorization_version", javaType = long.class)
    })
    AuthAccountRow findByNormalizedUsername(String normalizedUsername);

    @Select("""
            SELECT account.id, account.username, account.password_hash,
                   CASE WHEN employee.status = 'ACTIVE' THEN account.status ELSE 'DISABLED' END AS status,
                   account.initial_password_required, account.failed_attempts, account.locked_until,
                   account.auth_epoch, account.authorization_version
            FROM fams_system_account account
            JOIN fams_employee employee ON employee.id = account.employee_id
            WHERE account.id = #{accountId}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "username", javaType = String.class),
        @Arg(column = "password_hash", javaType = String.class),
        @Arg(column = "status", javaType = AccountStatus.class),
        @Arg(column = "initial_password_required", javaType = boolean.class),
        @Arg(column = "failed_attempts", javaType = int.class),
        @Arg(column = "locked_until", javaType = Instant.class),
        @Arg(column = "auth_epoch", javaType = long.class),
        @Arg(column = "authorization_version", javaType = long.class)
    })
    AuthAccountRow findById(long accountId);

    @Select("""
            SELECT role_code
            FROM fams_account_role
            WHERE account_id = #{accountId}
            ORDER BY role_code
            """)
    List<Role> findRoles(long accountId);

    @Update("""
            UPDATE fams_system_account
            SET failed_attempts = failed_attempts + 1,
                status = CASE WHEN #{lockedUntil} IS NULL THEN status ELSE 'LOCKED' END,
                locked_until = #{lockedUntil},
                version = version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{accountId}
            """)
    int recordFailedLogin(
            @Param("accountId") long accountId,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE fams_system_account
            SET failed_attempts = 0,
                status = CASE WHEN status = 'LOCKED' THEN 'ENABLED' ELSE status END,
                locked_until = NULL,
                version = version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{accountId}
            """)
    int clearFailedLogins(
            @Param("accountId") long accountId,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE fams_system_account
            SET password_hash = #{passwordHash},
                initial_password_required = FALSE,
                failed_attempts = 0,
                locked_until = NULL,
                auth_epoch = auth_epoch + 1,
                version = version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{accountId}
              AND auth_epoch = #{expectedAuthEpoch}
              AND status = 'ENABLED'
              AND initial_password_required = TRUE
            """)
    int changeInitialPassword(
            @Param("accountId") long accountId,
            @Param("expectedAuthEpoch") long expectedAuthEpoch,
            @Param("passwordHash") String passwordHash,
            @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO fams_security_event
                (account_id, event_type, request_id, occurred_at, detail_json)
            VALUES
                (#{accountId}, #{eventType}, #{requestId}, #{occurredAt}, #{detailJson})
            """)
    int insertSecurityEvent(
            @Param("accountId") Long accountId,
            @Param("eventType") String eventType,
            @Param("requestId") String requestId,
            @Param("occurredAt") Instant occurredAt,
            @Param("detailJson") String detailJson);

    @Select("""
            SELECT actor_account_id, operation_id, idempotency_key, payload_sha256,
                   resource_version, created_at, expires_at
            FROM fams_idempotency_record
            WHERE actor_account_id = #{accountId}
              AND operation_id = #{operationId}
              AND idempotency_key = #{idempotencyKey}
              AND expires_at > UTC_TIMESTAMP(3)
            """)
    @ConstructorArgs({
        @Arg(column = "actor_account_id", javaType = long.class),
        @Arg(column = "operation_id", javaType = String.class),
        @Arg(column = "idempotency_key", javaType = String.class),
        @Arg(column = "payload_sha256", javaType = String.class),
        @Arg(column = "resource_version", javaType = long.class),
        @Arg(column = "created_at", javaType = Instant.class),
        @Arg(column = "expires_at", javaType = Instant.class)
    })
    AuthIdempotencyRecord findIdempotency(
            @Param("accountId") long accountId,
            @Param("operationId") String operationId,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO fams_idempotency_record
                (actor_account_id, operation_id, idempotency_key, payload_sha256,
                 result_status, http_status, response_body_json, resource_type,
                 resource_id, resource_version, created_at, completed_at, expires_at)
            VALUES
                (#{accountId}, #{operationId}, #{idempotencyKey}, #{payloadFingerprint},
                 'SUCCEEDED', 200, '{"outcome":"PASSWORD_CHANGED"}', 'SYSTEM_ACCOUNT',
                 #{accountId}, #{resultAuthEpoch}, #{createdAt}, #{createdAt}, #{expiresAt})
            """)
    int insertIdempotency(AuthIdempotencyRecord record);
}
