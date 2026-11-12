package com.fams.assetimport;

import com.fams.business.InitialRepairPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
                           java.time.LocalDate expectedReturnDate, String preScrapState,
                           String scrapReason, String scrapCondition, String proposedDisposition,
                           String preRepairState, InitialRepairPort.Facts repairFacts) { }
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
            Map.entry("borrowedDate", "借出日期"), Map.entry("expectedReturnDate", "预计归还日"),
            Map.entry("preScrapState", "报废前状态"), Map.entry("scrapReason", "报废原因"),
            Map.entry("scrapCondition", "报废前现状"), Map.entry("proposedDisposition", "建议处置方式"),
            Map.entry("preRepairState", "维修前状态"), Map.entry("repairStage", "当前维修阶段"),
            Map.entry("repairFault", "维修故障"), Map.entry("repairImpact", "影响程度"),
            Map.entry("repairOccurredAt", "故障发生时刻"), Map.entry("repairAcceptedAt", "接修时刻"),
            Map.entry("repairLocation", "接修位置编码"), Map.entry("repairEvidence", "维修承接依据"),
            Map.entry("factoryProvider", "厂家名称"), Map.entry("factoryContact", "厂家联系人"),
            Map.entry("factoryPhone", "厂家联系电话"), Map.entry("factoryDestination", "厂家实物去向"),
            Map.entry("factoryDispatchedAt", "实际寄出时刻"), Map.entry("factoryDispatchEvidence", "寄出依据"),
            Map.entry("factoryDue", "预计收回日期"), Map.entry("factoryCostPolicy", "返厂费用规则"),
            Map.entry("factoryCostLimit", "返厂费用限额"), Map.entry("factoryRoundNumber", "当前返厂轮次"));

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
            String preScrapState = null;
            String scrapReason = null;
            String scrapCondition = null;
            String proposedDisposition = null;
            String custodyState = state;
            String preRepairState = null;
            InitialRepairPort.Facts repair = null;
            boolean repairOpening = "维修中".equals(state) || "返厂维修".equals(state);
            if (repairOpening) {
                preRepairState = required(row, mapping, "preRepairState", errors);
                custodyState = preRepairState;
                if (!List.of("在库", "在用个人", "在用公用", "借出").contains(preRepairState)) {
                    if (!preRepairState.isBlank()) {
                        errors.add(error(row, mapping, "preRepairState", preRepairState, "UNSUPPORTED_PRE_REPAIR_STATE"));
                    }
                    custodyState = null;
                }
                repair = repairFacts(row, mapping, catalog, "返厂维修".equals(state), errors);
            }
            if ("待报废".equals(state)) {
                preScrapState = required(row, mapping, "preScrapState", errors);
                if (!List.of("在库", "在用个人", "在用公用").contains(preScrapState)) {
                    if (!preScrapState.isBlank()) {
                        errors.add(error(row, mapping, "preScrapState", preScrapState,
                                "UNSUPPORTED_PRE_SCRAP_STATE"));
                    }
                    custodyState = null;
                } else {
                    custodyState = preScrapState;
                }
                scrapReason = required(row, mapping, "scrapReason", errors);
                scrapCondition = required(row, mapping, "scrapCondition", errors);
                proposedDisposition = required(row, mapping, "proposedDisposition", errors);
                limit(row, mapping, "scrapReason", scrapReason, 2000, errors);
                limit(row, mapping, "scrapCondition", scrapCondition, 2000, errors);
                limit(row, mapping, "proposedDisposition", proposedDisposition, 500, errors);
            }
            if ("在库".equals(custodyState)) {
                warehouse = reference(row, mapping, "warehouse", catalog.warehouses(), null, errors);
                location = reference(row, mapping, "location", catalog.locations(), warehouse, errors);
            } else if ("在用个人".equals(custodyState) || "在用公用".equals(custodyState) || "借出".equals(custodyState)) {
                usingOrganization = reference(row, mapping, "usingOrganization",
                        catalog.organizations(), null, errors);
                employee = reference(row, mapping, "employee", catalog.employees(), usingOrganization, errors);
                location = reference(row, mapping, "location", catalog.locations(), null, errors);
                if ("借出".equals(custodyState)) {
                    borrowedDate = date(row, mapping, "borrowedDate", errors);
                    expectedReturnDate = date(row, mapping, "expectedReturnDate", errors);
                    if (borrowedDate != null && expectedReturnDate != null
                            && !expectedReturnDate.isAfter(borrowedDate)) {
                        errors.add(error(row, mapping, "expectedReturnDate",
                                expectedReturnDate.toString(), "BORROW_DATE_ORDER_INVALID"));
                    }
                }
            } else if (!state.isBlank() && !"待报废".equals(state) && !repairOpening) {
                errors.add(error(row, mapping, "state", state, "UNSUPPORTED_INITIAL_STATE"));
            }
            if (errors.size() == before && !invalidRows.contains(row.sheetRow())) {
                ready.add(new ReadyRow(row.sheetRow(), code, name, category, organization, model, unit,
                        serial, state, warehouse, location, usingOrganization, employee,
                        borrowedDate, expectedReturnDate, preScrapState, scrapReason, scrapCondition,
                        proposedDisposition, preRepairState, repair));
            }
        }
        return new Result(List.copyOf(ready), List.copyOf(errors));
    }

    private static InitialRepairPort.Facts repairFacts(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            Catalog catalog, boolean factory, List<AssetImportWorkbook.CellError> errors) {
        String stage = required(row, mapping, "repairStage", errors);
        if (!stage.isBlank() && !(factory ? "已寄出".equals(stage) : List.of("原地维修", "移出接修").contains(stage))) {
            errors.add(error(row, mapping, "repairStage", stage, "UNSUPPORTED_INITIAL_REPAIR_STAGE"));
        }
        String fault = repairText(row, mapping, "repairFault", 2000, errors);
        String impact = required(row, mapping, "repairImpact", errors);
        if (!impact.isBlank() && !List.of("LOW", "MEDIUM", "HIGH").contains(impact)) {
            errors.add(error(row, mapping, "repairImpact", impact, "INVALID_REPAIR_IMPACT"));
        }
        Instant occurred = instant(row, mapping, "repairOccurredAt", errors);
        Instant accepted = instant(row, mapping, "repairAcceptedAt", errors);
        if (occurred != null && accepted != null && accepted.isBefore(occurred)) {
            errors.add(error(row, mapping, "repairAcceptedAt", accepted.toString(), "INITIAL_REPAIR_TIME_ORDER_INVALID"));
        }
        String evidence = repairText(row, mapping, "repairEvidence", 2000, errors);
        Long place = "移出接修".equals(stage) || factory && !value(row, mapping, "repairLocation").isBlank()
                ? reference(row, mapping, "repairLocation", catalog.locations(), null, errors) : null;
        return new InitialRepairPort.Facts(stage, fault, impact, occurred, accepted, place, evidence,
                factory ? factoryFacts(row, mapping, accepted, errors) : null);
    }

    private static InitialRepairPort.Factory factoryFacts(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            Instant accepted, List<AssetImportWorkbook.CellError> errors) {
        String number = required(row, mapping, "factoryRoundNumber", errors);
        int round = 0;
        if (number.matches("[1-9][0-9]{0,8}")) {
            round = Integer.parseInt(number);
        } else if (!number.isBlank()) {
            errors.add(error(row, mapping, "factoryRoundNumber", number, "INVALID_INITIAL_FACTORY_ROUND"));
        }
        String provider = repairText(row, mapping, "factoryProvider", 200, errors);
        String contact = repairText(row, mapping, "factoryContact", 100, errors);
        String phone = repairText(row, mapping, "factoryPhone", 64, errors);
        String destination = repairText(row, mapping, "factoryDestination", 500, errors);
        Instant dispatch = instant(row, mapping, "factoryDispatchedAt", errors);
        if (accepted != null && dispatch != null && dispatch.isBefore(accepted)) {
            errors.add(error(row, mapping, "factoryDispatchedAt", dispatch.toString(), "INITIAL_REPAIR_TIME_ORDER_INVALID"));
        }
        var due = date(row, mapping, "factoryDue", errors);
        String evidence = repairText(row, mapping, "factoryDispatchEvidence", 2000, errors);
        String policy = required(row, mapping, "factoryCostPolicy", errors);
        String amount = value(row, mapping, "factoryCostLimit");
        BigDecimal limit = null;
        if ("LIMITED".equals(policy) && amount.matches("[0-9]{1,12}([.][0-9]{1,2})?")) {
            limit = new BigDecimal(amount);
        }
        if ("NO_COST".equals(policy) && amount.isBlank()) {
            limit = BigDecimal.ZERO;
        } else if (!policy.isBlank()
                && !(("UNLIMITED".equals(policy) && amount.isBlank()) || limit != null && limit.signum() > 0)) {
            errors.add(error(row, mapping, "factoryCostPolicy", policy, "INVALID_INITIAL_FACTORY_COST_POLICY"));
        }
        return new InitialRepairPort.Factory(round, provider, contact, phone, destination, dispatch, evidence, due, policy, limit);
    }

    private static String repairText(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            String field, int maximum, List<AssetImportWorkbook.CellError> errors) {
        String result = required(row, mapping, field, errors);
        limit(row, mapping, field, result, maximum, errors);
        return result;
    }

    private static Instant instant(AssetImportWorkbook.DataRow row, Map<String, String> mapping,
            String field, List<AssetImportWorkbook.CellError> errors) {
        String raw = required(row, mapping, field, errors);
        if (!raw.isBlank()) {
            try {
                Instant value = Instant.parse(raw);
                if (value.equals(value.truncatedTo(ChronoUnit.MILLIS))) {
                    return value;
                }
            } catch (java.time.format.DateTimeParseException invalid) {
                // The cell receives the same error as an unsupported precision.
            }
            errors.add(error(row, mapping, field, raw, "INVALID_INITIAL_REPAIR_TIMESTAMP"));
        }
        return null;
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
