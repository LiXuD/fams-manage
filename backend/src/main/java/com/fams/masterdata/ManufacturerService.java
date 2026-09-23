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

import com.fams.product.contract.model.Manufacturer;
import com.fams.product.contract.model.ManufacturerActiveReference;
import com.fams.product.contract.model.ManufacturerActiveReferencePage;
import com.fams.product.contract.model.ManufacturerCreateRequest;
import com.fams.product.contract.model.ManufacturerUpdateRequest;
import com.fams.product.contract.model.PageManufacturer;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class ManufacturerService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final ManufacturerMapper mapper;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ManufacturerService(
            ManufacturerMapper mapper,
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

    PageManufacturer list(int page, int pageSize) {
        List<Manufacturer> items = mapper.list((page - 1) * pageSize, pageSize).stream()
                .map(this::toManufacturer).toList();
        return new PageManufacturer(page, pageSize, mapper.count(), items);
    }

    ManufacturerActiveReferencePage listActive(int page, int pageSize) {
        List<ManufacturerActiveReference> items = mapper
                .listActive((page - 1) * pageSize, pageSize).stream()
                .map(row -> new ManufacturerActiveReference(
                        Long.toString(row.id()), row.code(), row.name(), row.version()))
                .toList();
        return new ManufacturerActiveReferencePage(page, pageSize, mapper.countActive(), items);
    }

    Manufacturer get(long id) {
        return toManufacturer(require(id));
    }

    @Transactional
    Manufacturer create(
            long actorId,
            ManufacturerCreateRequest request,
            String key,
            UUID requestId) {
        String operation = "createManufacturer";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<Manufacturer> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        String code = required(request.getCode(), 64, "Manufacturer code");
        String name = required(request.getName(), 128, "Manufacturer name");
        assertCodeAvailable(code, null);
        Instant now = clock.instant();
        NewManufacturerRow row = new NewManufacturerRow(
                code, normalizeCode(code), name, optional(request.getDescription(), 500), actorId, now);
        requireOne(mapper.insert(row));
        Manufacturer result = get(row.getId());
        audit(actorId, requestId, "MANUFACTURER_CREATED", row.getId(), now);
        record(actorId, operation, key, digest, 201, result);
        return result;
    }

    @Transactional
    Manufacturer update(
            long actorId,
            long id,
            long expectedVersion,
            ManufacturerUpdateRequest request,
            String key,
            UUID requestId) {
        String operation = "updateManufacturer";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<Manufacturer> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ManufacturerRow current = requireVersion(require(id), expectedVersion);
        String code = request.getCode() == null ? current.code()
                : required(request.getCode(), 64, "Manufacturer code");
        String name = request.getName() == null ? current.name()
                : required(request.getName(), 128, "Manufacturer name");
        String description = request.getDescription() == null ? current.description()
                : optional(request.getDescription(), 500);
        assertCodeAvailable(code, id);
        Instant now = clock.instant();
        requireOne(mapper.update(id, expectedVersion, code, normalizeCode(code),
                name, description, actorId, now));
        Manufacturer result = get(id);
        audit(actorId, requestId, "MANUFACTURER_UPDATED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    @Transactional
    Manufacturer changeStatus(
            long actorId,
            long id,
            long expectedVersion,
            boolean enabled,
            String reason,
            String key,
            UUID requestId) {
        String operation = enabled ? "enableManufacturer" : "disableManufacturer";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<Manufacturer> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        required(reason, 500, "Reason");
        ManufacturerRow current = requireVersion(require(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw new MasterDataOperationException(
                    enabled ? "MANUFACTURER_ALREADY_ENABLED" : "MANUFACTURER_ALREADY_DISABLED",
                    "The manufacturer already has the requested status.", 409);
        }
        Instant now = clock.instant();
        requireOne(mapper.changeStatus(id, expectedVersion, status, actorId, now));
        Manufacturer result = get(id);
        audit(actorId, requestId, enabled ? "MANUFACTURER_ENABLED" : "MANUFACTURER_DISABLED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    private ManufacturerRow require(long id) {
        ManufacturerRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The manufacturer");
        }
        return row;
    }

    private ManufacturerRow requireVersion(ManufacturerRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private void assertCodeAvailable(String code, Long currentId) {
        ManufacturerRow existing = mapper.findByCode(normalizeCode(code));
        if (existing != null && (currentId == null || existing.id() != currentId)) {
            throw new MasterDataOperationException(
                    "MANUFACTURER_CODE_CONFLICT", "The manufacturer code is already in use.", 409);
        }
    }

    private Optional<Manufacturer> replay(long actorId, String operation, String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), Manufacturer.class));
    }

    private void record(
            long actorId, String operation, String key, String digest, int status, Manufacturer result) {
        Instant now = clock.instant();
        requireOne(records.insertIdempotency(actorId, operation, key, digest, status,
                objectMapper.writeValueAsString(result), "MANUFACTURER", Long.parseLong(result.getId()),
                result.getVersion(), now, now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private Manufacturer toManufacturer(ManufacturerRow row) {
        Manufacturer result = new Manufacturer(
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
