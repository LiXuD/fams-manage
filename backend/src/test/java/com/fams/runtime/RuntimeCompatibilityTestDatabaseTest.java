package com.fams.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RuntimeCompatibilityTestDatabaseTest {

    @Test
    void rejectsMissingTaskDatabaseEnvironmentBeforeContextStartup() {
        assertThrows(
                IllegalStateException.class,
                () -> RuntimeCompatibilityHttpTest.DatabaseSettings.fromEnvironment(Map.of()));
    }

    @Test
    void rejectsTheFormerFixedLocalFallback() {
        Map<String, String> environment = Map.of(
                "D6_02B_TEST_DATASOURCE_URL",
                "jdbc:mysql://127.0.0.1:3306/fams_runtime_compatibility",
                "D6_02B_TEST_DATASOURCE_USERNAME",
                "runtime",
                "D6_02B_TEST_DATASOURCE_PASSWORD",
                "runtime");

        assertThrows(
                IllegalStateException.class,
                () -> RuntimeCompatibilityHttpTest.DatabaseSettings.fromEnvironment(environment));
    }

    @Test
    void acceptsOnlyTheTaskOwnedRandomDatabaseShape() {
        String url = "jdbc:mysql://127.0.0.1:54321/fams-d6-02b-db-0123456789abcdef"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String password = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
        Map<String, String> environment = Map.of(
                "D6_02B_TEST_DATASOURCE_URL",
                url,
                "D6_02B_TEST_DATASOURCE_USERNAME",
                "d6_02b_runtime",
                "D6_02B_TEST_DATASOURCE_PASSWORD",
                password);

        RuntimeCompatibilityHttpTest.DatabaseSettings settings =
                RuntimeCompatibilityHttpTest.DatabaseSettings.fromEnvironment(environment);

        assertEquals(url, settings.url());
        assertEquals("d6_02b_runtime", settings.username());
        assertEquals(password, settings.password());
    }
}
