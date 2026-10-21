package com.fams.inventory;

import tools.jackson.databind.JsonNode;

/** A supplemental note cannot discharge an independently unresolved physical or time obligation. */
final class InventoryLatePolicy {
    private InventoryLatePolicy() {}

    static boolean requiresRecheck(String comparisonKind, JsonNode facts) {
        return "TIME_UNVERIFIED".equals(comparisonKind)
                || "FINDING".equals(comparisonKind)
                || "DAMAGED".equals(facts.path("actual").path("condition").asText());
    }
}
