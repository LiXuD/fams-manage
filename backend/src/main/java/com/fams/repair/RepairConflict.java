package com.fams.repair;

import java.util.Map;

public final class RepairConflict extends RepairFailure {
    private final Map<String, String> fields;

    public RepairConflict(String code, Map<String, String> fields) {
        super(409, code);
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
