package com.fams.masterdata;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
final class IdentityImportParser {

    static final String HEADER =
            "recordType,action,code,displayName,parentCode,organizationCode,phone,expectedVersion";
    private static final int MAX_ROWS = 500;

    List<IdentityImportRow> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw MasterDataOperationException.invalid("The identity import file is empty.");
        }
        List<List<String>> records = records(csv);
        if (records.isEmpty() || !String.join(",", records.getFirst()).equals(HEADER)) {
            throw MasterDataOperationException.invalid("The identity import header does not match the standard template.");
        }
        if (records.size() == 1) {
            throw MasterDataOperationException.invalid("The identity import contains no data rows.");
        }
        if (records.size() - 1 > MAX_ROWS) {
            throw MasterDataOperationException.invalid("The identity import exceeds 500 rows.");
        }
        List<IdentityImportRow> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> values = records.get(index);
            if (values.size() != 8) {
                throw MasterDataOperationException.invalid(
                        "Identity import row " + (index + 1) + " must contain exactly 8 columns.");
            }
            rows.add(new IdentityImportRow(
                    index + 1,
                    enumValue(values.get(0), RecordType.class, index),
                    enumValue(values.get(1), ImportAction.class, index),
                    required(values.get(2), "code", index),
                    values.get(3).strip(),
                    emptyToNull(values.get(4)),
                    emptyToNull(values.get(5)),
                    emptyToNull(values.get(6)),
                    version(values.get(7), index)));
        }
        return rows;
    }

    private List<List<String>> records(String csv) {
        List<List<String>> result = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < csv.length(); index++) {
            char current = csv.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                row.add(field.toString());
                field.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (!row.stream().allMatch(String::isBlank)) {
                    result.add(List.copyOf(row));
                }
                row.clear();
            } else {
                field.append(current);
            }
        }
        if (quoted) {
            throw MasterDataOperationException.invalid("The identity import contains an unterminated quoted field.");
        }
        row.add(field.toString());
        if (!row.stream().allMatch(String::isBlank)) {
            result.add(List.copyOf(row));
        }
        return result;
    }

    private String required(String value, String field, int index) {
        if (value == null || value.isBlank()) {
            throw MasterDataOperationException.invalid(
                    "Identity import row " + (index + 2) + " requires " + field + ".");
        }
        return value.strip();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Long version(String value, int index) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.invalid(
                    "Identity import row " + (index + 2) + " has an invalid expectedVersion.");
        }
    }

    private <T extends Enum<T>> T enumValue(String value, Class<T> type, int index) {
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException exception) {
            throw MasterDataOperationException.invalid(
                    "Identity import row " + (index + 2) + " has an invalid " + type.getSimpleName() + ".");
        }
    }
}

record IdentityImportRow(
        int rowNumber,
        RecordType recordType,
        ImportAction action,
        String code,
        String displayName,
        String parentCode,
        String organizationCode,
        String phone,
        Long expectedVersion) {
}

enum RecordType {
    ORGANIZATION,
    EMPLOYEE
}

enum ImportAction {
    CREATE,
    UPDATE,
    DISABLE
}
