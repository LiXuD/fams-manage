package com.fams.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.contract.probe.ContractProbeApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = ContractProbeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=local-observability",
            "management.server.port=0"
        })
class LocalObservabilityHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int businessPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    void metricsStayOffTheBusinessPort() throws Exception {
        HttpResponse<String> response = get(businessPort, "/actuator/prometheus");

        assertEquals(404, response.statusCode());
    }

    @Test
    void managementPortExposesOnlyHealthyLocalMetrics() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        assertEquals(200, health.statusCode());
        JsonNode healthBody = JSON.readTree(health.body());
        assertEquals("UP", healthBody.path("status").asText());
        assertFalse(healthBody.has("components"));

        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("jvm_memory_used_bytes"));
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
