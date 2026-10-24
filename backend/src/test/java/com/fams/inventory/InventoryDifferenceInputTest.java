package com.fams.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.asset.AssetTransferPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

class InventoryDifferenceInputTest {
    private record LegacyDecision(
            List<InventoryDifferenceInput.Subject> subjects,
            long assetVersion,
            String method,
            String reason,
            String evidence,
            AssetTransferPort.Target transferTarget) {}

    @Test
    void absentRepairRetainsTheExactLegacyPayloadForPermanentOriginalReceipts() {
        var subjects = List.of(new InventoryDifferenceInput.Subject(1, "LOCATION", 0));
        var json = new ObjectMapper();
        assertEquals(
                json.writeValueAsString(
                        new LegacyDecision(
                                subjects,
                                2,
                                "BOOK_CORRECT",
                                "checked",
                                "original physical evidence",
                                null)),
                json.writeValueAsString(
                        new InventoryDifferenceInput.Decision(
                                subjects,
                                2,
                                "BOOK_CORRECT",
                                "checked",
                                "original physical evidence",
                                null)));
    }
}
