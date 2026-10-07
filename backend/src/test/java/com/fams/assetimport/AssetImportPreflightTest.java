package com.fams.assetimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AssetImportPreflightTest {
    private static final List<String> COLUMNS = List.of("资产编码", "名称", "分类编码", "所属组织编码",
            "型号", "单位", "序列号", "资产状态", "仓库编码", "位置编码", "使用组织编码", "使用人编号");

    @Test
    void historicalBorrowingRequiresDatesAndPreservesOverdueOpeningFact() {
        var columns = new java.util.ArrayList<>(COLUMNS);
        columns.add("借出日期");
        columns.add("预计归还日");
        var values = new HashMap<>(row(2, "B-1", "CAT-1", "LOC-1", "借出").values());
        values.put("使用组织编码", "ORG-1");
        values.put("使用人编号", "E-1");
        values.put("借出日期", "2020-01-01");
        values.put("预计归还日", "2020-02-01");
        var parsed = new AssetImportWorkbook.Parsed(columns,
                List.of(new AssetImportWorkbook.DataRow(2, values)), List.of());
        var result = AssetImportPreflight.check(parsed, Map.of(), catalog());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(java.time.LocalDate.of(2020, 1, 1), result.ready().getFirst().borrowedDate());
        assertEquals(java.time.LocalDate.of(2020, 2, 1), result.ready().getFirst().expectedReturnDate());
        for (String invalid : List.of("", "bad-date", "2019-12-31")) {
            values.put("预计归还日", invalid);
            var rejected = AssetImportPreflight.check(new AssetImportWorkbook.Parsed(columns,
                    List.of(new AssetImportWorkbook.DataRow(2, values)), List.of()), Map.of(), catalog());
            assertTrue(rejected.ready().isEmpty());
            assertTrue(rejected.errors().stream().anyMatch(e -> e.column().equals("预计归还日")));
        }
    }

    @Test
    void codeWinsOverNameAndLocationNameIsScopedToWarehouse() {
        var catalog = catalog();
        var result = AssetImportPreflight.check(parsed(row(2, "A-1", "CAT-1", "库位",
                "在库")), Map.of(), catalog);
        assertTrue(result.errors().isEmpty());
        assertEquals(1, result.ready().getFirst().categoryId());
        assertEquals(4, result.ready().getFirst().locationId());
    }

    @Test
    void preservesBadRowsAndSeparatesDuplicateCodesFromGoodRows() {
        var first = row(2, "A-1", "CAT-1", "LOC-1", "在库");
        var second = row(3, "a-1", "CAT-1", "LOC-1", "在库");
        var third = row(4, "A-3", "CAT-1", "LOC-1", "在库");
        var original = new AssetImportWorkbook.CellError(4, "名称", "=1+1", "FORMULA_NOT_ALLOWED");
        var parsed = new AssetImportWorkbook.Parsed(COLUMNS, List.of(first, second, third), List.of(original));
        var result = AssetImportPreflight.check(parsed, Map.of(), catalog());
        assertEquals(1, result.ready().size());
        assertEquals(2, result.errors().size());
        assertEquals("DUPLICATE_ASSET_CODE", result.errors().get(1).reason());
        assertEquals(3, result.errors().get(1).sheetRow());
    }

    @Test
    void inactiveCodeDoesNotFallBackToNameAndMappingMustExist() {
        var inactive = row(2, "A-2", "CAT-OFF", "LOC-1", "在库");
        var result = AssetImportPreflight.check(parsed(inactive), Map.of(), catalog());
        assertTrue(result.ready().isEmpty());
        assertEquals("REFERENCE_INACTIVE_OR_OUT_OF_SCOPE", result.errors().getFirst().reason());
        assertThrows(IllegalArgumentException.class, () -> AssetImportPreflight.check(
                parsed(inactive), Map.of("name", "不存在的列"), catalog()));
    }

    @Test
    void customNameColumnAndAbsentOptionalColumnsCanPreview() {
        var source = row(5, "", "CAT-1", "LOC-1", "在库");
        Map<String, String> values = new HashMap<>(source.values());
        values.put("物品名称", values.remove("名称"));
        values.remove("序列号");
        values.remove("使用组织编码");
        values.remove("使用人编号");
        List<String> columns = List.of("资产编码", "物品名称", "分类编码", "所属组织编码",
                "型号", "单位", "资产状态", "仓库编码", "位置编码");
        var parsed = new AssetImportWorkbook.Parsed(columns,
                List.of(new AssetImportWorkbook.DataRow(5, Map.copyOf(values))), List.of());
        var result = AssetImportPreflight.check(parsed, Map.of("name", "物品名称"), catalog());
        assertEquals(1, result.ready().size());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void overlongAssetFieldStaysInErrorRowsBeforePublication() {
        Map<String, String> values = new HashMap<>(row(2, "A-4", "CAT-1", "LOC-1", "在库").values());
        values.put("单位", "x".repeat(33));
        var result = AssetImportPreflight.check(parsed(new AssetImportWorkbook.DataRow(
                2, Map.copyOf(values))), Map.of(), catalog());
        assertTrue(result.ready().isEmpty());
        assertEquals("FIELD_TOO_LONG", result.errors().getFirst().reason());
        assertEquals("资产", result.errors().getFirst().sheet());
    }

    @Test
    void personalAndSharedUsageResolveOnlyAnEmployeeInTheUsingOrganization() {
        var personal = row(2, "P-1", "CAT-1", "LOC-1", "在用个人");
        var shared = row(3, "S-1", "CAT-1", "LOC-1", "在用公用");
        Map<String, String> first = new HashMap<>(personal.values());
        Map<String, String> second = new HashMap<>(shared.values());
        first.put("使用组织编码", "ORG-1");
        first.put("使用人编号", "E-1");
        second.put("使用组织编码", "ORG-1");
        second.put("使用人编号", "E-2");
        var base = catalog();
        var scoped = new AssetImportPreflight.Catalog(base.categories(), base.organizations(),
                base.warehouses(), base.locations(), List.of(ref(1, "E-1", "张三", "ACTIVE", 1L),
                        ref(2, "E-2", "李四", "ACTIVE", 2L)), Set.of(), Set.of());
        var result = AssetImportPreflight.check(new AssetImportWorkbook.Parsed(COLUMNS,
                List.of(new AssetImportWorkbook.DataRow(2, Map.copyOf(first)),
                        new AssetImportWorkbook.DataRow(3, Map.copyOf(second))), List.of()),
                Map.of(), scoped);
        assertEquals(1, result.ready().size());
        assertEquals("P-1", result.ready().getFirst().assetCode());
        assertEquals("REFERENCE_INACTIVE_OR_OUT_OF_SCOPE", result.errors().getFirst().reason());
    }

    @Test
    void existingIdentifiersAndAmbiguousCategoryNameStayInErrorRows() {
        Map<String, String> values = new HashMap<>(row(2, "existing", "办公", "LOC-1", "在库").values());
        values.put("序列号", "SER-1");
        var base = catalog();
        var ambiguous = new AssetImportPreflight.Catalog(
                List.of(ref(1, "CAT-1", "办公", "ACTIVE", null),
                        ref(2, "CAT-2", "办公", "ACTIVE", null)),
                base.organizations(), base.warehouses(), base.locations(), base.employees(),
                Set.of("EXISTING"), Set.of("ser-1"));
        var result = AssetImportPreflight.check(parsed(new AssetImportWorkbook.DataRow(2,
                Map.copyOf(values))), Map.of(), ambiguous);
        assertTrue(result.ready().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> "REFERENCE_NAME_AMBIGUOUS".equals(e.reason())));
        assertTrue(result.errors().stream().anyMatch(e -> "DUPLICATE_ASSET_CODE".equals(e.reason())));
        assertTrue(result.errors().stream().anyMatch(e -> "DUPLICATE_SERIAL_NUMBER".equals(e.reason())));
    }

    @Test
    void invalidStateAndMappingAreRejectedBeforePublication() {
        var invalid = row(2, "A-5", "UNKNOWN", "LOC-1", "已报废");
        var result = AssetImportPreflight.check(parsed(invalid), Map.of(), catalog());
        assertTrue(result.ready().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> "REFERENCE_NOT_FOUND".equals(e.reason())));
        assertTrue(result.errors().stream().anyMatch(e -> "UNSUPPORTED_INITIAL_STATE".equals(e.reason())));
        assertThrows(IllegalArgumentException.class, () -> AssetImportPreflight.columns(
                parsed(invalid), Map.of("unknownField", "名称")));
        assertThrows(IllegalArgumentException.class, () -> AssetImportPreflight.columns(
                parsed(invalid), Map.of("assetCode", "名称")));
        assertThrows(IllegalArgumentException.class, () -> AssetImportPreflight.check(
                parsed(invalid), Map.of(), null));
    }

    @Test
    void missingRequiredFieldsAndBlankStateCannotBecomeReadyRows() {
        Map<String, String> values = new HashMap<>(row(2, "A-6", "CAT-1", "LOC-1", "在库").values());
        values.put("名称", " ");
        values.put("型号", "");
        values.put("单位", "");
        values.put("分类编码", "");
        values.put("所属组织编码", "");
        values.put("资产状态", "");
        var result = AssetImportPreflight.check(parsed(new AssetImportWorkbook.DataRow(
                2, Map.copyOf(values))), Map.of(), catalog());
        assertTrue(result.ready().isEmpty());
        assertEquals(6, result.errors().stream().filter(e -> "REQUIRED".equals(e.reason())).count());
    }

    @Test
    void duplicateSerialAndWrongWarehouseLocationRejectOnlyAffectedRows() {
        Map<String, String> first = new HashMap<>(row(2, "A-7", "CAT-1", "LOC-1", "在库").values());
        Map<String, String> second = new HashMap<>(row(3, "A-8", "CAT-1", "LOC-2", "在库").values());
        first.put("序列号", "serial-7");
        second.put("序列号", "SERIAL-7");
        var result = AssetImportPreflight.check(new AssetImportWorkbook.Parsed(COLUMNS,
                List.of(new AssetImportWorkbook.DataRow(2, Map.copyOf(first)),
                        new AssetImportWorkbook.DataRow(3, Map.copyOf(second))), List.of()),
                Map.of(), catalog());
        assertEquals(1, result.ready().size());
        assertTrue(result.errors().stream().anyMatch(e -> e.sheetRow() == 3
                && "DUPLICATE_SERIAL_NUMBER".equals(e.reason())));
        assertTrue(result.errors().stream().anyMatch(e -> e.sheetRow() == 3
                && "REFERENCE_INACTIVE_OR_OUT_OF_SCOPE".equals(e.reason())));
    }

    @Test
    void workbookCellErrorKeepsOtherwiseValidRowOutOfPublication() {
        var cellError = new AssetImportWorkbook.CellError(2, "备注", "=SUM(1)", "FORMULA_NOT_ALLOWED");
        var source = row(2, "A-9", "CAT-1", "LOC-1", "在库");
        var result = AssetImportPreflight.check(new AssetImportWorkbook.Parsed(COLUMNS,
                List.of(source), List.of(cellError)), Map.of(), catalog());
        assertTrue(result.ready().isEmpty());
        assertEquals(List.of(cellError), result.errors());
    }

    @Test
    void ambiguousScopedLocationAndInactiveWarehouseAreRejected() {
        var base = catalog();
        var scoped = new AssetImportPreflight.Catalog(base.categories(), base.organizations(),
                List.of(ref(1, "WH-1", "总仓", "DISABLED", null)),
                List.of(ref(4, "LOC-1", "同名库位", "ACTIVE", 1L),
                        ref(5, "LOC-2", "同名库位", "ACTIVE", 1L)),
                base.employees(), Set.of(), Set.of());
        var inactive = AssetImportPreflight.check(parsed(row(2, "A-10", "CAT-1", "LOC-1", "在库")),
                Map.of(), scoped);
        assertTrue(inactive.ready().isEmpty());
        assertTrue(inactive.errors().stream().anyMatch(e -> "REFERENCE_INACTIVE_OR_OUT_OF_SCOPE"
                .equals(e.reason())));
        var ambiguous = AssetImportPreflight.check(parsed(row(3, "A-11", "CAT-1", "同名库位", "在库")),
                Map.of(), new AssetImportPreflight.Catalog(base.categories(), base.organizations(),
                        base.warehouses(), scoped.locations(), base.employees(), Set.of(), Set.of()));
        assertTrue(ambiguous.ready().isEmpty());
        assertEquals("REFERENCE_NAME_AMBIGUOUS", ambiguous.errors().getFirst().reason());
    }

    private AssetImportPreflight.Catalog catalog() {
        return new AssetImportPreflight.Catalog(
                List.of(ref(1, "CAT-1", "办公", "ACTIVE", null),
                        ref(2, "OTHER", "CAT-1", "ACTIVE", null),
                        ref(3, "CAT-OFF", "旧分类", "DISABLED", null)),
                List.of(ref(1, "ORG-1", "总部", "ACTIVE", null)),
                List.of(ref(1, "WH-1", "总仓", "ACTIVE", null)),
                List.of(ref(4, "LOC-1", "库位", "ACTIVE", 1L),
                        ref(5, "LOC-2", "库位", "ACTIVE", 2L)),
                List.of(ref(1, "E-1", "张三", "ACTIVE", 1L)),
                Set.of("existing"), Set.of());
    }

    private AssetImportPreflight.Reference ref(long id, String code, String name, String status, Long scope) {
        return new AssetImportPreflight.Reference(id, code, name, status, scope);
    }

    private AssetImportWorkbook.Parsed parsed(AssetImportWorkbook.DataRow row) {
        return new AssetImportWorkbook.Parsed(COLUMNS, List.of(row), List.of());
    }

    private AssetImportWorkbook.DataRow row(int sheetRow, String code, String category,
            String location, String state) {
        Map<String, String> values = new HashMap<>();
        for (String column : COLUMNS) { values.put(column, ""); }
        values.put("资产编码", code);
        values.put("名称", "Desk");
        values.put("分类编码", category);
        values.put("所属组织编码", "ORG-1");
        values.put("型号", "M1");
        values.put("单位", "台");
        values.put("资产状态", state);
        values.put("仓库编码", "WH-1");
        values.put("位置编码", location);
        return new AssetImportWorkbook.DataRow(sheetRow, Map.copyOf(values));
    }
}
