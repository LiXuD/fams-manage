package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Populated V57 forward expansion, preserving old reports, evidence, events and receipts. */
@EnabledIfSystemProperty(named = "d6.21.loss.recovery.upgrade.runtime", matches = "true")
class LossRecoveryUpgradeRuntimeCase {
    private long report;

    @AfterEach
    void cleanupOnlyOwnedFacts() {
        var db = new JdbcTemplate(RepairFactoryPartsBaselineRuntimeCase.source());
        for (String table : List.of("fams_loss_receipt", "fams_loss_event")) {
            db.update("DELETE FROM " + table + " WHERE report_id=?", report);
        }
        db.update("DELETE FROM fams_loss_report WHERE id=?", report);
        new LossUpgradeRuntimeCase().removeOnlyOwnedFixtureBeforeSuccessorBusinessSuites();
    }

    @Test
    void populatedV57ReportsAndEveryLegacyColumnSurviveV58AndRepeat() {
        var source = RepairFactoryPartsBaselineRuntimeCase.source();
        var db = new JdbcTemplate(source);
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration/d6_04")
                .target("57")
                .load()
                .migrate();
        new RepairFactoryPartsBaselineRuntimeCase().seedAtCurrentSchema(db);
        long asset = db.queryForObject("SELECT id FROM fams_asset ORDER BY id LIMIT 1", Long.class);
        long actor =
                db.queryForObject(
                        "SELECT id FROM fams_system_account ORDER BY id LIMIT 1", Long.class);
        db.update(
                "INSERT INTO fams_loss_report(asset_id,creator_account_id,asset_version,"
                    + "source_snapshot_json,last_seen_at,last_location,circumstances,report_status,processor_account_id,next_review_date,created_at,updated_at)"
                    + " VALUES"
                    + " (?,?,0,'{\"legacy\":\"原责任快照\"}',NOW(3),'原登记位置','升级用已有查找事实，不代表实际业务验收','CONFIRMED_LOST',?,CURRENT_DATE+INTERVAL"
                    + " 7 DAY,NOW(3),NOW(3))",
                asset,
                actor,
                actor);
        report =
                db.queryForObject(
                        "SELECT id FROM fams_loss_report WHERE asset_id=? AND creator_account_id=?"
                                + " AND last_location='原登记位置'",
                        Long.class,
                        asset,
                        actor);
        db.update(
                "INSERT INTO"
                    + " fams_loss_event(report_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES"
                    + " (?,'LOSS_SEARCH_STARTED',0,1,?,'owned-upgrade','WAREHOUSE_OPERATOR','{\"oldEvidence\":true}',NOW(3))",
                report,
                actor);
        db.update(
                "INSERT INTO"
                    + " fams_loss_receipt(actor_account_id,operation,request_id,report_id,payload_sha256,response_json,created_at)"
                    + " VALUES"
                    + " (?,'SEARCH:legacy',?,?,SHA2('F1-owned-original',256),'{\"originalReceipt\":true}',NOW(3))",
                actor,
                UUID.randomUUID().toString(),
                report);
        db.update(
                "UPDATE fams_loss_report SET"
                    + " confirmed_lost_at=NOW(3),confirmation_snapshot_json=?,last_follow_up_at=NOW(3)"
                    + " WHERE id=?",
                "{\"legacyConfirmation\":\"原责任事实与永久回执保留\"}",
                report);
        var before = facts(db);
        var migration =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration/d6_04")
                        .target("58")
                        .load();
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals(0, migration.migrate().migrationsExecuted);
        assertEquals("58", migration.info().current().getVersion().toString());
        assertEquals(before, facts(db));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_report WHERE id=? AND recovered_at IS NULL"
                                + " AND recovery_snapshot_json IS NULL",
                        Long.class,
                        report));
        System.out.println(
                "{\"ownedLossRecoveryUpgrade\":true,\"version\":58,\"migrations\":1,\"repeat\":0,\"allOldColumnsUnchanged\":true}");
    }

    private Map<String, List<String>> facts(JdbcTemplate db) {
        var result = new LinkedHashMap<String, List<String>>();
        var json = new ObjectMapper();
        for (String table :
                db.queryForList(
                        "SELECT TABLE_NAME FROM information_schema.tables WHERE"
                            + " table_schema=DATABASE() AND table_name NOT IN"
                            + " ('flyway_schema_history','fams_loss_recovery') ORDER BY TABLE_NAME",
                        String.class)) {
            if (!table.matches("[a-zA-Z0-9_]+")) {
                throw new IllegalArgumentException("Unexpected owned table");
            }
            result.put(
                    table,
                    db.queryForList("SELECT * FROM " + table).stream()
                            .map(
                                    row -> {
                                        if ("fams_loss_report".equals(table)) {
                                            for (String field :
                                                    List.of(
                                                            "recovered_at",
                                                            "recovery_snapshot_json")) {
                                                row.remove(field);
                                            }
                                        }
                                        return json.writeValueAsString(row);
                                    })
                            .sorted()
                            .toList());
        }
        return result;
    }
}
