package com.fams.requisition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable receipt identity with bounded compatibility for earlier Map.of tuple ordering. */
final class ProxyReceiptPayloads {
    private ProxyReceiptPayloads() {}

    static String digest(ObjectMapper json, Object payload) {
        return hash(
                json.writeValueAsString(
                        canonical(json, json.readTree(json.writeValueAsString(payload)))));
    }

    static boolean matches(ObjectMapper json, Object payload, String expected) {
        if (digest(json, payload).equals(expected)
                || hash(json.writeValueAsString(payload)).equals(expected)) {
            return true;
        }
        // Earlier submit/decision/execute tuples used Map.of with two or three keys.
        // Values are unchanged; only those bounded top-level key permutations are accepted.
        if (!(payload instanceof Map<?, ?> values)
                || values.size() < 2
                || values.size() > 3
                || values.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            return false;
        }
        var keys = values.keySet().stream().map(Object::toString).sorted().toList();
        return legacy(json, values, keys, new ArrayList<>(), expected);
    }

    private static boolean legacy(
            ObjectMapper json,
            Map<?, ?> values,
            List<String> keys,
            List<String> order,
            String expected) {
        if (order.size() == keys.size()) {
            var tuple = new LinkedHashMap<String, Object>();
            order.forEach(key -> tuple.put(key, values.get(key)));
            return hash(json.writeValueAsString(tuple)).equals(expected);
        }
        for (String key : keys) {
            if (!order.contains(key)) {
                order.add(key);
                boolean found = legacy(json, values, keys, order, expected);
                order.removeLast();
                if (found) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode canonical(ObjectMapper json, JsonNode value) {
        if (value.isObject()) {
            var sorted = json.createObjectNode();
            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> sorted.set(entry.getKey(), canonical(json, entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(item -> array.add(canonical(json, item)));
            return array;
        }
        return value;
    }

    private static String hash(String json) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
