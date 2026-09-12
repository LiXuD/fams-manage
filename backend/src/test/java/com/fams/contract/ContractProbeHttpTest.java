package com.fams.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = ContractProbeHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractProbeHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "/api/v1/contract-probe";
    private static final String VALID_BODY = """
            {
              "assetId": "9007199254740993",
              "businessDate": "2026-09-13",
              "occurredAt": "2026-09-13T08:15:00+08:00",
              "status": "IN_SERVICE",
              "quantity": 0,
              "amount": "100.00",
              "serials": ["SN-SECOND", "SN-FIRST"]
            }
            """;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void acceptsRepresentativeWireValuesAndNormalizesTheInstant() throws Exception {
        HttpResponse<String> response = post(VALID_BODY);

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isObject());
        assertTrue(body.has("assetId"));
        assertTrue(body.get("assetId").isTextual());
        assertEquals("9007199254740993", body.get("assetId").asText());
        assertTrue(body.has("businessDate"));
        assertTrue(body.get("businessDate").isTextual());
        assertEquals("2026-09-13", body.get("businessDate").asText());
        assertTrue(body.has("occurredAt"));
        assertTrue(body.get("occurredAt").isTextual());
        assertEquals("2026-09-13T00:15:00Z", body.get("occurredAt").asText());
        assertTrue(body.has("status"));
        assertTrue(body.get("status").isTextual());
        assertEquals("IN_SERVICE", body.get("status").asText());
        assertTrue(body.has("quantity"));
        assertTrue(body.get("quantity").isIntegralNumber());
        assertEquals(0, body.get("quantity").asInt());
        assertTrue(body.has("amount"));
        assertTrue(body.get("amount").isTextual());
        assertEquals("100.00", body.get("amount").asText());
        assertTrue(body.has("serials"));
        assertTrue(body.get("serials").isArray());
        assertEquals(2, body.get("serials").size());
        assertTrue(body.get("serials").get(0).isTextual());
        assertEquals("SN-SECOND", body.get("serials").get(0).asText());
        assertTrue(body.get("serials").get(1).isTextual());
        assertEquals("SN-FIRST", body.get("serials").get(1).asText());
        assertTrue(body.has("note"));
        assertTrue(body.get("note").isNull());
    }

    @Test
    void acceptsExplicitNullForOptionalNullableNote() throws Exception {
        HttpResponse<String> response = post(VALID_BODY_WITH_NOTE.replace("\n}", ",\n  \"note\": null\n}"));

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.has("note"));
        assertTrue(body.get("note").isNull());
    }

    @Test
    void preservesEmptyOptionalNullableNote() throws Exception {
        HttpResponse<String> response = post(VALID_BODY_WITH_NOTE.replace("\n}", ",\n  \"note\": \"\"\n}"));

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.has("note"));
        assertTrue(body.get("note").isTextual());
        assertEquals("", body.get("note").asText());
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidRequests")
    void rejectsMalformedRequestsWithTheCommonErrorShape(String name, String requestBody) throws Exception {
        HttpResponse<String> response = post(requestBody);

        assertEquals(400, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isObject(), name);
        assertTrue(body.has("code"));
        assertTrue(body.path("code").isTextual());
        assertTrue(body.has("message"));
        assertTrue(body.path("message").isTextual());
        assertTrue(body.has("requestId"));
        assertFalse(body.path("requestId").asText().isBlank());
        assertTrue(body.has("fieldErrors"));
        assertTrue(body.path("fieldErrors").isObject());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("missing required data", "{}"),
                Arguments.of("null required integer", VALID_BODY.replace("\"quantity\": 0", "\"quantity\": null")),
                Arguments.of("numeric id", VALID_BODY.replace("\"9007199254740993\"", "9007199254740993")),
                Arguments.of("invalid enum", VALID_BODY.replace("IN_SERVICE", "UNKNOWN")),
                Arguments.of(
                        "malformed date",
                        VALID_BODY.replace(
                                "\"businessDate\": \"2026-09-13\"",
                                "\"businessDate\": \"2026-02-30\"")),
                Arguments.of("malformed date-time", VALID_BODY.replace("2026-09-13T08:15:00+08:00", "not-a-date-time")));
    }

    private static final String VALID_BODY_WITH_NOTE = VALID_BODY.stripTrailing();

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + ENDPOINT))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration
    @SpringBootApplication(scanBasePackages = "com.fams.contract.probe")
    static class TestApplication {
    }
}
