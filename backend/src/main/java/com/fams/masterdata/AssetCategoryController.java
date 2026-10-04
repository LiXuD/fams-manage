package com.fams.masterdata;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.AssetCategory;
import com.fams.product.contract.model.AssetCategoryCreateRequest;
import com.fams.product.contract.model.AssetCategoryUpdateRequest;
import com.fams.product.contract.model.CatalogStatusChangeRequest;
import com.fams.product.contract.model.PageAssetCategory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/asset-categories")
class AssetCategoryController {

    private final AssetCategoryService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;
    private final ObjectMapper objectMapper;

    AssetCategoryController(AssetCategoryService service, MasterDataActorContext actorContext,
            HttpServletRequest servletRequest, ObjectMapper objectMapper) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    ResponseEntity<PageAssetCategory> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.list(page, pageSize));
    }

    @GetMapping("/active-references")
    ResponseEntity<Map<String, Object>> listActive(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireCatalogSelector();
        return ResponseEntity.ok(service.listActive(page, pageSize));
    }

    @GetMapping("/{assetCategoryId}")
    ResponseEntity<AssetCategory> get(@PathVariable String assetCategoryId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.get(parseId(assetCategoryId)));
    }

    @PostMapping
    ResponseEntity<AssetCategory> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody AssetCategoryCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        AssetCategory result = service.create(actorId, body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/{assetCategoryId}")
    ResponseEntity<AssetCategory> update(
            @PathVariable String assetCategoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @RequestBody JsonNode body) {
        long actorId = actorContext.requireSystemAdministrator();
        AssetCategoryUpdateRequest request = objectMapper.treeToValue(body, AssetCategoryUpdateRequest.class);
        return ResponseEntity.ok(service.update(actorId, parseId(assetCategoryId), expectedVersion,
                request, body.has("extensionFieldTemplate"), key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{assetCategoryId}/disable")
    ResponseEntity<AssetCategory> disable(
            @PathVariable String assetCategoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(assetCategoryId),
                expectedVersion, false, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{assetCategoryId}/enable")
    ResponseEntity<AssetCategory> enable(
            @PathVariable String assetCategoryId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(assetCategoryId),
                expectedVersion, true, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The category");
        }
    }
}
