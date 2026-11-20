package com.fams.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fams.asset.AssetTransferPort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-01 wire equivalence: every typed view must serialize exactly like the frozen
 * pre-refactor row projection (legacyWire below is that projection, kept verbatim as
 * the reference oracle). Comparison is on JSON trees, so key presence, explicit nulls
 * and value shapes all count.
 */
class TransferWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Object> requestRow = requestRow();
    private final Map<String, Object> itemRow = itemRow();

    @Test
    void summaryMatchesLegacyProjection() {
        assertWireEquals(legacyWire(requestRow), TransferViews.summary(requestRow));
    }

    @Test
    void detailMatchesLegacyAssembly() {
        var item = TransferViews.item(JSON, itemRow);
        var eventRow = eventRow();
        var event = TransferViews.event(JSON, eventRow);
        var legacy = new LinkedHashMap<>(legacyWire(requestRow));
        legacy.put("items", List.of(legacyWire(itemRow)));
        legacy.put("events", List.of(legacyWire(eventRow)));
        legacy.put("totalQuantity", 1);
        legacy.put("completedQuantity", 0);
        legacy.put("cancelledQuantity", 0);
        legacy.put("pendingQuantity", 1);
        legacy.put("sourceGroups", 1);
        legacy.put("targetGroups", 1);
        var typed = TransferViews.detail(requestRow, List.of(item), List.of(event), 1, 1);
        assertWireEquals(legacy, typed);
    }

    @Test
    void itemMatchesLegacyProjectionWithAndWithoutLockVersion() {
        assertWireEquals(legacyWire(itemRow), TransferViews.item(JSON, itemRow));
        var locked = new LinkedHashMap<>(itemRow);
        locked.put("lock_version", 4L);
        assertWireEquals(legacyWire(locked), TransferViews.item(JSON, locked));
    }

    @Test
    void completedItemMatchesLegacyProjection() {
        var completed = new LinkedHashMap<>(itemRow);
        completed.put("item_status", "COMPLETED");
        completed.put("completed_at", Timestamp.from(Instant.parse("2026-11-02T03:04:05Z")));
        completed.put("completed_by", 77L);
        completed.put("after_snapshot_json", "{\"custody_type\":\"STORAGE\",\"version\":9}");
        assertWireEquals(legacyWire(completed), TransferViews.item(JSON, completed));
    }

    @Test
    void eventMatchesLegacyProjection() {
        var row = eventRow();
        assertWireEquals(legacyWire(row), TransferViews.event(JSON, row));
        var withoutItem = new LinkedHashMap<>(row);
        withoutItem.put("item_id", null);
        assertWireEquals(legacyWire(withoutItem), TransferViews.event(JSON, withoutItem));
    }

    @Test
    void eligibleAssetMatchesLegacyProjection() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 5L);
        row.put("asset_code", "FA-1");
        row.put("name", " laptop ");
        row.put("version", 3L);
        row.put("lifecycle_status", "IN_STOCK");
        row.put("custody_type", "STORAGE");
        row.put("owning_organization_id", 1L);
        row.put("using_organization_id", null);
        row.put("responsible_employee_id", null);
        row.put("department_responsible_employee_id", null);
        row.put("storage_warehouse_id", 2L);
        row.put("current_location_id", 9L);
        assertWireEquals(legacyWire(row), TransferViews.eligibleAsset(row));
    }

    @Test
    void pendingReceiptMatchesLegacyProjection() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 5L);
        row.put("request_id", 2L);
        row.put("item_status", "DELIVERED");
        row.put("version", 1L);
        row.put("destination", "front desk");
        row.put("delivered_at", LocalDateTime.of(2026, 11, 1, 10, 0, 0));
        row.put("receipt_deadline_at", Timestamp.from(Instant.parse("2026-11-04T02:00:00Z")));
        row.put("asset_code", "FA-1");
        row.put("name", "laptop");
        row.put("request_version", 6L);
        assertWireEquals(legacyWire(row), TransferViews.pendingReceipt(row));
    }

    @Test
    void catalogChoicesMatchLegacyProjectionPerKind() {
        var personnel = new LinkedHashMap<String, Object>();
        personnel.put("id", 1L);
        personnel.put("name", "ann");
        personnel.put("version", 0L);
        personnel.put("status", "ACTIVE");
        personnel.put("organization_id", null);
        assertWireEquals(legacyWire(personnel), TransferViews.catalogChoice("personnel", personnel));

        var organization = new LinkedHashMap<String, Object>();
        organization.put("id", 1L);
        organization.put("name", "hq");
        organization.put("version", 2L);
        assertWireEquals(legacyWire(organization), TransferViews.catalogChoice("organization", organization));
        assertWireEquals(legacyWire(organization), TransferViews.catalogChoice("warehouse", organization));

        var location = new LinkedHashMap<String, Object>();
        location.put("id", 9L);
        location.put("name", "bin-a");
        location.put("version", 1L);
        location.put("warehouse_id", 2L);
        location.put("location_type", "BIN");
        assertWireEquals(legacyWire(location), TransferViews.catalogChoice("location", location));

        var employee = new LinkedHashMap<String, Object>();
        employee.put("id", 3L);
        employee.put("name", "bob");
        employee.put("version", 1L);
        employee.put("organization_id", 1L);
        employee.put("shared_eligible", 1L);
        assertWireEquals(legacyWire(employee), TransferViews.catalogChoice("employee", employee));
    }

    @Test
    void personnelHandoverRowsMatchLegacyProjection() {
        var employee = new LinkedHashMap<String, Object>();
        employee.put("id", 3L);
        employee.put("name", "bob");
        employee.put("status", "ACTIVE");
        employee.put("organization_id", 1L);
        assertWireEquals(legacyWire(employee), TransferViews.employeeRef(employee));

        var asset = new LinkedHashMap<String, Object>();
        asset.put("id", 5L);
        asset.put("asset_code", "FA-1");
        asset.put("name", "laptop");
        asset.put("lifecycle_status", "IN_USE");
        asset.put("exception_status", "NORMAL");
        asset.put("custody_type", "PERSONAL");
        asset.put("version", 3L);
        asset.put("current_location_id", 9L);
        asset.put("using_organization_id", 1L);
        asset.put("business_type", null);
        asset.put("business_id", null);
        assertWireEquals(legacyWire(asset), TransferViews.handoverAsset(asset));

        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("id", 5L);
        receipt.put("request_id", 2L);
        receipt.put("item_status", "DELIVERED");
        assertWireEquals(legacyWire(receipt), TransferViews.pendingReceiptRef(receipt));

        var processing = new LinkedHashMap<String, Object>();
        processing.put("id", 2L);
        processing.put("request_status", "PROCESSING");
        assertWireEquals(legacyWire(processing), TransferViews.processingTransferRef(processing));

        var loan = new LinkedHashMap<String, Object>();
        loan.put("id", 8L);
        loan.put("asset_id", 5L);
        loan.put("expected_return_date", java.sql.Date.valueOf("2026-12-01"));
        loan.put("relation_status", "OPEN");
        assertWireEquals(legacyWire(loan), TransferViews.openLoanRef(loan));
        var localDateLoan = new LinkedHashMap<>(loan);
        localDateLoan.put("expected_return_date", java.time.LocalDate.of(2026, 12, 1));
        assertWireEquals(legacyWire(localDateLoan), TransferViews.openLoanRef(localDateLoan));

        var returned = new LinkedHashMap<String, Object>();
        returned.put("id", 8L);
        returned.put("asset_id", 5L);
        returned.put("return_status", "PENDING_APPROVAL");
        assertWireEquals(legacyWire(returned), TransferViews.openReturnRef(returned));

        var requisition = new LinkedHashMap<String, Object>();
        requisition.put("id", 8L);
        requisition.put("request_no", "REQ-8");
        requisition.put("request_status", "WAITING_HANDOVER");
        assertWireEquals(legacyWire(requisition), TransferViews.openRequisitionRef(requisition));

        var operator = new LinkedHashMap<String, Object>();
        operator.put("id", 12L);
        operator.put("name", "ops");
        assertWireEquals(legacyWire(operator), TransferViews.operator(operator));
    }

    @Test
    void reassignmentOmitsUnavailableReasonExactlyWhenAbsent() {
        var locked = new LinkedHashMap<>(itemRow);
        locked.put("lock_version", 4L);
        var items = List.of(TransferViews.item(JSON, locked));
        var operators = List.of(new TransferViews.Operator("12", "ops"));
        var legacy = new LinkedHashMap<String, Object>();
        legacy.put("id", "2");
        legacy.put("version", 6L);
        legacy.put("ownershipVersion", 1L);
        legacy.put("processorAccountId", "12");
        legacy.put("items", List.of(legacyWire(locked)));
        legacy.put("operators", List.of(Map.of("id", "12", "name", "ops")));
        assertWireEquals(legacy, new TransferViews.ReassignmentSnapshot(
                "2", 6, 1, "12", items, operators, null));
        legacy.put("unavailableReason", "sick leave");
        assertWireEquals(legacy, new TransferViews.ReassignmentSnapshot(
                "2", 6, 1, "12", items, operators, "sick leave"));
    }

    @Test
    void targetWireMatchesLegacyTargetChangeTarget() {
        var target = new AssetTransferPort.Target(
                null, "STORAGE", null, null, 2L, 9L, Map.of("warehouse", 1L, "location", 2L));
        var legacy = new LinkedHashMap<String, Object>();
        legacy.put("owningOrganizationId", null);
        legacy.put("custodyType", "STORAGE");
        legacy.put("usingOrganizationId", null);
        legacy.put("employeeId", null);
        legacy.put("warehouseId", "2");
        legacy.put("locationId", "9");
        legacy.put("referenceVersions", target.referenceVersions());
        assertWireEquals(legacy, TransferViews.targetWire(target));
        assertWireEquals(legacy, TransferService.targetChangeTarget(target));
    }

    @Test
    void sourceViewMatchesLegacySnapshotProjection() {
        var row = snapshotRow();
        var snapshot = snapshot(row);
        assertWireEquals(legacyWire(row), TransferViews.sourceView(snapshot));
    }

    @Test
    void snapshotAndCustodyPersistLegacySnakeCaseShapes() {
        var row = snapshotRow();
        assertWireEquals(row, snapshot(row));
        var custody = new AssetTransferPort.Custody(null, "STORAGE", null, null, null, 2L, 9L);
        var legacyCustody = new LinkedHashMap<String, Object>();
        legacyCustody.put("owning_organization_id", null);
        legacyCustody.put("custody_type", "STORAGE");
        legacyCustody.put("using_organization_id", null);
        legacyCustody.put("responsible_employee_id", null);
        legacyCustody.put("department_responsible_employee_id", null);
        legacyCustody.put("storage_warehouse_id", 2L);
        legacyCustody.put("current_location_id", 9L);
        assertWireEquals(legacyCustody, custody);
        var legacyAfter = new LinkedHashMap<>(legacyCustody);
        legacyAfter.put("version", 4L);
        assertWireEquals(legacyAfter, AssetTransferPort.After.of(custody, 4));
    }

    @Test
    void physicalViewMatchesLegacyProjection() {
        var physical = new LinkedHashMap<String, Object>();
        for (String field : List.of("destination", "delivery_evidence", "delivered_at", "receipt_deadline_at")) {
            physical.put(field, itemRow.get(field));
        }
        assertWireEquals(legacyWire(physical), TransferViews.physical(itemRow));
    }

    @Test
    void idempotencyReplayRoundTripsStoredDetailAndItem() {
        var detail = TransferViews.detail(
                requestRow,
                List.of(TransferViews.item(JSON, itemRow)),
                List.of(TransferViews.event(JSON, eventRow())),
                1,
                1);
        var storedDetail = JSON.writeValueAsString(detail);
        assertEquals(
                JSON.readTree(storedDetail),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(storedDetail, TransferViews.Detail.class))));
        var item = TransferViews.item(JSON, itemRow);
        var storedItem = JSON.writeValueAsString(item);
        assertEquals(
                JSON.readTree(storedItem),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(storedItem, TransferViews.Item.class))));
        var receipt = new TransferViews.UnavailableReceipt("2", 6, true);
        var storedReceipt = JSON.writeValueAsString(receipt);
        assertEquals(
                JSON.readTree(storedReceipt),
                JSON.readTree(JSON.writeValueAsString(
                        JSON.readValue(storedReceipt, TransferViews.UnavailableReceipt.class))));
    }

    @Test
    void preRefactorStoredDetailStillDeserializes() {
        var legacy = new LinkedHashMap<>(legacyWire(requestRow));
        legacy.put("items", List.of(legacyWire(itemRow)));
        legacy.put("events", List.of(legacyWire(eventRow())));
        legacy.put("totalQuantity", 1);
        legacy.put("completedQuantity", 0);
        legacy.put("cancelledQuantity", 0);
        legacy.put("pendingQuantity", 1);
        legacy.put("sourceGroups", 1);
        legacy.put("targetGroups", 1);
        var stored = JSON.writeValueAsString(legacy);
        var replayed = JSON.readValue(stored, TransferViews.Detail.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
    }

    @Test
    void proxySnapshotComparisonSurvivesPortConversionAndHttpRoundTrip() {
        var snapshot = new TransferViews.ProxySnapshot(
                "TRANSFER", "2", "5", "TRANSFER-2", 6, 4, 1, "40", 3, "FA-1", "3",
                "STORAGE", "12", 1, 4,
                JSON.readTree("{\"id\":40,\"current_location_id\":9}"),
                JSON.readTree("{\"warehouseId\":\"2\",\"locationId\":\"9\"}"),
                JSON.readTree("{\"handoverAcceptanceDeadlineNaturalDays\":3}"));
        // The proxy port converts the record to a Map; the client then returns it through
        // HTTP JSON, where small numbers deserialize as Integer rather than Long.
        Map<String, Object> viaPort = JSON.convertValue(
                snapshot, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        Map<String, Object> viaHttp = JSON.readValue(
                JSON.writeValueAsString(viaPort),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(snapshot)),
                JSON.readTree(JSON.writeValueAsString(viaHttp)));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    private static Map<String, Object> requestRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 2L);
        row.put("creator_account_id", 11L);
        row.put("reason", "office move");
        row.put("request_status", "PROCESSING");
        row.put("version", 6L);
        row.put("decided_by", null);
        row.put("decision_reason", null);
        row.put("approved_version", null);
        row.put("processor_account_id", null);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-01T02:00:00Z")));
        row.put("updated_at", LocalDateTime.of(2026, 11, 1, 3, 0, 0));
        row.put("decided_at", null);
        row.put("ownership_version", 1L);
        return row;
    }

    private static Map<String, Object> itemRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 5L);
        row.put("request_id", 2L);
        row.put("asset_id", 40L);
        row.put("asset_version", 3L);
        row.put("source_snapshot_json",
                "{\"id\":40,\"current_location_id\":9,\"custody_type\":\"STORAGE\"}");
        row.put("target_snapshot_json",
                "{\"owningOrganizationId\":null,\"custodyType\":\"STORAGE\",\"usingOrganizationId\":null,"
                        + "\"employeeId\":null,\"warehouseId\":2,\"locationId\":9,"
                        + "\"referenceVersions\":{\"warehouse\":1,\"location\":2}}");
        row.put("parameter_snapshot_json", "{\"timezone\":\"Asia/Shanghai\"}");
        row.put("receiver_employee_id", null);
        row.put("receipt_required", Boolean.FALSE);
        row.put("item_status", "READY");
        row.put("version", 0L);
        row.put("destination", null);
        row.put("delivery_evidence", null);
        row.put("delivered_at", null);
        row.put("receipt_deadline_at", null);
        row.put("completed_at", null);
        row.put("completed_by", null);
        row.put("after_snapshot_json", null);
        return row;
    }

    private static Map<String, Object> eventRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 30L);
        row.put("item_id", 5L);
        row.put("event_type", "TRANSFER_SUBMIT");
        row.put("actor_account_id", 11L);
        row.put("occurred_at", Timestamp.from(Instant.parse("2026-11-01T02:05:00Z")));
        row.put("detail_json", "{\"count\":1}");
        return row;
    }

    private static Map<String, Object> snapshotRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 40L);
        row.put("version", 3L);
        row.put("asset_code", "FA-1");
        row.put("name", "laptop");
        row.put("owning_organization_id", 1L);
        row.put("custody_type", "STORAGE");
        row.put("using_organization_id", null);
        row.put("responsible_employee_id", null);
        row.put("department_responsible_employee_id", null);
        row.put("storage_warehouse_id", 2L);
        row.put("current_location_id", 9L);
        row.put("lifecycle_status", "IN_STOCK");
        row.put("exception_status", "NORMAL");
        row.put("card_stage", "ACTIVE");
        row.put("parameter_version_id", 7L);
        return row;
    }

    private static AssetTransferPort.Snapshot snapshot(Map<String, Object> row) {
        return new AssetTransferPort.Snapshot(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("version")).longValue(),
                (String) row.get("asset_code"),
                (String) row.get("name"),
                (Long) row.get("owning_organization_id"),
                (String) row.get("custody_type"),
                (Long) row.get("using_organization_id"),
                (Long) row.get("responsible_employee_id"),
                (Long) row.get("department_responsible_employee_id"),
                (Long) row.get("storage_warehouse_id"),
                (Long) row.get("current_location_id"),
                (String) row.get("lifecycle_status"),
                (String) row.get("exception_status"),
                (String) row.get("card_stage"),
                (Long) row.get("parameter_version_id"));
    }

    /** Frozen pre-JAVA-01 TransferService.wire projection; reference oracle only. */
    private static Map<String, Object> legacyWire(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if ("parameter_snapshot_json".equals(key)) {
                return;
            }
            String field = key.endsWith("_json") ? key.substring(0, key.length() - 5) : key;
            String[] words = field.split("_");
            StringBuilder name = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) {
                name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
            }
            Object output = value;
            if (value != null && key.endsWith("_json")) {
                output = "target_snapshot_json".equals(key)
                        ? legacyWireTarget(value.toString()) : JSON.readTree(value.toString());
            } else if (value instanceof Timestamp || value instanceof LocalDateTime) {
                output = legacyInstant(value).toString();
            } else if (value instanceof Number
                    && (key.equals("id") || key.endsWith("_id") || key.endsWith("_by"))) {
                output = value.toString();
            }
            result.put(name.toString(), output);
        });
        return result;
    }

    private static JsonNode legacyWireTarget(String value) {
        var node = JSON.readTree(value);
        if (node instanceof ObjectNode target) {
            for (String field : List.of("owningOrganizationId", "usingOrganizationId",
                    "employeeId", "warehouseId", "locationId")) {
                var identifier = target.get(field);
                if (identifier != null && identifier.isIntegralNumber()) {
                    target.put(field, identifier.asText());
                }
            }
        }
        return node;
    }

    private static Instant legacyInstant(Object value) {
        return value instanceof Timestamp time
                ? time.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
