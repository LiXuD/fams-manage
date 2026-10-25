package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** F1 is a permanent receipt format; changing its encoding requires a new version. */
public class RepairFactoryPayloadsTest {
    private static final String GOLDEN =
            "e5dda39f2fd5a0a923a49d7d28079e6f323cda6a1a7faec21b020d23bb142a12";
    private final ObjectMapper json = new ObjectMapper();

    private static Map<String, Object> sample() {
        return Map.of(
                "roundVersion",
                10,
                "assetVersion",
                7,
                "metadata",
                Map.of("sha256", "a".repeat(64), "filename", "厂家原件.png"),
                "evidenceIds",
                List.of("9007199254740997", "file-b"),
                "ownershipVersion",
                2);
    }

    @Test
    void frozenUtf8GoldenIncludesOperationRootVersionAndNestedEvidence() {
        assertEquals(
                GOLDEN, RepairFactoryPayloads.fingerprint(json, "FACTORY_RECEIVE", 17, sample()));
    }

    @Test
    void reorderedMembersAndAbsentOptionalNullsHaveTheSameIdentity() {
        var reordered =
                json.readTree(
                        """
                        {"observedSerial":null,"ownershipVersion":2,"metadata":{
                          "filename":"厂家原件.png","optional":null,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                         "evidenceIds":["9007199254740997","file-b"],"assetVersion":7,"roundVersion":10}
                        """);
        assertEquals(
                GOLDEN, RepairFactoryPayloads.fingerprint(json, "FACTORY_RECEIVE", 17, reordered));
    }

    @Test
    void changedArrayOrderScalarTypeActualMetadataAndAnyVersionCannotReplay() {
        String body = json.writeValueAsString(sample());
        for (String changed :
                List.of(
                        body.replace(
                                "\"9007199254740997\",\"file-b\"",
                                "\"file-b\",\"9007199254740997\""),
                        body.replace("\"assetVersion\":7", "\"assetVersion\":\"7\""),
                        body.replace("\"assetVersion\":7", "\"assetVersion\":8"),
                        body.replace("\"roundVersion\":10", "\"roundVersion\":11"),
                        body.replace("\"ownershipVersion\":2", "\"ownershipVersion\":3"),
                        body.replace("厂家原件.png", "另一份厂家原件.png"),
                        body.replace("a".repeat(64), "b".repeat(64)))) {
            assertNotEquals(body, changed);
            assertNotEquals(
                    GOLDEN,
                    RepairFactoryPayloads.fingerprint(
                            json, "FACTORY_RECEIVE", 17, json.readTree(changed)));
        }
        assertNotEquals(
                GOLDEN, RepairFactoryPayloads.fingerprint(json, "FACTORY_DISPATCH", 17, sample()));
        assertNotEquals(
                GOLDEN, RepairFactoryPayloads.fingerprint(json, "FACTORY_RECEIVE", 18, sample()));
        assertNotEquals(
                RepairFactoryPayloads.fingerprint(json, "X", 1, json.readTree("[null]")),
                RepairFactoryPayloads.fingerprint(json, "X", 1, List.of()));
    }

    /** Used by the owned external JVM probe; no shell or host tooling dependency in unit tests. */
    public static void main(String[] args) {
        System.out.println(
                RepairFactoryPayloads.fingerprint(
                        new ObjectMapper(), "FACTORY_RECEIVE", 17, sample()));
    }
}
