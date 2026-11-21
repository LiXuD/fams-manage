package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fams.asset.AssetInventoryReadPort;
import com.fams.auth.InventoryExecutorPort;
import com.fams.masterdata.RequisitionMasterDataPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-09 wire equivalence for the three small cross-domain ports (RequisitionMasterDataPort,
 * InventoryExecutorPort, AssetInventoryReadPort). Each pre-refactor shape is frozen verbatim as a
 * reference oracle: raw JDBC row maps through the legacy requisition clean() projection, the
 * legacy map-copy stringification persisted into approved_target_json, the raw executor row list
 * returned as the /inventory/executors HTTP body, and the identify() row values re-emitted under
 * consumer-chosen keys. Comparison round-trips through JSON text so integral node widths normalize
 * and object key order (never stable in Map.of responses) is ignored, while key presence, explicit
 * nulls and value shapes all count (JAVA-01 standard).
 */
class SmallPortsWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- RequisitionMasterDataPort.choices -> catalog wire items (legacy clean projection) ----

    @Test
    void catalogChoiceMatchesLegacyRequisitionClean() {
        var category = choicesRow("category", 3L, "显示器", 2L, null);
        assertWireEquals(legacyClean(category), RequisitionMasterDataPort.CatalogChoice.fromRow(category));

        var employee = choicesRow("employee", 9L, "张三", 4L, 8L);
        assertWireEquals(legacyClean(employee), RequisitionMasterDataPort.CatalogChoice.fromRow(employee));

        // The catalog response embeds items in the untouched Map.of envelope.
        var legacyItems = List.of(legacyClean(category), legacyClean(employee));
        var typedItems =
                List.of(
                        RequisitionMasterDataPort.CatalogChoice.fromRow(category),
                        RequisitionMasterDataPort.CatalogChoice.fromRow(employee));
        assertWireEquals(
                Map.of("items", legacyItems, "page", 1, "pageSize", 50),
                Map.of("items", typedItems, "page", 1, "pageSize", 50));
    }

    @Test
    void catalogChoiceKeepsOrganizationIdOnlyOnEmployeeRows() {
        // Legacy employee rows always carry a non-null organization_id (DDL NOT NULL); non-employee
        // rows never carry the key. The typed projection must not add or drop the key.
        var employee = choicesRow("employee", 9L, "张三", 4L, 8L);
        var json = JSON.writeValueAsString(RequisitionMasterDataPort.CatalogChoice.fromRow(employee));
        assertEquals(JSON.readTree(JSON.writeValueAsString(legacyClean(employee))), JSON.readTree(json));

        var organization = choicesRow("organization", 8L, "研发部", 1L, null);
        assertNull(JSON.readTree(
                        JSON.writeValueAsString(
                                RequisitionMasterDataPort.CatalogChoice.fromRow(organization)))
                .get("organizationId"));
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacyClean(organization))),
                JSON.readTree(
                        JSON.writeValueAsString(
                                RequisitionMasterDataPort.CatalogChoice.fromRow(organization))));
    }

    // ---- RequisitionMasterDataPort.current -> target_snapshot_json storage shape ----

    @Test
    void masterReferenceKeepsPersistedTargetSnapshotShape() {
        var organization = currentRow("organization", 8L, "研发部", 1L, null);
        assertWireEquals(organization, RequisitionMasterDataPort.MasterReference.fromRow(organization));

        var employee = currentRow("employee", 9L, "张三", 4L, 8L);
        assertWireEquals(employee, RequisitionMasterDataPort.MasterReference.fromRow(employee));

        // insertDemands/decideChange persist Map.of(organization, employee, location) verbatim.
        var location = currentRow("location", 6L, "A栋货架", 2L, null);
        var legacy =
                Map.of(
                        "organization", organization,
                        "employee", employee,
                        "location", location);
        var typed =
                Map.of(
                        "organization",
                        RequisitionMasterDataPort.MasterReference.fromRow(organization),
                        "employee",
                        RequisitionMasterDataPort.MasterReference.fromRow(employee),
                        "location",
                        RequisitionMasterDataPort.MasterReference.fromRow(location));
        assertWireEquals(legacy, typed);

        // Storage stays the numeric-id snake_case form: no stringification, no camelCase.
        var node = JSON.readTree(JSON.writeValueAsString(typed)).get("employee");
        assertEquals(9L, node.get("id").asLong());
        assertEquals(8L, node.get("organization_id").asLong());
        assertEquals("ACTIVE", node.get("status").asText());
    }

    // ---- RequisitionHandoverService.targetReferenceSnapshot -> approved_target_json shape ----

    @Test
    void handoverReferenceSnapshotKeepsStringifiedStoredShape() {
        var organization = currentRow("organization", 8L, "研发部", 1L, null);
        assertWireEquals(
                legacyStringifiedSnapshot(organization),
                RequisitionHandoverService.ReferenceSnapshot.of(
                        RequisitionMasterDataPort.MasterReference.fromRow(organization)));

        var employee = currentRow("employee", 9L, "张三", 4L, 8L);
        assertWireEquals(
                legacyStringifiedSnapshot(employee),
                RequisitionHandoverService.ReferenceSnapshot.of(
                        RequisitionMasterDataPort.MasterReference.fromRow(employee)));

        var location = currentRow("location", 6L, "A栋货架", 2L, null);
        var legacy =
                Map.of(
                        "organization", legacyStringifiedSnapshot(organization),
                        "employee", legacyStringifiedSnapshot(employee),
                        "location", legacyStringifiedSnapshot(location));
        var typed =
                Map.of(
                        "organization",
                        RequisitionHandoverService.ReferenceSnapshot.of(
                                RequisitionMasterDataPort.MasterReference.fromRow(organization)),
                        "employee",
                        RequisitionHandoverService.ReferenceSnapshot.of(
                                RequisitionMasterDataPort.MasterReference.fromRow(employee)),
                        "location",
                        RequisitionHandoverService.ReferenceSnapshot.of(
                                RequisitionMasterDataPort.MasterReference.fromRow(location)));
        assertWireEquals(Map.of("referenceSnapshot", legacy), Map.of("referenceSnapshot", typed));

        // The stored snapshot stringifies id and *_id but keeps version numeric.
        var node =
                JSON.readTree(JSON.writeValueAsString(typed)).get("employee");
        assertEquals("9", node.get("id").asText());
        assertEquals("8", node.get("organization_id").asText());
        assertEquals(4L, node.get("version").asLong());
    }

    // ---- InventoryExecutorPort.choices -> /inventory/executors raw HTTP list ----

    @Test
    void executorChoicesMatchRawHttpShape() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", "12");
        row.put("name", "张三");
        row.put("organizationId", "7");
        assertWireEquals(row, InventoryExecutorPort.ExecutorChoice.fromRow(row));

        // The endpoint returns the row list as the whole body; the list shape must not drift.
        assertWireEquals(
                List.of(row), List.of(InventoryExecutorPort.ExecutorChoice.fromRow(row)));

        // A null name keeps the explicit key in the legacy map and in the typed record alike.
        var nullName = new LinkedHashMap<>(row);
        nullName.put("name", null);
        assertWireEquals(nullName, InventoryExecutorPort.ExecutorChoice.fromRow(nullName));
    }

    // ---- AssetInventoryReadPort.identify -> consumer-value shapes and JDBC pass-through ----

    @Test
    void identificationKeepsRawJdbcValuesForPassThroughColumns() {
        var issuedAt = new java.sql.Timestamp(System.currentTimeMillis());
        var scan = new LinkedHashMap<String, Object>();
        scan.put("id", "42");
        scan.put("asset_code", "FA-001");
        scan.put("name", "笔记本电脑");
        scan.put("tag_version", 4L);
        scan.put("issued_at", issuedAt);

        var typed = AssetInventoryReadPort.Identification.fromRow(scan);
        // tagVersion/issuedAt are bound straight into the tag-recognition insert: identical objects.
        assertSame(scan.get("tag_version"), typed.tagVersion());
        assertSame(issuedAt, typed.issuedAt());
        assertEquals("42", typed.id());
        assertEquals("FA-001", typed.assetCode());

        // The TASK_OUTSIDE response re-emits only these values under consumer-chosen keys.
        assertWireEquals(
                Map.of(
                        "kind",
                        "TASK_OUTSIDE",
                        "assetCode",
                        scan.get("asset_code"),
                        "name",
                        scan.get("name")),
                Map.of(
                        "kind",
                        "TASK_OUTSIDE",
                        "assetCode",
                        typed.assetCode(),
                        "name",
                        typed.name()));
    }

    @Test
    void codeIdentificationCarriesNoTagColumnsAndConsumersOnlyReadIdentity() {
        var code = new LinkedHashMap<String, Object>();
        code.put("id", "42");
        code.put("asset_code", "FA-001");
        code.put("name", "笔记本电脑");

        var typed = AssetInventoryReadPort.Identification.fromRow(code);
        assertNull(typed.tagVersion());
        assertNull(typed.issuedAt());
        // InventoryFindingService reads id().toString() only; the typed accessor yields the value.
        assertEquals(code.get("id").toString(), typed.id());
    }

    // ---- frozen pre-JAVA-09 oracles (verbatim copies) ----

    /** Frozen RequisitionService.clean projection (pre-JAVA-09); reference oracle only. */
    private static Map<String, Object> legacyClean(Map<String, Object> value) {
        var result = new LinkedHashMap<String, Object>();
        value.forEach(
                (key, raw) -> {
                    String[] parts = key.split("_");
                    StringBuilder name = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length; i++) {
                        name.append(Character.toUpperCase(parts[i].charAt(0)))
                                .append(parts[i].substring(1));
                    }
                    String field = name.toString();
                    Object cleaned = raw;
                    if (raw instanceof java.sql.Timestamp time) {
                        cleaned = time.toInstant().toString();
                    } else if (raw instanceof java.time.LocalDateTime time) {
                        cleaned = time.toInstant(java.time.ZoneOffset.UTC).toString();
                    } else if (raw instanceof java.sql.Date date) {
                        cleaned = date.toLocalDate().toString();
                    } else if (raw instanceof Number
                            && (field.equals("id") || field.endsWith("Id"))) {
                        cleaned = raw.toString();
                    } else if (field.endsWith("Json") && raw != null) {
                        cleaned = JSON.readValue(raw.toString(), Object.class);
                    }
                    result.put(field, cleaned);
                });
        return result;
    }

    /** Frozen RequisitionHandoverService map-copy stringification (pre-JAVA-09); oracle only. */
    private static Map<String, Object> legacyStringifiedSnapshot(Map<String, Object> row) {
        var reference = new LinkedHashMap<>(row);
        reference.replaceAll(
                (key, value) ->
                        value != null && ("id".equals(key) || key.endsWith("_id"))
                                ? value.toString()
                                : value);
        return reference;
    }

    /** Simulates a choices() JDBC row: category/organization/location rows have no organization_id. */
    private static LinkedHashMap<String, Object> choicesRow(
            String kind, long id, String name, long version, Long organizationId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
        row.put("version", version);
        if ("employee".equals(kind)) {
            row.put("organization_id", organizationId);
        }
        return row;
    }

    /** Simulates a current() JDBC row: id,name,status,version[,organization_id] in column order. */
    private static LinkedHashMap<String, Object> currentRow(
            String kind, long id, String name, long version, Long organizationId) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
        row.put("status", "ACTIVE");
        row.put("version", version);
        if ("employee".equals(kind)) {
            row.put("organization_id", organizationId);
        }
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
