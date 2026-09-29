package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named = "d6.08.capacity.runtime", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssetImportCapacityRuntimeCase {
    @Test
    @Order(2)
    void workbookRowAndByteBoundaries() throws Exception {
        byte[] limitFile = workbook(20_000);
        resetHeapPeak();
        assertEquals(20_000, AssetImportWorkbook.parse(limitFile).rows().size());
        long parsePeakMiB = peakHeapMiB();
        var tooMany = assertThrows(IllegalArgumentException.class,
                () -> AssetImportWorkbook.parse(workbook(20_001)));
        assertEquals("ASSET_IMPORT_ROW_LIMIT", tooMany.getMessage());
        var tooLarge = assertThrows(IllegalArgumentException.class,
                () -> AssetImportWorkbook.parse(new byte[AssetImportWorkbook.MAX_BYTES + 1]));
        assertEquals("ASSET_IMPORT_FILE_LIMIT", tooLarge.getMessage());
        System.out.println("D6_08_CAPACITY boundaryRows=20000 rejectedRows=20001 "
                + "rejectedBytes=20971521 parsePeakHeapMiB=" + parsePeakMiB);
    }

    @Test
    @Order(1)
    void tenThousandDistinctRowsPersistWithoutErrors() throws Exception {
        var datasource = new DriverManagerDataSource(System.getenv("D6_08_TEST_DATASOURCE_URL"),
                System.getenv("D6_08_TEST_DATASOURCE_USERNAME"),
                System.getenv("D6_08_TEST_DATASOURCE_PASSWORD"));
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration/d6_04").load().migrate();
        var db = new JdbcTemplate(datasource);
        db.update("INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at) "
                + "VALUES (1,'ROOT','ROOT','Root','ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,"
                + "created_at,updated_at) VALUES (1,'E1','E1','Worker',1,'ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                + "status,created_at,updated_at) VALUES (1,1,'worker','WORKER','test-only','ENABLED',NOW(),NOW())");
        db.update("INSERT INTO fams_asset_category (id,code,code_normalized,name,status,created_at,updated_at) "
                + "VALUES (1,'CAT-1','CAT-1','Office','ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at) "
                + "VALUES (1,'WH-1','WH-1','Main','ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,"
                + "created_at,updated_at) VALUES (1,'LOC-1','LOC-1','Shelf','BIN',1,'ACTIVE',NOW(),NOW())");
        var service = new AssetImportBatchService(db, new AssetImportCatalogRepository(db),
                new ObjectMapper(), Clock.systemUTC());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        long started = System.nanoTime();
        byte[] file = workbook(10_000);
        long generated = System.nanoTime();
        resetHeapPeak();
        long beforeUpload = System.nanoTime();
        var ticket = tx.execute(status -> service.upload(1, "capacity-10000-0001", file));
        long uploaded = System.nanoTime();
        var preview = tx.execute(status -> service.preview(1, ticket.id(), 0, Map.of()));
        long finished = System.nanoTime();
        assertEquals(10_000, preview.totalRows());
        assertEquals(10_000, preview.readyRows());
        assertEquals(0, preview.errorRows());
        assertEquals(10_000, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_row WHERE batch_id=?",
                Integer.class, ticket.id().toString()));
        System.out.printf("D6_08_CAPACITY rows=10000 bytes=%d generateMs=%d uploadMs=%d "
                + "previewMs=%d peakHeapMiB=%d%n", file.length, millis(started, generated),
                millis(beforeUpload, uploaded), millis(uploaded, finished), peakHeapMiB());
    }

    @Test
    @Order(3)
    void hundredThousandAssetsUseListDetailAndTagIndexes() {
        var datasource = new DriverManagerDataSource(System.getenv("D6_08_TEST_DATASOURCE_URL"),
                System.getenv("D6_08_TEST_DATASOURCE_USERNAME"),
                System.getenv("D6_08_TEST_DATASOURCE_PASSWORD"));
        var db = new JdbcTemplate(datasource);
        db.update("INSERT INTO fams_business_parameter_version (id,version,status,effective_at,"
                + "actor_account_id,actor_username,snapshot_json,created_at) "
                + "VALUES (1,1,'ACTIVE',NOW(),1,'worker','{}',NOW())");
        String insert = "INSERT INTO fams_asset (asset_code,name,category_id,owning_organization_id,"
                + "lifecycle_status,exception_status,card_stage,custody_type,storage_warehouse_id,"
                + "current_location_id,using_organization_id,responsible_employee_id,"
                + "created_business_date,inbound_business_date,parameter_version_id,creator_account_id,"
                + "creator_username_snapshot,source_text,version,created_at,updated_at,created_by,updated_by) "
                + "VALUES (?, ?, 1, 1, ?, 'NORMAL', 'ACTIVE', ?, ?, 1, ?, ?, CURRENT_DATE,"
                + "CURRENT_DATE, 1, 1, 'worker', 'CAPACITY_FIXTURE', 0, NOW(), NOW(), 1, 1)";
        long started = System.nanoTime();
        for (int batch = 0; batch < 10; batch++) {
            int first = batch * 10_000;
            db.batchUpdate(insert, new BatchPreparedStatementSetter() {
                @Override
                public int getBatchSize() { return 10_000; }

                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    int number = first + index + 1;
                    boolean personal = number > 80_000;
                    statement.setString(1, "SCALE-" + number);
                    statement.setString(2, "Asset " + number);
                    statement.setString(3, personal ? "IN_USE" : "IN_STOCK");
                    statement.setString(4, personal ? "PERSONAL" : "STORAGE");
                    if (personal) { statement.setNull(5, Types.BIGINT); }
                    else { statement.setLong(5, 1); }
                    if (personal) {
                        statement.setLong(6, 1);
                        statement.setLong(7, 1);
                    } else {
                        statement.setNull(6, Types.BIGINT);
                        statement.setNull(7, Types.BIGINT);
                    }
                }
            });
        }
        long seeded = System.nanoTime();
        assertEquals(100_000, db.queryForObject("SELECT COUNT(*) FROM fams_asset", Integer.class));
        Long firstId = db.queryForObject("SELECT id FROM fams_asset WHERE asset_code='SCALE-1'", Long.class);
        db.update("INSERT INTO fams_asset_tag (asset_id,token_ciphertext,token_hash,issued_at) "
                + "VALUES (?,?,?,NOW())", firstId, new byte[] {1}, "b".repeat(64));
        db.execute("ANALYZE TABLE fams_asset");
        String listSql = "SELECT a.* FROM fams_asset a WHERE "
                + "(a.card_stage NOT IN ('DRAFT','CANCELLED') OR a.creator_account_id=1) "
                + "AND a.category_id=1 AND a.lifecycle_status='IN_USE' "
                + "AND a.exception_status='NORMAL' ORDER BY a.id DESC LIMIT 50";
        long queryStarted = System.nanoTime();
        assertEquals(50, db.queryForList(listSql).size());
        long listed = System.nanoTime();
        assertEquals(20_000, db.queryForObject("SELECT COUNT(*) FROM fams_asset "
                + "WHERE category_id=1 AND lifecycle_status='IN_USE' AND exception_status='NORMAL'",
                Integer.class));
        long counted = System.nanoTime();
        assertEquals(firstId, db.queryForMap("SELECT id FROM fams_asset WHERE id=?", firstId).get("id"));
        long detailed = System.nanoTime();
        assertEquals(firstId, db.queryForObject("SELECT asset_id FROM fams_asset_tag WHERE token_hash=?",
                Long.class, "b".repeat(64)));
        long scanned = System.nanoTime();
        String listKey = (String) db.queryForMap("EXPLAIN " + listSql).get("key");
        String detailKey = (String) db.queryForMap("EXPLAIN SELECT * FROM fams_asset WHERE id=?",
                firstId).get("key");
        String scanKey = (String) db.queryForMap("EXPLAIN SELECT asset_id FROM fams_asset_tag "
                + "WHERE token_hash=?", "b".repeat(64)).get("key");
        assertNotNull(listKey);
        assertEquals("PRIMARY", detailKey);
        assertEquals("uk_asset_tag_hash", scanKey);
        System.out.printf("D6_08_CAPACITY assets=100000 inStock=80000 inUse=20000 seedMs=%d "
                        + "listMs=%d countMs=%d detailMs=%d scanMs=%d "
                        + "listKey=%s detailKey=%s scanKey=%s%n",
                millis(started, seeded), millis(queryStarted, listed), millis(listed, counted),
                millis(counted, detailed), millis(detailed, scanned), listKey, detailKey, scanKey);
    }

    private byte[] workbook(int rowCount) throws Exception {
        try (var input = new ByteArrayInputStream(AssetImportWorkbook.template());
                var book = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheet("资产");
            for (int index = 1; index <= rowCount; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue("CAP-" + index);
                row.createCell(1).setCellValue("Desk " + index);
                row.createCell(2).setCellValue("CAT-1");
                row.createCell(3).setCellValue("ROOT");
                row.createCell(4).setCellValue("M1");
                row.createCell(5).setCellValue("piece");
                row.createCell(7).setCellValue("在库");
                row.createCell(8).setCellValue("WH-1");
                row.createCell(9).setCellValue("LOC-1");
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    private long millis(long start, long end) {
        return (end - start) / 1_000_000;
    }

    private void resetHeapPeak() {
        System.gc();
        ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .forEach(java.lang.management.MemoryPoolMXBean::resetPeakUsage);
    }

    private long peakHeapMiB() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum() / (1024 * 1024);
    }
}
