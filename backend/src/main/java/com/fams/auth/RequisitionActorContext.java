package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Credential revalidation is performed before business locks and idempotent replies. */
@Component
@Profile("fams-product")
public final class RequisitionActorContext {
    public record Actor(
            long id,
            long authEpoch,
            long authorizationVersion,
            Instant expiresAt,
            String miniToken,
            UUID requestId) {}

    public record Current(
            long id, String username, long employeeId, long organizationId, Set<Role> roles) {
        public Current {
            roles = Set.copyOf(roles);
        }

        public boolean has(Role role) {
            return roles.contains(role);
        }

        public boolean globalApplicant() {
            return has(Role.ASSET_ADMIN) || has(Role.WAREHOUSE_OPERATOR);
        }
    }

    private final HttpServletRequest request;
    private final AuthAccountStore accounts;
    private final MiniSessionService miniSessions;
    private final JdbcTemplate db;
    private final Clock clock;

    RequisitionActorContext(
            HttpServletRequest request,
            AuthAccountStore accounts,
            MiniSessionService miniSessions,
            JdbcTemplate db,
            Clock clock) {
        this.request = request;
        this.accounts = accounts;
        this.miniSessions = miniSessions;
        this.db = db;
        this.clock = clock;
    }

    public Actor capture() {
        var account = AuthRequestContext.account(request);
        if (request.getRequestURI().startsWith("/api/v1/mini/")) {
            var current =
                    (MiniSessionService.Current) request.getAttribute(MiniAuthController.CURRENT);
            return new Actor(
                    account.id(),
                    account.authEpoch(),
                    account.authorizationVersion(),
                    current.absoluteExpiresAt(),
                    request.getHeader(MiniAuthController.HEADER),
                    AuthRequestContext.requestId(request));
        }
        var session = AuthRequestContext.session(request);
        return new Actor(
                account.id(),
                session.authEpoch(),
                session.authorizationVersion(),
                session.absoluteExpiresAt(),
                null,
                AuthRequestContext.requestId(request));
    }

    public Current revalidateLocked(Actor actor) {
        return revalidateBusinessIdentity(actor, false);
    }

    /** Repair invitation owners must separately authorize the participant and minimal fields. */
    public Current revalidateRepairParticipantLocked(Actor actor) {
        return revalidateBusinessIdentity(actor, true);
    }

    private Current revalidateBusinessIdentity(Actor actor, boolean repairParticipant) {
        if (db.queryForList("SELECT id FROM fams_system_account WHERE id=? FOR UPDATE", actor.id())
                .isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        var account =
                accounts.findById(actor.id()).orElseThrow(AuthOperationException::sessionInvalid);
        if (!AuthSessionValidator.isCurrent(
                        account,
                        actor.authEpoch(),
                        actor.authorizationVersion(),
                        actor.expiresAt(),
                        clock.instant())
                || account.initialPasswordRequired()) {
            throw AuthOperationException.sessionInvalid();
        }
        if (actor.miniToken() != null
                && miniSessions.requireCurrent(actor.miniToken()).account().id() != actor.id()) {
            throw AuthOperationException.sessionInvalid();
        }
        if (account.roles().stream()
                .noneMatch(
                        role ->
                                role == Role.EMPLOYEE
                                        || repairParticipant && role == Role.TECH_ASSISTANT
                                        || role == Role.DEPARTMENT_ASSET_MANAGER
                                        || role == Role.ASSET_ADMIN
                                        || role == Role.WAREHOUSE_OPERATOR)) {
            throw AuthOperationException.forbidden();
        }
        var people =
                db.queryForList(
                        "SELECT e.id,e.organization_id FROM fams_employee e JOIN"
                            + " fams_system_account a ON a.employee_id=e.id JOIN fams_organization"
                            + " o ON o.id=e.organization_id WHERE a.id=? AND e.status='ACTIVE' AND"
                            + " o.status='ACTIVE' FOR UPDATE",
                        actor.id());
        if (people.isEmpty()) {
            throw AuthOperationException.sessionInvalid();
        }
        var person = people.getFirst();
        return new Current(
                account.id(),
                account.username(),
                ((Number) person.get("id")).longValue(),
                ((Number) person.get("organization_id")).longValue(),
                account.roles());
    }
}
