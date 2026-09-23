package com.fams.masterdata;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.Location;
import com.fams.product.contract.model.LocationCreateRequest;
import com.fams.product.contract.model.LocationType;
import com.fams.product.contract.model.LocationUpdateRequest;
import com.fams.product.contract.model.PageLocation;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class LocationService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final LocationMapper mapper;
    private final WarehouseMapper warehouses;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LocationService(LocationMapper mapper, WarehouseMapper warehouses,
            IdentityMasterDataMapper records, MasterDataFingerprint fingerprint,
            ObjectMapper objectMapper, Clock authClock) {
        this.mapper = mapper;
        this.warehouses = warehouses;
        this.records = records;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageLocation list(int page, int pageSize) {
        List<Location> items = mapper.list((page - 1) * pageSize, pageSize).stream()
                .map(this::toLocation).toList();
        return new PageLocation(page, pageSize, mapper.count(), items);
    }

    Map<String, Object> listActive(int page, int pageSize) {
        List<Map<String, Object>> items = mapper.listActive((page - 1) * pageSize, pageSize)
                .stream().map(this::toActiveReference).toList();
        return Map.of("page", page, "pageSize", pageSize, "total", mapper.countActive(), "items", items);
    }

    Location get(long id) {
        return toLocation(require(id));
    }

    @Transactional
    Location create(long actorId, LocationCreateRequest request, String key, UUID requestId) {
        String operation = "createLocation";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<Location> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        String code = required(request.getCode(), 64, "Location code");
        String name = required(request.getName(), 128, "Location name");
        LocationType type = requireType(request.getType());
        assertCodeAvailable(code, null);
        Long warehouseId = validateWarehouse(request.getWarehouse());
        Long parentId = validateParent(request.getParentLocation(), null, warehouseId);
        Instant now = clock.instant();
        NewLocationRow row = new NewLocationRow(code, normalizeCode(code), name,
                type.name(), parentId, warehouseId, optional(request.getDescription()), actorId, now);
        requireOne(mapper.insert(row));
        Location result = get(row.getId());
        audit(actorId, requestId, "LOCATION_CREATED", row.getId(), now);
        record(actorId, operation, key, digest, 201, result);
        return result;
    }

    @Transactional
    Location update(long actorId, long id, long expectedVersion,
            LocationUpdateRequest request, String key, UUID requestId) {
        String operation = "updateLocation";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<Location> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        LocationRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String code = request.getCode() == null ? current.code()
                : required(request.getCode(), 64, "Location code");
        String name = request.getName() == null ? current.name()
                : required(request.getName(), 128, "Location name");
        LocationType type = request.getType() == null ? LocationType.valueOf(current.locationType())
                : requireType(request.getType());
        String description = request.getDescription() == null ? current.description()
                : optional(request.getDescription());
        Long warehouseId = request.getWarehouse() == null ? current.warehouseId()
                : validateWarehouse(request.getWarehouse());
        if (!java.util.Objects.equals(current.warehouseId(), warehouseId)
                && mapper.countChildren(id) != 0) {
            throw new MasterDataOperationException("LOCATION_WAREHOUSE_MISMATCH",
                    "A parent location with children cannot move to another warehouse.", 409);
        }
        Long parentId = request.getParentLocation() == null ? current.parentId()
                : validateParent(request.getParentLocation(), id, warehouseId);
        if (request.getParentLocation() == null && parentId != null) {
            assertParentWarehouse(parentId, warehouseId);
        }
        assertCodeAvailable(code, id);
        Instant now = clock.instant();
        requireOne(mapper.update(id, expectedVersion, code, normalizeCode(code), name,
                type.name(), parentId, warehouseId, description, actorId, now));
        Location result = get(id);
        audit(actorId, requestId, "LOCATION_UPDATED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    @Transactional
    Location changeStatus(long actorId, long id, long expectedVersion, boolean enabled,
            String reason, String key, UUID requestId) {
        String operation = enabled ? "enableLocation" : "disableLocation";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<Location> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        required(reason, 500, "Reason");
        LocationRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw new MasterDataOperationException(
                    enabled ? "LOCATION_ALREADY_ENABLED" : "LOCATION_ALREADY_DISABLED",
                    "The location already has the requested status.", 409);
        }
        if (enabled) {
            assertActiveParentChain(current.parentId(), id);
            assertWarehouseActive(current.warehouseId());
        } else if (mapper.countActiveChildren(id) != 0) {
            throw new MasterDataOperationException("LOCATION_HAS_ENABLED_CHILDREN",
                    "Enabled child locations must be disabled or moved first.", 409);
        }
        Instant now = clock.instant();
        requireOne(mapper.changeStatus(id, expectedVersion, status, actorId, now));
        Location result = get(id);
        audit(actorId, requestId, enabled ? "LOCATION_ENABLED" : "LOCATION_DISABLED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    private Long validateWarehouse(CatalogReference reference) {
        if (reference == null) {
            return null;
        }
        long id = referenceId(reference, "warehouse");
        WarehouseRow warehouse = warehouses.findForUpdate(id);
        if (warehouse == null) {
            throw new MasterDataOperationException("LOCATION_WAREHOUSE_NOT_FOUND",
                    "The warehouse does not exist.", 409);
        }
        if (reference.getVersion() == null || warehouse.version() != reference.getVersion()) {
            throw new MasterDataOperationException("LOCATION_WAREHOUSE_VERSION_CONFLICT",
                    "The warehouse version is no longer current.", 409);
        }
        if (!"ACTIVE".equals(warehouse.status())) {
            throw warehouseDisabled();
        }
        return id;
    }

    private Long validateParent(CatalogReference reference, Long childId, Long warehouseId) {
        if (reference == null) {
            return null;
        }
        long id = referenceId(reference, "parent location");
        LocationRow parent = mapper.findForUpdate(id);
        if (parent == null) {
            throw new MasterDataOperationException("LOCATION_PARENT_NOT_FOUND",
                    "The parent location does not exist.", 409);
        }
        if (reference.getVersion() == null || parent.version() != reference.getVersion()) {
            throw new MasterDataOperationException("LOCATION_PARENT_VERSION_CONFLICT",
                    "The parent location version is no longer current.", 409);
        }
        if (!"ACTIVE".equals(parent.status())) {
            throw parentDisabled();
        }
        assertMatchingWarehouse(parent.warehouseId(), warehouseId);
        assertNoCycle(id, childId);
        return id;
    }

    private long referenceId(CatalogReference reference, String field) {
        if (reference.getId() == null) {
            throw MasterDataOperationException.invalid("The " + field + " id is invalid.");
        }
        try {
            return Long.parseLong(reference.getId());
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.invalid("The " + field + " id is invalid.");
        }
    }

    private void assertNoCycle(long parentId, Long childId) {
        Long currentId = parentId;
        while (currentId != null) {
            if (childId != null && currentId.equals(childId)) {
                throw new MasterDataOperationException("LOCATION_CYCLE",
                        "The location hierarchy would contain a cycle.", 409);
            }
            currentId = requireForUpdate(currentId).parentId();
        }
    }

    private void assertActiveParentChain(Long parentId, long childId) {
        Long currentId = parentId;
        while (currentId != null) {
            if (currentId == childId) {
                throw new MasterDataOperationException("LOCATION_CYCLE",
                        "The location hierarchy contains a cycle.", 409);
            }
            LocationRow parent = mapper.findForUpdate(currentId);
            if (parent == null || !"ACTIVE".equals(parent.status())) {
                throw parentDisabled();
            }
            currentId = parent.parentId();
        }
    }

    private void assertParentWarehouse(long parentId, Long warehouseId) {
        LocationRow parent = requireForUpdate(parentId);
        assertMatchingWarehouse(parent.warehouseId(), warehouseId);
    }

    private void assertMatchingWarehouse(Long parentWarehouseId, Long warehouseId) {
        if (!java.util.Objects.equals(parentWarehouseId, warehouseId)) {
            throw new MasterDataOperationException("LOCATION_WAREHOUSE_MISMATCH",
                    "The child location must belong to its parent's warehouse.", 409);
        }
    }

    private void assertWarehouseActive(Long warehouseId) {
        if (warehouseId != null) {
            WarehouseRow warehouse = warehouses.findForUpdate(warehouseId);
            if (warehouse == null || !"ACTIVE".equals(warehouse.status())) {
                throw warehouseDisabled();
            }
        }
    }

    private MasterDataOperationException parentDisabled() {
        return new MasterDataOperationException("LOCATION_PARENT_DISABLED",
                "The parent location is disabled.", 409);
    }

    private MasterDataOperationException warehouseDisabled() {
        return new MasterDataOperationException("LOCATION_WAREHOUSE_DISABLED",
                "The warehouse is disabled.", 409);
    }

    private LocationRow require(long id) {
        LocationRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The location");
        }
        return row;
    }

    private LocationRow requireForUpdate(long id) {
        LocationRow row = mapper.findForUpdate(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The location");
        }
        return row;
    }

    private LocationRow requireVersion(LocationRow row, long version) {
        if (row.version() != version) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private LocationType requireType(LocationType type) {
        if (type == null) {
            throw MasterDataOperationException.invalid("Location type is required.");
        }
        return type;
    }

    private void assertCodeAvailable(String code, Long currentId) {
        LocationRow existing = mapper.findByCode(normalizeCode(code));
        if (existing != null && (currentId == null || existing.id() != currentId)) {
            throw new MasterDataOperationException("LOCATION_CODE_CONFLICT",
                    "The location code is already in use.", 409);
        }
    }

    private Optional<Location> replay(long actorId, String operation, String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), Location.class));
    }

    private void record(long actorId, String operation, String key, String digest,
            int status, Location result) {
        Instant now = clock.instant();
        requireOne(records.insertIdempotency(actorId, operation, key, digest, status,
                objectMapper.writeValueAsString(result), "LOCATION",
                Long.parseLong(result.getId()), result.getVersion(), now,
                now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private Location toLocation(LocationRow row) {
        Location result = new Location(Long.toString(row.id()), row.code(), row.name(),
                LocationType.valueOf(row.locationType()), ReferenceStatus.valueOf(row.status()),
                row.version());
        if (row.parentId() != null) {
            result.setParentLocation(new CatalogReference(Long.toString(row.parentId()), row.parentVersion()));
        }
        if (row.warehouseId() != null) {
            result.setWarehouse(new CatalogReference(Long.toString(row.warehouseId()),
                    row.warehouseVersion()));
        }
        result.setDescription(row.description());
        return result;
    }

    private Map<String, Object> toActiveReference(LocationRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(row.id()));
        result.put("code", row.code());
        result.put("name", row.name());
        result.put("version", row.version());
        result.put("type", row.locationType());
        if (row.warehouseId() != null) {
            result.put("warehouse", new CatalogReference(Long.toString(row.warehouseId()),
                    row.warehouseVersion()));
        }
        if (row.parentId() != null) {
            result.put("parentLocation", new CatalogReference(Long.toString(row.parentId()),
                    row.parentVersion()));
        }
        return result;
    }

    private String normalizeCode(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }

    private String required(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw MasterDataOperationException.invalid(field + " must be non-empty and within its limit.");
        }
        return value.strip();
    }

    private String optional(String value) {
        if (value != null && value.length() > 500) {
            throw MasterDataOperationException.invalid("Description exceeds its limit.");
        }
        return value;
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw MasterDataOperationException.writeConflict("The catalog write was not applied.");
        }
    }
}
