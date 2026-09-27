package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = AttachmentRegistryTest.TestApplication.class, properties = {
    "spring.profiles.active=fams-product",
    "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra"
})
@EnabledIfSystemProperty(named = "d6.09.attachment.runtime", matches = "true")
class AttachmentRegistryTest {
    @Autowired AttachmentRegistry registry;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("D6_09_TEST_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("D6_09_TEST_DATASOURCE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("D6_09_TEST_DATASOURCE_PASSWORD"));
    }

    @Test
    void concurrentAttachmentsCannotExceedBusinessQuotas() throws Exception {
        seedUploader();
        for (int i = 0; i < 8; i++) { register(1, AttachmentFormat.PNG, 1); }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.function.Supplier<Boolean> attempt = () -> {
            ready.countDown();
            try {
                start.await();
                register(1, AttachmentFormat.PNG, 1);
                return true;
            } catch (IllegalArgumentException error) { return false; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        };
        var first = CompletableFuture.supplyAsync(attempt);
        var second = CompletableFuture.supplyAsync(attempt);
        ready.await(); start.countDown();
        assertTrue(first.get() != second.get());
        assertEquals(9, count(1));
        for (int i = 0; i < 5; i++) { register(2, AttachmentFormat.PDF, 1); }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> register(2, AttachmentFormat.PDF, 1));
        register(3, AttachmentFormat.PDF, 20L * 1024 * 1024);
        register(3, AttachmentFormat.PDF, 20L * 1024 * 1024);
        register(3, AttachmentFormat.PNG, 10L * 1024 * 1024);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> register(3, AttachmentFormat.PNG, 1));
        assertEquals(3, count(3));
        assertEquals(5, count(2));
        for (int i = 0; i < 8; i++) { register(4, AttachmentFormat.PNG, 1); }
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        var staleReader = CompletableFuture.supplyAsync(() -> {
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    assertEquals(8, count(4));
                    snapshotRead.countDown();
                    try { committed.await(); }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    register(4, AttachmentFormat.PNG, 1);
                });
                return true;
            } catch (IllegalArgumentException error) { return false; }
        });
        snapshotRead.await();
        register(4, AttachmentFormat.PNG, 1);
        committed.countDown();
        org.junit.jupiter.api.Assertions.assertFalse(staleReader.get());
        assertEquals(9, count(4));
    }

    private void register(long businessId, AttachmentFormat format, long bytes) {
        registry.register("INBOUND", businessId, 0, 1, "evidence",
                new AttachmentIntake.QuarantinedAttachment(
                    new AttachmentStorage.StoredFile(UUID.randomUUID(), bytes, "a".repeat(64)),
                    format, MalwareScanner.Result.CLEAN));
    }

    private int count(long id) {
        return db.queryForObject("SELECT COUNT(*) FROM fams_attachment WHERE business_id=?", Integer.class, id);
    }

    private void seedUploader() {
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at) "
                + "VALUES (1,'ROOT','ROOT','Root','ACTIVE',0,NOW(3),NOW(3))");
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                + "status,version,created_at,updated_at) VALUES (1,'E1','E1','Uploader',1,'ACTIVE',0,NOW(3),NOW(3))");
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                + "status,initial_password_required,failed_attempts,auth_epoch,authorization_version,"
                + "version,created_at,updated_at) VALUES (1,1,'uploader','UPLOADER','unused','ENABLED',"
                + "FALSE,0,0,0,0,NOW(3),NOW(3))");
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
