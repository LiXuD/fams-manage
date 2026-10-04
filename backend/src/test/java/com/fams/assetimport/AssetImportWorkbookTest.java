package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class AssetImportWorkbookTest {
    @Test
    void templateHasVisibleColumnsAndSeparateInstructions() throws Exception {
        byte[] template = AssetImportWorkbook.template();
        var parsed = AssetImportWorkbook.parse(template);
        assertTrue(parsed.columns().contains("分类编码"));
        assertTrue(parsed.rows().isEmpty());
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            assertEquals("填写说明", workbook.getSheetAt(1).getSheetName());
        }
    }

    @Test
    void reportsLiteralCellAndFormulaAtExactSheetRow() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("资产");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("分类编码");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("Desk");
            row.createCell(1).setCellFormula("1+1");
            var parsed = AssetImportWorkbook.parse(bytes(workbook));
            assertEquals(3, parsed.rows().getFirst().sheetRow());
            assertEquals("Desk", parsed.rows().getFirst().values().get("名称"));
            assertEquals("分类编码", parsed.errors().getFirst().column());
            assertEquals(3, parsed.errors().getFirst().sheetRow());
            assertEquals("=1+1", parsed.errors().getFirst().original());
            assertEquals("FORMULA_NOT_ALLOWED", parsed.errors().getFirst().reason());
        }
    }

    @Test
    void rejectsOversizeAndDuplicateHeadersAndEscapesExportFormula() throws Exception {
        assertEquals("'  =cmd", AssetImportWorkbook.safeExportCell("  =cmd"));
        assertEquals("Desk", AssetImportWorkbook.safeExportCell("Desk"));
        assertThrows(IllegalArgumentException.class,
                () -> AssetImportWorkbook.parse(new byte[AssetImportWorkbook.MAX_BYTES + 1]));
        try (var workbook = new XSSFWorkbook()) {
            var header = workbook.createSheet("资产").createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("名称");
            assertEquals("ASSET_IMPORT_HEADER_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> AssetImportWorkbook.parse(bytes(workbook))).getMessage());
        }
    }

    private byte[] bytes(XSSFWorkbook workbook) throws IOException {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
