package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.api.AccountAdminApi;
import com.fams.product.contract.model.InitialPasswordResetRequest;
import com.fams.product.contract.model.PageSystemAccount;
import com.fams.product.contract.model.RoleConfiguration;
import com.fams.product.contract.model.RoleConfigurationPage;
import com.fams.product.contract.model.SystemAccount;
import com.fams.product.contract.model.SystemAccountAuthorization;
import com.fams.product.contract.model.SystemAccountAuthorizationReplaceRequest;
import com.fams.product.contract.model.SystemAccountAuthorizationRevokeRequest;
import com.fams.product.contract.model.SystemAccountCreateRequest;
import com.fams.product.contract.model.SystemAccountRoleRequest;
import com.fams.product.contract.model.SystemAccountStatusChangeRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
class AccountAdminController implements AccountAdminApi {

    private final AccountAdminService service;
    private final HttpServletRequest request;

    AccountAdminController(AccountAdminService service, HttpServletRequest request) {
        this.service = service;
        this.request = request;
    }

    @Override
    public ResponseEntity<SystemAccount> changeSystemAccountStatus(
            String systemAccountId,
            String idempotencyKey,
            String xCSRFToken,
            Long ifMatchVersion,
            SystemAccountStatusChangeRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(
                actor.id(),
                parseId(systemAccountId),
                ifMatchVersion,
                body.getAction(),
                body.getReason(),
                idempotencyKey,
                AuthRequestContext.requestId(request)));
    }

    @Override
    public ResponseEntity<SystemAccount> createSystemAccount(
            String idempotencyKey,
            String xCSRFToken,
            SystemAccountCreateRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        SystemAccount created = service.createAccount(
                actor.id(),
                body.getUsername(),
                parseId(body.getEmployee().getId()),
                body.getEmployee().getVersion(),
                body.getInitialPassword(),
                idempotencyKey,
                AuthRequestContext.requestId(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    public ResponseEntity<RoleConfiguration> getRoleConfiguration(
            com.fams.product.contract.model.Role role) {
        requireSystemAdministrator();
        return ResponseEntity.ok(service.getRoleConfiguration(Role.valueOf(role.name())));
    }

    @Override
    public ResponseEntity<SystemAccount> getSystemAccount(String systemAccountId) {
        requireSystemAdministrator();
        return ResponseEntity.ok(service.getAccount(parseId(systemAccountId)));
    }

    @Override
    public ResponseEntity<SystemAccountAuthorization> getSystemAccountAuthorization(
            String systemAccountId,
            com.fams.product.contract.model.Role role) {
        requireSystemAdministrator();
        return ResponseEntity.ok(service.getAuthorization(
                parseId(systemAccountId), Role.valueOf(role.name())));
    }

    @Override
    public ResponseEntity<SystemAccount> grantSystemAccountRole(
            String systemAccountId,
            String idempotencyKey,
            String xCSRFToken,
            Long ifMatchVersion,
            SystemAccountRoleRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        return ResponseEntity.ok(service.changeRole(
                actor.id(),
                parseId(systemAccountId),
                ifMatchVersion,
                Role.valueOf(body.getRole().name()),
                body.getReason(),
                idempotencyKey,
                AuthRequestContext.requestId(request),
                true));
    }

    @Override
    public ResponseEntity<RoleConfigurationPage> listRoleConfigurations(Integer page, Integer pageSize) {
        requireSystemAdministrator();
        return ResponseEntity.ok(service.listRoleConfigurations(page, pageSize));
    }

    @Override
    public ResponseEntity<PageSystemAccount> listSystemAccounts(Integer page, Integer pageSize) {
        requireSystemAdministrator();
        return ResponseEntity.ok(service.listAccounts(page, pageSize));
    }

    @Override
    public ResponseEntity<SystemAccountAuthorization> replaceSystemAccountAuthorization(
            String systemAccountId,
            com.fams.product.contract.model.Role role,
            String idempotencyKey,
            String xCSRFToken,
            Long ifMatchVersion,
            SystemAccountAuthorizationReplaceRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        return ResponseEntity.ok(service.replaceAuthorization(
                actor.id(),
                parseId(systemAccountId),
                Role.valueOf(role.name()),
                ifMatchVersion,
                body,
                idempotencyKey,
                AuthRequestContext.requestId(request)));
    }

    @Override
    public ResponseEntity<SystemAccount> resetInitialPassword(
            String systemAccountId,
            String idempotencyKey,
            String xCSRFToken,
            Long ifMatchVersion,
            InitialPasswordResetRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        return ResponseEntity.ok(service.resetInitialPassword(
                actor.id(),
                parseId(systemAccountId),
                ifMatchVersion,
                body.getNewInitialPassword(),
                body.getReason(),
                idempotencyKey,
                AuthRequestContext.requestId(request)));
    }

    @Override
    public ResponseEntity<SystemAccountAuthorization> revokeSystemAccountAuthorization(
            String systemAccountId,
            com.fams.product.contract.model.Role role,
            String idempotencyKey,
            String xCSRFToken,
            Long ifMatchVersion,
            SystemAccountAuthorizationRevokeRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        return ResponseEntity.ok(service.revokeAuthorization(
                actor.id(),
                parseId(systemAccountId),
                Role.valueOf(role.name()),
                ifMatchVersion,
                body.getReason(),
                idempotencyKey,
                AuthRequestContext.requestId(request)));
    }

    @Override
    public ResponseEntity<SystemAccount> revokeSystemAccountRole(
            String systemAccountId,
            String idempotencyKey,
            String xCSRFToken,
            Long ifMatchVersion,
            SystemAccountRoleRequest body) {
        AuthAccount actor = requireSystemAdministrator();
        return ResponseEntity.ok(service.changeRole(
                actor.id(),
                parseId(systemAccountId),
                ifMatchVersion,
                Role.valueOf(body.getRole().name()),
                body.getReason(),
                idempotencyKey,
                AuthRequestContext.requestId(request),
                false));
    }

    private AuthAccount requireSystemAdministrator() {
        AuthAccount actor = AuthRequestContext.account(request);
        if (!actor.roles().contains(Role.SYSTEM_ADMIN)) {
            throw AuthOperationException.forbidden();
        }
        return actor;
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw AuthOperationException.accountNotFound();
        }
    }
}
