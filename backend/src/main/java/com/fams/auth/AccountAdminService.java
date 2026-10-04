package com.fams.auth;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.DataScopeGrant;
import com.fams.product.contract.model.OrganizationReference;
import com.fams.product.contract.model.PageSystemAccount;
import com.fams.product.contract.model.RoleConfiguration;
import com.fams.product.contract.model.RoleConfigurationPage;
import com.fams.product.contract.model.SensitiveFieldPermission;
import com.fams.product.contract.model.SystemAccount;
import com.fams.product.contract.model.SystemAccountAuthorization;
import com.fams.product.contract.model.SystemAccountAuthorizationReplaceRequest;
import com.fams.product.contract.model.SystemAccountAuthorizationStatus;
import com.fams.product.contract.model.SystemAccountStatus;
import com.fams.product.contract.model.SystemAccountStatusChangeRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class AccountAdminService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final AccountAdminMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthPayloadFingerprint payloadFingerprint;
    private final AuthApplicationService authService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AccountAdminService(
            AccountAdminMapper mapper,
            PasswordEncoder passwordEncoder,
            AuthPayloadFingerprint payloadFingerprint,
            AuthApplicationService authService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.payloadFingerprint = payloadFingerprint;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    PageSystemAccount listAccounts(int page, int pageSize) {
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<SystemAccount> items = mapper.listAccounts(offset, pageSize).stream()
                .map(this::toContract)
                .toList();
        return new PageSystemAccount(page, pageSize, mapper.countAccounts(), items);
    }

    SystemAccount getAccount(long accountId) {
        return toContract(requireAccount(accountId));
    }

    RoleConfigurationPage listRoleConfigurations(int page, int pageSize) {
        List<RoleConfiguration> all = java.util.Arrays.stream(Role.values())
                .sorted(Comparator.comparing(Enum::name))
                .map(this::roleConfiguration)
                .toList();
        int from = Math.min((page - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new RoleConfigurationPage(page, pageSize, (long) all.size(), all.subList(from, to));
    }

    RoleConfiguration getRoleConfiguration(Role role) {
        return roleConfiguration(role);
    }

    @Transactional
    SystemAccount createAccount(
            long actorAccountId,
            String username,
            long employeeId,
            long employeeVersion,
            String initialPassword,
            String idempotencyKey,
            UUID requestId) {
        String normalizedUsername = normalizeUsername(username);
        String fingerprint = payloadFingerprint.fingerprint(
                "createSystemAccount",
                normalizedUsername,
                Long.toString(employeeId),
                Long.toString(employeeVersion),
                initialPassword);
        Optional<SystemAccount> replay = replay(
                actorAccountId,
                "createSystemAccount",
                idempotencyKey,
                fingerprint,
                SystemAccount.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        EmployeeAdminRow employee = Optional.ofNullable(mapper.findEmployee(employeeId))
                .orElseThrow(AuthOperationException::employeeNotFound);
        if (!"ACTIVE".equals(employee.status())) {
            throw AuthOperationException.relationConflict("The employee is not active.");
        }
        if (employee.version() != employeeVersion) {
            throw AuthOperationException.versionConflict();
        }
        if (mapper.countByNormalizedUsername(normalizedUsername) != 0) {
            throw AuthOperationException.relationConflict("The username is already in use.");
        }
        if (mapper.countByEmployeeId(employeeId) != 0) {
            throw AuthOperationException.relationConflict("The employee already has a system account.");
        }
        validatePassword(initialPassword, username);

        Instant now = clock.instant();
        NewSystemAccountRow newAccount = new NewSystemAccountRow(
                employeeId,
                username.strip(),
                normalizedUsername,
                passwordEncoder.encode(initialPassword),
                actorAccountId,
                now);
        requireOne(mapper.insertAccount(newAccount));
        SystemAccount result = getAccount(newAccount.getId());
        recordIdempotency(
                actorAccountId,
                "createSystemAccount",
                idempotencyKey,
                fingerprint,
                201,
                result,
                newAccount.getId(),
                result.getVersion(),
                now);
        authService.audit(
                actorAccountId,
                SecurityEventType.ACCOUNT_CREATED,
                requestId,
                "TARGET_ACCOUNT=" + newAccount.getId());
        return result;
    }

    @Transactional
    SystemAccount changeStatus(
            long actorAccountId,
            long accountId,
            long expectedVersion,
            SystemAccountStatusChangeRequest.ActionEnum action,
            String reason,
            String idempotencyKey,
            UUID requestId) {
        String operation = "changeSystemAccountStatus";
        String fingerprint = payloadFingerprint.fingerprint(
                operation,
                Long.toString(accountId),
                Long.toString(expectedVersion),
                action.name(),
                reason);
        Optional<SystemAccount> replay = replay(
                actorAccountId, operation, idempotencyKey, fingerprint, SystemAccount.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        SystemAccountAdminRow current = requireVersion(accountId, expectedVersion);
        AccountStatus target = switch (action) {
            case ENABLE, UNLOCK -> AccountStatus.ENABLED;
            case DISABLE -> AccountStatus.DISABLED;
        };
        if (action == SystemAccountStatusChangeRequest.ActionEnum.UNLOCK
                && current.status() != AccountStatus.LOCKED) {
            throw AuthOperationException.stateConflict("Only a locked account can be unlocked.");
        }
        if (current.status() == target && action != SystemAccountStatusChangeRequest.ActionEnum.UNLOCK) {
            throw AuthOperationException.stateConflict("The account already has the requested status.");
        }
        Instant now = clock.instant();
        requireVersionUpdate(mapper.updateStatus(accountId, expectedVersion, target, actorAccountId, now));
        SystemAccount result = getAccount(accountId);
        recordIdempotency(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                200,
                result,
                accountId,
                result.getVersion(),
                now);
        authService.audit(
                actorAccountId,
                SecurityEventType.ACCOUNT_STATUS_CHANGED,
                requestId,
                "TARGET_ACCOUNT=" + accountId + ";ACTION=" + action.name());
        return result;
    }

    @Transactional
    SystemAccount resetInitialPassword(
            long actorAccountId,
            long accountId,
            long expectedVersion,
            String newInitialPassword,
            String reason,
            String idempotencyKey,
            UUID requestId) {
        String operation = "resetInitialPassword";
        String fingerprint = payloadFingerprint.fingerprint(
                operation,
                Long.toString(accountId),
                Long.toString(expectedVersion),
                newInitialPassword,
                reason);
        Optional<SystemAccount> replay = replay(
                actorAccountId, operation, idempotencyKey, fingerprint, SystemAccount.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        SystemAccountAdminRow current = requireVersion(accountId, expectedVersion);
        validatePassword(newInitialPassword, current.username());
        Instant now = clock.instant();
        requireVersionUpdate(mapper.resetInitialPassword(
                accountId,
                expectedVersion,
                passwordEncoder.encode(newInitialPassword),
                actorAccountId,
                now));
        SystemAccount result = getAccount(accountId);
        recordIdempotency(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                200,
                result,
                accountId,
                result.getVersion(),
                now);
        authService.audit(
                actorAccountId,
                SecurityEventType.ACCOUNT_PASSWORD_RESET,
                requestId,
                "TARGET_ACCOUNT=" + accountId);
        return result;
    }

    @Transactional
    SystemAccount changeRole(
            long actorAccountId,
            long accountId,
            long expectedVersion,
            Role role,
            String reason,
            String idempotencyKey,
            UUID requestId,
            boolean grant) {
        String operation = grant ? "grantSystemAccountRole" : "revokeSystemAccountRole";
        String fingerprint = payloadFingerprint.fingerprint(
                operation,
                Long.toString(accountId),
                Long.toString(expectedVersion),
                role.name(),
                reason);
        Optional<SystemAccount> replay = replay(
                actorAccountId, operation, idempotencyKey, fingerprint, SystemAccount.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        requireVersion(accountId, expectedVersion);
        boolean assigned = mapper.countRole(accountId, role) != 0;
        if (grant == assigned) {
            throw AuthOperationException.stateConflict(
                    grant ? "The role is already assigned." : "The role is not assigned.");
        }
        Instant now = clock.instant();
        if (grant) {
            requireOne(mapper.insertRole(accountId, role, actorAccountId, now));
        } else {
            requireOne(mapper.deleteRole(accountId, role));
        }
        requireVersionUpdate(mapper.advanceRoleVersion(accountId, expectedVersion, actorAccountId, now));
        SystemAccount result = getAccount(accountId);
        recordIdempotency(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                200,
                result,
                accountId,
                result.getVersion(),
                now);
        authService.audit(
                actorAccountId,
                grant ? SecurityEventType.ACCOUNT_ROLE_GRANTED : SecurityEventType.ACCOUNT_ROLE_REVOKED,
                requestId,
                "TARGET_ACCOUNT=" + accountId + ";ROLE=" + role.name());
        return result;
    }

    SystemAccountAuthorization getAuthorization(long accountId, Role role) {
        SystemAccountAdminRow account = requireAccount(accountId);
        if (mapper.countRole(accountId, role) == 0) {
            throw AuthOperationException.relationConflict("The account does not have the requested role.");
        }
        AuthorizationAdminRow current = mapper.findCurrentAuthorization(accountId, role);
        if (current == null) {
            return emptyAuthorization(account, role);
        }
        return toContract(current);
    }

    @Transactional
    SystemAccountAuthorization replaceAuthorization(
            long actorAccountId,
            long accountId,
            Role role,
            long expectedVersion,
            SystemAccountAuthorizationReplaceRequest body,
            String idempotencyKey,
            UUID requestId) {
        String operation = "replaceSystemAccountAuthorization";
        String fingerprint = authorizationFingerprint(
                operation,
                accountId,
                role,
                expectedVersion,
                body.getDataScopes(),
                body.getFieldPermissions(),
                body.getReason());
        Optional<SystemAccountAuthorization> replay = replay(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                SystemAccountAuthorization.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        requireAccountRole(accountId, role);
        validateAuthorization(role, body.getDataScopes(), body.getFieldPermissions());
        AuthorizationAdminRow current = requireAuthorizationVersion(accountId, role, expectedVersion);
        Instant now = clock.instant();
        if (current != null) {
            requireOne(mapper.supersedeAuthorization(current.id(), now));
        }
        NewAuthorizationRow replacement = new NewAuthorizationRow(
                accountId,
                role,
                expectedVersion + 1,
                "ACTIVE",
                current == null ? null : current.id(),
                actorAccountId,
                body.getReason(),
                now);
        requireOne(mapper.insertAuthorization(replacement));
        insertAuthorizationDetails(replacement.getId(), body.getDataScopes(), body.getFieldPermissions(), now);
        requireOne(mapper.advanceAuthorizationEpoch(accountId, actorAccountId, now));
        SystemAccountAuthorization result = toContract(
                mapper.findCurrentAuthorization(accountId, role));
        recordIdempotency(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                200,
                result,
                replacement.getId(),
                result.getVersion(),
                now);
        authService.audit(
                actorAccountId,
                SecurityEventType.ACCOUNT_AUTHORIZATION_REPLACED,
                requestId,
                "TARGET_ACCOUNT=" + accountId + ";ROLE=" + role.name());
        return result;
    }

    @Transactional
    SystemAccountAuthorization revokeAuthorization(
            long actorAccountId,
            long accountId,
            Role role,
            long expectedVersion,
            String reason,
            String idempotencyKey,
            UUID requestId) {
        String operation = "revokeSystemAccountAuthorization";
        String fingerprint = payloadFingerprint.fingerprint(
                operation,
                Long.toString(accountId),
                role.name(),
                Long.toString(expectedVersion),
                reason);
        Optional<SystemAccountAuthorization> replay = replay(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                SystemAccountAuthorization.class);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }

        requireAccountRole(accountId, role);
        AuthorizationAdminRow current = requireAuthorizationVersion(accountId, role, expectedVersion);
        if (current == null || "REVOKED".equals(current.status())) {
            throw AuthOperationException.stateConflict("The authorization is already revoked.");
        }
        Instant now = clock.instant();
        requireOne(mapper.supersedeAuthorization(current.id(), now));
        NewAuthorizationRow revoked = new NewAuthorizationRow(
                accountId,
                role,
                expectedVersion + 1,
                "REVOKED",
                current.id(),
                actorAccountId,
                reason,
                now);
        requireOne(mapper.insertAuthorization(revoked));
        requireOne(mapper.advanceAuthorizationEpoch(accountId, actorAccountId, now));
        SystemAccountAuthorization result = toContract(
                mapper.findCurrentAuthorization(accountId, role));
        recordIdempotency(
                actorAccountId,
                operation,
                idempotencyKey,
                fingerprint,
                200,
                result,
                revoked.getId(),
                result.getVersion(),
                now);
        authService.audit(
                actorAccountId,
                SecurityEventType.ACCOUNT_AUTHORIZATION_REVOKED,
                requestId,
                "TARGET_ACCOUNT=" + accountId + ";ROLE=" + role.name());
        return result;
    }

    private RoleConfiguration roleConfiguration(Role role) {
        RoleConfiguration.BusinessScopeEnum scope = switch (role) {
            case EMPLOYEE -> RoleConfiguration.BusinessScopeEnum.PERSONAL;
            case DEPARTMENT_ASSET_MANAGER -> RoleConfiguration.BusinessScopeEnum.DEPARTMENT;
            case ASSET_ADMIN -> RoleConfiguration.BusinessScopeEnum.ENTERPRISE_WIDE;
            case WAREHOUSE_OPERATOR -> RoleConfiguration.BusinessScopeEnum.SPECIFIED_WAREHOUSE;
            case AUDIT_READ_ONLY -> RoleConfiguration.BusinessScopeEnum.SPECIFIED_ORGANIZATION;
            case TECH_ASSISTANT, SYSTEM_ADMIN -> RoleConfiguration.BusinessScopeEnum.NONE;
        };
        return new RoleConfiguration(
                RoleConfiguration.RoleEnum.valueOf(role.name()),
                List.copyOf(AuthPolicy.permissionsFor(role)),
                scope,
                false);
    }

    private SystemAccountAdminRow requireAccount(long accountId) {
        return Optional.ofNullable(mapper.findAccount(accountId))
                .orElseThrow(AuthOperationException::accountNotFound);
    }

    private SystemAccountAdminRow requireVersion(long accountId, long expectedVersion) {
        SystemAccountAdminRow current = requireAccount(accountId);
        if (current.version() != expectedVersion) {
            throw AuthOperationException.versionConflict();
        }
        return current;
    }

    private void requireAccountRole(long accountId, Role role) {
        requireAccount(accountId);
        if (mapper.countRole(accountId, role) == 0) {
            throw AuthOperationException.relationConflict("The account does not have the requested role.");
        }
    }

    private AuthorizationAdminRow requireAuthorizationVersion(
            long accountId,
            Role role,
            long expectedVersion) {
        AuthorizationAdminRow current = mapper.findCurrentAuthorization(accountId, role);
        long currentVersion = current == null ? 0 : current.generation();
        if (currentVersion != expectedVersion) {
            throw AuthOperationException.versionConflict();
        }
        return current;
    }

    private SystemAccount toContract(SystemAccountAdminRow row) {
        return new SystemAccount(
                Long.toString(row.id()),
                row.username(),
                Long.toString(row.employeeId()),
                SystemAccountStatus.valueOf(row.status().name()),
                row.version(),
                row.initialPasswordRequired(),
                OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(row.updatedAt(), ZoneOffset.UTC))
                .failedLoginCount(row.failedLoginCount());
    }

    private SystemAccountAuthorization emptyAuthorization(SystemAccountAdminRow account, Role role) {
        return new SystemAccountAuthorization(
                "0",
                Long.toString(account.id()),
                com.fams.product.contract.model.Role.valueOf(role.name()),
                SystemAccountAuthorizationStatus.REVOKED,
                List.of(),
                Set.of(),
                0L,
                OffsetDateTime.ofInstant(account.updatedAt(), ZoneOffset.UTC));
    }

    private SystemAccountAuthorization toContract(AuthorizationAdminRow authorization) {
        List<DataScopeGrant> scopes = mapper.findScopes(authorization.id()).stream()
                .map(scope -> new DataScopeGrant(
                        com.fams.product.contract.model.DataScopeType.valueOf(scope.scopeType().name()),
                        mapper.findOrganizationReferences(scope.id()).stream()
                                .map(reference -> new OrganizationReference(
                                        Long.toString(reference.id()), reference.version()))
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                        mapper.findWarehouseReferences(scope.id()).stream()
                                .map(reference -> new CatalogReference(
                                        Long.toString(reference.id()), reference.version()))
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))))
                .toList();
        Set<SensitiveFieldPermission> fieldPermissions = mapper.findFieldPermissions(authorization.id()).stream()
                .map(SensitiveFieldPermission::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new SystemAccountAuthorization(
                Long.toString(authorization.id()),
                Long.toString(authorization.accountId()),
                com.fams.product.contract.model.Role.valueOf(authorization.role().name()),
                SystemAccountAuthorizationStatus.valueOf(authorization.status()),
                scopes,
                fieldPermissions,
                authorization.generation(),
                OffsetDateTime.ofInstant(authorization.createdAt(), ZoneOffset.UTC));
    }

    private void validateAuthorization(
            Role role,
            List<DataScopeGrant> scopes,
            Set<SensitiveFieldPermission> fieldPermissions) {
        if (role == Role.SYSTEM_ADMIN && (!scopes.isEmpty() || !fieldPermissions.isEmpty())) {
            throw AuthOperationException.relationConflict(
                    "SYSTEM_ADMIN cannot receive asset business scopes or field permissions.");
        }
        Set<com.fams.product.contract.model.DataScopeType> seenScopes = new HashSet<>();
        for (DataScopeGrant scope : scopes) {
            if (!seenScopes.add(scope.getScopeType())) {
                throw AuthOperationException.relationConflict("A data scope type can only be configured once.");
            }
            boolean specifiedOrganizations = scope.getScopeType()
                    == com.fams.product.contract.model.DataScopeType.SPECIFIED_ORGANIZATIONS;
            boolean specifiedWarehouses = scope.getScopeType()
                    == com.fams.product.contract.model.DataScopeType.SPECIFIED_WAREHOUSES;
            if (specifiedOrganizations && (scope.getOrganizations().isEmpty()
                    || !scope.getWarehouses().isEmpty())) {
                throw AuthOperationException.relationConflict(
                        "SPECIFIED_ORGANIZATIONS requires only organization references.");
            }
            if (specifiedWarehouses && (scope.getWarehouses().isEmpty()
                    || !scope.getOrganizations().isEmpty())) {
                throw AuthOperationException.relationConflict(
                        "SPECIFIED_WAREHOUSES requires only warehouse references.");
            }
            if (!specifiedOrganizations && !specifiedWarehouses
                    && (!scope.getOrganizations().isEmpty() || !scope.getWarehouses().isEmpty())) {
                throw AuthOperationException.relationConflict(
                        "This data scope does not accept explicit references.");
            }
            scope.getOrganizations().forEach(reference -> {
                long referenceId = parseReferenceId(reference.getId());
                if (mapper.countCurrentOrganization(referenceId, reference.getVersion()) != 1) {
                    throw AuthOperationException.versionConflict();
                }
            });
            scope.getWarehouses().forEach(reference -> {
                long referenceId = parseReferenceId(reference.getId());
                if (mapper.countCurrentWarehouse(referenceId, reference.getVersion()) != 1) {
                    throw AuthOperationException.versionConflict();
                }
            });
        }
    }

    private void insertAuthorizationDetails(
            long authorizationId,
            List<DataScopeGrant> scopes,
            Set<SensitiveFieldPermission> fieldPermissions,
            Instant now) {
        for (DataScopeGrant scope : scopes) {
            NewDataScopeRow storedScope = new NewDataScopeRow(
                    authorizationId,
                    DataScope.valueOf(scope.getScopeType().name()));
            requireOne(mapper.insertDataScope(storedScope));
            for (OrganizationReference reference : scope.getOrganizations()) {
                requireOne(mapper.insertOrganizationReference(
                        storedScope.getId(),
                        parseReferenceId(reference.getId()),
                        reference.getVersion()));
            }
            for (CatalogReference reference : scope.getWarehouses()) {
                requireOne(mapper.insertWarehouseReference(
                        storedScope.getId(),
                        parseReferenceId(reference.getId()),
                        reference.getVersion()));
            }
        }
        for (SensitiveFieldPermission fieldPermission : fieldPermissions) {
            requireOne(mapper.insertFieldPermission(
                    authorizationId,
                    fieldPermission.name(),
                    now));
        }
    }

    private String authorizationFingerprint(
            String operation,
            long accountId,
            Role role,
            long expectedVersion,
            List<DataScopeGrant> scopes,
            Set<SensitiveFieldPermission> fieldPermissions,
            String reason) {
        List<String> canonicalScopes = scopes.stream()
                .map(scope -> scope.getScopeType().name()
                        + ":O=" + scope.getOrganizations().stream()
                                .map(reference -> reference.getId() + "@" + reference.getVersion())
                                .sorted()
                                .toList()
                        + ":W=" + scope.getWarehouses().stream()
                                .map(reference -> reference.getId() + "@" + reference.getVersion())
                                .sorted()
                                .toList())
                .sorted()
                .toList();
        List<String> canonicalFields = fieldPermissions.stream()
                .map(Enum::name)
                .sorted()
                .toList();
        return payloadFingerprint.fingerprint(
                operation,
                Long.toString(accountId),
                role.name(),
                Long.toString(expectedVersion),
                canonicalScopes.toString(),
                canonicalFields.toString(),
                reason);
    }

    private long parseReferenceId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw AuthOperationException.relationConflict("A referenced resource id is invalid.");
        }
    }

    private <T> Optional<T> replay(
            long actorAccountId,
            String operation,
            String idempotencyKey,
            String fingerprint,
            Class<T> responseType) {
        AdminIdempotencyRow recorded = mapper.findIdempotency(
                actorAccountId, operation, idempotencyKey);
        if (recorded == null) {
            return Optional.empty();
        }
        if (!recorded.payloadFingerprint().equals(fingerprint)) {
            throw AuthOperationException.idempotencyConflict();
        }
        try {
            return Optional.of(objectMapper.readValue(recorded.responseBodyJson(), responseType));
        } catch (Exception exception) {
            throw new IllegalStateException("Stored idempotency response is invalid.", exception);
        }
    }

    private void recordIdempotency(
            long actorAccountId,
            String operation,
            String idempotencyKey,
            String fingerprint,
            int httpStatus,
            Object response,
            Long resourceId,
            Long resourceVersion,
            Instant now) {
        try {
            requireOne(mapper.insertIdempotency(
                    actorAccountId,
                    operation,
                    idempotencyKey,
                    fingerprint,
                    httpStatus,
                    objectMapper.writeValueAsString(response),
                    resourceId,
                    resourceVersion,
                    now,
                    now.plus(IDEMPOTENCY_RETENTION)));
        } catch (AuthOperationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Idempotency response could not be stored.", exception);
        }
    }

    private void validatePassword(String password, String username) {
        try {
            PasswordPolicy.validateInitialPassword(password, username);
        } catch (AuthValidationException exception) {
            throw AuthOperationException.passwordPolicyViolation(exception.getMessage());
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        return Normalizer.normalize(username.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }

    private void requireOne(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Account administration write did not affect one row.");
        }
    }

    private void requireVersionUpdate(int affectedRows) {
        if (affectedRows != 1) {
            throw AuthOperationException.versionConflict();
        }
    }
}
