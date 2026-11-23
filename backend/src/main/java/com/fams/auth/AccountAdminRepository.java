package com.fams.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the AccountAdminMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class AccountAdminRepository {

    private final AccountAdminMapper mapper;

    AccountAdminRepository(AccountAdminMapper mapper) {
        this.mapper = mapper;
    }

    long countAccounts() {
        return mapper.countAccounts();
    }

    List<SystemAccountAdminRow> listAccounts(int offset, int limit) {
        return mapper.listAccounts(offset, limit);
    }

    SystemAccountAdminRow findAccount(long accountId) {
        return mapper.findAccount(accountId);
    }

    EmployeeAdminRow findEmployee(long employeeId) {
        return mapper.findEmployee(employeeId);
    }

    int countByNormalizedUsername(String username) {
        return mapper.countByNormalizedUsername(username);
    }

    int countByEmployeeId(long employeeId) {
        return mapper.countByEmployeeId(employeeId);
    }

    int insertAccount(NewSystemAccountRow account) {
        return mapper.insertAccount(account);
    }

    int updateStatus(long accountId, long expectedVersion, AccountStatus status,
            long actorAccountId, Instant now) {
        return mapper.updateStatus(accountId, expectedVersion, status, actorAccountId, now);
    }

    int resetInitialPassword(long accountId, long expectedVersion, String passwordHash,
            long actorAccountId, Instant now) {
        return mapper.resetInitialPassword(accountId, expectedVersion, passwordHash, actorAccountId, now);
    }

    int countRole(long accountId, Role role) {
        return mapper.countRole(accountId, role);
    }

    int insertRole(long accountId, Role role, long actorAccountId, Instant now) {
        return mapper.insertRole(accountId, role, actorAccountId, now);
    }

    int deleteRole(long accountId, Role role) {
        return mapper.deleteRole(accountId, role);
    }

    int advanceRoleVersion(long accountId, long expectedVersion, long actorAccountId, Instant now) {
        return mapper.advanceRoleVersion(accountId, expectedVersion, actorAccountId, now);
    }

    AdminIdempotencyRow findIdempotency(long actorAccountId, String operationId, String idempotencyKey) {
        return mapper.findIdempotency(actorAccountId, operationId, idempotencyKey);
    }

    int insertIdempotency(long actorAccountId, String operationId, String idempotencyKey,
            String payloadFingerprint, int httpStatus, String responseBodyJson, Long resourceId,
            Long resourceVersion, Instant now, Instant expiresAt) {
        return mapper.insertIdempotency(actorAccountId, operationId, idempotencyKey, payloadFingerprint,
                httpStatus, responseBodyJson, resourceId, resourceVersion, now, expiresAt);
    }

    AuthorizationAdminRow findCurrentAuthorization(long accountId, Role role) {
        return mapper.findCurrentAuthorization(accountId, role);
    }

    List<AuthorizationScopeRow> findScopes(long authorizationId) {
        return mapper.findScopes(authorizationId);
    }

    List<VersionedReferenceRow> findOrganizationReferences(long scopeId) {
        return mapper.findOrganizationReferences(scopeId);
    }

    List<VersionedReferenceRow> findWarehouseReferences(long scopeId) {
        return mapper.findWarehouseReferences(scopeId);
    }

    List<String> findFieldPermissions(long authorizationId) {
        return mapper.findFieldPermissions(authorizationId);
    }

    int countCurrentOrganization(long referenceId, long version) {
        return mapper.countCurrentOrganization(referenceId, version);
    }

    int countCurrentWarehouse(long referenceId, long version) {
        return mapper.countCurrentWarehouse(referenceId, version);
    }

    int supersedeAuthorization(long authorizationId, Instant now) {
        return mapper.supersedeAuthorization(authorizationId, now);
    }

    int insertAuthorization(NewAuthorizationRow authorization) {
        return mapper.insertAuthorization(authorization);
    }

    int insertDataScope(NewDataScopeRow scope) {
        return mapper.insertDataScope(scope);
    }

    int insertOrganizationReference(long scopeId, long referenceId, long version) {
        return mapper.insertOrganizationReference(scopeId, referenceId, version);
    }

    int insertWarehouseReference(long scopeId, long referenceId, long version) {
        return mapper.insertWarehouseReference(scopeId, referenceId, version);
    }

    int insertFieldPermission(long authorizationId, String permissionCode, Instant now) {
        return mapper.insertFieldPermission(authorizationId, permissionCode, now);
    }

    int advanceAuthorizationEpoch(long accountId, long actorAccountId, Instant now) {
        return mapper.advanceAuthorizationEpoch(accountId, actorAccountId, now);
    }
}
