package com.fams.repair;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** F1 sorts object members, preserves array order and normalizes absent optional fields. */
public final class RepairFactoryPayloads {
    private RepairFactoryPayloads() {}

    public static String fingerprint(
            ObjectMapper json, String operation, long version, Object body) {
        try {
            String encoded =
                    "F1\n"
                            + operation
                            + "\n"
                            + version
                            + "\n"
                            + json.writeValueAsString(canonical(json, json.valueToTree(body)));
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static JsonNode canonical(ObjectMapper json, JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.properties().stream()
                    .filter(entry -> !entry.getValue().isNull())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> result.set(entry.getKey(), canonical(json, entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(item -> result.add(canonical(json, item)));
            return result;
        }
        return value;
    }
}
