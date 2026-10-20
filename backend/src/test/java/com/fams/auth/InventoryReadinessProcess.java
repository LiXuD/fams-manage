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
 * Owned subprocess commits or replays a unobserved reason through its own real HTTP listener and
 * shared MySQL.
 */
public final class InventoryReadinessProcess {
    private InventoryReadinessProcess() {}

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
            String task = environment.get("FAMS_TEST_READINESS_TASK"),
                    item = environment.get("FAMS_TEST_READINESS_ITEM");
            if (!task.matches("[1-9][0-9]*") || !item.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("Owned unobserved reason identifiers required");
            }
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/api/v1/inventory-readiness/tasks/"
                                                    + task
                                                    + "/items/"
                                                    + item
                                                    + "/unobserved"))
                            .header("Cookie", environment.get("FAMS_TEST_READINESS_COOKIE"))
                            .header("X-CSRF-Token", environment.get("FAMS_TEST_READINESS_CSRF"))
                            .header("Idempotency-Key", environment.get("FAMS_TEST_READINESS_KEY"))
                            .header(
                                    "If-Match-Version",
                                    environment.get("FAMS_TEST_READINESS_VERSION"))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            environment.get("FAMS_TEST_READINESS_BODY")))
                            .build();
            var response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            var json = new ObjectMapper();
            var expected = environment.get("FAMS_TEST_READINESS_ORIGINAL");
            if (expected != null) {
                assertEquals(json.readTree(expected), json.readTree(response.body()));
            }
            Path proof = Path.of(environment.get("FAMS_TEST_READINESS_PROOF"));
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
            if ("hold".equals(environment.get("FAMS_TEST_READINESS_STAGE"))) {
                while (true) {
                    Thread.sleep(1000);
                }
            }
        }
    }
}
