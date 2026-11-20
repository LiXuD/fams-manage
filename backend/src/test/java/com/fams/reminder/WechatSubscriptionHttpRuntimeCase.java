package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fams.auth.MiniNotificationSmsPort;
import com.fams.auth.MiniWechatIdentityPort;
import com.fams.job.DurableJobService;
import com.fams.masterdata.NotificationChannelConfigPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D6-24 WeChat subscription over real HTTP: one acceptance is one send quota, ownership and template are
 * revalidated server-side, and a missing provider or quota leaves the in-app reminder untouched.
 */
@SpringBootTest(classes = {WechatSubscriptionHttpRuntimeCase.TestApplication.class,
        WechatSubscriptionHttpRuntimeCase.StubWechatIdentity.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra",
    "fams.attachments.cleanup-enabled=false"
})
@EnabledIfSystemProperty(named = "d6.24.wechat.subscription.runtime", matches = "true")
class WechatSubscriptionHttpRuntimeCase {
    private static final String PASSWORD = "owned-wechat-subscription-password";
    private static final String HMAC_KEY = "test-idempotency-hmac-key-0123456789abcdef";
    private static final long OWNER = 936001;
    private static final long OTHER = 936002;
    private static final long ADMIN = 936003;
    private static final long TASK = 936300;
    private final HttpClient http = HttpClient.newHttpClient();
    private long parameterVersion;

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired NotificationChannelConfigPort channels;
    @Autowired DurableJobService jobs;
    @Autowired NotificationDeliveryQueue deliveries;
    @Autowired Clock clock;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D6_05_TEST_DATASOURCE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("D6_05_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("D6_05_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void subscriptionGrantsAreOneShotQuotasWithServerSideOwnershipAndInAppFallback() throws Exception {
        clean();
        seed();
        try {
            verifySubscriptionAndDelivery();
        } finally {
            // Runtime cases share one compose database; leftover ACTIVE employees would
            // shift id-ordered catalogs that later cases index by position.
            clean();
        }
    }

    private void verifySubscriptionAndDelivery() throws Exception {
        channelVersion(1, "sms-sub-test", "wx-sub-test");
        String owner = miniLogin("wx-sub-owner-code");
        String other = miniLogin("wx-sub-other-code");

        var visibility = ok(get(owner, "/mini/subscription-visibility"));
        assertEquals("wx-sub-test", visibility.path("wechatTemplate").asText());

        String key = UUID.randomUUID().toString();
        var granted = created(register(owner, key, "INVENTORY_TASK", "INVENTORY_TASK", TASK, "wx-sub-test"));
        assertEquals("AVAILABLE", granted.path("status").asText());
        String grantId = granted.path("id").asText();
        var replay = created(register(owner, key, "INVENTORY_TASK", "INVENTORY_TASK", TASK, "wx-sub-test"));
        assertEquals(grantId, replay.path("id").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_wechat_subscription_grant WHERE account_id=" + OWNER));
        var conflict = register(owner, key, "INVENTORY_TASK", "INVENTORY_TASK", TASK + 1, "wx-sub-test");
        assertEquals(409, conflict.statusCode());
        assertEquals("WECHAT_SUBSCRIPTION_KEY_CONFLICT", json.readTree(conflict.body()).path("code").asText());
        var forbidden = register(other, UUID.randomUUID().toString(), "INVENTORY_TASK", "INVENTORY_TASK",
                TASK, "wx-sub-test");
        assertEquals(403, forbidden.statusCode());
        assertEquals("WECHAT_SUBSCRIPTION_FORBIDDEN", json.readTree(forbidden.body()).path("code").asText());
        var invalidScene = register(owner, UUID.randomUUID().toString(), "PROGRESS", "INVENTORY_TASK",
                TASK, "wx-sub-test");
        assertEquals(400, invalidScene.statusCode());
        assertEquals("WECHAT_SUBSCRIPTION_REQUEST_INVALID", json.readTree(invalidScene.body()).path("code").asText());
        var wrongTemplate = register(owner, UUID.randomUUID().toString(), "INVENTORY_TASK", "INVENTORY_TASK",
                TASK, "not-configured");
        assertEquals(409, wrongTemplate.statusCode());
        assertEquals("WECHAT_SUBSCRIPTION_TEMPLATE_UNCONFIGURED",
                json.readTree(wrongTemplate.body()).path("code").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_audit_event WHERE action='WECHAT_SUBSCRIPTION_GRANTED'"
                + " AND actor_account_id=" + OWNER));

        // One acceptance delivers exactly one message; the next reminder stays in-app only.
        reminder(936400, OWNER);
        var calls = new ArrayList<WechatSubscriptionPort.Delivery>();
        worker(delivery -> {
            calls.add(delivery);
            return WechatSubscriptionPort.Result.ACCEPTED;
        }).poll();
        assertEquals(1, calls.size());
        var first = calls.getFirst();
        assertEquals(OWNER, first.recipientAccountId());
        assertEquals("wx-sub-test", first.templateId());
        assertEquals("pages/inventory-tasks/index", first.page());
        assertEquals(1, count("SELECT COUNT(*) FROM fams_wechat_subscription_grant WHERE id=" + grantId
                + " AND status='CONSUMED' AND consumed_at IS NOT NULL AND delivery_key='" + first.deliveryKey() + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_notification_delivery WHERE reminder_id=936400"
                + " AND channel='WECHAT' AND accepted_at IS NOT NULL AND grant_id=" + grantId));
        assertEquals("SUCCEEDED", jobStatus(first.deliveryKey()));
        reminder(936401, OWNER);
        worker(delivery -> {
            calls.add(delivery);
            return WechatSubscriptionPort.Result.ACCEPTED;
        }).poll();
        assertEquals(1, calls.size());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_notification_delivery WHERE reminder_id=936401"));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_business_reminder WHERE id=936401"));
        // Reading the in-app reminder also suppresses any later push for the same occurrence.
        db.update("INSERT INTO fams_message_receipt (account_id,message_kind,message_id,read_at)"
                + " VALUES (?,'REMINDER',936401,NOW(3))", OWNER);

        // A retryable provider result keeps the same delivery key and never consumes a second quota.
        var secondGrant = created(register(owner, UUID.randomUUID().toString(), "INVENTORY_TASK",
                "INVENTORY_TASK", TASK, "wx-sub-test")).path("id").asText();
        reminder(936402, OWNER);
        var attempts = new AtomicInteger();
        worker(delivery -> {
            calls.add(delivery);
            return attempts.incrementAndGet() == 1
                    ? WechatSubscriptionPort.Result.RETRYABLE : WechatSubscriptionPort.Result.ACCEPTED;
        }).poll();
        assertEquals(2, calls.size());
        String retryKey = calls.getLast().deliveryKey();
        assertEquals("RETRY_WAIT", jobStatus(retryKey));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_wechat_subscription_grant WHERE id=" + secondGrant
                + " AND status='CONSUMED' AND delivery_key='" + retryKey + "'"));
        db.update("UPDATE fams_durable_job SET next_attempt_at=? WHERE id=?",
                Timestamp.from(clock.instant().minusSeconds(1)), retryKey);
        worker(delivery -> {
            calls.add(delivery);
            return WechatSubscriptionPort.Result.ACCEPTED;
        }).poll();
        assertEquals(3, calls.size());
        assertEquals(retryKey, calls.getLast().deliveryKey());
        assertEquals("SUCCEEDED", jobStatus(retryKey));
        assertNotEquals(first.deliveryKey(), retryKey);

        // A provider rejection is terminal; the quota is spent and no retry is scheduled.
        db.update("INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,"
                + "created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,"
                + "created_at,updated_at,created_by,updated_by)"
                + " VALUES (936201,'WXSUB-B','Subscription asset B','UNDER_REPAIR','NORMAL','ACTIVE',"
                + "'2026-11-01',?,936003,'wxsubadmin',NOW(3),NOW(3),936003,936003)", parameterVersion);
        db.update("INSERT INTO fams_inventory_item (task_id,asset_id,asset_version,snapshot_json,executor_account_id)"
                + " VALUES (936300,936201,0,'{}',936002)");
        var otherGrant = created(register(other, UUID.randomUUID().toString(), "INVENTORY_TASK",
                "INVENTORY_TASK", TASK, "wx-sub-test")).path("id").asText();
        reminder(936403, OTHER);
        worker(delivery -> {
            calls.add(delivery);
            return WechatSubscriptionPort.Result.REJECTED;
        }).poll();
        assertEquals(4, calls.size());
        String rejectedKey = calls.getLast().deliveryKey();
        assertEquals("FAILED", jobStatus(rejectedKey));
        assertEquals("WECHAT_REJECTED", db.queryForObject(
                "SELECT last_error_code FROM fams_durable_job WHERE id=?", String.class, rejectedKey));
        assertEquals(1, count("SELECT COUNT(*) FROM fams_wechat_subscription_grant WHERE id=" + otherGrant
                + " AND status='CONSUMED'"));

        // A revoked binding stops delivery while the untouched quota stays available.
        var thirdGrant = created(register(owner, UUID.randomUUID().toString(), "INVENTORY_TASK",
                "INVENTORY_TASK", TASK, "wx-sub-test")).path("id").asText();
        reminder(936404, OWNER);
        db.update("UPDATE fams_mini_binding SET revoked_at=NOW(3) WHERE account_id=?", OWNER);
        worker(delivery -> {
            calls.add(delivery);
            return WechatSubscriptionPort.Result.ACCEPTED;
        }).poll();
        assertEquals(4, calls.size());
        assertEquals(0, count("SELECT COUNT(*) FROM fams_notification_delivery WHERE reminder_id=936404"));
        assertEquals("AVAILABLE", db.queryForObject(
                "SELECT status FROM fams_wechat_subscription_grant WHERE id=?", String.class, thirdGrant));

        // Without a provider bean nothing is enqueued; the in-app reminder remains the only channel.
        worker(null).poll();
        assertEquals(4, calls.size());

        // Clearing the configured template hides the entry and rejects new acceptances.
        channelVersion(2, "sms-sub-test", "");
        assertEquals("", ok(get(other, "/mini/subscription-visibility")).path("wechatTemplate").asText());
        var unconfigured = register(other, UUID.randomUUID().toString(), "INVENTORY_TASK",
                "INVENTORY_TASK", TASK, "wx-sub-test");
        assertEquals(409, unconfigured.statusCode());
        assertEquals("WECHAT_SUBSCRIPTION_TEMPLATE_UNCONFIGURED",
                json.readTree(unconfigured.body()).path("code").asText());
    }

    private void channelVersion(long version, String sms, String wechat) {
        db.update("UPDATE fams_notification_channel_version SET status='SUPERSEDED' WHERE status='ACTIVE'");
        db.update("INSERT INTO fams_notification_channel_version (version,status,sms_template,wechat_template,"
                + "actor_account_id,actor_username,created_at) VALUES (?,'ACTIVE',?,?,936003,'wxsubadmin',NOW(3))",
                version, sms, wechat);
        db.update("UPDATE fams_notification_channel_state SET current_version=?, current_version_id="
                + "(SELECT id FROM fams_notification_channel_version WHERE version=?) WHERE singleton_id=1",
                version, version);
    }

    private void reminder(long id, long recipient) {
        // Each occurrence freezes a fresh deadline (as after a task due-date change): the reminder
        // unique key dedups same object/stage/deadline/day/recipient, and eligibility joins t.due_at.
        var previous = db.queryForObject("SELECT due_at FROM fams_inventory_task WHERE id=" + TASK, Timestamp.class);
        var deadline = new Timestamp(previous.getTime() + 60_000);
        db.update("UPDATE fams_inventory_task SET due_at=? WHERE id=?", deadline, TASK);
        db.update("INSERT INTO fams_business_reminder (id,business_type,business_id,stage,deadline_at,notice_date,"
                + "reminder_business_date,reminder_type,recipient_pool_type,recipient_account_id,created_at)"
                + " VALUES (?,'INVENTORY_TASK',936300,'INVENTORY_COMPLETION',?,DATE(?),DATE(?),'OVERDUE',"
                + "'EMPLOYEE',?,NOW(3))", id, deadline, deadline, deadline, recipient);
    }

    private NotificationDeliveryWorker worker(WechatSubscriptionPort port) {
        var factory = new StaticListableBeanFactory();
        if (port != null) {
            factory.addBean("wechat", port);
        }
        ObjectProvider<MiniNotificationSmsPort> noSms =
                new StaticListableBeanFactory().getBeanProvider(MiniNotificationSmsPort.class);
        return new NotificationDeliveryWorker(jobs, deliveries, noSms,
                factory.getBeanProvider(WechatSubscriptionPort.class), channels);
    }

    private String jobStatus(String jobId) {
        return db.queryForObject("SELECT job_status FROM fams_durable_job WHERE id=?", String.class, jobId);
    }

    private long count(String sql) {
        return db.queryForObject(sql, Long.class);
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-FAMS-Mini-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String token, String key, String scene, String businessType,
            long businessId, String template) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/mini/subscription-grants"))
                .header("Content-Type", "application/json").header("X-FAMS-Mini-Session", token)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "templateId", template, "scene", scene, "businessType", businessType,
                        "businessId", Long.toString(businessId)))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode created(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void clean() {
        db.update("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        db.update("DELETE FROM SPRING_SESSION");
        db.update("DELETE FROM fams_notification_delivery WHERE recipient_account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_durable_job WHERE actor_account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_wechat_subscription_grant WHERE account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_mini_session WHERE account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_mini_binding WHERE account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_business_reminder WHERE id BETWEEN 936400 AND 936499");
        db.update("DELETE FROM fams_message_receipt WHERE account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_inventory_item WHERE task_id=936300");
        db.update("DELETE FROM fams_inventory_task WHERE id=936300");
        db.update("DELETE FROM fams_asset WHERE id IN (936200,936201)");
        db.update("DELETE FROM fams_business_parameter_version WHERE id=936100");
        db.update("DELETE FROM fams_audit_event WHERE actor_account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_security_event WHERE account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_idempotency_record WHERE resource_type='NOTIFICATION_CHANNEL_CONFIG'");
        db.update("DELETE FROM fams_idempotency_record WHERE actor_account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_account_role WHERE account_id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_system_account WHERE id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_employee WHERE id BETWEEN 936000 AND 936999");
        db.update("DELETE FROM fams_organization WHERE id BETWEEN 936000 AND 936999");
        db.update("UPDATE fams_notification_channel_state"
                + " SET current_version=0, current_version_id=NULL WHERE singleton_id=1");
        db.update("DELETE FROM fams_notification_channel_version");
    }

    private void seed() {
        var hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at)"
                + " VALUES (936000,'WXSUB','WXSUB','WXSUB','ACTIVE',0,NOW(3),NOW(3))");
        String[][] people = {{"936001", "wxsubowner", "EMPLOYEE"}, {"936002", "wxsubother", "EMPLOYEE"},
            {"936003", "wxsubadmin", "SYSTEM_ADMIN"}};
        for (var person : people) {
            var id = Long.parseLong(person[0]);
            db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                    + "status,version,created_at,updated_at) VALUES (?,?,?,?,936000,'ACTIVE',0,NOW(3),NOW(3))",
                    id, person[1].toUpperCase(), person[1].toUpperCase(), "WXSUB person " + person[2]);
            db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                    + "status,initial_password_required,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,'ENABLED',FALSE,NOW(3),NOW(3))",
                    id, id, person[1], person[1].toUpperCase(), hash);
            db.update("INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (?,?,NOW(3))",
                    id, person[2]);
        }
        var activeParameter = db.queryForList(
                "SELECT id FROM fams_business_parameter_version WHERE status='ACTIVE'", Long.class);
        if (activeParameter.isEmpty()) {
            db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,"
                    + "actor_username,snapshot_json,created_at) VALUES (936100,936100,'ACTIVE',NOW(3),936003,"
                    + "'wxsubadmin','{}',NOW(3))");
            parameterVersion = 936100;
        } else {
            parameterVersion = activeParameter.getFirst();
        }
        db.update("INSERT INTO fams_asset (id,asset_code,name,lifecycle_status,exception_status,card_stage,"
                + "created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,"
                + "created_at,updated_at,created_by,updated_by)"
                + " VALUES (936200,'WXSUB-A','Subscription asset','UNDER_REPAIR','NORMAL','ACTIVE',"
                + "'2026-11-01',?,936003,'wxsubadmin',NOW(3),NOW(3),936003,936003)", parameterVersion);
        var deadline = Timestamp.from(clock.instant().minusSeconds(172800).truncatedTo(ChronoUnit.MILLIS));
        db.update("INSERT INTO fams_inventory_task (id,name,task_status,range_json,starts_at,due_at,timezone_id,"
                + "creator_account_id,created_at,updated_at,expected_count)"
                + " VALUES (936300,'Subscription fixture','IN_PROGRESS','{}','2026-11-01 00:00:00',?,'UTC',"
                + "936003,NOW(3),NOW(3),1)", deadline);
        db.update("INSERT INTO fams_inventory_item (task_id,asset_id,asset_version,snapshot_json,executor_account_id)"
                + " VALUES (936300,936200,0,'{}',936001)");
        db.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                OWNER, identityHash("wx-sub-owner"));
        db.update("INSERT INTO fams_mini_binding (account_id,identity_hash,created_at) VALUES (?,?,NOW(3))",
                OTHER, identityHash("wx-sub-other"));
    }

    private String miniLogin(String code) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/mini/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("code", code))))
                .build(), HttpResponse.BodyHandlers.ofString());
        return ok(response).path("sessionToken").asText();
    }

    private static String identityHash(String openId) {
        return fingerprint("mini-identity/v1", "owned-wx-sub-app", openId);
    }

    private static String fingerprint(String... values) {
        var payload = new StringBuilder();
        for (String value : values) {
            payload.append(value.length()).append(':').append(value);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    @TestConfiguration
    static class StubWechatIdentity {
        @Bean
        MiniWechatIdentityPort wechatIdentityPort() {
            return code -> switch (code) {
                case "wx-sub-owner-code" -> new MiniWechatIdentityPort.Identity("owned-wx-sub-app", "wx-sub-owner");
                case "wx-sub-other-code" -> new MiniWechatIdentityPort.Identity("owned-wx-sub-app", "wx-sub-other");
                default -> throw new IllegalArgumentException("Unknown login code.");
            };
        }
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
