package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.CurrentUser;
import com.fams.product.contract.model.DataScopeGrant;
import com.fams.product.contract.model.DataScopeType;
import com.fams.product.contract.model.MiniBindingChallenge;
import com.fams.product.contract.model.MiniBindingResult;
import com.fams.product.contract.model.MiniBindingSupport;
import com.fams.product.contract.model.MiniPhoneCorrectionResult;
import com.fams.product.contract.model.MiniSessionGrant;
import com.fams.product.contract.model.MiniSessionView;
import com.fams.product.contract.model.OrganizationReference;
import com.fams.product.contract.model.PermissionSnapshot;
import com.fams.product.contract.model.Role;
import com.fams.product.contract.model.SensitiveFieldPermission;
import com.fams.product.contract.model.SessionResponse;
import com.fams.product.contract.model.SystemAccountStatus;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JAVA-14a wire-shape freeze: the auth HTTP surface is already fully typed before this batch
 * (all five controllers return generated contract models from com.fams.product.contract.model,
 * never raw Map/Object), so instead of a pre-refactor projection oracle this test freezes the
 * current serialized shape of every auth projection as a literal JSON tree. The row/snapshot
 * to contract-model assembly expressions below are copied verbatim from the current controllers
 * (AuthController.toSessionResponse/toCurrentUser/toPermissionSnapshot, MiniAuthController
 * miniLogin/miniCurrentSession, MiniBindingController challenge/result) so a future refactor
 * must keep both the key set and the extraction semantics. Comparison round-trips through JSON
 * text (integral widths normalize, key order ignored); key presence and explicit nulls count.
 * The mapper mirrors the production HTTP mapper: com.fams.contract.probe.JsonConfiguration
 * restores ALWAYS value inclusion on top of the Jackson 3 NON_NULL default, so null components
 * (the whole UNBOUND mini session grant) serialize as explicit null keys.
 */
class AuthWireShapeTest {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private static final Instant IDLE = Instant.parse("2026-11-22T02:30:00Z");
    private static final Instant ABSOLUTE = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void loginSessionResponseFreezesSortedRolesAndUtcExpiry() {
        AuthAccount account = account(11L, "ops-a", AccountStatus.ENABLED, false,
                Set.of(com.fams.auth.Role.ASSET_ADMIN, com.fams.auth.Role.EMPLOYEE));
        // Verbatim extraction from AuthController.toSessionResponse.
        AuthSession session = new AuthSession(11L, 3L, 7L, "csrf-token-1", IDLE, ABSOLUTE, false);
        SessionResponse typed = new SessionResponse(
                session.csrfToken(),
                OffsetDateTime.ofInstant(session.idleExpiresAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(session.absoluteExpiresAt(), ZoneOffset.UTC),
                session.requiresInitialPasswordChange(),
                toCurrentUser(account));
        assertWireEquals("""
                {"csrfToken":"csrf-token-1","idleExpiresAt":"2026-11-22T02:30:00Z",
                 "absoluteExpiresAt":"2026-11-22T10:00:00Z","requiresInitialPasswordChange":false,
                 "user":{"id":"11","username":"ops-a","status":"ENABLED",
                  "roles":["ASSET_ADMIN","EMPLOYEE"],"requiresInitialPasswordChange":false}}""", typed);
        assertKeys(typed, "csrfToken", "idleExpiresAt", "absoluteExpiresAt",
                "requiresInitialPasswordChange", "user");
        assertKeys(typed.getUser(), "id", "username", "status", "roles",
                "requiresInitialPasswordChange");

        // changeInitialPassword replays the same projection with both flags flipped to true.
        SessionResponse rotated = new SessionResponse(
                "csrf-token-2",
                OffsetDateTime.ofInstant(IDLE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ABSOLUTE, ZoneOffset.UTC),
                true,
                toCurrentUser(account(11L, "ops-a", AccountStatus.ENABLED, true,
                        Set.of(com.fams.auth.Role.ASSET_ADMIN, com.fams.auth.Role.EMPLOYEE))));
        assertWireEquals("""
                {"csrfToken":"csrf-token-2","idleExpiresAt":"2026-11-22T02:30:00Z",
                 "absoluteExpiresAt":"2026-11-22T10:00:00Z","requiresInitialPasswordChange":true,
                 "user":{"id":"11","username":"ops-a","status":"ENABLED",
                  "roles":["ASSET_ADMIN","EMPLOYEE"],"requiresInitialPasswordChange":true}}""", rotated);
    }

    @Test
    void currentUserFreezesStringIdAndEnumNamePassThrough() {
        // Verbatim extraction from AuthController.getCurrentUser (LOCKED + initial password branch).
        CurrentUser typed = toCurrentUser(account(9L, "sys-a", AccountStatus.LOCKED, true,
                Set.of(com.fams.auth.Role.SYSTEM_ADMIN)));
        assertWireEquals("""
                {"id":"9","username":"sys-a","status":"LOCKED","roles":["SYSTEM_ADMIN"],
                 "requiresInitialPasswordChange":true}""", typed);
        assertKeys(typed, "id", "username", "status", "roles", "requiresInitialPasswordChange");
    }

    @Test
    void permissionSnapshotFreezesScopeGrantGroupingAndReferenceVersions() {
        // Verbatim input shape from AuthApplicationService.loadPermissions.
        com.fams.auth.PermissionSnapshot snapshot = new com.fams.auth.PermissionSnapshot(
                "11", List.of("asset:read", "asset:update"),
                List.of(DataScope.SPECIFIED_ORGANIZATIONS, DataScope.SPECIFIED_WAREHOUSES,
                        DataScope.ENTERPRISE),
                List.of("EMPLOYEE_PHONE", "REPAIR_COST"), 4L,
                List.of(new AuthorizedDataScope(DataScope.SPECIFIED_ORGANIZATIONS,
                                List.of(new VersionedReferenceRow(7L, 3L), new VersionedReferenceRow(8L, 1L)),
                                List.of()),
                        new AuthorizedDataScope(DataScope.SPECIFIED_WAREHOUSES, List.of(),
                                List.of(new VersionedReferenceRow(21L, 0L)))));
        // Verbatim extraction from AuthController.toPermissionSnapshot.
        List<DataScopeGrant> scopes = snapshot.dataScopes().stream()
                .map(scope -> {
                    Set<OrganizationReference> organizations = snapshot.scopeGrants().stream()
                            .filter(grant -> grant.scopeType() == scope)
                            .flatMap(grant -> grant.organizations().stream())
                            .map(reference -> new OrganizationReference(
                                    Long.toString(reference.id()), reference.version()))
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    Set<CatalogReference> warehouses = snapshot.scopeGrants().stream()
                            .filter(grant -> grant.scopeType() == scope)
                            .flatMap(grant -> grant.warehouses().stream())
                            .map(reference -> new CatalogReference(
                                    Long.toString(reference.id()), reference.version()))
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    return new DataScopeGrant(
                            DataScopeType.valueOf(scope.name()), organizations, warehouses);
                })
                .toList();
        Set<SensitiveFieldPermission> fieldPermissions = snapshot.fieldPermissions().stream()
                .map(SensitiveFieldPermission::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        PermissionSnapshot typed = new PermissionSnapshot(
                snapshot.userId(), snapshot.permissions(), scopes, fieldPermissions,
                snapshot.authorizationVersion());
        assertWireEquals("""
                {"userId":"11","permissions":["asset:read","asset:update"],
                 "dataScopes":[
                  {"scopeType":"SPECIFIED_ORGANIZATIONS",
                   "organizations":[{"id":"7","version":3},{"id":"8","version":1}],"warehouses":[]},
                  {"scopeType":"SPECIFIED_WAREHOUSES","organizations":[],
                   "warehouses":[{"id":"21","version":0}]},
                  {"scopeType":"ENTERPRISE","organizations":[],"warehouses":[]}],
                 "fieldPermissions":["EMPLOYEE_PHONE","REPAIR_COST"],"authorizationVersion":4}""", typed);
        assertKeys(typed, "userId", "permissions", "dataScopes", "fieldPermissions",
                "authorizationVersion");
        assertKeys(typed.getDataScopes().get(0), "scopeType", "organizations", "warehouses");
        assertKeys(typed.getDataScopes().get(0).getOrganizations().iterator().next(), "id", "version");
    }

    @Test
    void miniLoginGrantFreezesBoundAndAllNullUnboundBranches() {
        // Verbatim extraction from MiniAuthController.miniLogin (BOUND branch).
        MiniSessionService.Grant bound = new MiniSessionService.Grant(
                "BOUND", "tok-abc", "11", "5", IDLE, ABSOLUTE);
        MiniSessionGrant typed = new MiniSessionGrant(
                MiniSessionGrant.BindingStatusEnum.fromValue(bound.bindingStatus()), bound.sessionToken(),
                bound.accountId(), bound.bindingId(), utc(bound.idleExpiresAt()),
                utc(bound.absoluteExpiresAt()));
        assertWireEquals("""
                {"bindingStatus":"BOUND","sessionToken":"tok-abc","accountId":"11","bindingId":"5",
                 "idleExpiresAt":"2026-11-22T02:30:00Z","absoluteExpiresAt":"2026-11-22T10:00:00Z"}""", typed);
        assertKeys(typed, "bindingStatus", "sessionToken", "accountId", "bindingId",
                "idleExpiresAt", "absoluteExpiresAt");

        // UNBOUND branch (MiniSessionService.start no-binding return): every payload component
        // stays an explicit null key under the production ALWAYS inclusion.
        MiniSessionService.Grant unbound = new MiniSessionService.Grant(
                "UNBOUND", null, null, null, null, null);
        MiniSessionGrant unboundTyped = new MiniSessionGrant(
                MiniSessionGrant.BindingStatusEnum.fromValue(unbound.bindingStatus()),
                unbound.sessionToken(), unbound.accountId(), unbound.bindingId(),
                utc(unbound.idleExpiresAt()), utc(unbound.absoluteExpiresAt()));
        assertWireEquals("""
                {"bindingStatus":"UNBOUND","sessionToken":null,"accountId":null,"bindingId":null,
                 "idleExpiresAt":null,"absoluteExpiresAt":null}""", unboundTyped);
        JsonNode wire = JSON.readTree(JSON.writeValueAsString(unboundTyped));
        for (String key : List.of("sessionToken", "accountId", "bindingId",
                "idleExpiresAt", "absoluteExpiresAt")) {
            assertTrue(wire.has(key), key + " must stay an explicit null key");
        }
    }

    @Test
    void miniCurrentSessionFreezesBoundView() {
        // Verbatim extraction from MiniAuthController.miniCurrentSession.
        MiniSessionService.Current current = new MiniSessionService.Current(
                account(11L, "ops-a", AccountStatus.ENABLED, false,
                        Set.of(com.fams.auth.Role.EMPLOYEE)), "5", 2L, IDLE, ABSOLUTE);
        MiniSessionView typed = new MiniSessionView(
                MiniSessionView.BindingStatusEnum.BOUND, Long.toString(current.account().id()),
                current.bindingId(), current.bindingVersion(), utc(current.idleExpiresAt()),
                utc(current.absoluteExpiresAt()));
        assertWireEquals("""
                {"bindingStatus":"BOUND","accountId":"11","bindingId":"5","bindingVersion":2,
                 "idleExpiresAt":"2026-11-22T02:30:00Z","absoluteExpiresAt":"2026-11-22T10:00:00Z"}""", typed);
        assertKeys(typed, "bindingStatus", "accountId", "bindingId", "bindingVersion",
                "idleExpiresAt", "absoluteExpiresAt");
    }

    @Test
    void bindingChallengeAndResultFreezeFixedResendWindowAndStringIds() {
        // Verbatim extraction from MiniBindingController.miniIssueBindingChallenge.
        MiniBindingService.Delivery delivery = new MiniBindingService.Delivery(
                "chal-token", "13800000000", "123456", Instant.parse("2026-11-22T02:10:00Z"));
        MiniBindingChallenge challenge = new MiniBindingChallenge(
                delivery.challengeToken(), delivery.expiresAt().atOffset(ZoneOffset.UTC), 60);
        assertWireEquals("""
                {"challengeToken":"chal-token","expiresAt":"2026-11-22T02:10:00Z",
                 "resendAfterSeconds":60}""", challenge);
        assertKeys(challenge, "challengeToken", "expiresAt", "resendAfterSeconds");

        // Verbatim extraction from MiniBindingController.miniVerifyBinding.
        MiniBindingService.Result result = new MiniBindingService.Result("11", "5", 0L);
        MiniBindingResult typed = new MiniBindingResult(
                result.accountId(), result.bindingId(), result.bindingVersion());
        assertWireEquals("""
                {"accountId":"11","bindingId":"5","bindingVersion":0}""", typed);
        assertKeys(typed, "accountId", "bindingId", "bindingVersion");

        // Verbatim extraction from MiniBindingController.miniBindingSupport and
        // MiniPhoneCorrectionController.correctMiniEmployeePhone.
        assertWireEquals("{\"supportText\":\"请拨打 400-000-0000\"}",
                new MiniBindingSupport("请拨打 400-000-0000"));
        assertWireEquals("{\"employeeId\":\"3\",\"version\":4}",
                new MiniPhoneCorrectionResult("3", 4L));
    }

    @Test
    void everyAuthResponseModelRoundTripsThroughJsonText() {
        AuthAccount account = account(11L, "ops-a", AccountStatus.ENABLED, false,
                Set.of(com.fams.auth.Role.ASSET_ADMIN, com.fams.auth.Role.EMPLOYEE));
        SessionResponse session = new SessionResponse("csrf-token-1",
                OffsetDateTime.ofInstant(IDLE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ABSOLUTE, ZoneOffset.UTC), false, toCurrentUser(account));
        assertRoundTrip(session, SessionResponse.class);
        assertRoundTrip(toCurrentUser(account), CurrentUser.class);
        assertRoundTrip(new PermissionSnapshot("11", List.of("asset:read"),
                List.of(new DataScopeGrant(DataScopeType.ENTERPRISE, Set.of(), Set.of())),
                Set.of(), 4L), PermissionSnapshot.class);
        assertRoundTrip(new MiniSessionGrant(MiniSessionGrant.BindingStatusEnum.BOUND,
                "tok-abc", "11", "5", OffsetDateTime.ofInstant(IDLE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ABSOLUTE, ZoneOffset.UTC)), MiniSessionGrant.class);
        assertRoundTrip(new MiniSessionGrant(MiniSessionGrant.BindingStatusEnum.UNBOUND,
                null, null, null, null, null), MiniSessionGrant.class);
        assertRoundTrip(new MiniSessionView(MiniSessionView.BindingStatusEnum.BOUND,
                "11", "5", 2L, OffsetDateTime.ofInstant(IDLE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ABSOLUTE, ZoneOffset.UTC)), MiniSessionView.class);
        assertRoundTrip(new MiniBindingChallenge("chal-token",
                OffsetDateTime.ofInstant(IDLE, ZoneOffset.UTC), 60), MiniBindingChallenge.class);
        assertRoundTrip(new MiniBindingResult("11", "5", 0L), MiniBindingResult.class);
        assertRoundTrip(new MiniBindingSupport("support"), MiniBindingSupport.class);
        assertRoundTrip(new MiniPhoneCorrectionResult("3", 4L), MiniPhoneCorrectionResult.class);
    }

    // --- Verbatim copies of the controller projection helpers -------------------------------

    /** Verbatim copy of AuthController.toCurrentUser. */
    private static CurrentUser toCurrentUser(AuthAccount account) {
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

    /** Verbatim copy of MiniAuthController.utc. */
    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static AuthAccount account(long id, String username, AccountStatus status,
            boolean initialPasswordRequired, Set<com.fams.auth.Role> roles) {
        return new AuthAccount(id, username, "hash", status, initialPasswordRequired, 0, null,
                3L, 7L, roles);
    }

    private static void assertRoundTrip(Object typed, Class<?> type) {
        String stored = JSON.writeValueAsString(typed);
        assertEquals(JSON.readTree(stored),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static void assertWireEquals(String frozenJson, Object typed) {
        assertEquals(JSON.readTree(frozenJson), JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static void assertKeys(Object typed, String... keys) {
        Set<String> actual = new LinkedHashSet<>();
        JSON.readTree(JSON.writeValueAsString(typed)).propertyNames().forEach(actual::add);
        assertEquals(Set.of(keys), actual);
    }
}
