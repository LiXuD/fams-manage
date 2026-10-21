package com.fams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Owned subprocess commits or replays an immutable seal through its own real HTTP listener and
 * shared MySQL.
 */
public final class InventorySealProcess {
    private InventorySealProcess() {}

    public static void main(String[] args) throws Exception {
        var environment = System.getenv();
        var settings = AuthHttpTest.DatabaseSettings.fromEnvironment(environment);
        var application =
                new SpringApplication(
                        HandoverHttpTest.TestApplication.class,
                        HandoverHttpTest.MiniConfiguration.class);
        var properties =
                Map.of(
                        "spring.profiles.active",
                        "fams-product",
                        "server.address",
                        "127.0.0.1",
                        "server.port",
                        "0",
                        "spring.datasource.url",
                        settings.url(),
                        "spring.datasource.username",
                        settings.username(),
                        "spring.datasource.password",
                        settings.password(),
                        "spring.flyway.locations",
                        "classpath:db/migration/d6_04",
                        "fams.security.idempotency-hmac-key",
                        "test-idempotency-hmac-key-0123456789abcdef",
                        "fams.security.employee-phone-key",
                        "test-phone-key-0123456789abcdef-extra");
        try (var context =
                application.run(
                        properties.entrySet().stream()
                                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                                .toArray(String[]::new))) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String task = environment.get("FAMS_TEST_SEAL_TASK");
            if (!task.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("Owned seal task identifier required");
            }
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/api/v1/inventory-sealing/tasks/"
                                                    + task
                                                    + "/seal"))
                            .header("Cookie", environment.get("FAMS_TEST_SEAL_COOKIE"))
                            .header("X-CSRF-Token", environment.get("FAMS_TEST_SEAL_CSRF"))
                            .header("Idempotency-Key", environment.get("FAMS_TEST_SEAL_KEY"))
                            .header("If-Match-Version", environment.get("FAMS_TEST_SEAL_VERSION"))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            environment.get("FAMS_TEST_SEAL_BODY")))
                            .build();
            var response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            var json = new ObjectMapper();
            var expected = environment.get("FAMS_TEST_SEAL_ORIGINAL");
            if (expected != null) {
                assertEquals(json.readTree(expected), json.readTree(response.body()));
            }
            Path proof = Path.of(environment.get("FAMS_TEST_SEAL_PROOF"));
            Path temporary = proof.resolveSibling(proof.getFileName() + ".tmp");
            Files.writeString(
                    temporary,
                    json.writeValueAsString(
                            Map.of(
                                    "pid",
                                    ProcessHandle.current().pid(),
                                    "response",
                                    json.readTree(response.body()),
                                    "originalMatched",
                                    expected != null)));
            Files.move(temporary, proof, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            if ("hold".equals(environment.get("FAMS_TEST_SEAL_STAGE"))) {
                while (true) {
                    Thread.sleep(1000);
                }
            }
        }
    }
}
