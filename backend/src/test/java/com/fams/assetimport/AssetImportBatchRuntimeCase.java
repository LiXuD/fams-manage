package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@EnabledIfSystemProperty(named = "d6.08.import.runtime", matches = "true")
class AssetImportBatchRuntimeCase {
    @Test
    void mysqlUploadIdempotencyMappedPreviewAndRowReplacement() throws Exception {
        var datasource = new DriverManagerDataSource(System.getenv("D6_08_TEST_DATASOURCE_URL"),
                System.getenv("D6_08_TEST_DATASOURCE_USERNAME"),
                System.getenv("D6_08_TEST_DATASOURCE_PASSWORD"));
        var db = new JdbcTemplate(datasource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        db.update("INSERT INTO fams_asset_category (id,code,code_normalized,name,status,created_at,updated_at) "
                + "VALUES (1,'CAT-1','CAT-1','Office','ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at) "
                + "VALUES (1,'WH-1','WH-1','Main','ACTIVE',NOW(),NOW())");
        db.update("INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,"
                + "created_at,updated_at) VALUES (1,'LOC-1','LOC-1','Shelf','BIN',1,'ACTIVE',NOW(),NOW())");
        var service = new AssetImportBatchService(db, new AssetImportCatalogRepository(db),
                new ObjectMapper(), Clock.systemUTC());
        byte[] file = workbook("CAT-1");
        byte[] changed = workbook("UNKNOWN");
        var ticket = tx.execute(status -> service.upload(1, "asset-import-key-0001", file));
        assertEquals(ticket.id(), tx.execute(status -> service.upload(1, "asset-import-key-0001", file)).id());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_event "
                + "WHERE action='ASSET_IMPORT_UPLOADED'", Integer.class));
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                service.upload(1, "asset-import-key-0001", changed)));
        var preview = tx.execute(status -> service.preview(1, ticket.id(), 0, Map.of()));
        assertEquals(1, preview.readyRows());
        assertEquals(1, preview.errorRows());
        assertEquals(2, preview.totalRows());
        assertEquals(1, preview.version());
        assertEquals("DUPLICATE_ASSET_CODE", preview.errors().getFirst().reason());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_row", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_event", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_row "
                + "WHERE row_status='READY'", Integer.class));
        var page = tx.execute(status -> service.rows(1, ticket.id(), "ERROR", 1, 20));
        assertEquals(1, page.total());
        assertEquals(3, page.items().getFirst().sheetRow());
        assertEquals("DUPLICATE_ASSET_CODE", page.items().getFirst().errors().get(0).get("reason").asText());
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                service.preview(1, ticket.id(), 0, Map.of())));
        var repeated = tx.execute(status -> service.preview(1, ticket.id(), 1, Map.of()));
        assertEquals(2, repeated.version());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_event", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_row", Integer.class));
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                service.preview(2, ticket.id(), 2, Map.of())));
    }

    private byte[] workbook(String category) throws Exception {
        try (var input = new ByteArrayInputStream(AssetImportWorkbook.template());
                var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("资产");
            for (int index = 1; index <= 2; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue("A-100");
                row.createCell(1).setCellValue("Desk " + index);
                row.createCell(2).setCellValue(category);
                row.createCell(3).setCellValue("ROOT");
                row.createCell(4).setCellValue("M1");
                row.createCell(5).setCellValue("piece");
                row.createCell(7).setCellValue("在库");
                row.createCell(8).setCellValue("WH-1");
                row.createCell(9).setCellValue("LOC-1");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
