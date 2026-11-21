package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JAVA-10c wire equivalence for the initial-era and remaining cross-domain ports. Each
 * pre-refactor shape is frozen verbatim as a reference oracle: the repair detail view that
 * LossRepairPort returned as a raw Map (whose only consumer read was the stringified "id"
 * embedded into fams_loss_report.recovery_snapshot_json / fams_loss_recovery
 * .execution_snapshot_json, the audit payload and the loss repairId response key), the task
 * row Map InventoryLateEvidencePort.append consumed for its terminal guard, its SQL bindings
 * and the stored fams_inventory_late_case.snapshot_json packet keys, and the receipt documents
 * FactoryReplacementInboundPort produced (the id bound into fams_repair_factory_replacement
 * .inbound_receipt_id plus the embedded "inbound" object of the still-untyped repair
 * replacementView HTTP shape). Comparison round-trips through JSON text so integral node widths
 * normalize and object key order (never stable in the Map.of-based shapes) is ignored, while
 * key presence, explicit nulls and value shapes all count (JAVA-01 standard). The three
 * initial ports (Scrap/Repair/LossInitialPort) and LossScrapSourcePort keep no Map in their
 * public signatures (grep-verified this batch), so they need no new oracle here.
 */
class InitialPortsWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- LossRepairPort.create/createAfterConfirmation -> narrow Created(id) ----

    @Test
    void createdKeepsTheViewStringifiedIdAndLossEmbeddings() {
        var legacyView = legacyRepairView(42L, "REPAIR_REQUESTED");
        // Frozen consumer reads (LossService 835/848/1170): the raw view's "id" Object was
        // embedded directly; the typed path embeds Created.id() under the same keys.
        var typed = LossRepairPort.Created.fromView(legacyView);
        assertEquals(legacyView.get("id"), typed.id());
        assertWireEquals(
                Map.of("execution", "input-frozen", "repairId", legacyView.get("id")),
                Map.of("execution", "input-frozen", "repairId", typed.id()));
        assertWireEquals(
                new LinkedHashMap<>(Map.of("repairId", legacyView.get("id"))),
                new LinkedHashMap<>(Map.of("repairId", typed.id())));
        assertWireEquals(
                Map.of("execution", "input-frozen", "repairId", legacyView.get("id"),
                        "recoveryId", "77"),
                Map.of("execution", "input-frozen", "repairId", typed.id(), "recoveryId", "77"));
    }

    @Test
    void createdReplaysStoredPreRefactorResponseJson() {
        // The idempotent replay path deserializes the pre-refactor saved repair receipt
        // (response_json written by RepairService.saveReceipt from the same view) back into a
        // Map; Created.fromView must yield the identical stringified id.
        var legacyStored = JSON.writeValueAsString(legacyRepairView(42L, "REPAIR_REQUESTED"));
        Map<String, Object> replayed = JSON.readValue(
                legacyStored, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertEquals("42", LossRepairPort.Created.fromView(replayed).id());
        // JDBC integral-width variant: an Integer id still projects to the same string.
        var integerId = new LinkedHashMap<String, Object>();
        integerId.put("id", Integer.valueOf(42));
        assertEquals("42", LossRepairPort.Created.fromView(integerId).id());
    }

    // ---- InventoryLateEvidencePort.append -> narrow Task input record ----

    @Test
    void taskKeepsPacketSqlAndGuardValuesOfTheRawRow() {
        var row = taskRow(15L, "COMPLETED", 3L);
        var task = com.fams.inventory.InventoryLateEvidencePort.Task.fromRow(row);

        // SQL bindings: the FOR SHARE/INSERT statements bound the raw row's Long id before and
        // bind the same long value after typing.
        assertEquals(row.get("id"), Long.valueOf(task.id()));

        // Stored fams_inventory_late_case.snapshot_json packet keys assembled from the task row:
        // taskId/terminalStatus/taskVersion serialize identically from the record projections
        // (the remaining packet keys come from untouched observation/report rows).
        var legacyPacket = new LinkedHashMap<String, Object>();
        legacyPacket.put("taskId", row.get("id").toString());
        legacyPacket.put("terminalStatus", row.get("task_status"));
        legacyPacket.put("taskVersion", row.get("version"));
        var typedPacket = new LinkedHashMap<String, Object>();
        typedPacket.put("taskId", Long.toString(task.id()));
        typedPacket.put("terminalStatus", task.taskStatus());
        typedPacket.put("taskVersion", task.version());
        assertWireEquals(legacyPacket, typedPacket);

        // JDBC integral-width variant: an Integer version stores the same JSON number.
        var wide = taskRow(15L, "TERMINATED", 3L);
        wide.put("version", Integer.valueOf(3));
        var wideTask = com.fams.inventory.InventoryLateEvidencePort.Task.fromRow(wide);
        var legacyWide = new LinkedHashMap<String, Object>();
        legacyWide.put("taskVersion", wide.get("version"));
        assertWireEquals(legacyWide, Map.of("taskVersion", wideTask.version()));

        // Guard input fidelity: the terminal-status check reads the identical String, so the
        // accept set {COMPLETED, CANCELLED, TERMINATED} and the reject behavior are unchanged.
        assertEquals(row.get("task_status"), task.taskStatus());
        assertEquals("DRAFT",
                com.fams.inventory.InventoryLateEvidencePort.Task.fromRow(taskRow(1L, "DRAFT", 0L))
                        .taskStatus());
    }

    // ---- FactoryReplacementInboundPort.create -> narrow Receipt(id) ----

    @Test
    void receiptKeepsTheStoredIdBindingAndHttpEmbedding() {
        // Frozen InboundService.receipt document: id is already the Long.toString string.
        var legacyReceipt = legacyReceiptDocument(88L, 4L, "COMPLETED", "IN-0088");
        var typed = com.fams.inbound.FactoryReplacementInboundPort.Receipt.fromView(legacyReceipt);
        assertEquals(legacyReceipt.get("id"), typed.id());

        // INSERT binding for fams_repair_factory_replacement.inbound_receipt_id and the
        // inboundReceiptId key of the untyped repair HTTP result/idempotency archive.
        assertWireEquals(
                Map.of("replacementId", "7", "inboundReceiptId", legacyReceipt.get("id")),
                Map.of("replacementId", "7", "inboundReceiptId", typed.id()));
    }

    // ---- FactoryReplacementInboundPort.context -> four-key Context embedded in repair HTTP ----

    @Test
    void contextKeepsTheEmbeddedInboundWireShape() {
        var legacyReceipt = legacyReceiptDocument(88L, 4L, "COMPLETED", "IN-0088");
        // Pre-refactor assembly (verbatim): Map.of over the four receipt keys.
        var legacyContext = Map.of("id", legacyReceipt.get("id"),
                "version", legacyReceipt.get("version"),
                "status", legacyReceipt.get("status"),
                "receiptNumber", legacyReceipt.get("receiptNumber"));
        var typedContext =
                com.fams.inbound.FactoryReplacementInboundPort.Context.fromView(legacyReceipt);
        assertWireEquals(legacyContext, typedContext);

        // Embedding into the still-untyped replacementView response: the inbound object and the
        // replacementAssetId null-guard behave identically, including the absent case.
        var legacyView = new LinkedHashMap<String, Object>();
        legacyView.put("id", "7");
        legacyView.put("inbound", legacyContext);
        legacyView.put("replacementAssetId", "41");
        var typedView = new LinkedHashMap<String, Object>();
        typedView.put("id", "7");
        typedView.put("inbound", typedContext);
        typedView.put("replacementAssetId", "41");
        assertWireEquals(legacyView, typedView);

        var hidden = new LinkedHashMap<String, Object>();
        hidden.put("id", "7");
        hidden.put("inbound", null);
        hidden.put("replacementAssetId", null);
        assertTrue(JSON.readTree(JSON.writeValueAsString(hidden)).path("inbound").isNull());

        // JDBC integral-width variant: an Integer version projects to the same JSON number.
        var wideReceipt = new LinkedHashMap<>(legacyReceipt);
        wideReceipt.put("version", Integer.valueOf(4));
        assertWireEquals(
                Map.of("version", wideReceipt.get("version")),
                Map.of("version",
                        com.fams.inbound.FactoryReplacementInboundPort.Context
                                .fromView(wideReceipt).version()));
    }

    // ---- frozen pre-JAVA-10c oracles (verbatim copies) ----

    /** Frozen InboundService.receipt keys the two inbound port methods read (oracle only). */
    private static Map<String, Object> legacyReceiptDocument(
            long id, long version, String status, String receiptNumber) {
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("id", Long.toString(id));
        receipt.put("version", version);
        receipt.put("status", status);
        receipt.put("receiptNumber", receiptNumber);
        receipt.put("businessDate", "2026-11-22");
        return receipt;
    }

    /**
     * Frozen RepairService.view projection of the fields the loss chain can observe (pre-JAVA-10c
     * LossRepairPort return); the id column is stringified exactly like the live view does.
     */
    private static Map<String, Object> legacyRepairView(long id, String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", Long.toString(id));
        result.put("assetId", "9");
        result.put("version", 0L);
        result.put("status", status);
        return result;
    }

    /** Simulates a locked fams_inventory_task row as JDBC returns it (BIGINT columns as Long). */
    private static LinkedHashMap<String, Object> taskRow(long id, String status, long version) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("task_status", status);
        row.put("version", version);
        return row;
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
