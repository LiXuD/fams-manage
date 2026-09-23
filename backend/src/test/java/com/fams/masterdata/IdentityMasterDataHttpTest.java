package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = IdentityMasterDataHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=fams-product",
            "spring.flyway.locations=classpath:db/migration/d6_04",
            "spring.session.jdbc.initialize-schema=never",
            "mybatis.configuration.map-underscore-to-camel-case=true",
            "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
            "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
        })
@EnabledIfSystemProperty(named = "d6.05.identity.runtime", matches = "true")
class IdentityMasterDataHttpTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureTaskDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("D6_05_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("D6_05_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("D6_05_TEST_DATASOURCE_PASSWORD"));
    }

    @BeforeEach
    void seedAccounts() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        jdbcTemplate.update("DELETE FROM fams_security_event");
        jdbcTemplate.update("DELETE FROM fams_idempotency_record");
        jdbcTemplate.update("DELETE FROM fams_brand");
        jdbcTemplate.update("DELETE FROM fams_manufacturer");
        jdbcTemplate.update("UPDATE fams_location SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM fams_location");
        jdbcTemplate.update("UPDATE fams_asset_category SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM fams_asset_category");
        jdbcTemplate.update("DELETE FROM fams_account_role");
        jdbcTemplate.update("DELETE FROM fams_system_account");
        jdbcTemplate.update("DELETE FROM fams_employee");
        jdbcTemplate.update("UPDATE fams_organization SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM fams_organization");

        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update(
                "INSERT INTO fams_organization "
                        + "(id, code, code_normalized, name, status, version, created_at, updated_at) "
                        + "VALUES (1, 'ROOT', 'ROOT', 'Root', 'ACTIVE', 0, ?, ?)",
                now,
                now);
        insertEmployee(1, "ADMIN", "Administrator", now);
        insertEmployee(2, "USER", "Regular user", now);
        insertAccount(1, 1, "admin", now);
        insertAccount(2, 2, "user", now);
        jdbcTemplate.update(
                "INSERT INTO fams_account_role (account_id, role_code, granted_at) VALUES (1, 'SYSTEM_ADMIN', ?)",
                now);
        jdbcTemplate.update(
                "INSERT INTO fams_account_role (account_id, role_code, granted_at) VALUES (2, 'EMPLOYEE', ?)",
                now);
    }

    @Test
    void systemAdministratorCanCreateOrganizationAndEmployee() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> organization = request(
                "POST",
                "/api/v1/organizations",
                "{\"code\":\"OPS\",\"name\":\"Operations\","
                        + "\"parentOrganization\":{\"id\":\"1\",\"version\":0}}",
                admin,
                writeHeaders("11111111-1111-4111-8111-111111111111", admin));
        assertEquals(201, organization.statusCode());
        JsonNode createdOrganization = JSON.readTree(organization.body());

        HttpResponse<String> employee = request(
                "POST",
                "/api/v1/employees",
                "{\"employeeNo\":\"E100\",\"displayName\":\"Imported User\","
                        + "\"organization\":{\"id\":\""
                        + createdOrganization.path("id").asText()
                        + "\",\"version\":0},\"phone\":\"13800138000\"}",
                admin,
                writeHeaders("22222222-2222-4222-8222-222222222222", admin));

        assertEquals(201, employee.statusCode());
        assertEquals("E100", JSON.readTree(employee.body()).path("employeeNo").asText());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_employee WHERE employee_no_normalized = 'E100' "
                        + "AND mobile_ciphertext IS NOT NULL AND mobile_lookup_hash IS NOT NULL",
                Integer.class));
    }

    @Test
    void nonAdministratorIsRejectedBeforeMutation() throws Exception {
        Session user = login("user");
        HttpResponse<String> response = request(
                "POST",
                "/api/v1/organizations",
                "{\"code\":\"OPS\",\"name\":\"Operations\"}",
                user,
                writeHeaders("33333333-3333-4333-8333-333333333333", user));

        assertEquals(403, response.statusCode());
        assertEquals("AUTH_FORBIDDEN", JSON.readTree(response.body()).path("code").asText());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_organization WHERE code_normalized = 'OPS'", Integer.class));
        HttpResponse<String> importResponse = request(
                "POST",
                "/api/v1/identity-imports",
                "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion\n"
                        + "ORGANIZATION,CREATE,OPS,Operations,ROOT,,,\n",
                user,
                csvHeaders("33333333-3333-4333-8333-333333333334", user));
        assertEquals(403, importResponse.statusCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_organization WHERE code_normalized = 'OPS'", Integer.class));
    }

    @Test
    void manufacturerWritesEnforceRolesVersionsIdempotencyAndActiveSelectors() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String key = "88111111-1111-4111-8111-111111111111";
        HttpResponse<String> denied = request("POST", "/api/v1/manufacturers",
                "{\"code\":\"ACME\",\"name\":\"Acme\"}", user, writeHeaders(key, user));
        assertEquals(403, denied.statusCode());

        HttpResponse<String> created = request("POST", "/api/v1/manufacturers",
                "{\"code\":\"ACME\",\"name\":\"Acme\",\"description\":\"Maker\"}",
                admin, writeHeaders(key, admin));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode createdBody = JSON.readTree(created.body());
        String id = createdBody.path("id").asText();
        assertEquals("ACME", createdBody.path("code").asText());
        assertEquals(created.body(), request("POST", "/api/v1/manufacturers",
                "{\"code\":\"ACME\",\"name\":\"Acme\",\"description\":\"Maker\"}", admin,
                writeHeaders(key, admin)).body());
        HttpResponse<String> keyConflict = request("POST", "/api/v1/manufacturers",
                "{\"code\":\"OTHER\",\"name\":\"Other\"}", admin, writeHeaders(key, admin));
        assertEquals(409, keyConflict.statusCode());
        assertEquals(400, request("POST", "/api/v1/manufacturers",
                "{\"code\":\" \",\"name\":\"Invalid\"}", admin,
                writeHeaders("88666666-6666-4666-8666-666666666666", admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/manufacturers",
                "{\"code\":\"LONG\",\"name\":\"Long\",\"description\":\""
                        + "x".repeat(501) + "\"}", admin,
                writeHeaders("88777777-7777-4777-8777-777777777777", admin)).statusCode());

        HttpResponse<String> stale = request("PATCH", "/api/v1/manufacturers/" + id,
                "{\"name\":\"Updated\"}", admin,
                versionedHeaders("88222222-2222-4222-8222-222222222222", admin, 3));
        assertEquals(409, stale.statusCode());
        HttpResponse<String> updated = request("PATCH", "/api/v1/manufacturers/" + id,
                "{\"name\":\"Updated\"}", admin,
                versionedHeaders("88333333-3333-4333-8333-333333333333", admin, 0));
        assertEquals(200, updated.statusCode());
        assertEquals(1, JSON.readTree(updated.body()).path("version").asLong());
        HttpResponse<String> renamed = request("PATCH", "/api/v1/manufacturers/" + id,
                "{\"code\":\"ACME-NEW\",\"description\":\"Updated maker\"}", admin,
                versionedHeaders("88888888-8888-4888-8888-888888888888", admin, 1));
        assertEquals(200, renamed.statusCode());
        assertEquals("ACME-NEW", JSON.readTree(renamed.body()).path("code").asText());

        HttpResponse<String> disabled = request("POST", "/api/v1/manufacturers/" + id + "/disable",
                "{\"reason\":\"Inactive\"}", admin,
                versionedHeaders("88444444-4444-4444-8444-444444444444", admin, 2));
        assertEquals(200, disabled.statusCode());
        assertEquals("DISABLED", JSON.readTree(disabled.body()).path("status").asText());
        HttpResponse<String> alreadyDisabled = request("POST", "/api/v1/manufacturers/" + id + "/disable",
                "{\"reason\":\"Still inactive\"}", admin,
                versionedHeaders("88999999-9999-4999-8999-999999999999", admin, 3));
        assertEquals(409, alreadyDisabled.statusCode());
        HttpResponse<String> duplicate = request("POST", "/api/v1/manufacturers",
                "{\"code\":\"acme-new\",\"name\":\"Duplicate\"}", admin,
                writeHeaders("88555555-5555-4555-8555-555555555555", admin));
        assertEquals(409, duplicate.statusCode());
        assertEquals("MANUFACTURER_CODE_CONFLICT", JSON.readTree(duplicate.body()).path("code").asText());

        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                + "VALUES (2, 'WAREHOUSE_OPERATOR', ?)", now);
        Session warehouseOperator = login("user");
        HttpResponse<String> references = request("GET", "/api/v1/manufacturers/active-references",
                null, warehouseOperator, Map.of());
        assertEquals(200, references.statusCode());
        assertEquals(0, JSON.readTree(references.body()).path("total").asInt());
        HttpResponse<String> enabled = request("POST", "/api/v1/manufacturers/" + id + "/enable",
                "{\"reason\":\"Reactivated\"}", admin,
                versionedHeaders("88101010-1010-4010-8010-101010101010", admin, 3));
        assertEquals(200, enabled.statusCode());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/manufacturers/active-references",
                null, warehouseOperator, Map.of()).body()).path("total").asInt());
        assertEquals(403, request("GET", "/api/v1/manufacturers", null,
                warehouseOperator, Map.of()).statusCode());
    }

    @Test
    void brandReferencesTrackManufacturerStatusWithoutRewritingHistory() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> manufacturer = request("POST", "/api/v1/manufacturers",
                "{\"code\":\"MAKER\",\"name\":\"Maker\"}", admin,
                writeHeaders("89111111-1111-4111-8111-111111111111", admin));
        assertEquals(201, manufacturer.statusCode());
        String manufacturerId = JSON.readTree(manufacturer.body()).path("id").asText();

        String brandRequest = "{\"code\":\"BRAND\",\"name\":\"Brand\","
                + "\"defaultManufacturer\":{\"id\":\"" + manufacturerId + "\",\"version\":0}}";
        HttpResponse<String> brand = request("POST", "/api/v1/brands", brandRequest, admin,
                writeHeaders("89222222-2222-4222-8222-222222222222", admin));
        assertEquals(201, brand.statusCode(), brand.body());
        String brandId = JSON.readTree(brand.body()).path("id").asText();
        assertEquals(manufacturerId,
                JSON.readTree(brand.body()).path("defaultManufacturer").path("id").asText());

        HttpResponse<String> staleManufacturer = request("POST", "/api/v1/brands",
                "{\"code\":\"STALE\",\"name\":\"Stale\","
                        + "\"defaultManufacturer\":{\"id\":\"" + manufacturerId + "\",\"version\":9}}",
                admin, writeHeaders("89333333-3333-4333-8333-333333333333", admin));
        assertEquals(409, staleManufacturer.statusCode());
        assertEquals("BRAND_DEFAULT_MANUFACTURER_VERSION_CONFLICT",
                JSON.readTree(staleManufacturer.body()).path("code").asText());

        HttpResponse<String> disabledManufacturer = request("POST",
                "/api/v1/manufacturers/" + manufacturerId + "/disable",
                "{\"reason\":\"Inactive maker\"}", admin,
                versionedHeaders("89444444-4444-4444-8444-444444444444", admin, 0));
        assertEquals(200, disabledManufacturer.statusCode());
        HttpResponse<String> rejectedDefault = request("POST", "/api/v1/brands",
                "{\"code\":\"NEW\",\"name\":\"New\","
                        + "\"defaultManufacturer\":{\"id\":\"" + manufacturerId + "\",\"version\":1}}",
                admin, writeHeaders("89555555-5555-4555-8555-555555555555", admin));
        assertEquals(409, rejectedDefault.statusCode());
        assertEquals("BRAND_DEFAULT_MANUFACTURER_DISABLED",
                JSON.readTree(rejectedDefault.body()).path("code").asText());

        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                + "VALUES (2, 'ASSET_ADMIN', ?)", now);
        Session assetAdmin = login("user");
        JsonNode references = JSON.readTree(request("GET", "/api/v1/brands/active-references",
                null, assetAdmin, Map.of()).body());
        assertEquals(1, references.path("total").asInt());
        assertTrue(references.path("items").get(0).path("defaultManufacturerId").isMissingNode());
        JsonNode historical = JSON.readTree(request("GET", "/api/v1/brands/" + brandId,
                null, admin, Map.of()).body());
        assertEquals(manufacturerId, historical.path("defaultManufacturer").path("id").asText());
    }

    @Test
    void brandWritesEnforcePermissionsVersionsIdempotencyAndStatus() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String key = "8a111111-1111-4111-8111-111111111111";
        String createBody = "{\"code\":\"BRAND-X\",\"name\":\"Brand X\"}";
        assertEquals(403, request("POST", "/api/v1/brands", createBody, user,
                writeHeaders(key, user)).statusCode());
        HttpResponse<String> created = request("POST", "/api/v1/brands", createBody, admin,
                writeHeaders(key, admin));
        assertEquals(201, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(created.body(), request("POST", "/api/v1/brands", createBody, admin,
                writeHeaders(key, admin)).body());
        assertEquals(409, request("POST", "/api/v1/brands",
                "{\"code\":\"OTHER\",\"name\":\"Other\"}", admin,
                writeHeaders(key, admin)).statusCode());
        assertEquals(409, request("POST", "/api/v1/brands",
                "{\"code\":\"brand-x\",\"name\":\"Duplicate\"}", admin,
                writeHeaders("8a222222-2222-4222-8222-222222222222", admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/brands",
                "{\"code\":\" \",\"name\":\"Blank\"}", admin,
                writeHeaders("8a333333-3333-4333-8333-333333333333", admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/brands",
                "{\"code\":\"LONG\",\"name\":\"Long\",\"description\":\""
                        + "x".repeat(501) + "\"}", admin,
                writeHeaders("8a444444-4444-4444-8444-444444444444", admin)).statusCode());

        assertEquals(409, request("PATCH", "/api/v1/brands/" + id,
                "{\"name\":\"Changed\"}", admin,
                versionedHeaders("8a555555-5555-4555-8555-555555555555", admin, 9)).statusCode());
        HttpResponse<String> updated = request("PATCH", "/api/v1/brands/" + id,
                "{\"name\":\"Changed\",\"description\":\"Details\"}", admin,
                versionedHeaders("8a666666-6666-4666-8666-666666666666", admin, 0));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(1, JSON.readTree(updated.body()).path("version").asLong());
        assertEquals("Details", JSON.readTree(updated.body()).path("description").asText());
        HttpResponse<String> disabled = request("POST", "/api/v1/brands/" + id + "/disable",
                "{\"reason\":\"Retired\"}", admin,
                versionedHeaders("8a777777-7777-4777-8777-777777777777", admin, 1));
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals("DISABLED", JSON.readTree(disabled.body()).path("status").asText());
        assertEquals(409, request("POST", "/api/v1/brands/" + id + "/disable",
                "{\"reason\":\"Still retired\"}", admin,
                versionedHeaders("8a888888-8888-4888-8888-888888888888", admin, 2)).statusCode());
        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                + "VALUES (2, 'WAREHOUSE_OPERATOR', ?)", now);
        Session warehouseOperator = login("user");
        assertEquals(0, JSON.readTree(request("GET", "/api/v1/brands/active-references",
                null, warehouseOperator, Map.of()).body()).path("total").asInt());
        HttpResponse<String> enabled = request("POST", "/api/v1/brands/" + id + "/enable",
                "{\"reason\":\"Available again\"}", admin,
                versionedHeaders("8a999999-9999-4999-8999-999999999999", admin, 2));
        assertEquals(200, enabled.statusCode(), enabled.body());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/brands/active-references",
                null, warehouseOperator, Map.of()).body()).path("total").asInt());
        assertEquals(403, request("GET", "/api/v1/brands", null,
                warehouseOperator, Map.of()).statusCode());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_brand WHERE code_normalized = 'OTHER'", Integer.class));
    }

    @Test
    void disablingEmployeeDisablesAccountAndInvalidatesExistingSession() throws Exception {
        Session admin = login("admin");
        Session user = login("user");

        HttpResponse<String> disabled = request(
                "POST",
                "/api/v1/employees/2/disable",
                "{\"reason\":\"Employment ended\"}",
                admin,
                versionedHeaders("44444444-4444-4444-8444-444444444444", admin, 0));

        assertEquals(200, disabled.statusCode());
        assertEquals("DISABLED", JSON.readTree(disabled.body()).path("status").asText());
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT status, auth_epoch FROM fams_system_account WHERE id = 2");
        assertEquals("DISABLED", account.get("status"));
        assertEquals(1L, ((Number) account.get("auth_epoch")).longValue());
        assertEquals(401, request("GET", "/api/v1/auth/me", null, user, Map.of()).statusCode());
    }

    @Test
    void organizationDisableRejectsActiveDependenciesAndVersionConflicts() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> dependent = request(
                "POST",
                "/api/v1/organizations/1/disable",
                "{\"reason\":\"Close root\"}",
                admin,
                versionedHeaders("55555555-5555-4555-8555-555555555555", admin, 0));
        assertEquals(409, dependent.statusCode());
        assertEquals("MASTER_DATA_DEPENDENCY_CONFLICT", JSON.readTree(dependent.body()).path("code").asText());

        HttpResponse<String> stale = request(
                "PATCH",
                "/api/v1/organizations/1",
                "{\"name\":\"Renamed\"}",
                admin,
                versionedHeaders("66666666-6666-4666-8666-666666666666", admin, 9));
        assertEquals(409, stale.statusCode());
        assertEquals("MASTER_DATA_VERSION_CONFLICT", JSON.readTree(stale.body()).path("code").asText());
    }

    @Test
    void identityCsvTemplateImportsCreateUpdateAndDisableWithoutImplicitDeletion() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> template = request(
                "GET", "/api/v1/identity-imports/template", null, admin, Map.of());
        assertEquals(200, template.statusCode());
        assertTrue(template.body().startsWith(
                "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion"));

        String csv = "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion\n"
                + "ORGANIZATION,CREATE,OPS,Operations,ROOT,,,\n"
                + "EMPLOYEE,CREATE,E200,Template User,,OPS,13900139000,\n";
        HttpResponse<String> imported = request(
                "POST",
                "/api/v1/identity-imports",
                csv,
                admin,
                csvHeaders("77777777-7777-4777-8777-777777777777", admin));
        assertEquals(200, imported.statusCode());
        JsonNode result = JSON.readTree(imported.body());
        assertEquals(2, result.path("processedRows").asInt());
        assertEquals(2, result.path("createdRows").asInt());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_employee WHERE status = 'ACTIVE'", Integer.class));

        String updateAndDisable = "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion\n"
                + "ORGANIZATION,UPDATE,OPS,Operations Center,ROOT,,,0\n"
                + "EMPLOYEE,DISABLE,E200,,,,,0\n";
        HttpResponse<String> changed = request(
                "POST",
                "/api/v1/identity-imports",
                updateAndDisable,
                admin,
                csvHeaders("88888888-8888-4888-8888-888888888888", admin));
        assertEquals(200, changed.statusCode());
        assertEquals("Operations Center", jdbcTemplate.queryForObject(
                "SELECT name FROM fams_organization WHERE code_normalized = 'OPS'", String.class));
        assertEquals("DISABLED", jdbcTemplate.queryForObject(
                "SELECT status FROM fams_employee WHERE employee_no_normalized = 'E200'", String.class));
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM fams_employee WHERE employee_no_normalized = 'USER'", String.class));

        String failingBatch = "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion\n"
                + "ORGANIZATION,CREATE,ROLLBACK,Must Roll Back,ROOT,,,\n"
                + "EMPLOYEE,UPDATE,E200,Stale User,,OPS,,99\n";
        HttpResponse<String> rejected = request(
                "POST",
                "/api/v1/identity-imports",
                failingBatch,
                admin,
                csvHeaders("99999999-9999-4999-8999-999999999999", admin));
        assertEquals(409, rejected.statusCode());
        assertEquals("MASTER_DATA_VERSION_CONFLICT", JSON.readTree(rejected.body()).path("code").asText());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_organization WHERE code_normalized = 'ROLLBACK'", Integer.class));
    }

    @Test
    void organizationWritesRejectDuplicatesCyclesAndReusedIdempotencyKeys() throws Exception {
        Session admin = login("admin");
        String create = "{\"code\":\"OPS\",\"name\":\"Operations\","
                + "\"parentOrganization\":{\"id\":\"1\",\"version\":0}}";
        Map<String, String> firstHeaders = writeHeaders("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", admin);
        HttpResponse<String> first = request("POST", "/api/v1/organizations", create, admin, firstHeaders);
        assertEquals(201, first.statusCode());
        String opsId = JSON.readTree(first.body()).path("id").asText();
        HttpResponse<String> replay = request("POST", "/api/v1/organizations", create, admin, firstHeaders);
        assertEquals(201, replay.statusCode());
        assertEquals(opsId, JSON.readTree(replay.body()).path("id").asText());
        HttpResponse<String> changedPayload = request(
                "POST", "/api/v1/organizations",
                "{\"code\":\"DIFFERENT\",\"name\":\"Different\"}", admin, firstHeaders);
        assertEquals(409, changedPayload.statusCode());
        assertEquals("MASTER_DATA_IDEMPOTENCY_CONFLICT",
                JSON.readTree(changedPayload.body()).path("code").asText());
        HttpResponse<String> duplicate = request(
                "POST", "/api/v1/organizations", create, admin,
                writeHeaders("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", admin));
        assertEquals(409, duplicate.statusCode());

        HttpResponse<String> child = request(
                "POST", "/api/v1/organizations",
                "{\"code\":\"TEAM\",\"name\":\"Team\","
                        + "\"parentOrganization\":{\"id\":\"" + opsId + "\",\"version\":0}}",
                admin, writeHeaders("cccccccc-cccc-4ccc-8ccc-cccccccccccc", admin));
        assertEquals(201, child.statusCode());
        String childId = JSON.readTree(child.body()).path("id").asText();
        HttpResponse<String> cycle = request(
                "PATCH", "/api/v1/organizations/" + opsId,
                "{\"parentOrganization\":{\"id\":\"" + childId + "\",\"version\":0}}",
                admin, versionedHeaders("dddddddd-dddd-4ddd-8ddd-dddddddddddd", admin, 0));
        assertEquals(409, cycle.statusCode());
        assertEquals("MASTER_DATA_DEPENDENCY_CONFLICT", JSON.readTree(cycle.body()).path("code").asText());

        HttpResponse<String> renamed = request(
                "PATCH", "/api/v1/organizations/" + opsId,
                "{\"name\":\"Operations East\"}", admin,
                versionedHeaders("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", admin, 0));
        assertEquals(200, renamed.statusCode());
        assertEquals(1, JSON.readTree(renamed.body()).path("version").asInt());
        assertEquals("Operations East", JSON.readTree(renamed.body()).path("name").asText());
    }

    @Test
    void employeeUpdatePreservesPhoneAndStatusTransitionsKeepAccountDisabled() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> created = request(
                "POST", "/api/v1/employees",
                "{\"employeeNo\":\"E300\",\"displayName\":\"Initial\","
                        + "\"organization\":{\"id\":\"1\",\"version\":0},"
                        + "\"phone\":\"13800138000\"}",
                admin, writeHeaders("12121212-1212-4212-8212-121212121212", admin));
        assertEquals(201, created.statusCode());
        String employeeId = JSON.readTree(created.body()).path("id").asText();
        byte[] before = jdbcTemplate.queryForObject(
                "SELECT mobile_ciphertext FROM fams_employee WHERE id = ?", byte[].class,
                Long.parseLong(employeeId));
        HttpResponse<String> updated = request(
                "PATCH", "/api/v1/employees/" + employeeId,
                "{\"displayName\":\"Updated\"}", admin,
                versionedHeaders("13131313-1313-4313-8313-131313131313", admin, 0));
        assertEquals(200, updated.statusCode());
        assertEquals("Updated", JSON.readTree(updated.body()).path("displayName").asText());
        byte[] after = jdbcTemplate.queryForObject(
                "SELECT mobile_ciphertext FROM fams_employee WHERE id = ?", byte[].class,
                Long.parseLong(employeeId));
        assertTrue(java.util.Arrays.equals(before, after));

        HttpResponse<String> stale = request(
                "PATCH", "/api/v1/employees/" + employeeId,
                "{\"displayName\":\"Stale\"}", admin,
                versionedHeaders("14141414-1414-4414-8414-141414141414", admin, 0));
        assertEquals(409, stale.statusCode());
        HttpResponse<String> disabled = request(
                "POST", "/api/v1/employees/" + employeeId + "/disable",
                "{\"reason\":\"Left\"}", admin,
                versionedHeaders("15151515-1515-4515-8515-151515151515", admin, 1));
        assertEquals(200, disabled.statusCode());
        HttpResponse<String> repeatedDisable = request(
                "POST", "/api/v1/employees/" + employeeId + "/disable",
                "{\"reason\":\"Still left\"}", admin,
                versionedHeaders("16161616-1616-4616-8616-161616161616", admin, 2));
        assertEquals(409, repeatedDisable.statusCode());
        HttpResponse<String> enabled = request(
                "POST", "/api/v1/employees/" + employeeId + "/enable",
                "{\"reason\":\"Returned\"}", admin,
                versionedHeaders("17171717-1717-4717-8717-171717171717", admin, 2));
        assertEquals(200, enabled.statusCode());
        assertEquals("ACTIVE", JSON.readTree(enabled.body()).path("status").asText());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_system_account WHERE employee_id = ? AND status = 'ENABLED'",
                Integer.class, Long.parseLong(employeeId)));
    }

    private void insertEmployee(long id, String number, String name, Timestamp now) {
        jdbcTemplate.update(
                "INSERT INTO fams_employee "
                        + "(id, employee_no, employee_no_normalized, name, organization_id, status, version, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'ACTIVE', 0, ?, ?)",
                id,
                number,
                number,
                name,
                now,
                now);
    }

    private void insertAccount(long id, long employeeId, String username, Timestamp now) {
        jdbcTemplate.update(
                "INSERT INTO fams_system_account "
                        + "(id, employee_id, username, username_normalized, password_hash, status, "
                        + "initial_password_required, failed_attempts, auth_epoch, authorization_version, "
                        + "version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ENABLED', FALSE, 0, 0, 0, 0, ?, ?)",
                id,
                employeeId,
                username,
                username.toUpperCase(),
                new BCryptPasswordEncoder(4).encode(PASSWORD),
                now,
                now);
    }

    private Session login(String username) throws Exception {
        HttpResponse<String> response = request(
                "POST",
                "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                Map.of("Content-Type", "application/json"));
        assertEquals(200, response.statusCode());
        return new Session(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).path("csrfToken").asText());
    }

    private Map<String, String> writeHeaders(String idempotencyKey, Session session) {
        return Map.of(
                "Content-Type", "application/json",
                "Idempotency-Key", idempotencyKey,
                "X-CSRF-Token", session.csrfToken());
    }

    private Map<String, String> versionedHeaders(String key, Session session, long version) {
        return Map.of(
                "Content-Type", "application/json",
                "Idempotency-Key", key,
                "X-CSRF-Token", session.csrfToken(),
                "If-Match-Version", Long.toString(version));
    }

    private Map<String, String> csvHeaders(String key, Session session) {
        return Map.of(
                "Content-Type", "text/csv",
                "Idempotency-Key", key,
                "X-CSRF-Token", session.csrfToken());
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String body,
            Session session,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(request::header);
        if (session != null) {
            request.header("Cookie", session.cookie());
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private record Session(String cookie, String csrfToken) {
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {
        public static void main(String[] args) {
            SpringApplication.run(TestApplication.class, args);
        }
    }
}
