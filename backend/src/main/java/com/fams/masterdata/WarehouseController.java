package com.fams.masterdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.fams.auth.MasterDataActorContext;
import com.fams.product.contract.model.CatalogStatusChangeRequest;
import com.fams.product.contract.model.Warehouse;
import com.fams.product.contract.model.WarehouseActiveReferencePage;
import com.fams.product.contract.model.WarehouseCreateRequest;
import com.fams.product.contract.model.WarehouseUpdateRequest;
import com.fams.product.contract.model.PageWarehouse;
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
@RequestMapping("/api/v1/warehouses")
class WarehouseController {

    private final WarehouseService service;
    private final MasterDataActorContext actorContext;
    private final HttpServletRequest servletRequest;

    WarehouseController(
            WarehouseService service,
            MasterDataActorContext actorContext,
            HttpServletRequest servletRequest) {
        this.service = service;
        this.actorContext = actorContext;
        this.servletRequest = servletRequest;
    }

    @GetMapping
    ResponseEntity<PageWarehouse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.list(page, pageSize));
    }

    @GetMapping("/active-references")
    ResponseEntity<WarehouseActiveReferencePage> listActive(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        actorContext.requireCatalogSelector();
        return ResponseEntity.ok(service.listActive(page, pageSize));
    }

    @GetMapping("/{warehouseId}")
    ResponseEntity<Warehouse> get(@PathVariable String warehouseId) {
        actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.get(parseId(warehouseId)));
    }

    @PostMapping
    ResponseEntity<Warehouse> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody WarehouseCreateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        Warehouse result = service.create(actorId, body, key, RequestIds.from(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/{warehouseId}")
    ResponseEntity<Warehouse> update(
            @PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody WarehouseUpdateRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.update(actorId, parseId(warehouseId), expectedVersion,
                body, key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{warehouseId}/disable")
    ResponseEntity<Warehouse> disable(
            @PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(warehouseId),
                expectedVersion, false, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    @PostMapping("/{warehouseId}/enable")
    ResponseEntity<Warehouse> enable(
            @PathVariable String warehouseId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestHeader("If-Match-Version") @Min(0) long expectedVersion,
            @Valid @RequestBody CatalogStatusChangeRequest body) {
        long actorId = actorContext.requireSystemAdministrator();
        return ResponseEntity.ok(service.changeStatus(actorId, parseId(warehouseId),
                expectedVersion, true, body.getReason(), key, RequestIds.from(servletRequest)));
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.notFound("The warehouse");
        }
    }
}
