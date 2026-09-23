package com.fams.masterdata;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fams.product.contract.model.Warehouse;
import com.fams.product.contract.model.WarehouseActiveReference;
import com.fams.product.contract.model.WarehouseActiveReferencePage;
import com.fams.product.contract.model.WarehouseCreateRequest;
import com.fams.product.contract.model.WarehouseUpdateRequest;
import com.fams.product.contract.model.PageWarehouse;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class WarehouseService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final WarehouseMapper mapper;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    WarehouseService(
            WarehouseMapper mapper,
            IdentityMasterDataMapper records,
            MasterDataFingerprint fingerprint,
            ObjectMapper objectMapper,
            Clock authClock) {
        this.mapper = mapper;
        this.records = records;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageWarehouse list(int page, int pageSize) {
        List<Warehouse> items = mapper.list((page - 1) * pageSize, pageSize).stream()
                .map(this::toWarehouse).toList();
        return new PageWarehouse(page, pageSize, mapper.count(), items);
    }

    WarehouseActiveReferencePage listActive(int page, int pageSize) {
        List<WarehouseActiveReference> items = mapper
                .listActive((page - 1) * pageSize, pageSize).stream()
                .map(row -> new WarehouseActiveReference(
                        Long.toString(row.id()), row.code(), row.name(), row.version()))
                .toList();
        return new WarehouseActiveReferencePage(page, pageSize, mapper.countActive(), items);
    }

    Warehouse get(long id) {
        return toWarehouse(require(id));
    }

    @Transactional
    Warehouse create(
            long actorId,
            WarehouseCreateRequest request,
            String key,
            UUID requestId) {
        String operation = "createWarehouse";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<Warehouse> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        String code = required(request.getCode(), 64, "Warehouse code");
        String name = required(request.getName(), 128, "Warehouse name");
        assertCodeAvailable(code, null);
        Instant now = clock.instant();
        NewWarehouseRow row = new NewWarehouseRow(
                code, normalizeCode(code), name, optional(request.getDescription(), 500), actorId, now);
        requireOne(mapper.insert(row));
        Warehouse result = get(row.getId());
        audit(actorId, requestId, "WAREHOUSE_CREATED", row.getId(), now);
        record(actorId, operation, key, digest, 201, result);
        return result;
    }

    @Transactional
    Warehouse update(
            long actorId,
            long id,
            long expectedVersion,
            WarehouseUpdateRequest request,
            String key,
            UUID requestId) {
        String operation = "updateWarehouse";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<Warehouse> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        WarehouseRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String code = request.getCode() == null ? current.code()
                : required(request.getCode(), 64, "Warehouse code");
        String name = request.getName() == null ? current.name()
                : required(request.getName(), 128, "Warehouse name");
        String description = request.getDescription() == null ? current.description()
                : optional(request.getDescription(), 500);
        assertCodeAvailable(code, id);
        Instant now = clock.instant();
        requireOne(mapper.update(id, expectedVersion, code, normalizeCode(code),
                name, description, actorId, now));
        Warehouse result = get(id);
        audit(actorId, requestId, "WAREHOUSE_UPDATED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    @Transactional
    Warehouse changeStatus(
            long actorId,
            long id,
            long expectedVersion,
            boolean enabled,
            String reason,
            String key,
            UUID requestId) {
        String operation = enabled ? "enableWarehouse" : "disableWarehouse";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<Warehouse> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        required(reason, 500, "Reason");
        WarehouseRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw new MasterDataOperationException(
                    enabled ? "WAREHOUSE_ALREADY_ENABLED" : "WAREHOUSE_ALREADY_DISABLED",
                    "The warehouse already has the requested status.", 409);
        }
        if (!enabled && mapper.countActiveLocations(id) != 0) {
            throw new MasterDataOperationException("WAREHOUSE_HAS_OPEN_BUSINESS_REFERENCES",
                    "Active locations still reference this warehouse.", 409);
        }
        Instant now = clock.instant();
        requireOne(mapper.changeStatus(id, expectedVersion, status, actorId, now));
        Warehouse result = get(id);
        audit(actorId, requestId, enabled ? "WAREHOUSE_ENABLED" : "WAREHOUSE_DISABLED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    private WarehouseRow require(long id) {
        WarehouseRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The warehouse");
        }
        return row;
    }

    private WarehouseRow requireForUpdate(long id) {
        WarehouseRow row = mapper.findForUpdate(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The warehouse");
        }
        return row;
    }

    private WarehouseRow requireVersion(WarehouseRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private void assertCodeAvailable(String code, Long currentId) {
        WarehouseRow existing = mapper.findByCode(normalizeCode(code));
        if (existing != null && (currentId == null || existing.id() != currentId)) {
            throw new MasterDataOperationException(
                    "WAREHOUSE_CODE_CONFLICT", "The warehouse code is already in use.", 409);
        }
    }

    private Optional<Warehouse> replay(long actorId, String operation, String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), Warehouse.class));
    }

    private void record(
            long actorId, String operation, String key, String digest, int status, Warehouse result) {
        Instant now = clock.instant();
        requireOne(records.insertIdempotency(actorId, operation, key, digest, status,
                objectMapper.writeValueAsString(result), "WAREHOUSE", Long.parseLong(result.getId()),
                result.getVersion(), now, now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private Warehouse toWarehouse(WarehouseRow row) {
        Warehouse result = new Warehouse(
                Long.toString(row.id()), row.code(), row.name(),
                ReferenceStatus.valueOf(row.status()), row.version());
        result.setDescription(row.description());
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

    private String optional(String value, int maximumLength) {
        if (value != null && value.length() > maximumLength) {
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
