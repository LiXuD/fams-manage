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

import com.fams.product.contract.model.AssetCategory;
import com.fams.product.contract.model.AssetCategoryCreateRequest;
import com.fams.product.contract.model.AssetCategoryUpdateRequest;
import com.fams.product.contract.model.CatalogReference;
import com.fams.product.contract.model.ExtensionFieldTemplate;
import com.fams.product.contract.model.PageAssetCategory;
import com.fams.product.contract.model.ReferenceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class AssetCategoryService {

    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private final AssetCategoryMapper mapper;
    private final IdentityMasterDataMapper records;
    private final MasterDataFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AssetCategoryService(AssetCategoryMapper mapper, IdentityMasterDataMapper records,
            MasterDataFingerprint fingerprint, ObjectMapper objectMapper, Clock authClock) {
        this.mapper = mapper;
        this.records = records;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    PageAssetCategory list(int page, int pageSize) {
        List<AssetCategory> items = mapper.list((page - 1) * pageSize, pageSize).stream()
                .map(this::toCategory).toList();
        return new PageAssetCategory(page, pageSize, mapper.count(), items);
    }

    Map<String, Object> listActive(int page, int pageSize) {
        List<Map<String, Object>> items = mapper.listActive((page - 1) * pageSize, pageSize)
                .stream().map(this::toActiveReference).toList();
        return Map.of("page", page, "pageSize", pageSize, "total", mapper.countActive(), "items", items);
    }

    AssetCategory get(long id) {
        return toCategory(require(id));
    }

    @Transactional
    AssetCategory create(long actorId, AssetCategoryCreateRequest request, String key, UUID requestId) {
        String operation = "createAssetCategory";
        String digest = fingerprint.of(operation, objectMapper.writeValueAsString(request));
        Optional<AssetCategory> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        String code = required(request.getCode(), 64, "Category code");
        String name = required(request.getName(), 128, "Category name");
        assertCodeAvailable(code, null);
        Long parentId = validateParent(request.getParentCategory(), null);
        Instant now = clock.instant();
        NewAssetCategoryRow row = new NewAssetCategoryRow(code, normalizeCode(code), name,
                parentId, optional(request.getDescription(), 500, "Description"),
                optional(request.getSuggestedUnit(), 32, "Suggested unit"),
                optional(request.getNumberingPrefix(), 32, "Numbering prefix"),
                objectMapper.writeValueAsString(request.getExtensionFieldTemplate()), actorId, now);
        requireOne(mapper.insert(row));
        AssetCategory result = get(row.getId());
        audit(actorId, requestId, "ASSET_CATEGORY_CREATED", row.getId(), now);
        record(actorId, operation, key, digest, 201, result);
        return result;
    }

    @Transactional
    AssetCategory update(long actorId, long id, long expectedVersion,
            AssetCategoryUpdateRequest request, boolean templatePresent, String key, UUID requestId) {
        String operation = "updateAssetCategory";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion),
                Boolean.toString(templatePresent), objectMapper.writeValueAsString(request));
        Optional<AssetCategory> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        AssetCategoryRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String code = request.getCode() == null ? current.code()
                : required(request.getCode(), 64, "Category code");
        String name = request.getName() == null ? current.name()
                : required(request.getName(), 128, "Category name");
        String description = request.getDescription() == null ? current.description()
                : optional(request.getDescription(), 500, "Description");
        String suggestedUnit = request.getSuggestedUnit() == null ? current.suggestedUnit()
                : optional(request.getSuggestedUnit(), 32, "Suggested unit");
        String numberingPrefix = request.getNumberingPrefix() == null ? current.numberingPrefix()
                : optional(request.getNumberingPrefix(), 32, "Numbering prefix");
        String template = templatePresent
                ? objectMapper.writeValueAsString(request.getExtensionFieldTemplate())
                : current.extensionTemplateJson();
        Long parentId = request.getParentCategory() == null ? current.parentId()
                : validateParent(request.getParentCategory(), id);
        assertCodeAvailable(code, id);
        Instant now = clock.instant();
        requireOne(mapper.update(id, expectedVersion, code, normalizeCode(code), name,
                parentId, description, suggestedUnit, numberingPrefix, template, actorId, now));
        AssetCategory result = get(id);
        audit(actorId, requestId, "ASSET_CATEGORY_UPDATED", id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    @Transactional
    AssetCategory changeStatus(long actorId, long id, long expectedVersion, boolean enabled,
            String reason, String key, UUID requestId) {
        String operation = enabled ? "enableAssetCategory" : "disableAssetCategory";
        String digest = fingerprint.of(operation, Long.toString(id), Long.toString(expectedVersion), reason);
        Optional<AssetCategory> replay = replay(actorId, operation, key, digest);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        required(reason, 500, "Reason");
        AssetCategoryRow current = requireVersion(requireForUpdate(id), expectedVersion);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(current.status())) {
            throw new MasterDataOperationException(enabled
                    ? "ASSET_CATEGORY_ALREADY_ENABLED" : "ASSET_CATEGORY_ALREADY_DISABLED",
                    "The category already has the requested status.", 409);
        }
        if (enabled) {
            assertActiveAncestors(current.parentId(), id);
        }
        Instant now = clock.instant();
        requireOne(mapper.changeStatus(id, expectedVersion, status, actorId, now));
        AssetCategory result = get(id);
        audit(actorId, requestId, enabled ? "ASSET_CATEGORY_ENABLED" : "ASSET_CATEGORY_DISABLED",
                id, now);
        record(actorId, operation, key, digest, 200, result);
        return result;
    }

    private Long validateParent(CatalogReference reference, Long childId) {
        if (reference == null) {
            return null;
        }
        if (reference.getId() == null) {
            throw MasterDataOperationException.invalid("The parent category id is invalid.");
        }
        long parentId;
        try {
            parentId = Long.parseLong(reference.getId());
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.invalid("The parent category id is invalid.");
        }
        AssetCategoryRow parent = mapper.findForUpdate(parentId);
        if (parent == null) {
            throw new MasterDataOperationException("ASSET_CATEGORY_PARENT_NOT_FOUND",
                    "The parent category does not exist.", 409);
        }
        if (reference.getVersion() == null || parent.version() != reference.getVersion()) {
            throw new MasterDataOperationException("ASSET_CATEGORY_PARENT_VERSION_CONFLICT",
                    "The parent category version is no longer current.", 409);
        }
        if (!"ACTIVE".equals(parent.status())) {
            throw parentDisabled();
        }
        assertNoCycle(parentId, childId);
        return parentId;
    }

    private void assertNoCycle(long parentId, Long childId) {
        Long currentId = parentId;
        while (currentId != null) {
            if (childId != null && currentId.equals(childId)) {
                throw new MasterDataOperationException("ASSET_CATEGORY_CYCLE",
                        "The category hierarchy would contain a cycle.", 409);
            }
            AssetCategoryRow row = mapper.findForUpdate(currentId);
            if (row == null) {
                throw new MasterDataOperationException("ASSET_CATEGORY_PARENT_NOT_FOUND",
                        "The parent category does not exist.", 409);
            }
            currentId = row.parentId();
        }
    }

    private void assertActiveAncestors(Long parentId, long childId) {
        Long currentId = parentId;
        while (currentId != null) {
            if (currentId == childId) {
                throw new MasterDataOperationException("ASSET_CATEGORY_CYCLE",
                        "The category hierarchy contains a cycle.", 409);
            }
            AssetCategoryRow row = mapper.findForUpdate(currentId);
            if (row == null || !"ACTIVE".equals(row.status())) {
                throw parentDisabled();
            }
            currentId = row.parentId();
        }
    }

    private MasterDataOperationException parentDisabled() {
        return new MasterDataOperationException("ASSET_CATEGORY_PARENT_DISABLED",
                "An ancestor category is disabled.", 409);
    }

    private AssetCategoryRow require(long id) {
        AssetCategoryRow row = mapper.find(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The category");
        }
        return row;
    }

    private AssetCategoryRow requireForUpdate(long id) {
        AssetCategoryRow row = mapper.findForUpdate(id);
        if (row == null) {
            throw MasterDataOperationException.notFound("The category");
        }
        return row;
    }

    private AssetCategoryRow requireVersion(AssetCategoryRow row, long version) {
        if (row.version() != version) {
            throw MasterDataOperationException.versionConflict();
        }
        return row;
    }

    private void assertCodeAvailable(String code, Long currentId) {
        AssetCategoryRow existing = mapper.findByCode(normalizeCode(code));
        if (existing != null && (currentId == null || existing.id() != currentId)) {
            throw new MasterDataOperationException("ASSET_CATEGORY_CODE_CONFLICT",
                    "The category code is already in use.", 409);
        }
    }

    private Optional<AssetCategory> replay(long actorId, String operation, String key, String digest) {
        IdempotencyRow existing = records.findIdempotency(actorId, operation, key, clock.instant());
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadSha256().equals(digest)) {
            throw MasterDataOperationException.idempotencyConflict();
        }
        return Optional.of(objectMapper.readValue(existing.responseBodyJson(), AssetCategory.class));
    }

    private void record(long actorId, String operation, String key, String digest,
            int status, AssetCategory result) {
        Instant now = clock.instant();
        requireOne(records.insertIdempotency(actorId, operation, key, digest, status,
                objectMapper.writeValueAsString(result), "ASSET_CATEGORY",
                Long.parseLong(result.getId()), result.getVersion(), now,
                now.plus(IDEMPOTENCY_RETENTION)));
    }

    private void audit(long actorId, UUID requestId, String action, long targetId, Instant now) {
        requireOne(records.insertAudit(actorId, requestId.toString(), now,
                objectMapper.writeValueAsString(Map.of("action", action, "targetId", targetId))));
    }

    private AssetCategory toCategory(AssetCategoryRow row) {
        AssetCategory result = new AssetCategory(Long.toString(row.id()), row.code(), row.name(),
                ReferenceStatus.valueOf(row.status()), row.version());
        if (row.parentId() != null) {
            result.setParentCategory(new CatalogReference(
                    Long.toString(row.parentId()), row.parentVersion()));
        }
        result.setDescription(row.description());
        result.setSuggestedUnit(row.suggestedUnit());
        result.setNumberingPrefix(row.numberingPrefix());
        if (row.extensionTemplateJson() != null) {
            result.setExtensionFieldTemplate(List.of(objectMapper.readValue(
                    row.extensionTemplateJson(), ExtensionFieldTemplate[].class)));
        }
        return result;
    }

    private Map<String, Object> toActiveReference(AssetCategoryRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(row.id()));
        result.put("code", row.code());
        result.put("name", row.name());
        result.put("version", row.version());
        if (row.suggestedUnit() != null) {
            result.put("suggestedUnit", row.suggestedUnit());
        }
        if (row.numberingPrefix() != null) {
            result.put("numberingPrefix", row.numberingPrefix());
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

    private String optional(String value, int maximumLength, String field) {
        if (value != null && value.length() > maximumLength) {
            throw MasterDataOperationException.invalid(field + " exceeds its limit.");
        }
        return value;
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw MasterDataOperationException.writeConflict("The catalog write was not applied.");
        }
    }
}
