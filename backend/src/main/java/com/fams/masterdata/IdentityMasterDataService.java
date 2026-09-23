package com.fams.masterdata;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fams.product.contract.model.Employee;
import com.fams.product.contract.model.EmployeeCreateRequest;
import com.fams.product.contract.model.EmployeeUpdateRequest;
import com.fams.product.contract.model.Organization;
import com.fams.product.contract.model.OrganizationActiveReference;
import com.fams.product.contract.model.OrganizationActiveReferencePage;
import com.fams.product.contract.model.OrganizationCreateRequest;
import com.fams.product.contract.model.OrganizationReference;
import com.fams.product.contract.model.OrganizationUpdateRequest;
import com.fams.product.contract.model.PageEmployee;
import com.fams.product.contract.model.PageOrganization;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class IdentityMasterDataService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final IdentityMasterDataMapper mapper;
    private final MasterDataPhoneCodec phoneCodec;
    private final MasterDataFingerprint fingerprint;
    private final IdentityImportParser importParser;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    IdentityMasterDataService(
            IdentityMasterDataMapper mapper,
            MasterDataPhoneCodec phoneCodec,
            MasterDataFingerprint fingerprint,
            IdentityImportParser importParser,
            ObjectMapper objectMapper,
            Clock authClock) {
        this.mapper = mapper;
        this.phoneCodec = phoneCodec;
        this.fingerprint = fingerprint;
        this.importParser = importParser;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageOrganization listOrganizations(int page, int pageSize) {
        return new PageOrganization(
                page,
                pageSize,
                mapper.countOrganizations(),
                mapper.listOrganizations((page - 1) * pageSize, pageSize).stream()
                        .map(this::toOrganization)
                        .toList());
    }

    PageEmployee listEmployees(int page, int pageSize) {
        return new PageEmployee(
                page,
                pageSize,
                mapper.countEmployees(),
                mapper.listEmployees((page - 1) * pageSize, pageSize).stream()
                        .map(this::toEmployee)
                        .toList());
    }

    Organization getOrganization(long id) {
        return toOrganization(requireOrganization(id));
    }

    Employee getEmployee(long id) {
        return toEmployee(requireEmployee(id));
    }

    OrganizationActiveReferencePage listActiveOrganizations(int page, int pageSize) {
        List<OrganizationActiveReference> items = mapper
                .listOrganizations((page - 1) * pageSize, pageSize).stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .map(row -> new OrganizationActiveReference(
                        Long.toString(row.id()), row.name(), row.version()))
                .toList();
        return new OrganizationActiveReferencePage(page, pageSize, (long) items.size(), items);
    }

    @Transactional
    Organization createOrganization(
            long actorId,
            OrganizationCreateRequest request,
            String idempotencyKey,
            UUID requestId) {
        String operation = "createOrganization";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<Organization> replay = replay(actorId, operation, idempotencyKey, digest, Organization.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Long parentId = request.getParentOrganization() == null
                ? null
                : validateActiveOrganization(request.getParentOrganization()).id();
        Organization result = createOrganizationInternal(
                actorId, request.getCode(), request.getName(), parentId, requestId);
        record(actorId, operation, idempotencyKey, digest, 201, result, "ORGANIZATION", result.getId());
        return result;
    }

    @Transactional
    Organization updateOrganization(
            long actorId,
            long id,
            long expectedVersion,
            OrganizationUpdateRequest request,
            String idempotencyKey,
            UUID requestId) {
        String operation = "updateOrganization";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<Organization> replay = replay(actorId, operation, idempotencyKey, digest, Organization.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        OrganizationRow current = requireVersion(requireOrganization(id), expectedVersion);
        Long parentId = current.parentId();
        if (request.getParentOrganization() != null) {
            OrganizationRow parent = validateActiveOrganization(request.getParentOrganization());
            if (parent.id() == id || mapper.countOrganizationCycle(id, parent.id()) != 0) {
                throw MasterDataOperationException.dependencyConflict("The organization hierarchy would contain a cycle.");
            }
            parentId = parent.id();
        }
        String code = optional(request.getCode()).orElse(current.code());
        String name = optional(request.getName()).orElse(current.name());
        assertOrganizationCodeAvailable(code, id);
        Instant now = clock.instant();
        requireVersionUpdate(mapper.updateOrganization(
                id, expectedVersion, code.strip(), normalizeCode(code), name.strip(), parentId, actorId, now));
        Organization result = getOrganization(id);
        audit(actorId, requestId, "ORGANIZATION_UPDATED", id, now);
        record(actorId, operation, idempotencyKey, digest, 200, result, "ORGANIZATION", result.getId());
        return result;
    }

    @Transactional
    Organization disableOrganization(
            long actorId,
            long id,
            long expectedVersion,
            String reason,
            String idempotencyKey,
            UUID requestId) {
        String operation = "disableOrganization";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<Organization> replay = replay(actorId, operation, idempotencyKey, digest, Organization.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Organization result = disableOrganizationInternal(actorId, id, expectedVersion, requestId);
        record(actorId, operation, idempotencyKey, digest, 200, result, "ORGANIZATION", result.getId());
        return result;
    }

    @Transactional
    Employee createEmployee(
            long actorId,
            EmployeeCreateRequest request,
            String idempotencyKey,
            UUID requestId) {
        String operation = "createEmployee";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<Employee> replay = replay(actorId, operation, idempotencyKey, digest, Employee.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        OrganizationRow organization = validateActiveOrganization(request.getOrganization());
        Employee result = createEmployeeInternal(
                actorId,
                request.getEmployeeNo(),
                request.getDisplayName(),
                organization.id(),
                request.getPhone(),
                requestId);
        record(actorId, operation, idempotencyKey, digest, 201, result, "EMPLOYEE", result.getId());
        return result;
    }

    @Transactional
    Employee updateEmployee(
            long actorId,
            long id,
            long expectedVersion,
            EmployeeUpdateRequest request,
            String idempotencyKey,
            UUID requestId) {
        String operation = "updateEmployee";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<Employee> replay = replay(actorId, operation, idempotencyKey, digest, Employee.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        EmployeeRow current = requireVersion(requireEmployee(id), expectedVersion);
        long organizationId = current.organizationId();
        if (request.getOrganization() != null) {
            organizationId = validateActiveOrganization(request.getOrganization()).id();
        }
        Employee result = updateEmployeeInternal(
                actorId,
                current,
                optional(request.getEmployeeNo()).orElse(current.employeeNo()),
                optional(request.getDisplayName()).orElse(current.name()),
                organizationId,
                request.getPhone(),
                requestId);
        record(actorId, operation, idempotencyKey, digest, 200, result, "EMPLOYEE", result.getId());
        return result;
    }

    @Transactional
    Employee changeEmployeeStatus(
            long actorId,
            long id,
            long expectedVersion,
            boolean enabled,
            String reason,
            String idempotencyKey,
            UUID requestId) {
        String operation = enabled ? "enableEmployee" : "disableEmployee";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<Employee> replay = replay(actorId, operation, idempotencyKey, digest, Employee.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Employee result = changeEmployeeStatusInternal(actorId, id, expectedVersion, enabled, requestId);
        record(actorId, operation, idempotencyKey, digest, 200, result, "EMPLOYEE", result.getId());
        return result;
    }

    @Transactional
    IdentityImportResult importIdentity(
            long actorId,
            String csv,
            String idempotencyKey,
            UUID requestId) {
        String operation = "importIdentityTemplate";
        String digest = fingerprint.of(operation, csv);
        Optional<IdentityImportResult> replay = replay(
                actorId, operation, idempotencyKey, digest, IdentityImportResult.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        List<IdentityImportRow> rows = importParser.parse(csv);
        int created = 0;
        int updated = 0;
        int disabled = 0;
        for (IdentityImportRow row : rows) {
            switch (row.action()) {
                case CREATE -> {
                    importCreate(actorId, row, requestId);
                    created++;
                }
                case UPDATE -> {
                    importUpdate(actorId, row, requestId);
                    updated++;
                }
                case DISABLE -> {
                    importDisable(actorId, row, requestId);
                    disabled++;
                }
            }
        }
        IdentityImportResult result = new IdentityImportResult(rows.size(), created, updated, disabled);
        record(actorId, operation, idempotencyKey, digest, 200, result, "IDENTITY_IMPORT", null);
        return result;
    }

    private Organization createOrganizationInternal(
            long actorId,
            String code,
            String name,
            Long parentId,
            UUID requestId) {
        validateText(code, name);
        assertOrganizationCodeAvailable(code, null);
        Instant now = clock.instant();
        NewOrganizationRow inserted = new NewOrganizationRow(
                code.strip(), normalizeCode(code), name.strip(), parentId, actorId, now);
        requireOne(mapper.insertOrganization(inserted));
        audit(actorId, requestId, "ORGANIZATION_CREATED", inserted.getId(), now);
        return getOrganization(inserted.getId());
    }

    private Organization disableOrganizationInternal(
            long actorId,
            long id,
            long expectedVersion,
            UUID requestId) {
        OrganizationRow current = requireVersion(requireOrganization(id), expectedVersion);
        if (!"ACTIVE".equals(current.status())) {
            throw MasterDataOperationException.dependencyConflict("The organization is already disabled.");
        }
        if (mapper.countActiveChildOrganizations(id) != 0
                || mapper.countActiveEmployees(id) != 0
                || mapper.countActiveAuthorizationScopes(id) != 0) {
            throw MasterDataOperationException.dependencyConflict(
                    "The organization still has active child, employee, or authorization dependencies.");
        }
        Instant now = clock.instant();
        requireVersionUpdate(mapper.disableOrganization(id, expectedVersion, actorId, now));
        audit(actorId, requestId, "ORGANIZATION_DISABLED", id, now);
        return getOrganization(id);
    }

    private Employee createEmployeeInternal(
            long actorId,
            String employeeNo,
            String name,
            long organizationId,
            String phone,
            UUID requestId) {
        validateText(employeeNo, name);
        assertEmployeeNumberAvailable(employeeNo, null);
        Instant now = clock.instant();
        NewEmployeeRow inserted = new NewEmployeeRow(
                employeeNo.strip(), normalizeCode(employeeNo), name.strip(), organizationId,
                phoneCodec.encrypt(phone), phoneCodec.lookupHash(phone), actorId, now);
        requireOne(mapper.insertEmployee(inserted));
        audit(actorId, requestId, "EMPLOYEE_CREATED", inserted.getId(), now);
        return getEmployee(inserted.getId());
    }

    private Employee updateEmployeeInternal(
            long actorId,
            EmployeeRow current,
            String employeeNo,
            String name,
            long organizationId,
            String phone,
            UUID requestId) {
        validateText(employeeNo, name);
        assertEmployeeNumberAvailable(employeeNo, current.id());
        Instant now = clock.instant();
        requireVersionUpdate(mapper.updateEmployee(
                current.id(), current.version(), employeeNo.strip(), normalizeCode(employeeNo), name.strip(),
                organizationId, phoneCodec.encrypt(phone), phoneCodec.lookupHash(phone), actorId, now));
        audit(actorId, requestId, "EMPLOYEE_UPDATED", current.id(), now);
        return getEmployee(current.id());
    }

    private Employee changeEmployeeStatusInternal(
            long actorId,
            long id,
            long expectedVersion,
            boolean enabled,
            UUID requestId) {
        EmployeeRow current = requireVersion(requireEmployee(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw MasterDataOperationException.dependencyConflict("The employee already has the requested status.");
        }
        if (enabled) {
            OrganizationRow organization = requireOrganization(current.organizationId());
            if (!"ACTIVE".equals(organization.status())) {
                throw MasterDataOperationException.dependencyConflict(
                        "The employee cannot be enabled in a disabled organization.");
            }
        }
        Instant now = clock.instant();
        requireVersionUpdate(mapper.changeEmployeeStatus(id, expectedVersion, status, actorId, now));
        if (!enabled) {
            mapper.disableEmployeeAccount(id, actorId, now);
        }
        audit(actorId, requestId, enabled ? "EMPLOYEE_ENABLED" : "EMPLOYEE_DISABLED", id, now);
        return getEmployee(id);
    }

    private void importCreate(long actorId, IdentityImportRow row, UUID requestId) {
        if (row.recordType() == RecordType.ORGANIZATION) {
            Long parentId = row.parentCode() == null ? null : requireActiveOrganizationByCode(row.parentCode()).id();
            createOrganizationInternal(actorId, row.code(), row.displayName(), parentId, requestId);
        } else {
            OrganizationRow organization = requireActiveOrganizationByCode(row.organizationCode());
            createEmployeeInternal(
                    actorId, row.code(), row.displayName(), organization.id(), row.phone(), requestId);
        }
    }

    private void importUpdate(long actorId, IdentityImportRow row, UUID requestId) {
        long version = requireImportVersion(row);
        if (row.recordType() == RecordType.ORGANIZATION) {
            OrganizationRow current = requireVersion(requireOrganizationByCode(row.code()), version);
            Long parentId = row.parentCode() == null
                    ? current.parentId()
                    : requireActiveOrganizationByCode(row.parentCode()).id();
            if (parentId != null && (parentId == current.id()
                    || mapper.countOrganizationCycle(current.id(), parentId) != 0)) {
                throw MasterDataOperationException.dependencyConflict(
                        "Identity import row " + row.rowNumber() + " creates an organization cycle.");
            }
            Instant now = clock.instant();
            requireVersionUpdate(mapper.updateOrganization(
                    current.id(), version, current.code(), normalizeCode(current.code()),
                    requiredImportName(row), parentId, actorId, now));
            audit(actorId, requestId, "ORGANIZATION_UPDATED", current.id(), now);
        } else {
            EmployeeRow current = requireVersion(requireEmployeeByNumber(row.code()), version);
            long organizationId = row.organizationCode() == null
                    ? current.organizationId()
                    : requireActiveOrganizationByCode(row.organizationCode()).id();
            updateEmployeeInternal(
                    actorId, current, current.employeeNo(), requiredImportName(row),
                    organizationId, row.phone(), requestId);
        }
    }

    private void importDisable(long actorId, IdentityImportRow row, UUID requestId) {
        long version = requireImportVersion(row);
        if (row.recordType() == RecordType.ORGANIZATION) {
            OrganizationRow current = requireOrganizationByCode(row.code());
            disableOrganizationInternal(actorId, current.id(), version, requestId);
        } else {
            EmployeeRow current = requireEmployeeByNumber(row.code());
            changeEmployeeStatusInternal(actorId, current.id(), version, false, requestId);
        }
    }

    private long requireImportVersion(IdentityImportRow row) {
        if (row.expectedVersion() == null) {
            throw MasterDataOperationException.invalid(
                    "Identity import row " + row.rowNumber() + " requires expectedVersion.");
        }
        return row.expectedVersion();
    }

    private String requiredImportName(IdentityImportRow row) {
        if (row.displayName() == null || row.displayName().isBlank()) {
            throw MasterDataOperationException.invalid(
                    "Identity import row " + row.rowNumber() + " requires displayName for UPDATE.");
        }
        return row.displayName();
    }

    private OrganizationRow validateActiveOrganization(OrganizationReference reference) {
        long id = parseId(reference.getId());
        OrganizationRow organization = requireVersion(requireOrganization(id), reference.getVersion());
        if (!"ACTIVE".equals(organization.status())) {
            throw MasterDataOperationException.dependencyConflict("The organization reference is disabled.");
        }
        return organization;
    }

    private OrganizationRow requireActiveOrganizationByCode(String code) {
        if (code == null) {
            throw MasterDataOperationException.invalid("organizationCode is required.");
        }
        OrganizationRow organization = requireOrganizationByCode(code);
        if (!"ACTIVE".equals(organization.status())) {
            throw MasterDataOperationException.dependencyConflict("The organization reference is disabled.");
        }
        return organization;
    }

    private OrganizationRow requireOrganization(long id) {
        return Optional.ofNullable(mapper.findOrganization(id))
                .orElseThrow(() -> MasterDataOperationException.notFound("The organization"));
    }

    private OrganizationRow requireOrganizationByCode(String code) {
        return Optional.ofNullable(mapper.findOrganizationByCode(normalizeCode(code)))
                .orElseThrow(() -> MasterDataOperationException.notFound("The organization"));
    }

    private EmployeeRow requireEmployee(long id) {
        return Optional.ofNullable(mapper.findEmployee(id))
                .orElseThrow(() -> MasterDataOperationException.notFound("The employee"));
    }

    private EmployeeRow requireEmployeeByNumber(String employeeNo) {
        return Optional.ofNullable(mapper.findEmployeeByNumber(normalizeCode(employeeNo)))
                .orElseThrow(() -> MasterDataOperationException.notFound("The employee"));
    }

    private OrganizationRow requireVersion(OrganizationRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private EmployeeRow requireVersion(EmployeeRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private void assertOrganizationCodeAvailable(String code, Long currentId) {
        OrganizationRow existing = mapper.findOrganizationByCode(normalizeCode(code));
        if (existing != null && !Long.valueOf(existing.id()).equals(currentId)) {
            throw MasterDataOperationException.writeConflict("The organization code is already in use.");
        }
    }

    private void assertEmployeeNumberAvailable(String employeeNo, Long currentId) {
        EmployeeRow existing = mapper.findEmployeeByNumber(normalizeCode(employeeNo));
        if (existing != null && !Long.valueOf(existing.id()).equals(currentId)) {
            throw MasterDataOperationException.writeConflict("The employee number is already in use.");
        }
    }

    private <T> Optional<T> replay(
            long actorId,
            String operation,
            String key,
            String digest,
            Class<T> type) {
        IdempotencyRow existing = mapper.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), type));
    }

    private void record(
            long actorId,
            String operation,
            String key,
            String digest,
            int status,
            Object response,
            String resourceType,
            String resourceId) {
        Instant now = clock.instant();
        Long numericId = resourceId == null ? null : parseId(resourceId);
        Long version = response instanceof Organization organization ? organization.getVersion()
                : response instanceof Employee employee ? employee.getVersion() : null;
        requireOne(mapper.insertIdempotency(
                actorId,
                operation,
                key,
                digest,
                status,
                objectMapper.writeValueAsString(response),
                resourceType,
                numericId,
                version,
                now,
                now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(mapper.insertAudit(
                actorId,
                requestId.toString(),
                now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private Organization toOrganization(OrganizationRow row) {
        Organization result = new Organization(
                Long.toString(row.id()),
                row.code(),
                row.name(),
                ReferenceStatus.valueOf(row.status()),
                row.version(),
                utc(row.createdAt()),
                utc(row.updatedAt()));
        if (row.parentId() != null) {
            result.setParentOrganization(new OrganizationReference(
                    Long.toString(row.parentId()), row.parentVersion()));
        }
        return result;
    }

    private Employee toEmployee(EmployeeRow row) {
        return new Employee(
                Long.toString(row.id()),
                row.employeeNo(),
                row.name(),
                new OrganizationReference(Long.toString(row.organizationId()), row.organizationVersion()),
                ReferenceStatus.valueOf(row.status()),
                row.version(),
                utc(row.createdAt()),
                utc(row.updatedAt()));
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw MasterDataOperationException.invalid("A non-empty code is required.");
        }
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }

    private void validateText(String code, String name) {
        normalizeCode(code);
        if (name == null || name.isBlank()) {
            throw MasterDataOperationException.invalid("A non-empty display name is required.");
        }
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The referenced record");
        }
    }

    private Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private void requireVersionUpdate(int count) {
        if (count != 1) {
            throw MasterDataOperationException.versionConflict();
        }
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw new IllegalStateException("Expected one persisted row, got " + count);
        }
    }
}
