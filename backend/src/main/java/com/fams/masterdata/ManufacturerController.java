package com.fams.masterdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.CatalogStatusChangeRequest;
import com.fams.product.contract.model.Manufacturer;
import com.fams.product.contract.model.ManufacturerActiveReferencePage;
import com.fams.product.contract.model.ManufacturerCreateRequest;
import com.fams.product.contract.model.ManufacturerUpdateRequest;
import com.fams.product.contract.model.PageManufacturer;
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
@RequestMapping("/api/v1/manufacturers")
class ManufacturerController {

    private final ManufacturerService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    ManufacturerController(
            ManufacturerService service,
            MasterDataActorContext actorContext,
            HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping
    ResponseEntity<PageManufacturer> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.list(page, pageSize));
    }

    @GetMapping("/active-references")
    ResponseEntity<ManufacturerActiveReferencePage> listActive(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireCatalogSelector();
        return ResponseEntity.ok(service.listActive(page, pageSize));
    }

    @GetMapping("/{manufacturerId}")
    ResponseEntity<Manufacturer> get(@PathVariable String manufacturerId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.get(parseId(manufacturerId)));
    }

    @PostMapping
    ResponseEntity<Manufacturer> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody ManufacturerCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        Manufacturer result = service.create(actorId, body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/{manufacturerId}")
    ResponseEntity<Manufacturer> update(
            @PathVariable String manufacturerId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody ManufacturerUpdateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.update(actorId, parseId(manufacturerId), expectedVersion,
                body, key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{manufacturerId}/disable")
    ResponseEntity<Manufacturer> disable(
            @PathVariable String manufacturerId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(manufacturerId),
                expectedVersion, false, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{manufacturerId}/enable")
    ResponseEntity<Manufacturer> enable(
            @PathVariable String manufacturerId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(manufacturerId),
                expectedVersion, true, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The manufacturer");
        }
    }
}
