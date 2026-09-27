package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(classes = AttachmentCleanupTest.TestApplication.class, properties = {
    "spring.profiles.active=fams-product", "spring.flyway.locations=classpath:db/migration/d6_04",
    "fams.security.idempotency-hmac-key=test-idempotency-hmac-key-0123456789abcdef",
    "fams.security.employee-phone-key=test-phone-key-0123456789abcdef-extra", "fams.attachments.cleanup-enabled=false"
})
@EnabledIfSystemProperty(named = "d6.09.attachment.runtime", matches = "true")
class AttachmentCleanupTest {
    private static final Path ROOT = temporaryRoot();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);
    @Autowired JdbcTemplate db;
    @Autowired AttachmentStorage storage;
    @Autowired AttachmentRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("D6_09_TEST_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("D6_09_TEST_DATASOURCE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("D6_09_TEST_DATASOURCE_PASSWORD"));
        properties.add("fams.attachments.quarantine-root", () -> ROOT.toString());
    }

    @Test
    void expirationRechecksSubmissionAndRetriesCommittedFileDeletionWithoutDuplicateAudit() throws Exception {
        try {
            seed();
            UUID expired = attachment(200, 30);
            UUID fresh = attachment(201, 29);
            UUID submitted = attachment(202, 31);
            UUID completed = attachment(203, 31);
            UUID previouslySubmitted = attachment(204, 31);
            UUID raced = attachment(205, 31);
            var guardedDb = spy(db);
            doAnswer(invocation -> {
                Object actualRows = invocation.callRealMethod();
                // Commit submission after the real candidate SELECT and before locked recheck.
                db.update("UPDATE fams_inbound_receipt SET receipt_status='PENDING_ADMIN_DECISION',"
                        + "submitted_at=NOW(3),version=version+1 WHERE id=205");
                return actualRows;
            }).when(guardedDb).queryForList(startsWith("SELECT a.object_id,a.business_id"), any(Object.class), org.mockito.ArgumentMatchers.anyInt());
            var cleanup = new AttachmentCleanupService(guardedDb, storage, CLOCK, transactions);
            assertEquals(1, cleanup.expireDrafts(100));
            assertTrue(Files.exists(ROOT.resolve(expired.toString())));
            assertEquals(1, count("fams_attachment_cleanup_audit"));
            assertEquals(1, count("fams_attachment_deletion_job"));
            for (UUID preserved : java.util.List.of(fresh, submitted, completed, previouslySubmitted, raced)) {
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_attachment "
                        + "WHERE object_id=? AND deleted_at IS NULL", Integer.class, preserved.toString()));
                assertTrue(Files.exists(ROOT.resolve(preserved.toString())));
            }
            assertEquals(1L, db.queryForObject("SELECT version FROM fams_inbound_receipt WHERE id=200", Long.class));
            assertEquals("SCHEDULED_MAINTENANCE", db.queryForObject(
                    "SELECT executor_kind FROM fams_attachment_cleanup_audit", String.class));

            AtomicBoolean fail = new AtomicBoolean(true);
            var flaky = new AttachmentStorage() {
                @Override public StoredFile quarantine(InputStream source) throws IOException { return storage.quarantine(source); }
                @Override public InputStream open(UUID id) throws IOException { return storage.open(id); }
                @Override public void delete(UUID id) throws IOException {
                    if (fail.getAndSet(false)) { throw new IOException("Injected deletion failure"); }
                    storage.delete(id);
                }
            };
            var deleting = new AttachmentCleanupService(db, flaky, CLOCK, transactions);
            assertEquals(0, deleting.deletePending(100));
            assertEquals("PENDING", db.queryForObject("SELECT job_status FROM fams_attachment_deletion_job", String.class));
            assertTrue(Files.exists(ROOT.resolve(expired.toString())));
            assertEquals(0, deleting.deletePending(100));
            var restarted = new AttachmentCleanupService(db, storage,
                    Clock.offset(CLOCK, Duration.ofSeconds(31)), transactions);
            assertEquals(1, restarted.deletePending(100));
            assertFalse(Files.exists(ROOT.resolve(expired.toString())));
            assertEquals(0, restarted.deletePending(100));
            assertEquals(0, restarted.expireDrafts(100));
            assertEquals(1, count("fams_attachment_cleanup_audit"));
            // Restore the durable state left if bytes were deleted before completion committed.
            db.update("UPDATE fams_attachment_deletion_job SET job_status='PENDING',next_attempt_at=?,completed_at=NULL",
                    CLOCK.instant());
            assertEquals(1, restarted.deletePending(100));
            assertEquals("DONE", db.queryForObject("SELECT job_status FROM fams_attachment_deletion_job", String.class));
            assertEquals(1, count("fams_attachment_cleanup_audit"));

            UUID rolledBack = attachment(206, 31);
            db.execute("CREATE TRIGGER attachment_cleanup_fail_audit BEFORE INSERT ON fams_attachment_cleanup_audit "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected cleanup audit failure'");
            try { assertThrows(org.springframework.dao.DataAccessException.class, () -> deleting.expireDrafts(100)); }
            finally { db.execute("DROP TRIGGER attachment_cleanup_fail_audit"); }
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_attachment "
                    + "WHERE object_id=? AND deleted_at IS NULL", Integer.class, rolledBack.toString()));
            assertEquals(0L, db.queryForObject("SELECT version FROM fams_inbound_receipt WHERE id=206", Long.class));
            assertEquals(1, count("fams_attachment_deletion_job"));
            assertTrue(Files.exists(ROOT.resolve(rolledBack.toString())));
            assertEquals(1, restarted.expireDrafts(100));
            assertEquals(1, restarted.deletePending(100));

            // Old bytes with committed metadata survive reconciliation, including quarantined states.
            for (UUID preserved : java.util.List.of(fresh, submitted, completed, previouslySubmitted, raced)) {
                Files.setLastModifiedTime(ROOT.resolve(preserved.toString()),
                        java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(Duration.ofDays(2))));
            }
            var orphan = storage.quarantine(new ByteArrayInputStream("%PDF-1.7 orphan".getBytes()));
            Files.setLastModifiedTime(ROOT.resolve(orphan.id().toString()),
                    java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(Duration.ofHours(24))));
            var recent = storage.quarantine(new ByteArrayInputStream("%PDF-1.7 recent".getBytes()));
            Files.setLastModifiedTime(ROOT.resolve(recent.id().toString()),
                    java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(Duration.ofHours(23))));
            Path unrelated = ROOT.resolve("maintenance-note");
            Files.writeString(unrelated, "keep");
            Files.setLastModifiedTime(unrelated,
                    java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(Duration.ofDays(2))));
            var guard = new AttachmentObjectGuard(db, storage);
            var recon = new AttachmentReconciliationService(ROOT.toString(), db, storage, guard, CLOCK, transactions);
            assertEquals(1, recon.removeOrphans(100));
            assertEquals(0, recon.removeOrphans(100));
            assertFalse(Files.exists(ROOT.resolve(orphan.id().toString())));
            assertTrue(Files.exists(ROOT.resolve(recent.id().toString())));
            assertTrue(Files.exists(unrelated));
            for (UUID preserved : java.util.List.of(fresh, submitted, completed, previouslySubmitted, raced)) {
                assertTrue(Files.exists(ROOT.resolve(preserved.toString())));
            }
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            assertThrows(java.io.UncheckedIOException.class,
                    () -> tx.execute(status -> { guard.requireStored(orphan.id()); return null; }));

            // Registration holding the object guard wins over a concurrent orphan candidate.
            var registering = storage.quarantine(new ByteArrayInputStream("%PDF-1.7 registering".getBytes()));
            Files.setLastModifiedTime(ROOT.resolve(registering.id().toString()),
                    java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(Duration.ofDays(2))));
            try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var entered = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>> pending =
                        new java.util.concurrent.atomic.AtomicReference<>();
                tx.execute(status -> {
                    guard.requireStored(registering.id());
                    pending.set(workers.submit(() -> { entered.countDown(); return recon.removeOrphans(100); }));
                    try { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    registry.register("INBOUND", 201, 0, 200, "registered.pdf",
                            new AttachmentIntake.QuarantinedAttachment(registering, AttachmentFormat.PDF,
                                    MalwareScanner.Result.UNAVAILABLE));
                    return null;
                });
                assertEquals(0, pending.get().get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(Files.exists(ROOT.resolve(registering.id().toString())));
            }
        } finally {
            try (var files = Files.list(ROOT)) { for (Path file : files.toList()) { Files.delete(file); } }
            Files.delete(ROOT);
        }
    }

    private UUID attachment(long receiptId, int ageDays) throws Exception {
        var file = storage.quarantine(new ByteArrayInputStream("%PDF-1.7".getBytes()));
        registry.register("INBOUND", receiptId, 0, 200, "test.pdf",
                new AttachmentIntake.QuarantinedAttachment(file, AttachmentFormat.PDF, MalwareScanner.Result.CLEAN));
        db.update("UPDATE fams_attachment SET created_at=? WHERE object_id=?",
                CLOCK.instant().minus(Duration.ofDays(ageDays)), file.id().toString());
        return file.id();
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void seed() {
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at) "
                + "VALUES (200,'CLN','CLN','Cleanup','ACTIVE',0,NOW(3),NOW(3))");
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,"
                + "status,version,created_at,updated_at) VALUES (200,'CLN200','CLN200','Cleanup',200,'ACTIVE',0,NOW(3),NOW(3))");
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,"
                + "initial_password_required,failed_attempts,auth_epoch,authorization_version,version,created_at,updated_at) "
                + "VALUES (200,200,'cleanup','CLEANUP','unused','ENABLED',FALSE,0,0,0,0,NOW(3),NOW(3))");
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,"
                + "actor_username,snapshot_json,created_at) VALUES (200,200,'SUPERSEDED',NOW(3),200,'cleanup','{}',NOW(3))");
        for (int id = 200; id <= 206; id++) {
            String status = id == 202 ? "PENDING_ADMIN_DECISION" : id == 203 ? "COMPLETED" : "DRAFT";
            db.update("INSERT INTO fams_inbound_receipt (id,receipt_no,creator_account_id,creator_username_snapshot,"
                    + "receipt_status,business_date,parameter_version_id,timezone_snapshot,admin_decision_deadline_workdays,"
                    + "warehouse_claim_deadline_workdays,warehouse_first_execution_deadline_workdays,version,"
                    + "created_at,updated_at,created_by,updated_by,submitted_at) VALUES (?, ?,200,'cleanup',?,"
                    + "'2026-09-27',200,'Asia/Shanghai',2,1,2,0,NOW(3),NOW(3),200,200,?)", id, "CLN" + id, status,
                    id >= 202 && id <= 204 ? CLOCK.instant() : null);
        }
    }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("fams-attachment-cleanup-"); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }
    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication { }
}
