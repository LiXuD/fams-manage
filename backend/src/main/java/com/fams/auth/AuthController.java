package com.fams.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.api.AuthApi;
import com.fams.product.contract.model.ChangePasswordRequest;
import com.fams.product.contract.model.CurrentUser;
import com.fams.product.contract.model.DataScopeGrant;
import com.fams.product.contract.model.DataScopeType;
import com.fams.product.contract.model.OrganizationReference;
import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.LoginRequest;
import com.fams.product.contract.model.Role;
import com.fams.product.contract.model.SensitiveFieldPermission;
import com.fams.product.contract.model.SessionResponse;
import com.fams.product.contract.model.SystemAccountStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Profile("fams-product")
@RestController
class AuthController implements AuthApi {

    private final AuthApplicationService authService;
    private final AuthSessionManager sessionManager;
    private final HttpServletRequest request;

    AuthController(
            AuthApplicationService authService,
            AuthSessionManager sessionManager,
            HttpServletRequest request) {
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.request = request;
    }

    @Override
    public ResponseEntity<SessionResponse> login(LoginRequest loginRequest) {
        AuthAccount account = authService.authenticate(
                loginRequest.getUsername(),
                loginRequest.getPassword(),
                AuthRequestContext.requestId(request));
        return ResponseEntity.ok(toSessionResponse(sessionManager.start(request, account), account));
    }

    @Override
    public ResponseEntity<SessionResponse> changeInitialPassword(
            String idempotencyKey,
            String xCSRFToken,
            ChangePasswordRequest changePasswordRequest) {
        AuthAccount current = AuthRequestContext.account(request);
        AuthAccount changed = authService.changeInitialPassword(
                current.id(),
                changePasswordRequest.getCurrentPassword(),
                changePasswordRequest.getNewPassword(),
                idempotencyKey,
                AuthRequestContext.requestId(request));
        return ResponseEntity.ok(toSessionResponse(sessionManager.start(request, changed), changed));
    }

    @Override
    public ResponseEntity<CurrentUser> getCurrentUser() {
        return ResponseEntity.ok(toCurrentUser(AuthRequestContext.account(request)));
    }

    @Override
    public ResponseEntity<com.fams.product.contract.model.PermissionSnapshot> getMyPermissions() {
        AuthAccount account = AuthRequestContext.account(request);
        return ResponseEntity.ok(toPermissionSnapshot(authService.loadPermissions(account.id())));
    }

    @Override
    public ResponseEntity<Void> logout(String xCSRFToken) {
        AuthAccount account = AuthRequestContext.account(request);
        authService.audit(
                account.id(),
                SecurityEventType.LOGOUT,
                AuthRequestContext.requestId(request),
                "SESSION_INVALIDATED");
        sessionManager.invalidate(request);
        return ResponseEntity.noContent().build();
    }

    private SessionResponse toSessionResponse(AuthSession session, AuthAccount account) {
        return new SessionResponse(
                session.csrfToken(),
                OffsetDateTime.ofInstant(session.idleExpiresAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(session.absoluteExpiresAt(), ZoneOffset.UTC),
                session.requiresInitialPasswordChange(),
                toCurrentUser(account));
    }

    private CurrentUser toCurrentUser(AuthAccount account) {
        List<Role> roles = account.roles().stream()
                .map(role -> Role.valueOf(role.name()))
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        return new CurrentUser(
                Long.toString(account.id()),
                account.username(),
                SystemAccountStatus.valueOf(account.status().name()),
                roles,
                account.initialPasswordRequired());
    }

    private com.fams.product.contract.model.PermissionSnapshot toPermissionSnapshot(
            PermissionSnapshot snapshot) {
        List<DataScopeGrant> scopes = snapshot.dataScopes().stream()
                .map(scope -> {
                    Set<OrganizationReference> organizations = snapshot.scopeGrants().stream()
                            .filter(grant -> grant.scopeType() == scope)
                            .flatMap(grant -> grant.organizations().stream())
                            .map(reference -> new OrganizationReference(
                                    Long.toString(reference.id()), reference.version()))
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                    Set<CatalogReference> warehouses = snapshot.scopeGrants().stream()
                            .filter(grant -> grant.scopeType() == scope)
                            .flatMap(grant -> grant.warehouses().stream())
                            .map(reference -> new CatalogReference(
                                    Long.toString(reference.id()), reference.version()))
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                    return new DataScopeGrant(
                            DataScopeType.valueOf(scope.name()), organizations, warehouses);
                })
                .toList();
        Set<SensitiveFieldPermission> fieldPermissions = snapshot.fieldPermissions().stream()
                .map(SensitiveFieldPermission::valueOf)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return new com.fams.product.contract.model.PermissionSnapshot(
                snapshot.userId(),
                snapshot.permissions(),
                scopes,
                fieldPermissions,
                snapshot.authorizationVersion());
    }
}
