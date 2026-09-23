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

import com.fams.product.contract.model.Brand;
import com.fams.product.contract.model.BrandCreateRequest;
import com.fams.product.contract.model.BrandUpdateRequest;
import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.PageBrand;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class BrandService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final BrandMapper mapper;
    private final ManufacturerMapper manufacturers;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    BrandService(
            BrandMapper mapper,
            ManufacturerMapper manufacturers,
            IdentityMasterDataMapper records,
            MasterDataFingerprint fingerprint,
            ObjectMapper objectMapper,
            Clock authClock) {
        this.mapper = mapper;
        this.manufacturers = manufacturers;
        this.records = records;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageBrand list(int page, int pageSize) {
        List<Brand> items = mapper.list((page - 1) * pageSize, pageSize).stream()
                .map(this::toBrand).toList();
        return new PageBrand(page, pageSize, mapper.count(), items);
    }

    Map<String, Object> listActive(int page, int pageSize) {
        List<Map<String, Object>> items = mapper.listActive((page - 1) * pageSize, pageSize).stream()
                .map(this::toActiveReference).toList();
        return Map.of("page", page, "pageSize", pageSize, "total", mapper.countActive(), "items", items);
    }

    Brand get(long id) {
        return toBrand(require(id));
    }

    @Transactional
    Brand create(long actorId, BrandCreateRequest request, String key, UUID requestId) {
        String operation = "createBrand";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<Brand> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        String code = required(request.getCode(), 64, "Brand code");
        String name = required(request.getName(), 128, "Brand name");
        assertCodeAvailable(code, null);
        Long manufacturerId = validateManufacturer(request.getDefaultManufacturer());
        Instant now = clock.instant();
        NewBrandRow row = new NewBrandRow(code, normalizeCode(code), name,
                optional(request.getDescription(), 500), manufacturerId, actorId, now);
        requireOne(mapper.insert(row));
        Brand result = get(row.getId());
        audit(actorId, requestId, "BRAND_CREATED", row.getId(), now);
        record(actorId, operation, key, digest, 201, result);
        return result;
    }

    @Transactional
    Brand update(long actorId, long id, long expectedVersion,
            BrandUpdateRequest request, String key, UUID requestId) {
        String operation = "updateBrand";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                objectMapper.writeValueAsString(request));
        Optional<Brand> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        BrandRow current = requireVersion(require(id), expectedVersion);
        String code = request.getCode() == null ? current.code()
                : required(request.getCode(), 64, "Brand code");
        String name = request.getName() == null ? current.name()
                : required(request.getName(), 128, "Brand name");
        String description = request.getDescription() == null ? current.description()
                : optional(request.getDescription(), 500);
        Long manufacturerId = request.getDefaultManufacturer() == null
                ? current.defaultManufacturerId() : validateManufacturer(request.getDefaultManufacturer());
        assertCodeAvailable(code, id);
        Instant now = clock.instant();
        requireOne(mapper.update(id, expectedVersion, code, normalizeCode(code), name,
                manufacturerId, description, actorId, now));
        Brand result = get(id);
        audit(actorId, requestId, "BRAND_UPDATED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    @Transactional
    Brand changeStatus(long actorId, long id, long expectedVersion, boolean enabled,
            String reason, String key, UUID requestId) {
        String operation = enabled ? "enableBrand" : "disableBrand";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<Brand> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        required(reason, 500, "Reason");
        BrandRow current = requireVersion(require(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw new MasterDataOperationException(
                    enabled ? "BRAND_ALREADY_ENABLED" : "BRAND_ALREADY_DISABLED",
                    "The brand already has the requested status.", 409);
        }
        Instant now = clock.instant();
        requireOne(mapper.changeStatus(id, expectedVersion, status, actorId, now));
        Brand result = get(id);
        audit(actorId, requestId, enabled ? "BRAND_ENABLED" : "BRAND_DISABLED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    private Long validateManufacturer(CatalogReference reference) {
        if (reference == null) {
            return null;
        }
        if (reference.getId() == null) {
            throw MasterDataOperationException.invalid("The manufacturer id is invalid.");
        }
        long id;
        try {
            id = Long.parseLong(reference.getId());
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.invalid("The manufacturer id is invalid.");
        }
        ManufacturerRow manufacturer = manufacturers.find(id);
        if (manufacturer == null) {
            throw new MasterDataOperationException("BRAND_DEFAULT_MANUFACTURER_NOT_FOUND",
                    "The default manufacturer does not exist.", 409);
        }
        if (reference.getVersion() == null || manufacturer.version() != reference.getVersion()) {
            throw new MasterDataOperationException("BRAND_DEFAULT_MANUFACTURER_VERSION_CONFLICT",
                    "The default manufacturer version is no longer current.", 409);
        }
        if (!"ACTIVE".equals(manufacturer.status())) {
            throw new MasterDataOperationException("BRAND_DEFAULT_MANUFACTURER_DISABLED",
                    "The default manufacturer is disabled.", 409);
        }
        return id;
    }

    private BrandRow require(long id) {
        BrandRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The brand");
        }
        return row;
    }

    private BrandRow requireVersion(BrandRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private void assertCodeAvailable(String code, Long currentId) {
        BrandRow existing = mapper.findByCode(normalizeCode(code));
        if (existing != null && (currentId == null || existing.id() != currentId)) {
            throw new MasterDataOperationException(
                    "BRAND_CODE_CONFLICT", "The brand code is already in use.", 409);
        }
    }

    private Optional<Brand> replay(long actorId, String operation, String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), Brand.class));
    }

    private void record(long actorId, String operation, String key, String digest, int status, Brand result) {
        Instant now = clock.instant();
        requireOne(records.insertIdempotency(actorId, operation, key, digest, status,
                objectMapper.writeValueAsString(result), "BRAND", Long.parseLong(result.getId()),
                result.getVersion(), now, now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private Brand toBrand(BrandRow row) {
        Brand result = new Brand(Long.toString(row.id()), row.code(), row.name(),
                ReferenceStatus.valueOf(row.status()), row.version());
        if (row.defaultManufacturerId() != null) {
            result.setDefaultManufacturer(new CatalogReference(
                    Long.toString(row.defaultManufacturerId()), row.defaultManufacturerVersion()));
        }
        result.setDescription(row.description());
        return result;
    }

    private Map<String, Object> toActiveReference(BrandRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(row.id()));
        result.put("code", row.code());
        result.put("name", row.name());
        result.put("version", row.version());
        if (row.defaultManufacturerId() != null
                && "ACTIVE".equals(row.defaultManufacturerStatus())) {
            result.put("defaultManufacturerId", Long.toString(row.defaultManufacturerId()));
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
