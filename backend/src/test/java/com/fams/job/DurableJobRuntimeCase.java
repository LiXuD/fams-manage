package com.fams.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "d6.08.job.runtime", matches = "true")
class DurableJobRuntimeCase {
    private static final String HASH = "a".repeat(64);
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Test
    void mysqlSubmissionClaimFencingBackoffAndBusinessRollback() throws Exception {
        var datasource = new DriverManagerDataSource(System.getenv("D6_08_TEST_DATASOURCE_URL"),
                System.getenv("D6_08_TEST_DATASOURCE_USERNAME"),
                System.getenv("D6_08_TEST_DATASOURCE_PASSWORD"));
        var flyway = Flyway.configure().dataSource(datasource).locations("classpath:db/migration/d6_04").load();
        assertEquals(13, flyway.migrate().migrationsExecuted);
        var db = new JdbcTemplate(datasource);
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at) "
                + "VALUES (1,'ROOT','ROOT','Root','ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,"
                + "created_at,updated_at) VALUES (1,'E1','E1','Worker',1,'ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                + "status,created_at,updated_at) VALUES (1,1,'worker','WORKER','test-only','ENABLED',NOW(),NOW())");
        db.execute("CREATE TABLE d6_08_business_probe (id INT NOT NULL PRIMARY KEY)");
        var clock = new AdjustableClock();
        var jobs = new DurableJobService(db, clock);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return tx.execute(status ->
                    jobs.submit("ASSET_IMPORT", "batch-1", 1, "same-request-0001", HASH)); });
            var second = workers.submit(() -> { start.await(); return tx.execute(status ->
                    jobs.submit("ASSET_IMPORT", "batch-1", 1, "same-request-0001", HASH)); });
            start.countDown();
            assertEquals(first.get(30, TimeUnit.SECONDS).id(), second.get(30, TimeUnit.SECONDS).id());
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_durable_job", Integer.class));
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                jobs.submit("ASSET_IMPORT", "batch-1", 1, "same-request-0001", "b".repeat(64))));

        var firstLease = tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-A", LEASE).orElseThrow());
        assertTrue(tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-B", LEASE)).isEmpty());
        assertEquals(1, firstLease.generation());
        assertEquals(1, firstLease.attempts());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(tx.execute(status -> jobs.renew(firstLease, LEASE)).isAfter(firstLease.leaseUntil()));
        clock.advance(Duration.ofMinutes(3));
        var secondLease = tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-B", LEASE).orElseThrow());
        assertEquals(2, secondLease.generation());
        assertEquals(2, secondLease.attempts());
        var wrongBusiness = new DurableJobService.Claim(secondLease.id(), secondLease.kind(), "other-batch",
                secondLease.actorId(), secondLease.owner(), secondLease.generation(), secondLease.attempts(),
                secondLease.leaseUntil());
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jobs.complete(wrongBusiness, "wrong", () -> db.update("INSERT INTO d6_08_business_probe VALUES (3)"));
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jobs.complete(firstLease, "stale", () -> db.update("INSERT INTO d6_08_business_probe VALUES (1)"));
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jobs.fail(firstLease, "STALE", false);
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> jobs.renew(firstLease, LEASE)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM d6_08_business_probe", Integer.class));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jobs.complete(secondLease, "failed", () -> {
                db.update("INSERT INTO d6_08_business_probe VALUES (1)");
                throw new IllegalStateException("business rejected");
            });
            return null;
        }));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM d6_08_business_probe", Integer.class));
        assertEquals("RUNNING", db.queryForObject("SELECT job_status FROM fams_durable_job", String.class));
        tx.executeWithoutResult(status -> jobs.fail(secondLease, "TEMPORARY", true));
        assertTrue(tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-C", LEASE)).isEmpty());
        clock.advance(Duration.ofSeconds(61));
        var recovered = tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-C", LEASE).orElseThrow());
        tx.executeWithoutResult(status -> jobs.complete(recovered, "result-1",
                () -> db.update("INSERT INTO d6_08_business_probe VALUES (1)")));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM d6_08_business_probe", Integer.class));
        assertEquals("SUCCEEDED", db.queryForObject("SELECT job_status FROM fams_durable_job", String.class));
        assertFalse(tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-D", LEASE)).isPresent());
        tx.execute(status -> jobs.submit("ASSET_IMPORT", "terminal-batch", 1,
                "terminal-request-0001", HASH));
        var terminal = tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-D", LEASE).orElseThrow());
        tx.executeWithoutResult(status -> jobs.fail(terminal, "REJECTED", false));
        assertEquals("FAILED", db.queryForObject("SELECT job_status FROM fams_durable_job "
                + "WHERE id=?", String.class, terminal.id().toString()));
        assertTrue(tx.execute(status -> jobs.claim("ASSET_IMPORT", "worker-E", LEASE)).isEmpty());
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void processOneClaimsAndExitsWithoutFinishing() {
        var db = database();
        var jobs = new DurableJobService(db, new AdjustableClock());
        var tx = transaction(db);
        var ticket = tx.execute(status -> jobs.submit("ASSET_IMPORT", "restart-batch", 1,
                "restart-request-0001", HASH));
        var claim = tx.execute(status -> jobs.claim("ASSET_IMPORT", "process-one", LEASE).orElseThrow());
        assertEquals(ticket.id(), claim.id());
        assertEquals(1, claim.generation());
    }

    @Test
    void processTwoReclaimsAfterPreviousJvmExited() {
        var db = database();
        var clock = new AdjustableClock();
        clock.advance(Duration.ofMinutes(3));
        var jobs = new DurableJobService(db, clock);
        var tx = transaction(db);
        var claim = tx.execute(status -> jobs.claim("ASSET_IMPORT", "process-two", LEASE).orElseThrow());
        assertEquals("restart-batch", claim.businessReference());
        assertEquals(2, claim.generation());
        tx.executeWithoutResult(status -> jobs.complete(claim, "restart-result",
                () -> db.update("INSERT INTO d6_08_business_probe VALUES (2)")));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM d6_08_business_probe WHERE id=2", Integer.class));
        assertTrue(tx.execute(status -> jobs.claim("ASSET_IMPORT", "process-three", LEASE)).isEmpty());
    }

    private JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(System.getenv("D6_08_TEST_DATASOURCE_URL"),
                System.getenv("D6_08_TEST_DATASOURCE_USERNAME"),
                System.getenv("D6_08_TEST_DATASOURCE_PASSWORD")));
    }

    private TransactionTemplate transaction(JdbcTemplate db) {
        return new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-28T00:00:00Z"));

        void advance(Duration amount) { now.updateAndGet(value -> value.plus(amount)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
