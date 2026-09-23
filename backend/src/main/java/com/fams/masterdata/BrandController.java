package com.fams.masterdata;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.Brand;
import com.fams.product.contract.model.BrandCreateRequest;
import com.fams.product.contract.model.BrandUpdateRequest;
import com.fams.product.contract.model.CatalogStatusChangeRequest;
import com.fams.product.contract.model.PageBrand;
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

@Profile("fams-product")
@RestController
@Validated
@RequestMapping("/api/v1/brands")
class BrandController {

    private final BrandService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    BrandController(BrandService service, MasterDataActorContext actorContext,
            HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping
    ResponseEntity<PageBrand> list(
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

    @GetMapping("/{brandId}")
    ResponseEntity<Brand> get(@PathVariable String brandId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.get(parseId(brandId)));
    }

    @PostMapping
    ResponseEntity<Brand> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody BrandCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        Brand result = service.create(actorId, body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/{brandId}")
    ResponseEntity<Brand> update(
            @PathVariable String brandId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody BrandUpdateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.update(actorId, parseId(brandId), expectedVersion,
                body, key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{brandId}/disable")
    ResponseEntity<Brand> disable(
            @PathVariable String brandId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(brandId),
                expectedVersion, false, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{brandId}/enable")
    ResponseEntity<Brand> enable(
            @PathVariable String brandId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(brandId),
                expectedVersion, true, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The brand");
        }
    }
}
