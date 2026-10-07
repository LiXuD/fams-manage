package com.fams.assetimport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic preview: reference snapshots and existing identities are supplied by the application transaction. */
public final class AssetImportPreflight {
    public record Reference(long id, String code, String name, String status, Long scopeId) { }
    public record Catalog(List<Reference> categories, List<Reference> organizations,
                          List<Reference> warehouses, List<Reference> locations,
                          List<Reference> employees, Set<String> existingAssetCodes,
                          Set<String> existingSerialNumbers) {
        public Catalog {
            categories = List.copyOf(categories);
            organizations = List.copyOf(organizations);
            warehouses = List.copyOf(warehouses);
            locations = List.copyOf(locations);
            employees = List.copyOf(employees);
            existingAssetCodes = Set.copyOf(existingAssetCodes);
            existingSerialNumbers = Set.copyOf(existingSerialNumbers);
        }
    }
    public record ReadyRow(int sheetRow, String assetCode, String name, long categoryId,
                           long organizationId, String model, String unit, String serialNumber,
                           String state, Long warehouseId, Long locationId, Long usingOrganizationId,
                           Long employeeId, java.time.LocalDate borrowedDate,
                           java.time.LocalDate expectedReturnDate) { }
    public record Result(List<ReadyRow> ready, List<AssetImportWorkbook.CellError> errors) {
        public Result {
            ready = List.copyOf(ready);
            errors = List.copyOf(errors);
        }
    }

    private static final Map<String, String> DEFAULT_COLUMNS = Map.ofEntries(
            Map.entry("assetCode", "资产编码"), Map.entry("name", "名称"),
            Map.entry("category", "分类编码"), Map.entry("organization", "所属组织编码"),
            Map.entry("model", "型号"), Map.entry("unit", "单位"),
            Map.entry("serialNumber", "序列号"), Map.entry("state", "资产状态"),
            Map.entry("warehouse", "仓库编码"), Map.entry("location", "位置编码"),
            Map.entry("usingOrganization", "使用组织编码"), Map.entry("employee", "使用人编号"),
            Map.entry("borrowedDate", "借出日期"), Map.entry("expectedReturnDate", "预计归还日"));

    private AssetImportPreflight() { }

    public static Map<String, String> columns(AssetImportWorkbook.Parsed parsed,
            Map<String, String> selectedColumns) {
        if (parsed == null || selectedColumns == null
                || !DEFAULT_COLUMNS.keySet().containsAll(selectedColumns.keySet())) {
            throw new IllegalArgumentException("ASSET_IMPORT_MAPPING_INVALID");
        }
        Map<String, String> mapping = new HashMap<>();
        DEFAULT_COLUMNS.forEach((field, column) -> {
            if (parsed.columns().contains(column)) { mapping.put(field, column); }
        });
        mapping.putAll(selectedColumns);
        if (mapping.values().stream().anyMatch(value -> value == null || !parsed.columns().contains(value))
                || new HashSet<>(mapping.values()).size() != mapping.size()) {
            throw new IllegalArgumentException("ASSET_IMPORT_MAPPING_INVALID");
        }
        return Map.copyOf(mapping);
    }

    public static Result check(AssetImportWorkbook.Parsed parsed, Map<String, String> selectedColumns,
            Catalog catalog) {
        if (catalog == null) { throw new IllegalArgumentException("ASSET_IMPORT_CATALOG_MISSING"); }
        Map<String, String> mapping = columns(parsed, selectedColumns);
        List<ReadyRow> ready = new ArrayList<>();
        List<AssetImportWorkbook.CellError> errors = new ArrayList<>(parsed.errors());
        Set<Integer> invalidRows = new HashSet<>();
        for (var error : parsed.errors()) { invalidRows.add(error.sheetRow()); }
        Set<String> existingCodes = new HashSet<>();
        for (String code : catalog.existingAssetCodes()) { existingCodes.add(normalize(code)); }
        Set<String> existingSerials = new HashSet<>();
        for (String serial : catalog.existingSerialNumbers()) { existingSerials.add(normalize(serial)); }
        Set<String> seenCodes = new HashSet<>();
        Set<String> seenSerials = new HashSet<>();
        for (var row : parsed.rows()) {
            int before = errors.size();
            String code = value(row, mapping, "assetCode");
            String serial = value(row, mapping, "serialNumber");
            String name = required(row, mapping, "name", errors);
            String model = required(row, mapping, "model", errors);
            String unit = required(row, mapping, "unit", errors);
            limit(row, mapping, "assetCode", code, 128, errors);
            limit(row, mapping, "name", name, 256, errors);
            limit(row, mapping, "model", model, 128, errors);
            limit(row, mapping, "unit", unit, 32, errors);
            limit(row, mapping, "serialNumber", serial, 128, errors);
            Long category = reference(row, mapping, "category", catalog.categories(), null, errors);
            Long organization = reference(row, mapping, "organization", catalog.organizations(), null, errors);
            String state = required(row, mapping, "state", errors);
            if (!code.isBlank() && (!seenCodes.add(normalize(code))
                    || existingCodes.contains(normalize(code)))) {
                errors.add(error(row, mapping, "assetCode", code, "DUPLICATE_ASSET_CODE"));
            }
            if (!serial.isBlank() && (!seenSerials.add(normalize(serial))
                    || existingSerials.contains(normalize(serial)))) {
                errors.add(error(row, mapping, "serialNumber", serial, "DUPLICATE_SERIAL_NUMBER"));
            }
            Long warehouse = null;
            Long location = null;
            Long usingOrganization = null;
            Long employee = null;
            java.time.LocalDate borrowedDate = null;
            java.time.LocalDate expectedReturnDate = null;
            if ("在库".equals(state)) {
                warehouse = reference(row, mapping, "warehouse", catalog.warehouses(), null, errors);
                location = reference(row, mapping, "location", catalog.locations(), warehouse, errors);
            } else if ("在用个人".equals(state) || "在用公用".equals(state) || "借出".equals(state)) {
                usingOrganization = reference(row, mapping, "usingOrganization",
                        catalog.organizations(), null, errors);
                employee = reference(row, mapping, "employee", catalog.employees(), usingOrganization, errors);
                location = reference(row, mapping, "location", catalog.locations(), null, errors);
                if ("借出".equals(state)) {
                    borrowedDate = date(row, mapping, "borrowedDate", errors);
                    expectedReturnDate = date(row, mapping, "expectedReturnDate", errors);
                    if (borrowedDate != null && expectedReturnDate != null
                            && !expectedReturnDate.isAfter(borrowedDate)) {
                        errors.add(error(row, mapping, "expectedReturnDate",
                                expectedReturnDate.toString(), "BORROW_DATE_ORDER_INVALID"));
                    }
                }
            } else if (!state.isBlank()) {
                errors.add(error(row, mapping, "state", state, "UNSUPPORTED_INITIAL_STATE"));
            }
            if (errors.size() == before && !invalidRows.contains(row.sheetRow())) {
                ready.add(new ReadyRow(row.sheetRow(), code, name, category, organization, model, unit,
                        serial, state, warehouse, location, usingOrganization, employee,
                        borrowedDate, expectedReturnDate));
            }
        }
        return new Result(List.copyOf(ready), List.copyOf(errors));
    }

    private static Long reference(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            String field, List<Reference> references, Long scope, List<AssetImportWorkbook.CellError> errors) {
        String input = required(row, mapping, field, errors);
        if (input.isBlank()) { return null; }
        List<Reference> byCode = references.stream()
                .filter(reference -> normalize(reference.code()).equals(normalize(input))).toList();
        if (!byCode.isEmpty()) {
            Reference match = byCode.getFirst();
            if (!"ACTIVE".equals(match.status()) || (scope != null && !scope.equals(match.scopeId()))) {
                errors.add(error(row, mapping, field, input, "REFERENCE_INACTIVE_OR_OUT_OF_SCOPE"));
                return null;
            }
            return match.id();
        }
        List<Reference> byName = references.stream()
                .filter(reference -> reference.name().equals(input) && "ACTIVE".equals(reference.status()))
                .filter(reference -> scope == null || scope.equals(reference.scopeId())).toList();
        if (byName.size() != 1) {
            errors.add(error(row, mapping, field, input,
                    byName.isEmpty() ? "REFERENCE_NOT_FOUND" : "REFERENCE_NAME_AMBIGUOUS"));
            return null;
        }
        return byName.getFirst().id();
    }

    private static java.time.LocalDate date(AssetImportWorkbook.DataRow row,
            Map<String, String> mapping, String field, List<AssetImportWorkbook.CellError> errors) {
        String text = required(row, mapping, field, errors);
        if (text.isEmpty()) { return null; }
        try {
            return java.time.LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException invalid) {
            errors.add(error(row, mapping, field, text, "DATE_INVALID"));
            return null;
        }
    }

    private static String required(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            String field, List<AssetImportWorkbook.CellError> errors) {
        String result = value(row, mapping, field);
        if (result.isBlank()) { errors.add(error(row, mapping, field, result, "REQUIRED")); }
        return result;
    }

    private static void limit(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            String field, String value, int maximum, List<AssetImportWorkbook.CellError> errors) {
        if (value.length() > maximum) {
            errors.add(error(row, mapping, field, value, "FIELD_TOO_LONG"));
        }
    }

    private static String value(AssetImportWorkbook.DataRow row, Map<String, String> mapping, String field) {
        String column = mapping.get(field);
        return column == null ? "" : row.values().get(column).strip();
    }

    private static String normalize(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static AssetImportWorkbook.CellError error(AssetImportWorkbook.DataRow row,
            Map<String, String> mapping, String field, String original, String reason) {
        return new AssetImportWorkbook.CellError(row.sheetRow(),
                mapping.getOrDefault(field, DEFAULT_COLUMNS.get(field)), original, reason);
    }
}
