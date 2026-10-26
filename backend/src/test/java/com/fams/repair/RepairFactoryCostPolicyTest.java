package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

class RepairFactoryCostPolicyTest {
    @Test
    void oldRootDecisionSerializationMustKeepOriginalThreeFieldsForPermanentReplay() {
        assertEquals(
                "{\"result\":\"APPROVE\",\"allowedMethod\":\"INTERNAL_NO_COST\",\"reason\":\"Original"
                    + " checked scope\"}",
                new ObjectMapper()
                        .writeValueAsString(
                                new RepairInput.Decision(
                                        "APPROVE", "INTERNAL_NO_COST", "Original checked scope")));
    }

    @Test
    void repairWideCeilingsUseExactDecimalAndRejectAmbiguousPolicies() {
        var limited = RepairFactoryCostPolicy.parse("LIMITED", "100.00");
        assertTrue(limited.covers(new BigDecimal("39.99").add(new BigDecimal("60.01"))));
        assertFalse(limited.covers(new BigDecimal("100.01")));
        assertEquals("NO_COST", RepairFactoryCostPolicy.parse(null, null).mode());
        assertTrue(
                RepairFactoryCostPolicy.parse("UNLIMITED", null)
                        .covers(new BigDecimal("99999999999999.99")));
        assertThrows(RepairFailure.class, () -> RepairFactoryCostPolicy.parse("NO_COST", "0.01"));
        assertThrows(RepairFailure.class, () -> RepairFactoryCostPolicy.parse("LIMITED", "0"));
        assertThrows(RepairFailure.class, () -> RepairFactoryCostPolicy.parse("UNLIMITED", "100"));
        for (String value : new String[] {"-1", "1.001", "1e2", " 1", "1000000000000.00"}) {
            assertThrows(RepairFailure.class, () -> RepairFactoryCostPolicy.amount(value));
        }
    }

    @Test
    void nullableScopeExpansionPreservesOriginalF1Body() {
        var json = new ObjectMapper();
        var old =
                json.readTree(
                        "{\"assetVersion\":1,\"ownershipVersion\":2,\"lockVersion\":3,\"reason\":\"Original"
                            + " checked scope\"}");
        assertEquals(
                RepairFactoryPayloads.fingerprint(json, "FACTORY_SCOPE_REQUEST:1", 4, old),
                RepairFactoryPayloads.fingerprint(
                        json,
                        "FACTORY_SCOPE_REQUEST:1",
                        4,
                        new RepairFactoryInput.ScopeRequest(1, 2, 3, "Original checked scope")));
    }
}
