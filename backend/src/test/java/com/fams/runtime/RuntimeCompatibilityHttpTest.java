package com.fams.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Small real-socket contract for the runtime compatibility probe.
 *
 * <p>The test is opt-in because it needs the task-owned MySQL instance. The
 * repeatable verifier supplies the datasource properties and enables this
 * class after it has created the isolated database.</p>
 */
@SpringBootTest(
        classes = RuntimeCompatibilityHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=runtime-compatibility",
                "spring.datasource.url=${D6_02B_TEST_DATASOURCE_URL:jdbc:mysql://127.0.0.1:3306/fams_runtime_compatibility}",
                "spring.datasource.username=${D6_02B_TEST_DATASOURCE_USERNAME:runtime}",
                "spring.datasource.password=${D6_02B_TEST_DATASOURCE_PASSWORD:runtime}"
        })
@EnabledIfSystemProperty(named = "d6.02b.runtime", matches = "true")
class RuntimeCompatibilityHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void commitsAndReadsAProbeThroughRealHttp() throws Exception {
        String key = "junit-commit-" + System.nanoTime();
        HttpResponse<String> write = request(
                "POST",
                "/runtime-compatibility/probes",
                "{\"probeKey\":\"" + key + "\",\"probeValue\":\"committed\"}",
                null);

        assertEquals(201, write.statusCode());
        JsonNode writeBody = JSON.readTree(write.body());
        assertEquals(key, writeBody.path("probeKey").asText());
        assertEquals("committed", writeBody.path("probeValue").asText());

        HttpResponse<String> read = request("GET", "/runtime-compatibility/probes/" + key, null, null);
        assertEquals(200, read.statusCode());
        JsonNode readBody = JSON.readTree(read.body());
        assertEquals(key, readBody.path("probeKey").asText());
        assertEquals("committed", readBody.path("probeValue").asText());
    }

    @Test
    void rejectsTransactionalFailureWithoutACommittedResponse() throws Exception {
        String key = "junit-rollback-" + System.nanoTime();
        HttpResponse<String> response = request(
                "POST",
                "/runtime-compatibility/probes/rollback",
                "{\"probeKey\":\"" + key + "\"}",
                null);

        assertEquals(409, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("RUNTIME_COMPATIBILITY_TRANSACTION_REJECTED", body.path("code").asText());
        assertEquals(key, body.path("probeKey").asText());

        assertEquals(404, request("GET", "/runtime-compatibility/probes/" + key + "-first", null, null).statusCode());
        assertEquals(404, request("GET", "/runtime-compatibility/probes/" + key + "-second", null, null).statusCode());
    }

    @Test
    void persistsHttpSessionProofInJdbcSession() throws Exception {
        HttpResponse<String> establish = request(
                "POST",
                "/runtime-compatibility/session/proof",
                "{\"proofValue\":\"junit-session\"}",
                null);

        assertEquals(200, establish.statusCode());
        JsonNode establishBody = JSON.readTree(establish.body());
        assertEquals("junit-session", establishBody.path("proofValue").asText());
        String setCookie = establish.headers().firstValue("Set-Cookie").orElse(null);
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("FAMS_D6_02B_SESSION="));
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> persisted = request("GET", "/runtime-compatibility/session/proof", null, cookie);
        assertEquals(200, persisted.statusCode());
        assertEquals("junit-session", JSON.readTree(persisted.body()).path("proofValue").asText());

        HttpResponse<String> absent = request("GET", "/runtime-compatibility/session/proof", null, null);
        assertEquals(401, absent.statusCode());
        assertEquals("RUNTIME_COMPATIBILITY_SESSION_UNAVAILABLE", JSON.readTree(absent.body()).path("code").asText());

        HttpResponse<String> wrong = request(
                "GET", "/runtime-compatibility/session/proof", null, "FAMS_D6_02B_SESSION=not-a-real-session");
        assertEquals(401, wrong.statusCode());
        JsonNode wrongBody = JSON.readTree(wrong.body());
        assertEquals("RUNTIME_COMPATIBILITY_SESSION_UNAVAILABLE", wrongBody.path("code").asText());
        assertFalse(wrongBody.path("proofValue").isTextual());
    }

    private HttpResponse<String> request(String method, String path, String body, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootApplication(scanBasePackages = "com.fams")
    static class TestApplication {
        public static void main(String[] args) {
            SpringApplication.run(TestApplication.class, args);
        }
    }
}
