package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Shared repository boundary for the IdentityMasterDataMapper cross-aggregate read/write surface:
 * organization and employee master data plus the idempotency and audit writes used by every
 * masterdata service. Pass-through persistence calls only.
 */
@Repository
@Profile("fams-product")
class IdentityMasterDataRepository {

    private final IdentityMasterDataMapper mapper;

    IdentityMasterDataRepository(IdentityMasterDataMapper mapper) {
        this.mapper = mapper;
    }

    List<OrganizationRow> listOrganizations(int offset, int limit) {
        return mapper.listOrganizations(offset, limit);
    }

    long countOrganizations() {
        return mapper.countOrganizations();
    }

    OrganizationRow findOrganization(long id) {
        return mapper.findOrganization(id);
    }

    OrganizationRow findOrganizationByCode(String normalizedCode) {
        return mapper.findOrganizationByCode(normalizedCode);
    }

    int insertOrganization(NewOrganizationRow row) {
        return mapper.insertOrganization(row);
    }

    int updateOrganization(long id, long expectedVersion, String code, String normalizedCode,
            String name, Long parentId, long actorId, Instant now) {
        return mapper.updateOrganization(id, expectedVersion, code, normalizedCode, name,
                parentId, actorId, now);
    }

    int disableOrganization(long id, long expectedVersion, long actorId, Instant now) {
        return mapper.disableOrganization(id, expectedVersion, actorId, now);
    }

    int countActiveChildOrganizations(long organizationId) {
        return mapper.countActiveChildOrganizations(organizationId);
    }

    int countActiveEmployees(long organizationId) {
        return mapper.countActiveEmployees(organizationId);
    }

    int countActiveAuthorizationScopes(long organizationId) {
        return mapper.countActiveAuthorizationScopes(organizationId);
    }

    int countOrganizationCycle(long organizationId, long parentId) {
        return mapper.countOrganizationCycle(organizationId, parentId);
    }

    List<EmployeeRow> listEmployees(int offset, int limit) {
        return mapper.listEmployees(offset, limit);
    }

    long countEmployees() {
        return mapper.countEmployees();
    }

    EmployeeRow findEmployee(long id) {
        return mapper.findEmployee(id);
    }

    EmployeeRow findEmployeeByNumber(String normalizedEmployeeNo) {
        return mapper.findEmployeeByNumber(normalizedEmployeeNo);
    }

    int insertEmployee(NewEmployeeRow row) {
        return mapper.insertEmployee(row);
    }

    int updateEmployee(long id, long expectedVersion, String employeeNo,
            String normalizedEmployeeNo, String name, long organizationId,
            byte[] phoneCiphertext, byte[] phoneLookupHash, long actorId, Instant now) {
        return mapper.updateEmployee(id, expectedVersion, employeeNo, normalizedEmployeeNo, name,
                organizationId, phoneCiphertext, phoneLookupHash, actorId, now);
    }

    int changeEmployeeStatus(long id, long expectedVersion, String status, long actorId,
            Instant now) {
        return mapper.changeEmployeeStatus(id, expectedVersion, status, actorId, now);
    }

    int disableEmployeeAccount(long employeeId, long actorId, Instant now) {
        return mapper.disableEmployeeAccount(employeeId, actorId, now);
    }

    IdempotencyRow findIdempotency(long actorId, String operationId, String idempotencyKey,
            Instant now) {
        return mapper.findIdempotency(actorId, operationId, idempotencyKey, now);
    }

    int insertIdempotency(long actorId, String operationId, String idempotencyKey,
            String fingerprint, int httpStatus, String responseJson, String resourceType,
            Long resourceId, Long resourceVersion, Instant now, Instant expiresAt) {
        return mapper.insertIdempotency(actorId, operationId, idempotencyKey, fingerprint,
                httpStatus, responseJson, resourceType, resourceId, resourceVersion, now,
                expiresAt);
    }

    int insertAudit(long actorId, String requestId, Instant now, String detailJson) {
        return mapper.insertAudit(actorId, requestId, now, detailJson);
    }
}
