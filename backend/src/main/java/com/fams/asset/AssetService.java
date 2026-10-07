package com.fams.asset;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fams.auth.AssetActorContext.Actor;
import com.fams.masterdata.AssetMasterDataPort;
import com.fams.product.contract.model.Asset;
import com.fams.product.contract.model.AssetCardStage;
import com.fams.product.contract.model.AssetCopyResult;
import com.fams.product.contract.model.AssetCreateRequest;
import com.fams.product.contract.model.AssetCreatorReference;
import com.fams.product.contract.model.AssetCustodySummaryOneOf;
import com.fams.product.contract.model.AssetCustodySummaryOneOf1;
import com.fams.product.contract.model.AssetCustodySummaryOneOf2;
import com.fams.product.contract.model.AssetException;
import com.fams.product.contract.model.AssetLifecycle;
import com.fams.product.contract.model.AssetQrAccessResult;
import com.fams.product.contract.model.AssetTag;
import com.fams.product.contract.model.AssetTagBatchPrintItem;
import com.fams.product.contract.model.AssetTagBatchPrintJob;
import com.fams.product.contract.model.AssetTagBatchPrintRequest;
import com.fams.product.contract.model.AssetTagPrintJob;
import com.fams.product.contract.model.AssetTagPrintJobResult;
import com.fams.product.contract.model.AssetTagPrintJobStatus;
import com.fams.product.contract.model.AssetTagReplacementResult;
import com.fams.product.contract.model.AssetTimelineEvent;
import com.fams.product.contract.model.AssetUpdateRequest;
import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.EmployeeReference;
import com.fams.product.contract.model.OrganizationReference;
import com.fams.product.contract.model.UsageMode;
import com.fams.product.contract.model.PageAsset;
import com.fams.product.contract.model.PageAssetTimelineEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class AssetService {
    private final AssetMapper mapper;
    private final AssetMasterDataPort masterData;
    private final AssetSecretCodec codec;
    private final ObjectMapper json;
    private final Clock clock;
    private final String enterpriseName;

    AssetService(AssetMapper mapper, AssetMasterDataPort masterData, AssetSecretCodec codec,
            ObjectMapper json, Clock authClock,
            @Value("${fams.asset.enterprise-name:FAMS}") String enterpriseName) {
        this.mapper = mapper;
        this.masterData = masterData;
        this.codec = codec;
        this.json = json;
        this.clock = authClock;
        this.enterpriseName = enterpriseName;
    }

    PageAsset list(Actor actor, int page, int pageSize, Map<String, String> query) {
        if (page < 1 || pageSize < 1 || pageSize > 200) {
            throw AssetOperationException.invalid("ASSET_FILTER_INVALID", "Invalid page range.");
        }
        int offset = Math.toIntExact((long) (page - 1) * pageSize);
        Map<String, Object> filters = filters(query);
        List<Map<String, Object>> rows = mapper.list(actor.id(), filters, pageSize, offset);
        Map<String, Map<Long, Map<String, Object>>> references = listReferences(rows);
        List<Asset> items = rows.stream().map(row -> toAsset(row, (id, kind) ->
                id == null ? null : references.getOrDefault(kind, Map.of()).get(id))).toList();
        return new PageAsset(page, pageSize,
                mapper.count(actor.id(), filters), items);
    }

    private Map<String, Object> filters(Map<String, String> query) {
        Map<String, Object> filters = new HashMap<>();
        for (var entry : query.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank() || "page".equals(key) || "pageSize".equals(key)) {
                continue; }
            switch (key) {
                case "assetCode", "name" -> filters.put(key, value.strip());
                case "serialNumber" -> filters.put(key, value.strip().toUpperCase(Locale.ROOT));
                case "assetCategoryId", "warehouseId", "locationId", "organizationId",
                        "usingOrganizationId", "responsibleEmployeeId",
                        "departmentResponsibleEmployeeId" -> filters.put(key, parseId(value));
                case "lifecycle" -> filters.put(key, enumName(AssetLifecycle.class, value));
                case "exception" -> filters.put(key, enumName(AssetException.class, value));
                case "usageMode" -> filters.put(key, enumName(UsageMode.class, value));
                case "inboundDateFrom", "inboundDateTo", "lastInventoryDateFrom",
                        "lastInventoryDateTo" -> {
                    try { filters.put(key, LocalDate.parse(value)); }
                    catch (java.time.format.DateTimeParseException exception) {
                        throw AssetOperationException.invalid("ASSET_FILTER_INVALID", "A date filter is invalid.");
                    }
                }
                case "currentProcessorId", "businessReference", "borrowOverdue",
                        "longUninventoried" -> throw AssetOperationException.invalid(
                                "ASSET_FILTER_UNAVAILABLE",
                                "This filter requires a later business workflow.");
                default -> throw AssetOperationException.invalid("ASSET_FILTER_INVALID",
                        "An unknown asset filter was supplied.");
            }
        }
        return filters;
    }

    private <T extends Enum<T>> String enumName(Class<T> type, String value) {
        try { return Enum.valueOf(type, value).name(); }
        catch (IllegalArgumentException exception) {
            throw AssetOperationException.invalid("ASSET_FILTER_INVALID", "A status filter is invalid.");
        }
    }

    Asset get(Actor actor, long id) {
        return toAsset(visible(actor, id, false));
    }

    @Transactional
    Asset update(Actor actor, long id, long expectedVersion,
            AssetUpdateRequest request, String key) {
        String operation = "updateAsset";
        String digest = digest(operation, id + ":" + expectedVersion + ":"
                + json.writeValueAsString(request));
        Asset replay = replay(actor.id(), operation, key, digest, Asset.class);
        if (replay != null) { return replay; }
        Map<String, Object> current = visible(actor, id, true);
        checkVersion(current, expectedVersion);
        String stage = text(current, "card_stage");
        if ("PENDING_INBOUND".equals(stage)) {
            throw AssetOperationException.conflict("ASSET_PENDING_INBOUND",
                    "A submitted card cannot be directly edited.");
        }
        if ("CANCELLED".equals(stage)) {
            throw AssetOperationException.conflict("ASSET_NOT_DRAFT", "A cancelled card is immutable.");
        }
        if ("DRAFT".equals(stage) && number(current, "creator_account_id") != actor.id()) {
            throw AssetOperationException.notFound();
        }
        if ("ACTIVE".equals(stage) && (request.getCategory() != null
                || request.getOrganization() != null
                || request.getPlannedInboundWarehouse() != null
                || request.getPlannedInboundLocation() != null)) {
            throw AssetOperationException.conflict("ASSET_FIELD_NOT_EDITABLE",
                    "Identity and custody changes require a formal business process.");
        }
        Map<String, Object> values = updateValues(current);
        if (request.getName() != null) {
            values.put("name", required(request.getName(), 256, "ASSET_NAME_REQUIRED")); }
        if (request.getCategory() != null) {
            putReference(values, "categoryId", request.getCategory(), "category"); }
        if (request.getOrganization() != null) {
            putReference(values, "organizationId", request.getOrganization(), "organization"); }
        if (request.getPlannedInboundWarehouse() != null) {
            putReference(values, "warehouseId", request.getPlannedInboundWarehouse(), "warehouse"); }
        if (request.getPlannedInboundLocation() != null) {
            putReference(values, "locationId", request.getPlannedInboundLocation(), "location"); }
        if (request.getBrand() != null) {
            putReference(values, "brandId", request.getBrand(), "brand"); }
        if (request.getManufacturer() != null) {
            putReference(values, "manufacturerId", request.getManufacturer(), "manufacturer"); }
        if (request.getModel() != null) { values.put("model", optional(request.getModel(), 128)); }
        if (request.getUnit() != null) { values.put("unit", optional(request.getUnit(), 32)); }
        if (request.getSerialNumber() != null) {
            String serial = optional(request.getSerialNumber(), 128);
            values.put("serial", serial);
            values.put("serialNormalized", serial == null ? null : serial.toUpperCase(Locale.ROOT));
        }
        if (request.getAcquisitionDate() != null) {
            values.put("acquisitionDate", request.getAcquisitionDate()); }
        if (request.getWarrantyExpiryDate() != null) {
            values.put("warrantyDate", request.getWarrantyExpiryDate()); }
        if (request.getServiceProvider() != null) {
            values.put("provider", optional(request.getServiceProvider(), 256)); }
        if (request.getServiceContact() != null) {
            values.put("contact", optional(request.getServiceContact(), 128)); }
        if (request.getServicePhone() != null) {
            values.put("phoneCiphertext", codec.encrypt(optional(request.getServicePhone(), 32))); }
        if (request.getSource() != null) { values.put("source", optional(request.getSource(), 128)); }
        if (request.getNotes() != null) { values.put("notes", optional(request.getNotes(), 2000)); }
        if (request.getExtensionValues() != null && !request.getExtensionValues().isEmpty()) {
            validateExtensions((Long) values.get("categoryId"), request.getExtensionValues());
            values.put("extensionJson", json.writeValueAsString(request.getExtensionValues()));
        }
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            throw AssetOperationException.invalid("ASSET_ATTACHMENT_UNAVAILABLE",
                    "Attachments require the D6-09 storage adapter.");
        }
        Long locationId = (Long) values.get("locationId");
        Long warehouseId = (Long) values.get("warehouseId");
        Map<String, Object> location = locationId == null ? null : mapper.location(locationId);
        if (locationId != null && (location == null || warehouseId == null
                || number(location, "warehouse_id") != warehouseId)) {
            throw AssetOperationException.conflict("ASSET_MASTER_DATA_DISABLED",
                    "The planned location is outside its warehouse.");
        }
        values.put("id", id);
        values.put("version", expectedVersion);
        values.put("actorId", actor.id());
        values.put("now", now());
        if (mapper.update(values) != 1) { throw versionConflict(); }
        event(actor, id, "ASSET_UPDATED", "Asset card corrected", expectedVersion, expectedVersion + 1);
        Asset result = get(actor, id);
        record(actor.id(), operation, key, digest, 200, result, id, result.getVersion());
        return result;
    }

    private Map<String, Object> updateValues(Map<String, Object> current) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", text(current, "name"));
        values.put("categoryId", numberOrNull(current, "category_id"));
        values.put("organizationId", numberOrNull(current, "owning_organization_id"));
        values.put("warehouseId", numberOrNull(current, "planned_inbound_warehouse_id"));
        values.put("locationId", numberOrNull(current, "planned_inbound_location_id"));
        values.put("brandId", numberOrNull(current, "brand_id"));
        values.put("manufacturerId", numberOrNull(current, "manufacturer_id"));
        values.put("model", text(current, "model"));
        values.put("unit", text(current, "unit"));
        values.put("serial", text(current, "serial_number"));
        values.put("serialNormalized", text(current, "serial_number_normalized"));
        values.put("acquisitionDate", date(current, "acquisition_date"));
        values.put("warrantyDate", date(current, "warranty_expiry_date"));
        values.put("provider", text(current, "service_provider"));
        values.put("contact", text(current, "service_contact"));
        values.put("phoneCiphertext", current.get("service_phone_ciphertext"));
        values.put("source", text(current, "source_text"));
        values.put("notes", text(current, "notes"));
        values.put("extensionJson", text(current, "extension_values_json"));
        return values;
    }

    @Transactional
    Asset create(Actor actor, AssetCreateRequest request, String key) {
        String operation = "createAsset";
        String digest = digest(operation, json.writeValueAsString(request));
        Asset replay = replay(actor.id(), operation, key, digest, Asset.class);
        if (replay != null) { return replay; }
        Map<String, Object> values = draftValues(actor, request, null);
        Long categoryId = (Long) values.get("categoryId");
        var allocated = masterData.reserveAssetCodes(categoryId, 1).getFirst();
        values.put("code", allocated.code());
        mapper.insert(values);
        long id = ((Number) values.get("id")).longValue();
        masterData.markUsed(allocated.reservationId());
        issueTag(id, (Instant) values.get("now"));
        event(actor, id, "ASSET_DRAFT_CREATED", "Draft created", null, 0L);
        Asset result = get(actor, id);
        record(actor.id(), operation, key, digest, 201, result, id, result.getVersion());
        return result;
    }

    @Transactional
    AssetCopyResult copy(Actor actor, long sourceId, long expectedVersion, int quantity,
            String reason, String key) {
        String operation = "copyAsset";
        String digest = digest(operation, sourceId + ":" + expectedVersion + ":" + quantity + ":" + reason);
        AssetCopyResult replay = replay(actor.id(), operation, key, digest, AssetCopyResult.class);
        if (replay != null) { return replay; }
        if (quantity < 1 || quantity > 100) {
            throw AssetOperationException.invalid("ASSET_COPY_QUANTITY_INVALID", "Quantity must be 1 through 100.");
        }
        Map<String, Object> source = visible(actor, sourceId, true);
        checkVersion(source, expectedVersion);
        if ("CANCELLED".equals(text(source, "card_stage"))) {
            throw AssetOperationException.conflict("ASSET_SOURCE_CANCELLED", "The source card was cancelled.");
        }
        validateReferences(source);
        Long categoryId = numberOrNull(source, "category_id");
        List<AssetMasterDataPort.AllocatedAssetCode> codes = masterData.reserveAssetCodes(categoryId, quantity);
        List<Asset> created = new ArrayList<>();
        for (var code : codes) {
            AssetCreateRequest template = new AssetCreateRequest(text(source, "name"));
            template.setCategory(reference(categoryId, refRow(categoryId, "category")));
            template.setBrand(reference(numberOrNull(source, "brand_id"),
                    refRow(numberOrNull(source, "brand_id"), "brand")));
            template.setManufacturer(reference(numberOrNull(source, "manufacturer_id"),
                    refRow(numberOrNull(source, "manufacturer_id"), "manufacturer")));
            template.setModel(text(source, "model"));
            template.setUnit(text(source, "unit"));
            template.setExtensionValues(extensionValues(source));
            Map<String, Object> values = draftValues(actor, template, sourceId);
            values.put("code", code.code());
            mapper.insert(values);
            long id = ((Number) values.get("id")).longValue();
            masterData.markUsed(code.reservationId());
            issueTag(id, (Instant) values.get("now"));
            event(actor, id, "ASSET_DRAFT_COPIED", "Draft copied from " + sourceId, null, 0L);
            created.add(get(actor, id));
        }
        AssetCopyResult result = new AssetCopyResult(created);
        record(actor.id(), operation, key, digest, 201, result, created.size() == 1
                ? Long.parseLong(created.getFirst().getId()) : null, null);
        return result;
    }

    @Transactional
    Asset cancel(Actor actor, long id, long expectedVersion, String reason, String key) {
        String operation = "cancelAsset";
        String digest = digest(operation, id + ":" + expectedVersion + ":" + reason);
        Asset replay = replay(actor.id(), operation, key, digest, Asset.class);
        if (replay != null) { return replay; }
        Map<String, Object> row = visible(actor, id, true);
        checkVersion(row, expectedVersion);
        if (!"DRAFT".equals(text(row, "card_stage"))) {
            throw AssetOperationException.conflict("ASSET_NOT_DRAFT", "Only a draft can be cancelled.");
        }
        if (number(row, "creator_account_id") != actor.id()) {
            throw AssetOperationException.conflict("ASSET_NOT_CREATOR", "Only the creator can cancel the draft.");
        }
        if (mapper.activeLock(id) != 0) {
            throw AssetOperationException.conflict("ASSET_DRAFT_LOCKED", "The asset has an active business lock.");
        }
        required(reason, 500, "ASSET_CANCEL_REASON_REQUIRED");
        if (mapper.cancel(id, expectedVersion, reason.strip(), actor.id(), now()) != 1) {
            throw versionConflict();
        }
        event(actor, id, "ASSET_DRAFT_CANCELLED", "Draft cancelled", expectedVersion, expectedVersion + 1);
        Asset result = get(actor, id);
        record(actor.id(), operation, key, digest, 200, result, id, result.getVersion());
        return result;
    }

    PageAssetTimelineEvent timeline(Actor actor, long id, int page, int pageSize) {
        visible(actor, id, false);
        List<AssetTimelineEvent> events = mapper.timeline(id, pageSize, (page - 1) * pageSize)
                .stream().map(row -> new AssetTimelineEvent(
                        Long.toString(number(row, "id")), text(row, "event_type"),
                        instant(row, "occurred_at").atOffset(ZoneOffset.UTC),
                        json.readTree(text(row, "summary_json")).path("summary").asText(),
                        text(row, "business_type"), Long.toString(number(row, "business_id"))))
                .toList();
        return new PageAssetTimelineEvent(page, pageSize, mapper.timelineCount(id), events);
    }

    AssetTag tag(Actor actor, long id) {
        Map<String, Object> row = visible(actor, id, false);
        Map<String, Object> tag = mapper.tag(id);
        if (tag == null) { throw AssetOperationException.notFound(); }
        String token = codec.decrypt((byte[]) tag.get("token_ciphertext"));
        return new AssetTag(enterpriseName, text(row, "name"), text(row, "asset_code"), token, token);
    }

    AssetQrAccessResult resolve(Actor actor, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw AssetOperationException.notFound();
        }
        Long id = mapper.assetIdByTokenHash(codec.hash(token));
        if (id == null) { throw AssetOperationException.notFound(); }
        Asset asset = get(actor, id);
        return new AssetQrAccessResult(asset.getAssetCode(), asset.getName(),
                asset.getLifecycle(), asset.getException(), asset.getCardStage());
    }

    @Transactional
    AssetTagReplacementResult replaceTag(Actor actor, long id, long expectedVersion,
            String reason, String key) {
        String operation = "replaceAssetTag";
        String digest = digest(operation, id + ":" + expectedVersion + ":" + reason);
        AssetTagReplacementResult replay = replay(actor.id(), operation, key, digest,
                AssetTagReplacementResult.class);
        if (replay != null) { return replay; }
        Map<String, Object> asset = visible(actor, id, true);
        checkVersion(asset, expectedVersion);
        if (!"ACTIVE".equals(text(asset, "card_stage"))) {
            throw AssetOperationException.conflict("ASSET_TAG_NOT_PRINTABLE", "Only published assets have printable tags.");
        }
        required(reason, 500, "ASSET_TAG_REASON_REQUIRED");
        Map<String, Object> old = mapper.tag(id);
        if (old == null) { throw AssetOperationException.notFound(); }
        Instant timestamp = now();
        String token = codec.token();
        mapper.archiveTag(id, text(old, "token_hash"), instant(old, "issued_at"), timestamp,
                reason.strip());
        if (mapper.rotateTag(id, number(old, "tag_version"), codec.encrypt(token),
                codec.hash(token), timestamp) != 1
                || mapper.touch(id, expectedVersion, actor.id(), timestamp) != 1) {
            throw versionConflict();
        }
        event(actor, id, "ASSET_TAG_REPLACED", "Tag replaced", expectedVersion, expectedVersion + 1);
        AssetTagReplacementResult result = new AssetTagReplacementResult(tag(actor, id),
                expectedVersion + 1);
        record(actor.id(), operation, key, digest, 200, result, id, expectedVersion + 1);
        return result;
    }

    @Transactional
    AssetTagPrintJob reprint(Actor actor, long id, long expectedVersion,
            String reason, String key) {
        String operation = "reprintAssetTag";
        String digest = digest(operation, id + ":" + expectedVersion + ":" + reason);
        AssetTagPrintJob replay = replay(actor.id(), operation, key, digest, AssetTagPrintJob.class);
        if (replay != null) { return replay; }
        Map<String, Object> asset = printable(actor, id, expectedVersion);
        long jobId = createPrintJob(actor.id(), List.of(asset), "SINGLE");
        AssetTagPrintJob result = new AssetTagPrintJob(Long.toString(jobId),
                AssetTagPrintJobStatus.COMPLETED, text(asset, "asset_code"));
        result.setTag(tag(actor, id));
        event(actor, id, "ASSET_TAG_REPRINT_REQUESTED", "Tag reprinted", expectedVersion, expectedVersion);
        record(actor.id(), operation, key, digest, 202, result, id, expectedVersion);
        return result;
    }

    @Transactional
    AssetTagBatchPrintJob batchPrint(Actor actor, AssetTagBatchPrintRequest request, String key) {
        String operation = "batchPrintAssetTags";
        String digest = digest(operation, json.writeValueAsString(request));
        AssetTagBatchPrintJob replay = replay(actor.id(), operation, key, digest,
                AssetTagBatchPrintJob.class);
        if (replay != null) { return replay; }
        if (request.getAssets() == null || request.getAssets().isEmpty()
                || request.getAssets().size() > 100) {
            throw AssetOperationException.invalid("ASSET_TAG_BATCH_INVALID", "Select 1 through 100 assets.");
        }
        List<Map<String, Object>> assets = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (var selection : request.getAssets()) {
            long id = parseId(selection.getAssetId());
            if (!seen.add(id)) {
                throw AssetOperationException.invalid("ASSET_TAG_BATCH_INVALID", "An asset was selected twice.");
            }
            assets.add(printable(actor, id, selection.getVersion()));
        }
        long jobId = createPrintJob(actor.id(), assets, "BATCH");
        List<AssetTagBatchPrintItem> items = assets.stream().map(asset -> {
            long id = number(asset, "id");
            AssetTagBatchPrintItem item = new AssetTagBatchPrintItem(Long.toString(id),
                    text(asset, "asset_code"));
            item.setTag(tag(actor, id));
            long version = number(asset, "version");
            event(actor, id, "ASSET_TAG_BATCH_PRINT_REQUESTED", "Tag printed in batch", version, version);
            return item;
        }).toList();
        AssetTagBatchPrintJob result = new AssetTagBatchPrintJob(Long.toString(jobId),
                AssetTagPrintJobStatus.COMPLETED, items);
        record(actor.id(), operation, key, digest, 202, result, null, null);
        return result;
    }

    AssetTagPrintJobResult printJob(Actor actor, long id) {
        Map<String, Object> job = mapper.printJob(id, actor.id());
        if (job == null) { throw AssetOperationException.notFound(); }
        List<Map<String, Object>> rows = mapper.printItems(id);
        if ("SINGLE".equals(text(job, "job_type"))) {
            Map<String, Object> item = rows.getFirst();
            visible(actor, number(item, "asset_id"), false);
            AssetTagPrintJob result = new AssetTagPrintJob(Long.toString(id),
                    AssetTagPrintJobStatus.valueOf(text(job, "job_status")),
                    text(item, "asset_code_snapshot"));
            result.setTag(printTag(item));
            return result;
        }
        List<AssetTagBatchPrintItem> items = rows.stream().map(row -> {
            long assetId = number(row, "asset_id");
            visible(actor, assetId, false);
            AssetTagBatchPrintItem item = new AssetTagBatchPrintItem(Long.toString(assetId),
                    text(row, "asset_code_snapshot"));
            item.setTag(printTag(row));
            return item;
        }).toList();
        return new AssetTagBatchPrintJob(Long.toString(id),
                AssetTagPrintJobStatus.valueOf(text(job, "job_status")), items);
    }

    private AssetTag printTag(Map<String, Object> item) {
        String token = codec.decrypt((byte[]) item.get("token_ciphertext_snapshot"));
        return new AssetTag(enterpriseName, text(item, "asset_name_snapshot"),
                text(item, "asset_code_snapshot"), token, token);
    }

    private Map<String, Object> printable(Actor actor, long id, long expectedVersion) {
        Map<String, Object> asset = visible(actor, id, true);
        if (number(asset, "version") != expectedVersion) {
            throw AssetOperationException.conflict("ASSET_TAG_VERSION_CONFLICT", "The asset version changed.");
        }
        if (!"ACTIVE".equals(text(asset, "card_stage"))) {
            throw AssetOperationException.conflict("ASSET_TAG_NOT_PRINTABLE", "The asset is not published.");
        }
        return asset;
    }

    private long createPrintJob(long actorId, List<Map<String, Object>> assets, String jobType) {
        Map<String, Object> values = new HashMap<>();
        values.put("actorId", actorId);
        values.put("jobType", jobType);
        values.put("now", now());
        mapper.insertPrintJob(values);
        long id = ((Number) values.get("id")).longValue();
        for (Map<String, Object> asset : assets) {
            Map<String, Object> tag = mapper.tag(number(asset, "id"));
            if (tag == null) { throw AssetOperationException.notFound(); }
            mapper.insertPrintItem(id, number(asset, "id"), number(asset, "version"),
                    number(tag, "tag_version"), text(asset, "asset_code"),
                    text(asset, "name"), (byte[]) tag.get("token_ciphertext"));
        }
        return id;
    }

    private Map<String, Object> draftValues(Actor actor, AssetCreateRequest request, Long sourceId) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", required(request.getName(), 256, "ASSET_NAME_REQUIRED"));
        putReference(values, "categoryId", request.getCategory(), "category");
        putReference(values, "organizationId", request.getOrganization(), "organization");
        putReference(values, "warehouseId", request.getPlannedInboundWarehouse(), "warehouse");
        putReference(values, "locationId", request.getPlannedInboundLocation(), "location");
        putReference(values, "brandId", request.getBrand(), "brand");
        putReference(values, "manufacturerId", request.getManufacturer(), "manufacturer");
        Long warehouse = (Long) values.get("warehouseId");
        Long location = (Long) values.get("locationId");
        Map<String, Object> locationRow = location == null ? null : mapper.location(location);
        if (location != null && (locationRow == null || warehouse == null
                || number(locationRow, "warehouse_id") != warehouse)) {
            throw AssetOperationException.conflict("ASSET_MASTER_DATA_DISABLED", "The planned location is outside its warehouse.");
        }
        values.put("model", optional(request.getModel(), 128));
        values.put("unit", optional(request.getUnit(), 32));
        values.put("serial", optional(request.getSerialNumber(), 128));
        values.put("serialNormalized", values.get("serial") == null ? null
                : ((String) values.get("serial")).toUpperCase(Locale.ROOT));
        values.put("acquisitionDate", request.getAcquisitionDate());
        values.put("warrantyDate", request.getWarrantyExpiryDate());
        values.put("provider", optional(request.getServiceProvider(), 256));
        values.put("contact", optional(request.getServiceContact(), 128));
        values.put("phoneCiphertext", codec.encrypt(optional(request.getServicePhone(), 32)));
        values.put("source", optional(request.getSource(), 128));
        values.put("notes", optional(request.getNotes(), 2000));
        Map<String, Object> extensions = request.getExtensionValues();
        validateExtensions((Long) values.get("categoryId"), extensions);
        values.put("extensionJson", extensions == null || extensions.isEmpty()
                ? null : json.writeValueAsString(extensions));
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            throw AssetOperationException.invalid("ASSET_ATTACHMENT_UNAVAILABLE",
                    "Attachments require the D6-09 storage adapter.");
        }
        Instant timestamp = now();
        values.put("now", timestamp);
        values.put("businessDate", LocalDate.ofInstant(timestamp, masterData.currentZone()));
        values.put("parameterId", masterData.currentParameterVersionId());
        values.put("actorId", actor.id());
        values.put("username", actor.username());
        values.put("sourceAssetId", sourceId);
        return values;
    }

    private void validateExtensions(Long categoryId, Map<String, Object> extensions) {
        if (extensions == null || extensions.isEmpty()) { return; }
        if (categoryId == null) {
            throw AssetOperationException.invalid("ASSET_EXTENSION_INVALID", "A category is required for extension values.");
        }
        String templateJson = mapper.categoryTemplate(categoryId);
        JsonNode template = json.readTree(templateJson == null ? "[]" : templateJson);
        if (!template.isArray() || extensions.size() > template.size()) {
            throw AssetOperationException.invalid("ASSET_EXTENSION_INVALID", "Extension fields do not match the category.");
        }
        for (var field : extensions.entrySet()) {
            JsonNode definition = null;
            for (JsonNode candidate : template) {
                if (field.getKey().equals(candidate.path("key").asText())) {
                    definition = candidate;
                    break;
                }
            }
            if (definition == null || field.getValue() == null) {
                throw AssetOperationException.invalid("ASSET_EXTENSION_INVALID", "An extension field is unknown.");
            }
            String type = definition.path("type").asText();
            Object value = field.getValue();
            boolean valid = switch (type) {
                case "TEXT" -> value instanceof String;
                case "NUMBER" -> value instanceof Number;
                case "BOOLEAN" -> value instanceof Boolean;
                case "DATE" -> value instanceof String text && text.matches("\\d{4}-\\d{2}-\\d{2}");
                default -> false;
            };
            if (!valid) {
                throw AssetOperationException.invalid("ASSET_EXTENSION_INVALID", "An extension field has the wrong type.");
            }
        }
    }

    private void validateReferences(Map<String, Object> source) {
        for (String kind : List.of("category", "brand", "manufacturer")) {
            Long id = numberOrNull(source, kind.equals("category") ? "category_id"
                    : kind.equals("brand") ? "brand_id" : "manufacturer_id");
            if (id != null) { assertReference(refRow(id, kind), null); }
        }
    }

    private void putReference(Map<String, Object> target, String field,
            CatalogReference reference, String kind) {
        if (reference == null) {
            target.put(field, null);
            return;
        }
        long id = parseId(reference.getId());
        assertReference(refRow(id, kind), reference.getVersion());
        target.put(field, id);
    }

    private void putReference(Map<String, Object> target, String field,
            OrganizationReference reference, String kind) {
        if (reference == null) {
            target.put(field, null);
            return;
        }
        long id = parseId(reference.getId());
        assertReference(refRow(id, kind), reference.getVersion());
        target.put(field, id);
    }

    private Map<String, Object> refRow(Long id, String kind) {
        if (id == null) { return null; }
        return switch (kind) {
            case "category" -> mapper.category(id);
            case "organization" -> mapper.organization(id);
            case "warehouse" -> mapper.warehouse(id);
            case "location" -> mapper.location(id);
            case "brand" -> mapper.brand(id);
            case "manufacturer" -> mapper.manufacturer(id);
            default -> throw new IllegalArgumentException("Unknown reference kind");
        };
    }

    private Map<String, Object> refViewRow(Long id, String kind) {
        if (id == null) { return null; }
        return switch (kind) {
            case "category" -> mapper.categoryView(id);
            case "organization" -> mapper.organizationView(id);
            case "warehouse" -> mapper.warehouseView(id);
            case "location" -> mapper.locationView(id);
            case "brand" -> mapper.brandView(id);
            case "manufacturer" -> mapper.manufacturerView(id);
            case "employee" -> mapper.employee(id);
            default -> throw new IllegalArgumentException("Unknown reference kind");
        };
    }

    private Map<String, Map<Long, Map<String, Object>>> listReferences(List<Map<String, Object>> rows) {
        Map<String, Set<Long>> ids = new HashMap<>();
        for (var row : rows) {
            collectReference(ids, "category", row, "category_id");
            collectReference(ids, "organization", row, "owning_organization_id",
                    "using_organization_id");
            collectReference(ids, "warehouse", row, "planned_inbound_warehouse_id",
                    "storage_warehouse_id");
            collectReference(ids, "location", row, "planned_inbound_location_id",
                    "current_location_id");
            collectReference(ids, "brand", row, "brand_id");
            collectReference(ids, "manufacturer", row, "manufacturer_id");
            collectReference(ids, "employee", row, "responsible_employee_id",
                    "department_responsible_employee_id");
        }
        Map<String, Map<Long, Map<String, Object>>> result = new HashMap<>();
        for (var entry : ids.entrySet()) {
            Map<Long, Map<String, Object>> byId = new HashMap<>();
            for (var value : mapper.referenceViews(entry.getKey(), List.copyOf(entry.getValue()))) {
                byId.put(number(value, "id"), value);
            }
            result.put(entry.getKey(), byId);
        }
        return result;
    }

    private void collectReference(Map<String, Set<Long>> ids, String kind,
            Map<String, Object> row, String... columns) {
        for (String column : columns) {
            Long id = numberOrNull(row, column);
            if (id != null) { ids.computeIfAbsent(kind, ignored -> new HashSet<>()).add(id); }
        }
    }

    private void assertReference(Map<String, Object> row, Long expectedVersion) {
        if (row == null || !"ACTIVE".equals(text(row, "status"))) {
            throw AssetOperationException.conflict("ASSET_MASTER_DATA_DISABLED", "A referenced record is unavailable.");
        }
        if (expectedVersion != null && number(row, "version") != expectedVersion) {
            throw AssetOperationException.conflict("ASSET_REFERENCE_VERSION_CONFLICT", "A reference version changed.");
        }
    }

    private Map<String, Object> visible(Actor actor, long id, boolean lock) {
        Map<String, Object> row = lock ? mapper.lock(id) : mapper.find(id);
        if (row == null || (("DRAFT".equals(text(row, "card_stage"))
                || "CANCELLED".equals(text(row, "card_stage")))
                && number(row, "creator_account_id") != actor.id())) {
            throw AssetOperationException.notFound();
        }
        return row;
    }

    private Asset toAsset(Map<String, Object> row) {
        return toAsset(row, this::refViewRow);
    }

    @FunctionalInterface
    private interface ReferenceLookup {
        Map<String, Object> get(Long id, String kind);
    }

    private Asset toAsset(Map<String, Object> row, ReferenceLookup references) {
        Asset result = new Asset(Long.toString(number(row, "id")), text(row, "asset_code"),
                text(row, "name"), AssetLifecycle.valueOf(text(row, "lifecycle_status")),
                AssetException.valueOf(text(row, "exception_status")),
                AssetCardStage.valueOf(text(row, "card_stage")), number(row, "version"),
                date(row, "created_business_date"), instant(row, "created_at").atOffset(ZoneOffset.UTC),
                Long.toString(number(row, "parameter_version_id")));
        Long category = numberOrNull(row, "category_id");
        Long org = numberOrNull(row, "owning_organization_id");
        result.setCategory(reference(category, references.get(category, "category")));
        if (org != null) { result.setOrganization(new OrganizationReference(org.toString(),
                number(references.get(org, "organization"), "version"))); }
        result.setPlannedInboundWarehouse(reference(numberOrNull(row, "planned_inbound_warehouse_id"),
                references.get(numberOrNull(row, "planned_inbound_warehouse_id"), "warehouse")));
        result.setPlannedInboundLocation(reference(numberOrNull(row, "planned_inbound_location_id"),
                references.get(numberOrNull(row, "planned_inbound_location_id"), "location")));
        result.setBrand(reference(numberOrNull(row, "brand_id"),
                references.get(numberOrNull(row, "brand_id"), "brand")));
        result.setManufacturer(reference(numberOrNull(row, "manufacturer_id"),
                references.get(numberOrNull(row, "manufacturer_id"), "manufacturer")));
        result.setModel(text(row, "model"));
        result.setUnit(text(row, "unit"));
        result.setSerialNumber(text(row, "serial_number"));
        result.setAcquisitionDate(date(row, "acquisition_date"));
        result.setWarrantyExpiryDate(date(row, "warranty_expiry_date"));
        result.setServiceProvider(text(row, "service_provider"));
        result.setServiceContact(text(row, "service_contact"));
        result.setSource(text(row, "source_text"));
        result.setNotes(text(row, "notes"));
        result.setExtensionValues(extensionValues(row));
        result.setCreator(new AssetCreatorReference(Long.toString(number(row, "creator_account_id")),
                text(row, "creator_username_snapshot")));
        String custody = text(row, "custody_type");
        Long currentLocation = numberOrNull(row, "current_location_id");
        if ("STORAGE".equals(custody)) {
            Long storage = numberOrNull(row, "storage_warehouse_id");
            result.setCurrentCustody(new AssetCustodySummaryOneOf(
                    AssetCustodySummaryOneOf.CustodyTypeEnum.STORAGE,
                    reference(storage, references.get(storage, "warehouse")),
                    reference(currentLocation, references.get(currentLocation, "location"))));
        } else if ("PERSONAL".equals(custody)) {
            Long using = numberOrNull(row, "using_organization_id");
            Long employee = numberOrNull(row, "responsible_employee_id");
            result.setUsageMode(UsageMode.PERSONAL);
            result.setCurrentCustody(new AssetCustodySummaryOneOf1(
                    AssetCustodySummaryOneOf1.CustodyTypeEnum.PERSONAL,
                    organizationReference(using, references), employeeReference(employee, references),
                    reference(currentLocation, references.get(currentLocation, "location"))));
        } else if ("SHARED".equals(custody)) {
            Long using = numberOrNull(row, "using_organization_id");
            Long employee = numberOrNull(row, "department_responsible_employee_id");
            result.setUsageMode(UsageMode.SHARED);
            result.setCurrentCustody(new AssetCustodySummaryOneOf2(
                    AssetCustodySummaryOneOf2.CustodyTypeEnum.SHARED,
                    organizationReference(using, references), employeeReference(employee, references),
                    reference(currentLocation, references.get(currentLocation, "location"))));
        }
        if (row.get("physical_destination_json") != null) {
            result.setPhysicalDestination(json.readValue(row.get("physical_destination_json").toString(),
                    com.fams.product.contract.model.AssetPhysicalDestination.class));
        }
        result.setInboundBusinessDate(date(row, "inbound_business_date"));
        result.setLastInventoryBusinessDate(date(row, "last_inventory_business_date"));
        return result;
    }

    private OrganizationReference organizationReference(Long id, ReferenceLookup references) {
        return id == null ? null : new OrganizationReference(id.toString(),
                number(references.get(id, "organization"), "version"));
    }

    private EmployeeReference employeeReference(Long id, ReferenceLookup references) {
        return id == null ? null : new EmployeeReference(id.toString(),
                number(references.get(id, "employee"), "version"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extensionValues(Map<String, Object> row) {
        String content = text(row, "extension_values_json");
        if (content == null) { return Map.of(); }
        return json.readValue(content, Map.class);
    }

    private CatalogReference reference(Long id, Map<String, Object> row) {
        return id == null || row == null ? null
                : new CatalogReference(id.toString(), number(row, "version"));
    }

    private void issueTag(long id, Instant timestamp) {
        String token = codec.token();
        mapper.insertTag(id, codec.encrypt(token), codec.hash(token), timestamp);
    }

    private void event(Actor actor, long assetId, String event, String summary,
            Long beforeVersion, Long afterVersion) {
        Instant timestamp = now();
        mapper.event(assetId, event, timestamp, json.writeValueAsString(Map.of("summary", summary)), actor.id());
        Map<String, Object> detail = new HashMap<>();
        detail.put("assetId", assetId);
        detail.put("role", actor.role());
        detail.put("beforeVersion", beforeVersion);
        detail.put("afterVersion", afterVersion);
        detail.put("summary", summary);
        mapper.audit(actor.id(), event, actor.requestId(), timestamp,
                json.writeValueAsString(detail));
    }

    private <T> T replay(long actorId, String operation, String key, String digest, Class<T> type) {
        Map<String, Object> prior = mapper.replay(actorId, operation, key, now());
        if (prior == null) { return null; }
        if (!digest.equals(text(prior, "payload_sha256"))) {
            throw AssetOperationException.conflict("ASSET_IDEMPOTENCY_CONFLICT", "The key has another payload.");
        }
        return json.readValue(text(prior, "response_body_json"), type);
    }

    private void record(long actorId, String operation, String key, String digest,
            int status, Object response, Long id, Long version) {
        Instant timestamp = now();
        mapper.record(actorId, operation, key, digest, status, json.writeValueAsString(response),
                id, version, timestamp, timestamp.plus(24, ChronoUnit.HOURS));
    }

    private String digest(String operation, String payload) { return codec.hash(operation + ":" + payload); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private long parseId(String id) {
        try {
            long value = Long.parseLong(id);
            if (value > 0) { return value; }
        } catch (NumberFormatException ignored) { }
        throw AssetOperationException.notFound();
    }
    private String required(String value, int max, String code) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw AssetOperationException.invalid(code, "A required value is invalid."); }
        return value.strip();
    }
    private String optional(String value, int max) {
        if (value == null || value.isBlank()) { return null; }
        if (value.strip().length() > max) {
            throw AssetOperationException.invalid("ASSET_FIELD_INVALID", "A field is too long."); }
        return value.strip();
    }
    private void checkVersion(Map<String, Object> row, long version) {
        if (number(row, "version") != version) { throw versionConflict(); }
    }
    private AssetOperationException versionConflict() {
        return AssetOperationException.conflict("ASSET_VERSION_CONFLICT", "The asset version changed.");
    }
    private String text(Map<String, Object> row, String key) {
        if (row == null || row.get(key) == null) { return null; }
        return row.get(key).toString();
    }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Long numberOrNull(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : number(row, key);
    }
    private LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) { return null; }
        if (value instanceof LocalDate date) { return date; }
        if (value instanceof Date date) { return date.toLocalDate(); }
        return LocalDate.parse(value.toString());
    }
    private Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof Timestamp timestamp) { return timestamp.toInstant(); }
        if (value instanceof java.time.LocalDateTime dateTime) { return dateTime.toInstant(ZoneOffset.UTC); }
        return Instant.parse(value.toString());
    }
}
