package com.fams.auth;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AccountAdminMapper {

    @Select("SELECT COUNT(*) FROM fams_system_account")
    long countAccounts();

    @Select("""
            SELECT id, username, employee_id, status, version, initial_password_required,
                   failed_attempts, created_at, updated_at
            FROM fams_system_account
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "username", javaType = String.class),
        @Arg(column = "employee_id", javaType = long.class),
        @Arg(column = "status", javaType = AccountStatus.class),
        @Arg(column = "version", javaType = long.class),
        @Arg(column = "initial_password_required", javaType = boolean.class),
        @Arg(column = "failed_attempts", javaType = int.class),
        @Arg(column = "created_at", javaType = Instant.class),
        @Arg(column = "updated_at", javaType = Instant.class)
    })
    List<SystemAccountAdminRow> listAccounts(
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("""
            SELECT id, username, employee_id, status, version, initial_password_required,
                   failed_attempts, created_at, updated_at
            FROM fams_system_account
            WHERE id = #{accountId}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "username", javaType = String.class),
        @Arg(column = "employee_id", javaType = long.class),
        @Arg(column = "status", javaType = AccountStatus.class),
        @Arg(column = "version", javaType = long.class),
        @Arg(column = "initial_password_required", javaType = boolean.class),
        @Arg(column = "failed_attempts", javaType = int.class),
        @Arg(column = "created_at", javaType = Instant.class),
        @Arg(column = "updated_at", javaType = Instant.class)
    })
    SystemAccountAdminRow findAccount(long accountId);

    @Select("SELECT id, status, version FROM fams_employee WHERE id = #{employeeId}")
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "version", javaType = long.class)
    })
    EmployeeAdminRow findEmployee(long employeeId);

    @Select("SELECT COUNT(*) FROM fams_system_account WHERE username_normalized = #{username}")
    int countByNormalizedUsername(String username);

    @Select("SELECT COUNT(*) FROM fams_system_account WHERE employee_id = #{employeeId}")
    int countByEmployeeId(long employeeId);

    @Insert("""
            INSERT INTO fams_system_account
                (employee_id, username, username_normalized, password_hash, status,
                 initial_password_required, failed_attempts, auth_epoch, authorization_version,
                 version, created_at, updated_at, created_by, updated_by)
            VALUES
                (#{employeeId}, #{username}, #{normalizedUsername}, #{passwordHash}, 'ENABLED',
                 TRUE, 0, 0, 0, 0, #{now}, #{now}, #{actorAccountId}, #{actorAccountId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAccount(NewSystemAccountRow account);

    @Update("""
            UPDATE fams_system_account
            SET status = #{status}, failed_attempts = 0, locked_until = NULL,
                auth_epoch = auth_epoch + 1, version = version + 1,
                updated_at = #{now}, updated_by = #{actorAccountId}
            WHERE id = #{accountId} AND version = #{expectedVersion}
            """)
    int updateStatus(
            @Param("accountId") long accountId,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") AccountStatus status,
            @Param("actorAccountId") long actorAccountId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_system_account
            SET password_hash = #{passwordHash}, initial_password_required = TRUE,
                failed_attempts = 0, locked_until = NULL, auth_epoch = auth_epoch + 1,
                version = version + 1, updated_at = #{now}, updated_by = #{actorAccountId}
            WHERE id = #{accountId} AND version = #{expectedVersion}
            """)
    int resetInitialPassword(
            @Param("accountId") long accountId,
            @Param("expectedVersion") long expectedVersion,
            @Param("passwordHash") String passwordHash,
            @Param("actorAccountId") long actorAccountId,
            @Param("now") Instant now);

    @Select("""
            SELECT COUNT(*) FROM fams_account_role
            WHERE account_id = #{accountId} AND role_code = #{role}
            """)
    int countRole(@Param("accountId") long accountId, @Param("role") Role role);

    @Insert("""
            INSERT INTO fams_account_role (account_id, role_code, granted_at, granted_by)
            VALUES (#{accountId}, #{role}, #{now}, #{actorAccountId})
            """)
    int insertRole(
            @Param("accountId") long accountId,
            @Param("role") Role role,
            @Param("actorAccountId") long actorAccountId,
            @Param("now") Instant now);

    @Update("""
            DELETE FROM fams_account_role
            WHERE account_id = #{accountId} AND role_code = #{role}
            """)
    int deleteRole(@Param("accountId") long accountId, @Param("role") Role role);

    @Update("""
            UPDATE fams_system_account
            SET auth_epoch = auth_epoch + 1,
                authorization_version = authorization_version + 1,
                version = version + 1, updated_at = #{now}, updated_by = #{actorAccountId}
            WHERE id = #{accountId} AND version = #{expectedVersion}
            """)
    int advanceRoleVersion(
            @Param("accountId") long accountId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorAccountId") long actorAccountId,
            @Param("now") Instant now);

    @Select("""
            SELECT payload_sha256, http_status, response_body_json, resource_id, resource_version
            FROM fams_idempotency_record
            WHERE actor_account_id = #{actorAccountId}
              AND operation_id = #{operationId}
              AND idempotency_key = #{idempotencyKey}
              AND expires_at > UTC_TIMESTAMP(3)
            """)
    @ConstructorArgs({
        @Arg(column = "payload_sha256", javaType = String.class),
        @Arg(column = "http_status", javaType = int.class),
        @Arg(column = "response_body_json", javaType = String.class),
        @Arg(column = "resource_id", javaType = Long.class),
        @Arg(column = "resource_version", javaType = Long.class)
    })
    AdminIdempotencyRow findIdempotency(
            @Param("actorAccountId") long actorAccountId,
            @Param("operationId") String operationId,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO fams_idempotency_record
                (actor_account_id, operation_id, idempotency_key, payload_sha256,
                 result_status, http_status, response_body_json, resource_type,
                 resource_id, resource_version, created_at, completed_at, expires_at)
            VALUES
                (#{actorAccountId}, #{operationId}, #{idempotencyKey}, #{payloadFingerprint},
                 'SUCCEEDED', #{httpStatus}, #{responseBodyJson}, 'SYSTEM_ACCOUNT',
                 #{resourceId}, #{resourceVersion}, #{now}, #{now}, #{expiresAt})
            """)
    int insertIdempotency(
            @Param("actorAccountId") long actorAccountId,
            @Param("operationId") String operationId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("payloadFingerprint") String payloadFingerprint,
            @Param("httpStatus") int httpStatus,
            @Param("responseBodyJson") String responseBodyJson,
            @Param("resourceId") Long resourceId,
            @Param("resourceVersion") Long resourceVersion,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT id, account_id, role_code, authorization_status, generation_no, created_at
            FROM fams_account_authorization
            WHERE account_id = #{accountId} AND role_code = #{role} AND superseded_at IS NULL
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "account_id", javaType = long.class),
        @Arg(column = "role_code", javaType = Role.class),
        @Arg(column = "authorization_status", javaType = String.class),
        @Arg(column = "generation_no", javaType = long.class),
        @Arg(column = "created_at", javaType = Instant.class)
    })
    AuthorizationAdminRow findCurrentAuthorization(
            @Param("accountId") long accountId,
            @Param("role") Role role);

    @Select("""
            SELECT id, scope_type
            FROM fams_account_data_scope
            WHERE authorization_id = #{authorizationId}
            ORDER BY scope_type
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "scope_type", javaType = DataScope.class)
    })
    List<AuthorizationScopeRow> findScopes(long authorizationId);

    @Select("""
            SELECT organization_id AS id, organization_version_at_grant AS version
            FROM fams_account_scope_organization
            WHERE data_scope_id = #{scopeId}
            ORDER BY organization_id
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "version", javaType = long.class)
    })
    List<VersionedReferenceRow> findOrganizationReferences(long scopeId);

    @Select("""
            SELECT warehouse_id AS id, warehouse_version_at_grant AS version
            FROM fams_account_scope_warehouse
            WHERE data_scope_id = #{scopeId}
            ORDER BY warehouse_id
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "version", javaType = long.class)
    })
    List<VersionedReferenceRow> findWarehouseReferences(long scopeId);

    @Select("""
            SELECT permission_code
            FROM fams_account_field_permission
            WHERE authorization_id = #{authorizationId}
            ORDER BY permission_code
            """)
    List<String> findFieldPermissions(long authorizationId);

    @Select("""
            SELECT authorization.id, authorization.account_id, authorization.role_code,
                   authorization.authorization_status, authorization.generation_no,
                   authorization.created_at
            FROM fams_account_authorization authorization
            JOIN fams_account_role role
              ON role.account_id = authorization.account_id
             AND role.role_code = authorization.role_code
            WHERE authorization.account_id = #{accountId}
              AND authorization.superseded_at IS NULL
              AND authorization.authorization_status = 'ACTIVE'
            ORDER BY authorization.role_code
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "account_id", javaType = long.class),
        @Arg(column = "role_code", javaType = Role.class),
        @Arg(column = "authorization_status", javaType = String.class),
        @Arg(column = "generation_no", javaType = long.class),
        @Arg(column = "created_at", javaType = Instant.class)
    })
    List<AuthorizationAdminRow> findActiveAuthorizations(long accountId);

    @Select("""
            SELECT COUNT(*) FROM fams_organization
            WHERE id = #{referenceId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int countCurrentOrganization(
            @Param("referenceId") long referenceId,
            @Param("version") long version);

    @Select("""
            SELECT COUNT(*) FROM fams_warehouse
            WHERE id = #{referenceId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int countCurrentWarehouse(
            @Param("referenceId") long referenceId,
            @Param("version") long version);

    @Update("""
            UPDATE fams_account_authorization
            SET superseded_at = #{now}
            WHERE id = #{authorizationId} AND superseded_at IS NULL
            """)
    int supersedeAuthorization(
            @Param("authorizationId") long authorizationId,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO fams_account_authorization
                (account_id, role_code, generation_no, authorization_status,
                 predecessor_authorization_id, changed_by, change_reason, created_at)
            VALUES
                (#{accountId}, #{role}, #{generation}, #{status},
                 #{predecessorId}, #{actorAccountId}, #{reason}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAuthorization(NewAuthorizationRow authorization);

    @Insert("""
            INSERT INTO fams_account_data_scope (authorization_id, scope_type)
            VALUES (#{authorizationId}, #{scopeType})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDataScope(NewDataScopeRow scope);

    @Insert("""
            INSERT INTO fams_account_scope_organization
                (data_scope_id, organization_id, organization_version_at_grant)
            VALUES (#{scopeId}, #{referenceId}, #{version})
            """)
    int insertOrganizationReference(
            @Param("scopeId") long scopeId,
            @Param("referenceId") long referenceId,
            @Param("version") long version);

    @Insert("""
            INSERT INTO fams_account_scope_warehouse
                (data_scope_id, warehouse_id, warehouse_version_at_grant)
            VALUES (#{scopeId}, #{referenceId}, #{version})
            """)
    int insertWarehouseReference(
            @Param("scopeId") long scopeId,
            @Param("referenceId") long referenceId,
            @Param("version") long version);

    @Insert("""
            INSERT INTO fams_account_field_permission
                (authorization_id, permission_code, granted_at)
            VALUES (#{authorizationId}, #{permissionCode}, #{now})
            """)
    int insertFieldPermission(
            @Param("authorizationId") long authorizationId,
            @Param("permissionCode") String permissionCode,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_system_account
            SET auth_epoch = auth_epoch + 1,
                authorization_version = authorization_version + 1,
                updated_at = #{now}, updated_by = #{actorAccountId}
            WHERE id = #{accountId}
            """)
    int advanceAuthorizationEpoch(
            @Param("accountId") long accountId,
            @Param("actorAccountId") long actorAccountId,
            @Param("now") Instant now);
}
