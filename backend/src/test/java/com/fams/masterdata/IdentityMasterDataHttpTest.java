package com.fams.masterdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fams.product.contract.model.NumberingTarget;

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

    @Autowired
    private NumberAllocationService numberAllocations;

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
        jdbcTemplate.update("DELETE FROM fams_number_allocation");
        jdbcTemplate.update("DELETE FROM fams_number_sequence");
        jdbcTemplate.update("DELETE FROM fams_numbering_rule");
        jdbcTemplate.update("UPDATE fams_business_parameter_state "
                + "SET current_version = 0, current_version_id = NULL WHERE singleton_id = 1");
        jdbcTemplate.update("DELETE FROM fams_business_parameter_version");
        jdbcTemplate.update("DELETE FROM fams_brand");
        jdbcTemplate.update("DELETE FROM fams_manufacturer");
        jdbcTemplate.update("UPDATE fams_location SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM fams_location");
        jdbcTemplate.update("DELETE FROM fams_warehouse");
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
    void categoryTreeRejectsCyclesDisabledAncestorsAndStaleReferences() throws Exception {
        Session admin = login("admin");
        String rootRequest = "{\"code\":\"EQUIPMENT\",\"name\":\"Equipment\","
                + "\"suggestedUnit\":\"piece\",\"numberingPrefix\":\"EQ\","
                + "\"extensionFieldTemplate\":[]}";
        HttpResponse<String> root = request("POST", "/api/v1/asset-categories", rootRequest,
                admin, writeHeaders("8b111111-1111-4111-8111-111111111111", admin));
        assertEquals(201, root.statusCode(), root.body());
        String rootId = JSON.readTree(root.body()).path("id").asText();
        HttpResponse<String> child = request("POST", "/api/v1/asset-categories",
                "{\"code\":\"LAPTOP\",\"name\":\"Laptop\","
                        + "\"parentCategory\":{\"id\":\"" + rootId + "\",\"version\":0}}",
                admin, writeHeaders("8b222222-2222-4222-8222-222222222222", admin));
        assertEquals(201, child.statusCode(), child.body());
        String childId = JSON.readTree(child.body()).path("id").asText();
        HttpResponse<String> cycle = request("PATCH", "/api/v1/asset-categories/" + rootId,
                "{\"parentCategory\":{\"id\":\"" + childId + "\",\"version\":0}}",
                admin, versionedHeaders("8b333333-3333-4333-8333-333333333333", admin, 0));
        assertEquals(409, cycle.statusCode(), cycle.body());
        assertEquals("ASSET_CATEGORY_CYCLE", JSON.readTree(cycle.body()).path("code").asText());
        HttpResponse<String> staleParent = request("PATCH", "/api/v1/asset-categories/" + childId,
                "{\"parentCategory\":{\"id\":\"" + rootId + "\",\"version\":9}}",
                admin, versionedHeaders("8b444444-4444-4444-8444-444444444444", admin, 0));
        assertEquals(409, staleParent.statusCode());
        assertEquals("ASSET_CATEGORY_PARENT_VERSION_CONFLICT",
                JSON.readTree(staleParent.body()).path("code").asText());
        HttpResponse<String> disabled = request("POST", "/api/v1/asset-categories/" + rootId
                + "/disable", "{\"reason\":\"Retired\"}", admin,
                versionedHeaders("8b555555-5555-4555-8555-555555555555", admin, 0));
        assertEquals(200, disabled.statusCode(), disabled.body());
        HttpResponse<String> childDisabled = request("POST", "/api/v1/asset-categories/" + childId
                + "/disable", "{\"reason\":\"Retired\"}", admin,
                versionedHeaders("8b666666-6666-4666-8666-666666666666", admin, 0));
        assertEquals(200, childDisabled.statusCode(), childDisabled.body());
        HttpResponse<String> enableChild = request("POST", "/api/v1/asset-categories/" + childId
                + "/enable", "{\"reason\":\"Reopen\"}", admin,
                versionedHeaders("8b777777-7777-4777-8777-777777777777", admin, 1));
        assertEquals(409, enableChild.statusCode());
        assertEquals("ASSET_CATEGORY_PARENT_DISABLED",
                JSON.readTree(enableChild.body()).path("code").asText());
        HttpResponse<String> duplicate = request("POST", "/api/v1/asset-categories",
                "{\"code\":\"equipment\",\"name\":\"Duplicate\"}", admin,
                writeHeaders("8b888888-8888-4888-8888-888888888888", admin));
        assertEquals(409, duplicate.statusCode());
        assertEquals("ASSET_CATEGORY_CODE_CONFLICT",
                JSON.readTree(duplicate.body()).path("code").asText());
        assertEquals("DISABLED", JSON.readTree(request("GET", "/api/v1/asset-categories/"
                + rootId, null, admin, Map.of()).body()).path("status").asText());
    }

    @Test
    void categoryWritesPreserveTemplatesAndEnforceRolesAndVersions() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String createBody = "{\"code\":\"FURNITURE\",\"name\":\"Furniture\","
                + "\"description\":\"Fixtures\",\"extensionFieldTemplate\":[]}";
        String key = "8c111111-1111-4111-8111-111111111111";
        assertEquals(403, request("POST", "/api/v1/asset-categories", createBody, user,
                writeHeaders(key, user)).statusCode());
        HttpResponse<String> created = request("POST", "/api/v1/asset-categories", createBody,
                admin, writeHeaders(key, admin));
        assertEquals(201, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(created.body(), request("POST", "/api/v1/asset-categories", createBody,
                admin, writeHeaders(key, admin)).body());
        assertEquals(409, request("POST", "/api/v1/asset-categories",
                "{\"code\":\"OTHER\",\"name\":\"Other\"}", admin,
                writeHeaders(key, admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/asset-categories",
                "{\"code\":\" \",\"name\":\"Blank\"}", admin,
                writeHeaders("8c222222-2222-4222-8222-222222222222", admin)).statusCode());
        assertEquals(409, request("PATCH", "/api/v1/asset-categories/" + id,
                "{\"name\":\"Updated\"}", admin,
                versionedHeaders("8c333333-3333-4333-8333-333333333333", admin, 9)).statusCode());
        HttpResponse<String> updated = request("PATCH", "/api/v1/asset-categories/" + id,
                "{\"name\":\"Updated\",\"suggestedUnit\":\"set\","
                        + "\"numberingPrefix\":\"FU\",\"extensionFieldTemplate\":[]}", admin,
                versionedHeaders("8c444444-4444-4444-8444-444444444444", admin, 0));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(1, JSON.readTree(updated.body()).path("version").asLong());
        assertEquals("Fixtures", JSON.readTree(updated.body()).path("description").asText());
        assertEquals("FU", JSON.readTree(updated.body()).path("numberingPrefix").asText());
        assertEquals(0, JSON.readTree(updated.body()).path("extensionFieldTemplate").size());
        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                + "VALUES (2, 'ASSET_ADMIN', ?)", now);
        Session assetAdmin = login("user");
        JsonNode references = JSON.readTree(request("GET", "/api/v1/asset-categories/active-references",
                null, assetAdmin, Map.of()).body());
        assertEquals(1, references.path("total").asInt());
        assertEquals("FU", references.path("items").get(0).path("numberingPrefix").asText());
        assertEquals(403, request("GET", "/api/v1/asset-categories", null,
                assetAdmin, Map.of()).statusCode());
        assertEquals(404, request("GET", "/api/v1/asset-categories/999", null,
                admin, Map.of()).statusCode());
    }

    @Test
    void warehouseWritesEnforcePermissionsVersionsAndActiveSelectors() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String createBody = "{\"code\":\"MAIN\",\"name\":\"Main warehouse\","
                + "\"description\":\"Ground floor\"}";
        String key = "8d111111-1111-4111-8111-111111111111";
        assertEquals(403, request("POST", "/api/v1/warehouses", createBody, user,
                writeHeaders(key, user)).statusCode());
        HttpResponse<String> created = request("POST", "/api/v1/warehouses", createBody,
                admin, writeHeaders(key, admin));
        assertEquals(201, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(created.body(), request("POST", "/api/v1/warehouses", createBody,
                admin, writeHeaders(key, admin)).body());
        assertEquals(409, request("POST", "/api/v1/warehouses",
                "{\"code\":\"OTHER\",\"name\":\"Other\"}", admin,
                writeHeaders(key, admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/warehouses",
                "{\"code\":\" \",\"name\":\"Blank\"}", admin,
                writeHeaders("8d222222-2222-4222-8222-222222222222", admin)).statusCode());
        assertEquals(409, request("PATCH", "/api/v1/warehouses/" + id,
                "{\"name\":\"Changed\"}", admin,
                versionedHeaders("8d333333-3333-4333-8333-333333333333", admin, 9)).statusCode());
        HttpResponse<String> updated = request("PATCH", "/api/v1/warehouses/" + id,
                "{\"name\":\"Main depot\"}", admin,
                versionedHeaders("8d444444-4444-4444-8444-444444444444", admin, 0));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("Ground floor", JSON.readTree(updated.body()).path("description").asText());
        HttpResponse<String> disabled = request("POST", "/api/v1/warehouses/" + id + "/disable",
                "{\"reason\":\"Closed\"}", admin,
                versionedHeaders("8d555555-5555-4555-8555-555555555555", admin, 1));
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals(409, request("POST", "/api/v1/warehouses/" + id + "/disable",
                "{\"reason\":\"Still closed\"}", admin,
                versionedHeaders("8d515151-5151-4515-8515-515151515151", admin, 2)).statusCode());
        assertEquals(409, request("POST", "/api/v1/warehouses",
                "{\"code\":\"main\",\"name\":\"Reused\"}", admin,
                writeHeaders("8d666666-6666-4666-8666-666666666666", admin)).statusCode());
        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                + "VALUES (2, 'WAREHOUSE_OPERATOR', ?)", now);
        Session operator = login("user");
        assertEquals(0, JSON.readTree(request("GET", "/api/v1/warehouses/active-references",
                null, operator, Map.of()).body()).path("total").asInt());
        HttpResponse<String> enabled = request("POST", "/api/v1/warehouses/" + id + "/enable",
                "{\"reason\":\"Reopen\"}", admin,
                versionedHeaders("8d777777-7777-4777-8777-777777777777", admin, 2));
        assertEquals(200, enabled.statusCode(), enabled.body());
        assertEquals(1, JSON.readTree(request("GET", "/api/v1/warehouses/active-references",
                null, operator, Map.of()).body()).path("total").asInt());
        assertEquals(403, request("GET", "/api/v1/warehouses", null,
                operator, Map.of()).statusCode());
    }

    @Test
    void warehouseDisableRejectsAnActiveWarehouseLocation() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> created = request("POST", "/api/v1/warehouses",
                "{\"code\":\"DEPENDENT\",\"name\":\"Dependent warehouse\"}", admin,
                writeHeaders("8e111111-1111-4111-8111-111111111111", admin));
        assertEquals(201, created.statusCode(), created.body());
        String warehouseId = JSON.readTree(created.body()).path("id").asText();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_location "
                + "(code, code_normalized, name, location_type, warehouse_id, status, version, "
                + "created_at, updated_at) VALUES ('BIN-1', 'BIN-1', 'Bin 1', 'BIN', ?, 'ACTIVE', 0, ?, ?)",
                Long.parseLong(warehouseId), now, now);
        HttpResponse<String> rejected = request("POST", "/api/v1/warehouses/"
                + warehouseId + "/disable", "{\"reason\":\"Close\"}", admin,
                versionedHeaders("8e222222-2222-4222-8222-222222222222", admin, 0));
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals("WAREHOUSE_HAS_OPEN_BUSINESS_REFERENCES",
                JSON.readTree(rejected.body()).path("code").asText());
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM fams_warehouse WHERE id = ?", String.class,
                Long.parseLong(warehouseId)));
    }

    @Test
    void locationTreeRejectsCrossWarehouseCyclesAndEnabledChildren() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> firstWarehouse = request("POST", "/api/v1/warehouses",
                "{\"code\":\"W1\",\"name\":\"Warehouse One\"}", admin,
                writeHeaders("90111111-1111-4111-8111-111111111111", admin));
        HttpResponse<String> secondWarehouse = request("POST", "/api/v1/warehouses",
                "{\"code\":\"W2\",\"name\":\"Warehouse Two\"}", admin,
                writeHeaders("90222222-2222-4222-8222-222222222222", admin));
        assertEquals(201, firstWarehouse.statusCode());
        assertEquals(201, secondWarehouse.statusCode());
        String warehouseId = JSON.readTree(firstWarehouse.body()).path("id").asText();
        String otherWarehouseId = JSON.readTree(secondWarehouse.body()).path("id").asText();
        String warehouseRef = "{\"id\":\"" + warehouseId + "\",\"version\":0}";
        HttpResponse<String> root = request("POST", "/api/v1/locations",
                "{\"code\":\"ROOT-BIN\",\"name\":\"Root bin\",\"type\":\"AREA\","
                        + "\"warehouse\":" + warehouseRef + "}", admin,
                writeHeaders("90333333-3333-4333-8333-333333333333", admin));
        assertEquals(201, root.statusCode(), root.body());
        String rootId = JSON.readTree(root.body()).path("id").asText();
        String parentRef = "{\"id\":\"" + rootId + "\",\"version\":0}";
        HttpResponse<String> child = request("POST", "/api/v1/locations",
                "{\"code\":\"CHILD-BIN\",\"name\":\"Child bin\",\"type\":\"BIN\","
                        + "\"parentLocation\":" + parentRef + ",\"warehouse\":" + warehouseRef + "}",
                admin, writeHeaders("90444444-4444-4444-8444-444444444444", admin));
        assertEquals(201, child.statusCode(), child.body());
        String childId = JSON.readTree(child.body()).path("id").asText();
        HttpResponse<String> parentWarehouseMove = request("PATCH", "/api/v1/locations/" + rootId,
                "{\"warehouse\":{\"id\":\"" + otherWarehouseId + "\",\"version\":0}}",
                admin, versionedHeaders("90454545-4545-4454-8454-454545454545", admin, 0));
        assertEquals(409, parentWarehouseMove.statusCode(), parentWarehouseMove.body());
        assertEquals("LOCATION_WAREHOUSE_MISMATCH",
                JSON.readTree(parentWarehouseMove.body()).path("code").asText());
        HttpResponse<String> mismatch = request("POST", "/api/v1/locations",
                "{\"code\":\"CROSS\",\"name\":\"Cross\",\"type\":\"BIN\","
                        + "\"parentLocation\":" + parentRef + ","
                        + "\"warehouse\":{\"id\":\"" + otherWarehouseId + "\",\"version\":0}}",
                admin, writeHeaders("90555555-5555-4555-8555-555555555555", admin));
        assertEquals(409, mismatch.statusCode(), mismatch.body());
        assertEquals("LOCATION_WAREHOUSE_MISMATCH", JSON.readTree(mismatch.body()).path("code").asText());
        HttpResponse<String> cycle = request("PATCH", "/api/v1/locations/" + rootId,
                "{\"parentLocation\":{\"id\":\"" + childId + "\",\"version\":0}}",
                admin, versionedHeaders("90666666-6666-4666-8666-666666666666", admin, 0));
        assertEquals(409, cycle.statusCode(), cycle.body());
        assertEquals("LOCATION_CYCLE", JSON.readTree(cycle.body()).path("code").asText());
        HttpResponse<String> parentDisable = request("POST", "/api/v1/locations/" + rootId
                + "/disable", "{\"reason\":\"Close\"}", admin,
                versionedHeaders("90777777-7777-4777-8777-777777777777", admin, 0));
        assertEquals(409, parentDisable.statusCode());
        assertEquals("LOCATION_HAS_ENABLED_CHILDREN",
                JSON.readTree(parentDisable.body()).path("code").asText());
        HttpResponse<String> childDisable = request("POST", "/api/v1/locations/" + childId
                + "/disable", "{\"reason\":\"Close\"}", admin,
                versionedHeaders("90888888-8888-4888-8888-888888888888", admin, 0));
        assertEquals(200, childDisable.statusCode(), childDisable.body());
        HttpResponse<String> rootDisable = request("POST", "/api/v1/locations/" + rootId
                + "/disable", "{\"reason\":\"Close\"}", admin,
                versionedHeaders("90999999-9999-4999-8999-999999999999", admin, 0));
        assertEquals(200, rootDisable.statusCode(), rootDisable.body());
        HttpResponse<String> childEnable = request("POST", "/api/v1/locations/" + childId
                + "/enable", "{\"reason\":\"Reopen\"}", admin,
                versionedHeaders("90aaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", admin, 1));
        assertEquals(409, childEnable.statusCode());
        assertEquals("LOCATION_PARENT_DISABLED", JSON.readTree(childEnable.body()).path("code").asText());
        HttpResponse<String> rootEnable = request("POST", "/api/v1/locations/" + rootId
                + "/enable", "{\"reason\":\"Reopen\"}", admin,
                versionedHeaders("90bbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", admin, 1));
        assertEquals(200, rootEnable.statusCode(), rootEnable.body());
        HttpResponse<String> enabledChild = request("POST", "/api/v1/locations/" + childId
                + "/enable", "{\"reason\":\"Reopen\"}", admin,
                versionedHeaders("90cccccc-cccc-4ccc-8ccc-cccccccccccc", admin, 1));
        assertEquals(200, enabledChild.statusCode(), enabledChild.body());
        HttpResponse<String> staleParent = request("POST", "/api/v1/locations",
                "{\"code\":\"STALE-PARENT\",\"name\":\"Stale\",\"type\":\"BIN\","
                        + "\"parentLocation\":{\"id\":\"" + rootId + "\",\"version\":0},"
                        + "\"warehouse\":" + warehouseRef + "}", admin,
                writeHeaders("90dddddd-dddd-4ddd-8ddd-dddddddddddd", admin));
        assertEquals(409, staleParent.statusCode());
        assertEquals("LOCATION_PARENT_VERSION_CONFLICT",
                JSON.readTree(staleParent.body()).path("code").asText());
        HttpResponse<String> warehouseDisable = request("POST", "/api/v1/warehouses/"
                + warehouseId + "/disable", "{\"reason\":\"Close\"}", admin,
                versionedHeaders("90eeeeee-eeee-4eee-8eee-eeeeeeeeeeee", admin, 0));
        assertEquals(409, warehouseDisable.statusCode());
        assertEquals("WAREHOUSE_HAS_OPEN_BUSINESS_REFERENCES",
                JSON.readTree(warehouseDisable.body()).path("code").asText());
    }

    @Test
    void locationsSupportOfficeSelectorsAndVersionedWarehouseReferences() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String officeBody = "{\"code\":\"OFFICE\",\"name\":\"Office\",\"type\":\"ROOM\"}";
        String key = "91111111-1111-4111-8111-111111111111";
        assertEquals(403, request("POST", "/api/v1/locations", officeBody, user,
                writeHeaders(key, user)).statusCode());
        HttpResponse<String> office = request("POST", "/api/v1/locations", officeBody,
                admin, writeHeaders(key, admin));
        assertEquals(201, office.statusCode(), office.body());
        String officeId = JSON.readTree(office.body()).path("id").asText();
        assertEquals(office.body(), request("POST", "/api/v1/locations", officeBody,
                admin, writeHeaders(key, admin)).body());
        assertEquals(409, request("POST", "/api/v1/locations",
                "{\"code\":\"OTHER\",\"name\":\"Other\",\"type\":\"ROOM\"}",
                admin, writeHeaders(key, admin)).statusCode());
        assertEquals(409, request("POST", "/api/v1/locations",
                "{\"code\":\"office\",\"name\":\"Duplicate\",\"type\":\"ROOM\"}",
                admin, writeHeaders("91222222-2222-4222-8222-222222222222", admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/locations",
                "{\"code\":\" \",\"name\":\"Blank\",\"type\":\"ROOM\"}",
                admin, writeHeaders("91333333-3333-4333-8333-333333333333", admin)).statusCode());
        assertEquals(409, request("PATCH", "/api/v1/locations/" + officeId,
                "{\"name\":\"Updated\"}", admin,
                versionedHeaders("91444444-4444-4444-8444-444444444444", admin, 9)).statusCode());
        HttpResponse<String> updated = request("PATCH", "/api/v1/locations/" + officeId,
                "{\"name\":\"Main office\",\"description\":\"North\"}", admin,
                versionedHeaders("91555555-5555-4555-8555-555555555555", admin, 0));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("North", JSON.readTree(updated.body()).path("description").asText());
        Timestamp now = Timestamp.from(Instant.parse("2026-09-23T01:00:00Z"));
        jdbcTemplate.update("INSERT INTO fams_account_role (account_id, role_code, granted_at) "
                + "VALUES (2, 'ASSET_ADMIN', ?)", now);
        Session assetAdmin = login("user");
        JsonNode references = JSON.readTree(request("GET", "/api/v1/locations/active-references",
                null, assetAdmin, Map.of()).body());
        assertEquals(1, references.path("total").asInt());
        assertTrue(references.path("items").get(0).path("warehouse").isMissingNode());
        assertEquals(403, request("GET", "/api/v1/locations", null,
                assetAdmin, Map.of()).statusCode());
        assertEquals(404, request("GET", "/api/v1/locations/999", null,
                admin, Map.of()).statusCode());

        HttpResponse<String> warehouse = request("POST", "/api/v1/warehouses",
                "{\"code\":\"STORAGE\",\"name\":\"Storage\"}", admin,
                writeHeaders("91666666-6666-4666-8666-666666666666", admin));
        assertEquals(201, warehouse.statusCode(), warehouse.body());
        String warehouseId = JSON.readTree(warehouse.body()).path("id").asText();
        HttpResponse<String> staleWarehouse = request("POST", "/api/v1/locations",
                "{\"code\":\"STALE-WH\",\"name\":\"Stale\",\"type\":\"BIN\","
                        + "\"warehouse\":{\"id\":\"" + warehouseId + "\",\"version\":9}}",
                admin, writeHeaders("91777777-7777-4777-8777-777777777777", admin));
        assertEquals(409, staleWarehouse.statusCode());
        assertEquals("LOCATION_WAREHOUSE_VERSION_CONFLICT",
                JSON.readTree(staleWarehouse.body()).path("code").asText());
        HttpResponse<String> warehouseBound = request("POST", "/api/v1/locations",
                "{\"code\":\"BIN-A\",\"name\":\"Bin A\",\"type\":\"BIN\","
                        + "\"warehouse\":{\"id\":\"" + warehouseId + "\",\"version\":0}}",
                admin, writeHeaders("91888888-8888-4888-8888-888888888888", admin));
        assertEquals(201, warehouseBound.statusCode(), warehouseBound.body());
        assertEquals(2, JSON.readTree(request("GET", "/api/v1/locations/active-references",
                null, assetAdmin, Map.of()).body()).path("total").asInt());
    }

    @Test
    void numberingRulesRejectTargetOverlapAndIllegalModes() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String enterprise = "{\"code\":\"ASSET-DEFAULT\",\"target\":\"ASSET\","
                + "\"mode\":\"ENTERPRISE\",\"fixedPrefix\":\"FA\","
                + "\"datePart\":\"YYYY\",\"sequenceLength\":6}";
        String key = "92111111-1111-4111-8111-111111111111";
        assertEquals(403, request("POST", "/api/v1/numbering-rules", enterprise,
                user, writeHeaders(key, user)).statusCode());
        HttpResponse<String> created = request("POST", "/api/v1/numbering-rules", enterprise,
                admin, writeHeaders(key, admin));
        assertEquals(201, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asText();
        assertEquals(created.body(), request("POST", "/api/v1/numbering-rules", enterprise,
                admin, writeHeaders(key, admin)).body());
        assertEquals(409, request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"OTHER\",\"target\":\"ASSET\","
                        + "\"mode\":\"ENTERPRISE\",\"datePart\":\"NONE\","
                        + "\"sequenceLength\":4}", admin,
                writeHeaders("92222222-2222-4222-8222-222222222222", admin)).statusCode());
        assertEquals(400, request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"BAD-MODE\",\"target\":\"INBOUND_RECEIPT\","
                        + "\"mode\":\"CATEGORY_PREFIX\",\"datePart\":\"NONE\","
                        + "\"sequenceLength\":4}", admin,
                writeHeaders("92333333-3333-4333-8333-333333333333", admin)).statusCode());
        HttpResponse<String> stale = request("PATCH", "/api/v1/numbering-rules/" + id,
                "{\"sequenceLength\":8}", admin,
                versionedHeaders("92444444-4444-4444-8444-444444444444", admin, 9));
        assertEquals(409, stale.statusCode());
        HttpResponse<String> updated = request("PATCH", "/api/v1/numbering-rules/" + id,
                "{\"sequenceLength\":8}", admin,
                versionedHeaders("92555555-5555-4555-8555-555555555555", admin, 0));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(8, JSON.readTree(updated.body()).path("sequenceLength").asInt());
        HttpResponse<String> disabled = request("POST", "/api/v1/numbering-rules/" + id
                + "/disable", "{\"reason\":\"Switch rule\"}", admin,
                versionedHeaders("92666666-6666-4666-8666-666666666666", admin, 1));
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals(409, request("POST", "/api/v1/numbering-rules",
                enterprise.replace("ASSET-DEFAULT", "asset-default"), admin,
                writeHeaders("92777777-7777-4777-8777-777777777777", admin)).statusCode());
        HttpResponse<String> replacement = request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"ASSET-NEW\",\"target\":\"ASSET\","
                        + "\"mode\":\"ENTERPRISE\",\"datePart\":\"YYYYMM\","
                        + "\"sequenceLength\":5}", admin,
                writeHeaders("92888888-8888-4888-8888-888888888888", admin));
        assertEquals(201, replacement.statusCode(), replacement.body());
        String replacementId = JSON.readTree(replacement.body()).path("id").asText();
        HttpResponse<String> blockedEnable = request("POST", "/api/v1/numbering-rules/" + id
                + "/enable", "{\"reason\":\"Restore\"}", admin,
                versionedHeaders("92999999-9999-4999-8999-999999999999", admin, 2));
        assertEquals(409, blockedEnable.statusCode());
        assertEquals("NUMBERING_RULE_TARGET_CONFLICT",
                JSON.readTree(blockedEnable.body()).path("code").asText());
        HttpResponse<String> replacementDisabled = request("POST", "/api/v1/numbering-rules/"
                + replacementId + "/disable", "{\"reason\":\"Yield target\"}", admin,
                versionedHeaders("92aaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", admin, 0));
        assertEquals(200, replacementDisabled.statusCode(), replacementDisabled.body());
        HttpResponse<String> restored = request("POST", "/api/v1/numbering-rules/" + id
                + "/enable", "{\"reason\":\"Restore\"}", admin,
                versionedHeaders("92bbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", admin, 2));
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(409, request("POST", "/api/v1/numbering-rules/" + id + "/enable",
                "{\"reason\":\"Again\"}", admin,
                versionedHeaders("92cccccc-cccc-4ccc-8ccc-cccccccccccc", admin, 3)).statusCode());
        assertEquals(400, request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"BAD-PREFIX\",\"target\":\"INBOUND_RECEIPT\","
                        + "\"mode\":\"ENTERPRISE\",\"fixedPrefix\":\"bad space\","
                        + "\"datePart\":\"NONE\",\"sequenceLength\":4}", admin,
                writeHeaders("92dddddd-dddd-4ddd-8ddd-dddddddddddd", admin)).statusCode());
    }

    @Test
    void categoryPrefixRulesRequireCompleteUnambiguousCategoryPrefixes() throws Exception {
        Session admin = login("admin");
        HttpResponse<String> category = request("POST", "/api/v1/asset-categories",
                "{\"code\":\"LAPTOP\",\"name\":\"Laptop\"}", admin,
                writeHeaders("93111111-1111-4111-8111-111111111111", admin));
        assertEquals(201, category.statusCode());
        String categoryId = JSON.readTree(category.body()).path("id").asText();
        String rule = "{\"code\":\"ASSET-CATEGORY\",\"target\":\"ASSET\","
                + "\"mode\":\"CATEGORY_PREFIX\",\"datePart\":\"YYYYMMDD\","
                + "\"sequenceLength\":5}";
        HttpResponse<String> missing = request("POST", "/api/v1/numbering-rules", rule,
                admin, writeHeaders("93222222-2222-4222-8222-222222222222", admin));
        assertEquals(409, missing.statusCode(), missing.body());
        assertEquals("NUMBERING_CATEGORY_PREFIX_MISSING",
                JSON.readTree(missing.body()).path("code").asText());
        HttpResponse<String> fixedNotAllowed = request("POST", "/api/v1/numbering-rules",
                rule.replace("\"datePart\"", "\"fixedPrefix\":\"FA\",\"datePart\""),
                admin, writeHeaders("93333333-3333-4333-8333-333333333333", admin));
        assertEquals(400, fixedNotAllowed.statusCode());
        assertEquals("NUMBERING_RULE_FIXED_PREFIX_NOT_ALLOWED",
                JSON.readTree(fixedNotAllowed.body()).path("code").asText());
        HttpResponse<String> categoryUpdated = request("PATCH", "/api/v1/asset-categories/"
                + categoryId, "{\"numberingPrefix\":\"LT\"}", admin,
                versionedHeaders("93444444-4444-4444-8444-444444444444", admin, 0));
        assertEquals(200, categoryUpdated.statusCode(), categoryUpdated.body());
        HttpResponse<String> accepted = request("POST", "/api/v1/numbering-rules", rule,
                admin, writeHeaders("93555555-5555-4555-8555-555555555555", admin));
        assertEquals(201, accepted.statusCode(), accepted.body());
        String ruleId = JSON.readTree(accepted.body()).path("id").asText();
        HttpResponse<String> clearedPrefix = request("PATCH", "/api/v1/asset-categories/"
                + categoryId, "{\"numberingPrefix\":\"\"}", admin,
                versionedHeaders("93555555-5555-4555-8555-666666666666", admin, 1));
        assertEquals(409, clearedPrefix.statusCode(), clearedPrefix.body());
        assertEquals("NUMBERING_CATEGORY_PREFIX_MISSING",
                JSON.readTree(clearedPrefix.body()).path("code").asText());
        HttpResponse<String> secondCategory = request("POST", "/api/v1/asset-categories",
                "{\"code\":\"DESKTOP\",\"name\":\"Desktop\","
                        + "\"numberingPrefix\":\"lt\"}", admin,
                writeHeaders("93666666-6666-4666-8666-666666666666", admin));
        assertEquals(409, secondCategory.statusCode());
        assertEquals("NUMBERING_CATEGORY_PREFIX_CONFLICT",
                JSON.readTree(secondCategory.body()).path("code").asText());
        HttpResponse<String> missingAfterActivation = request("POST", "/api/v1/asset-categories",
                "{\"code\":\"TABLET\",\"name\":\"Tablet\"}", admin,
                writeHeaders("93666666-6666-4666-8666-777777777777", admin));
        assertEquals(409, missingAfterActivation.statusCode());
        assertEquals("NUMBERING_CATEGORY_PREFIX_MISSING",
                JSON.readTree(missingAfterActivation.body()).path("code").asText());
        HttpResponse<String> disable = request("POST", "/api/v1/numbering-rules/" + ruleId
                + "/disable", "{\"reason\":\"Pause\"}", admin,
                versionedHeaders("93777777-7777-4777-8777-777777777777", admin, 0));
        assertEquals(200, disable.statusCode());
        assertEquals(201, request("POST", "/api/v1/asset-categories",
                "{\"code\":\"DESKTOP\",\"name\":\"Desktop\","
                        + "\"numberingPrefix\":\"lt\"}", admin,
                writeHeaders("93666666-6666-4666-8666-888888888888", admin)).statusCode());
        HttpResponse<String> conflict = request("POST", "/api/v1/numbering-rules/" + ruleId
                + "/enable", "{\"reason\":\"Resume\"}", admin,
                versionedHeaders("93888888-8888-4888-8888-888888888888", admin, 1));
        assertEquals(409, conflict.statusCode());
        assertEquals("NUMBERING_CATEGORY_PREFIX_CONFLICT",
                JSON.readTree(conflict.body()).path("code").asText());
    }

    @Test
    void numberReservationsRemainUniqueAcrossConcurrentAndVoidedBatches() throws Exception {
        assertEquals("NUMBERING_RULE_UNAVAILABLE", assertThrows(
                MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, null, 1)).code());
        Session admin = login("admin");
        HttpResponse<String> created = request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"ASSET-RESERVE\",\"target\":\"ASSET\","
                        + "\"mode\":\"ENTERPRISE\",\"fixedPrefix\":\"FA\","
                        + "\"datePart\":\"YYYYMMDD\",\"sequenceLength\":4}",
                admin, writeHeaders("93999999-9999-4999-8999-999999999999", admin));
        assertEquals(201, created.statusCode(), created.body());
        List<NumberReservation> first = numberAllocations.reserve(NumberingTarget.ASSET, null, 3);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals(List.of("ASSET-E-PFA-DYYYYMMDD" + today + "-0001",
                "ASSET-E-PFA-DYYYYMMDD" + today + "-0002",
                "ASSET-E-PFA-DYYYYMMDD" + today + "-0003"),
                first.stream().map(NumberReservation::number).toList());
        numberAllocations.markUsed(first.get(0).id());
        numberAllocations.voidReservation(first.get(1).id());
        assertThrows(MasterDataOperationException.class,
                () -> numberAllocations.markUsed(first.get(1).id()));
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<List<NumberReservation>>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(pool.submit(() -> numberAllocations.reserve(NumberingTarget.ASSET,
                        null, 1)));
            }
            var numbers = new HashSet<String>();
            for (Future<List<NumberReservation>> future : futures) {
                numbers.add(future.get().getFirst().number());
            }
            assertEquals(8, numbers.size());
            assertTrue(numbers.stream().noneMatch(first.stream().map(NumberReservation::number)
                    .toList()::contains));
        }
        assertEquals(11L, jdbcTemplate.queryForObject(
                "SELECT last_allocated_value FROM fams_number_sequence", Long.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fams_number_allocation WHERE state = 'VOID'", Integer.class));
    }

    @Test
    void categoryAndBusinessNumberReservationsRequireValidActiveRules() throws Exception {
        assertEquals(400, assertThrows(MasterDataOperationException.class,
                () -> numberAllocations.reserve(null, null, 1)).status());
        assertEquals(400, assertThrows(MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, null, 0)).status());
        assertEquals(400, assertThrows(MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, null, 1001)).status());
        Session admin = login("admin");
        HttpResponse<String> category = request("POST", "/api/v1/asset-categories",
                "{\"code\":\"LAPTOP\",\"name\":\"Laptop\","
                        + "\"numberingPrefix\":\"LT\"}", admin,
                writeHeaders("93aaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", admin));
        assertEquals(201, category.statusCode(), category.body());
        long categoryId = JSON.readTree(category.body()).path("id").asLong();
        HttpResponse<String> rule = request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"CATEGORY-ALLOCATE\",\"target\":\"ASSET\","
                        + "\"mode\":\"CATEGORY_PREFIX\",\"datePart\":\"NONE\","
                        + "\"sequenceLength\":1}", admin,
                writeHeaders("93bbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", admin));
        assertEquals(201, rule.statusCode(), rule.body());
        String ruleId = JSON.readTree(rule.body()).path("id").asText();
        assertEquals("NUMBERING_CATEGORY_PREFIX_MISSING", assertThrows(
                MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, null, 1)).code());
        assertEquals("NUMBERING_CATEGORY_PREFIX_MISSING", assertThrows(
                MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, 999999L, 1)).code());
        List<NumberReservation> segment = numberAllocations.reserve(
                NumberingTarget.ASSET, categoryId, 9);
        assertEquals("ASSET-C-PLT-N-1", segment.getFirst().number());
        assertEquals("ASSET-C-PLT-N-9", segment.getLast().number());
        assertEquals("NUMBERING_SEQUENCE_EXHAUSTED", assertThrows(
                MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, categoryId, 1)).code());
        HttpResponse<String> changed = request("PATCH", "/api/v1/numbering-rules/" + ruleId,
                "{\"datePart\":\"YYYY\"}", admin,
                versionedHeaders("93cccccc-cccc-4ccc-8ccc-cccccccccccc", admin, 0));
        assertEquals(200, changed.statusCode(), changed.body());
        String year = Integer.toString(LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear());
        assertEquals("ASSET-C-PLT-DYYYY" + year + "-1",
                numberAllocations.reserve(NumberingTarget.ASSET, categoryId, 1).getFirst().number());
        HttpResponse<String> disabled = request("POST", "/api/v1/numbering-rules/" + ruleId
                + "/disable", "{\"reason\":\"Stop numbering\"}", admin,
                versionedHeaders("93dddddd-dddd-4ddd-8ddd-dddddddddddd", admin, 1));
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals("NUMBERING_RULE_UNAVAILABLE", assertThrows(
                MasterDataOperationException.class,
                () -> numberAllocations.reserve(NumberingTarget.ASSET, categoryId, 1)).code());
        HttpResponse<String> inbound = request("POST", "/api/v1/numbering-rules",
                "{\"code\":\"INBOUND-ALLOCATE\",\"target\":\"INBOUND_RECEIPT\","
                        + "\"mode\":\"ENTERPRISE\",\"datePart\":\"YYYYMM\","
                        + "\"sequenceLength\":2}", admin,
                writeHeaders("93eeeeee-eeee-4eee-8eee-eeeeeeeeeeee", admin));
        assertEquals(201, inbound.statusCode(), inbound.body());
        assertTrue(numberAllocations.reserve(NumberingTarget.INBOUND_RECEIPT, null, 1)
                .getFirst().number().startsWith("INBOUND_RECEIPT-"));
    }

    @Test
    void businessParameterVersionsAreImmutableAndSwitchAtomically() throws Exception {
        Session admin = login("admin");
        Session user = login("user");
        String firstBody = "{\"snapshot\":" + parameterSnapshot() + "}";
        String key = "94111111-1111-4111-8111-111111111111";
        assertEquals(403, request("POST", "/api/v1/business-parameters", firstBody,
                user, versionedHeaders(key, user, 0)).statusCode());
        assertEquals(404, request("GET", "/api/v1/business-parameters/current",
                null, admin, Map.of()).statusCode());
        HttpResponse<String> first = request("POST", "/api/v1/business-parameters", firstBody,
                admin, versionedHeaders(key, admin, 0));
        assertEquals(201, first.statusCode(), first.body());
        JsonNode firstJson = JSON.readTree(first.body());
        String firstId = firstJson.path("id").asText();
        assertEquals(1, firstJson.path("version").asLong());
        assertEquals("ACTIVE", firstJson.path("status").asText());
        assertEquals("admin", firstJson.path("actor").path("username").asText());
        assertEquals(first.body(), request("POST", "/api/v1/business-parameters", firstBody,
                admin, versionedHeaders(key, admin, 0)).body());
        HttpResponse<String> replayConflict = request("POST", "/api/v1/business-parameters",
                firstBody.replace("Asia/Shanghai", "Asia/Tokyo"),
                admin, versionedHeaders(key, admin, 0));
        assertEquals(409, replayConflict.statusCode());
        assertEquals(404, request("GET", "/api/v1/business-parameters/999999",
                null, admin, Map.of()).statusCode());
        assertEquals(404, request("GET", "/api/v1/business-parameters/not-an-id",
                null, admin, Map.of()).statusCode());
        HttpResponse<String> stale = request("POST", "/api/v1/business-parameters", firstBody,
                admin, versionedHeaders("94222222-2222-4222-8222-222222222222", admin, 0));
        assertEquals(409, stale.statusCode());
        assertEquals("BUSINESS_PARAMETER_VERSION_CONFLICT",
                JSON.readTree(stale.body()).path("code").asText());
        String secondBody = firstBody.replace("Asia/Shanghai", "Asia/Tokyo");
        HttpResponse<String> second = request("POST", "/api/v1/business-parameters", secondBody,
                admin, versionedHeaders("94333333-3333-4333-8333-333333333333", admin, 1));
        assertEquals(201, second.statusCode(), second.body());
        assertEquals(2, JSON.readTree(second.body()).path("version").asLong());
        assertEquals("Asia/Tokyo", JSON.readTree(request("GET", "/api/v1/business-parameters/current",
                null, admin, Map.of()).body()).path("snapshot").path("timezone").asText());
        JsonNode historical = JSON.readTree(request("GET", "/api/v1/business-parameters/" + firstId,
                null, admin, Map.of()).body());
        assertEquals("SUPERSEDED", historical.path("status").asText());
        assertEquals("Asia/Shanghai", historical.path("snapshot").path("timezone").asText());
        assertEquals(2, JSON.readTree(request("GET", "/api/v1/business-parameters",
                null, admin, Map.of()).body()).path("total").asInt());
        assertEquals(403, request("GET", "/api/v1/business-parameters",
                null, user, Map.of()).statusCode());
    }

    @Test
    void businessParametersRejectInvalidCalendarsDurationsAndScheduling() throws Exception {
        Session admin = login("admin");
        String body = "{\"snapshot\":" + parameterSnapshot() + "}";
        HttpResponse<String> badZone = request("POST", "/api/v1/business-parameters",
                body.replace("Asia/Shanghai", "Mars/Phobos"), admin,
                versionedHeaders("95111111-1111-4111-8111-111111111111", admin, 0));
        assertEquals(400, badZone.statusCode(), badZone.body());
        assertEquals("BUSINESS_PARAMETER_TIMEZONE_INVALID",
                JSON.readTree(badZone.body()).path("code").asText());
        HttpResponse<String> duplicate = request("POST", "/api/v1/business-parameters",
                body.replace("\"holidays\":[]", "\"holidays\":[\"2026-10-01\",\"2026-10-01\"]"),
                admin, versionedHeaders("95222222-2222-4222-8222-222222222222", admin, 0));
        assertEquals(400, duplicate.statusCode(), duplicate.body());
        assertEquals("BUSINESS_PARAMETER_DATE_DUPLICATE",
                JSON.readTree(duplicate.body()).path("code").asText());
        HttpResponse<String> overlap = request("POST", "/api/v1/business-parameters",
                body.replace("\"holidays\":[]", "\"holidays\":[\"2026-10-01\"]")
                        .replace("\"adjustedWorkdays\":[]",
                                "\"adjustedWorkdays\":[\"2026-10-01\"]"), admin,
                versionedHeaders("95333333-3333-4333-8333-333333333333", admin, 0));
        assertEquals(400, overlap.statusCode(), overlap.body());
        assertEquals("BUSINESS_PARAMETER_DATE_CONFLICT",
                JSON.readTree(overlap.body()).path("code").asText());
        HttpResponse<String> badDuration = request("POST", "/api/v1/business-parameters",
                body.replace("\"adminDecisionDeadlineWorkdays\":2",
                        "\"adminDecisionDeadlineWorkdays\":0"), admin,
                versionedHeaders("95444444-4444-4444-8444-444444444444", admin, 0));
        assertEquals(400, badDuration.statusCode(), badDuration.body());
        assertEquals("BUSINESS_PARAMETER_DURATION_INVALID",
                JSON.readTree(badDuration.body()).path("code").asText());
        String[] invalidSnapshots = {
                "{}",
                "{\"snapshot\":{}}",
                body.replace("{\"snapshot\":", "{\"unexpected\":true,\"snapshot\":"),
                body.replace("\"timezone\":", "\"unexpected\":true,\"timezone\":"),
                body.replace("\"administratorUnreadHours\":",
                        "\"unexpected\":true,\"administratorUnreadHours\":"),
                body.replace("\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\","
                        + "\"THURSDAY\",\"FRIDAY\"]", "\"workWeek\":[]"),
                body.replace("\"holidays\":[]", "\"holidays\":null"),
                body.replace("\"adjustedWorkdays\":[]",
                        "\"adjustedWorkdays\":[\"2026-10-02\",\"2026-10-02\"]"),
                body.replace("\"notificationEscalationThresholds\":{",
                        "\"notificationEscalationThresholds\":null,\"discarded\":{")
        };
        for (int index = 0; index < invalidSnapshots.length; index++) {
            HttpResponse<String> invalid = request("POST", "/api/v1/business-parameters",
                    invalidSnapshots[index], admin,
                    versionedHeaders("95666666-6666-4666-8666-66666666666" + index, admin, 0));
            assertEquals(400, invalid.statusCode(), invalid.body());
        }
        String[] durationFields = {
                "warehouseClaimDeadlineWorkdays", "warehouseFirstExecutionDeadlineWorkdays",
                "handoverAcceptanceDeadlineNaturalDays", "administratorUnreadHours",
                "onDutyWarehouseOperatorUnreadHours", "firstExecutionOverdueNaturalDays",
                "receiptDueUnreadHours"
        };
        for (int index = 0; index < durationFields.length; index++) {
            String field = durationFields[index];
            String invalidBody = body.replaceAll("(\\\"" + field + "\\\":)\\d+", "$10");
            HttpResponse<String> invalid = request("POST", "/api/v1/business-parameters",
                    invalidBody, admin,
                    versionedHeaders("95777777-7777-4777-8777-77777777777" + index, admin, 0));
            assertEquals(400, invalid.statusCode(), field + ": " + invalid.body());
            assertEquals("BUSINESS_PARAMETER_DURATION_INVALID",
                    JSON.readTree(invalid.body()).path("code").asText());
        }
        HttpResponse<String> scheduled = request("POST", "/api/v1/business-parameters",
                body.replace("{\"snapshot\":", "{\"effectiveAt\":\"2026-10-01T00:00:00Z\","
                        + "\"snapshot\":"), admin,
                versionedHeaders("95555555-5555-4555-8555-555555555555", admin, 0));
        assertEquals(400, scheduled.statusCode(), scheduled.body());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT current_version FROM fams_business_parameter_state WHERE singleton_id = 1",
                Integer.class));
    }

    private String parameterSnapshot() {
        return """
                {"timezone":"Asia/Shanghai","workWeek":["MONDAY","TUESDAY","WEDNESDAY",
                "THURSDAY","FRIDAY"],"holidays":[],"adjustedWorkdays":[],
                "adminDecisionDeadlineWorkdays":2,"warehouseClaimDeadlineWorkdays":1,
                "warehouseFirstExecutionDeadlineWorkdays":2,
                "handoverAcceptanceDeadlineNaturalDays":3,
                "notificationEscalationThresholds":{"administratorUnreadHours":4,
                "onDutyWarehouseOperatorUnreadHours":4,
                "firstExecutionOverdueNaturalDays":1,"receiptDueUnreadHours":24}}
                """.replace("\n", "").replace(" ", "");
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
