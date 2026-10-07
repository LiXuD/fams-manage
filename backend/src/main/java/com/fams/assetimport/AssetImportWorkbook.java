package com.fams.assetimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Reads only bounded, literal XLSX cells; reference and business checks belong to the preflight service. */
public final class AssetImportWorkbook {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_CELL_LENGTH = 4_096;
    private static final List<String> TEMPLATE_COLUMNS = List.of("资产编码", "名称", "分类编码", "所属组织编码",
            "型号", "单位", "序列号", "资产状态", "仓库编码", "位置编码", "使用组织编码", "使用人编号",
            "借出日期", "预计归还日");

    public record CellError(String sheet, int sheetRow, String column, String original, String reason) {
        public CellError(int sheetRow, String column, String original, String reason) {
            this("资产", sheetRow, column, original, reason);
        }
    }
    public record DataRow(int sheetRow, Map<String, String> values) {
        public DataRow { values = Map.copyOf(values); }
    }
    public record Parsed(List<String> columns, List<DataRow> rows, List<CellError> errors) {
        public Parsed {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
            errors = List.copyOf(errors);
        }
    }

    private AssetImportWorkbook() { }

    public static Parsed parse(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            throw new IllegalArgumentException("ASSET_IMPORT_FILE_LIMIT");
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("资产");
            if (sheet == null) {
                throw new IllegalArgumentException("ASSET_IMPORT_SHEET_MISSING");
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw new IllegalArgumentException("ASSET_IMPORT_ROW_LIMIT");
            }
            var formatter = new DataFormatter(Locale.ROOT);
            List<String> columns = readColumns(sheet.getRow(0), formatter);
            List<DataRow> rows = new ArrayList<>();
            List<CellError> errors = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                readRow(sheet.getRow(index), index + 1, columns, formatter, rows, errors);
            }
            return new Parsed(List.copyOf(columns), List.copyOf(rows), List.copyOf(errors));
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw new IllegalArgumentException("ASSET_IMPORT_INVALID_XLSX", failure);
        }
    }

    public static byte[] template() {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("资产");
            var header = sheet.createRow(0);
            for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
                header.createCell(index).setCellValue(TEMPLATE_COLUMNS.get(index));
            }
            var help = workbook.createSheet("填写说明");
            help.createRow(0).createCell(0).setCellValue("请在资产页表头下填写数据，不依赖隐藏列。");
            help.createRow(1).createCell(0).setCellValue("分类、组织、仓库、位置和人员优先填写唯一编码。");
            help.createRow(2).createCell(0).setCellValue("示例：A-001 / 办公桌 / CAT-01 / ORG-01 / M1 / 台。");
            help.createRow(3).createCell(0).setCellValue("单文件最多 20,000 行、20 MB；公式单元格不能导入。");
            help.createRow(4).createCell(0).setCellValue("资产状态：在库、在用个人、在用公用或借出；预检将逐行报告错误。");
            help.createRow(5).createCell(0).setCellValue("借出须填写使用人、当前位置、借出日期、预计归还日（YYYY-MM-DD）；历史逾期允许导入。");
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("ASSET_IMPORT_TEMPLATE_FAILED", failure);
        }
    }

    public static String safeExportCell(String value) {
        if (value == null) { return ""; }
        String leading = value.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static List<String> readColumns(Row header, DataFormatter formatter) {
        if (header == null || header.getLastCellNum() < 1 || header.getLastCellNum() > MAX_COLUMNS) {
            throw new IllegalArgumentException("ASSET_IMPORT_HEADER_INVALID");
        }
        List<String> columns = new ArrayList<>();
        var seen = new HashSet<String>();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            Cell cell = header.getCell(index);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                throw new IllegalArgumentException("ASSET_IMPORT_HEADER_INVALID");
            }
            String name = text(cell, formatter).strip();
            if (name.isEmpty() || !seen.add(name)) {
                throw new IllegalArgumentException("ASSET_IMPORT_HEADER_INVALID");
            }
            columns.add(name);
        }
        return columns;
    }

    private static void readRow(Row row, int sheetRow, List<String> columns, DataFormatter formatter,
            List<DataRow> rows, List<CellError> errors) {
        if (row == null) { return; }
        Map<String, String> values = new LinkedHashMap<>();
        boolean populated = false;
        for (int index = 0; index < columns.size(); index++) {
            Cell cell = row.getCell(index);
            String value = text(cell, formatter);
            if (!value.isBlank()) { populated = true; }
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                errors.add(new CellError(sheetRow, columns.get(index), value, "FORMULA_NOT_ALLOWED"));
            } else if (value.length() > MAX_CELL_LENGTH) {
                errors.add(new CellError(sheetRow, columns.get(index), value, "CELL_TOO_LONG"));
            }
            values.put(columns.get(index), value);
        }
        if (row.getLastCellNum() > columns.size()) {
            for (int index = columns.size(); index < row.getLastCellNum(); index++) {
                String extra = text(row.getCell(index), formatter);
                if (!extra.isBlank()) {
                    populated = true;
                    errors.add(new CellError(sheetRow, "第" + (index + 1) + "列", extra, "UNMAPPED_COLUMN"));
                }
            }
        }
        if (populated) {
            rows.add(new DataRow(sheetRow, Collections.unmodifiableMap(values)));
        }
    }

    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) { return ""; }
        if (cell.getCellType() == CellType.FORMULA) { return "=" + cell.getCellFormula(); }
        return formatter.formatCellValue(cell);
    }
}
