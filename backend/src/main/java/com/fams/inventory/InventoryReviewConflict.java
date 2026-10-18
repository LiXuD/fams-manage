package com.fams.inventory;

import java.util.Map;

final class InventoryReviewConflict extends RuntimeException {
    private final Map<String, String> fields;

    InventoryReviewConflict(String code, Map<String, String> fields) {
        super(code);
        this.fields = Map.copyOf(fields);
    }

    Map<String, String> fields() {
        return fields;
    }
}
