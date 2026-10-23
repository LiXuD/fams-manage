package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ProxyReceiptPayloadsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void bothObservedLegacyJvmOrdersReplayButDifferentVersionsCannot() throws Exception {
        var input = Map.of("id", 1L, "version", 3L);
        for (String legacy : List.of("{\"id\":1,\"version\":3}", "{\"version\":3,\"id\":1}")) {
            assertTrue(ProxyReceiptPayloads.matches(json, input, hash(legacy)));
            assertFalse(
                    ProxyReceiptPayloads.matches(
                            json, Map.of("id", 1L, "version", 4L), hash(legacy)));
        }
    }

    @Test
    void everyLegacyApprovalTupleOrderIsBoundedToTheSameDecision() throws Exception {
        var input = Map.of("id", 1, "version", 2, "decision", Map.of("approved", true));
        for (var order :
                List.of(
                        List.of("id", "version", "decision"),
                        List.of("id", "decision", "version"),
                        List.of("version", "id", "decision"),
                        List.of("version", "decision", "id"),
                        List.of("decision", "version", "id"),
                        List.of("decision", "id", "version"))) {
            var legacy = new LinkedHashMap<String, Object>();
            order.forEach(key -> legacy.put(key, input.get(key)));
            String digest = hash(json.writeValueAsString(legacy));
            assertTrue(ProxyReceiptPayloads.matches(json, input, digest));
            assertFalse(
                    ProxyReceiptPayloads.matches(
                            json,
                            Map.of("id", 1, "version", 2, "decision", Map.of("approved", false)),
                            digest));
        }
    }

    @Test
    void stableIdentityIgnoresObjectOrderButPreservesArraysTypesAndActualEvidence() {
        var first =
                json.readTree(
                        "{\"snapshot\":{\"target\":\"9007199254740993\",\"version\":3},\"evidence\":[\"a\",\"b\"]}");
        var reordered =
                json.readTree(
                        "{\"evidence\":[\"a\",\"b\"],\"snapshot\":{\"version\":3,\"target\":\"9007199254740993\"}}");
        assertEquals(
                ProxyReceiptPayloads.digest(json, first),
                ProxyReceiptPayloads.digest(json, reordered));
        assertTrue(
                ProxyReceiptPayloads.matches(
                        json, first, ProxyReceiptPayloads.digest(json, reordered)));
        assertNotEquals(
                ProxyReceiptPayloads.digest(json, first),
                ProxyReceiptPayloads.digest(
                        json,
                        json.readTree(
                                reordered.toString().replace("[\"a\",\"b\"]", "[\"b\",\"a\"]"))));
        assertFalse(
                ProxyReceiptPayloads.matches(
                        json,
                        json.readTree("{\"changed\":true}"),
                        ProxyReceiptPayloads.digest(json, first)));
        assertFalse(ProxyReceiptPayloads.matches(json, List.of(1, 2), "0".repeat(64)));
        assertFalse(
                ProxyReceiptPayloads.matches(
                        json, Map.of("a", 1, "b", 2, "c", 3, "d", 4), "0".repeat(64)));
    }

    private String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
