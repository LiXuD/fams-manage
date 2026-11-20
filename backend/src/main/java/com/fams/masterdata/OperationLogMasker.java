package com.fams.masterdata;

import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Phone values keep prefix and suffix; credential-like keys are redacted entirely. */
final class OperationLogMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern REDACT_KEY =
            Pattern.compile("(?i)(password|passwd|token|secret|credential|hash|sha256)");

    private final ObjectMapper json;

    OperationLogMasker(ObjectMapper json) {
        this.json = json;
    }

    Map<String, Object> mask(String detailJson) {
        JsonNode masked = maskNode(json.readTree(detailJson));
        if (!masked.isObject()) {
            return Map.of("detail", json.convertValue(masked, Object.class));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = json.convertValue(masked, Map.class);
        return result;
    }

    private JsonNode maskNode(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.properties().forEach(entry -> {
                if (REDACT_KEY.matcher(entry.getKey()).find()) {
                    result.put(entry.getKey(), "***");
                } else {
                    result.set(entry.getKey(), maskNode(entry.getValue()));
                }
            });
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(item -> result.add(maskNode(item)));
            return result;
        }
        if (value.isTextual()) {
            return tools.jackson.databind.node.StringNode.valueOf(
                    PHONE.matcher(value.asText()).replaceAll(match ->
                            match.group(1).substring(0, 3) + "****" + match.group(1).substring(7)));
        }
        return value;
    }
}
