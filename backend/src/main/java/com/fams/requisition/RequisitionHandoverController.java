package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/requisitions", "/api/v1/mini/requisitions"})
class RequisitionHandoverController {
    private final RequisitionHandoverService service;
    private final RequisitionActorContext actors;

    RequisitionHandoverController(
            RequisitionHandoverService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/receipts/pending")
    RequisitionViews.Page<RequisitionViews.PendingReceiptItem> pending(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Min(1) Long allocationId) {
        return service.pending(actors.capture(), page, pageSize, allocationId);
    }

    @GetMapping("/{request}/allocations/{allocation}/receipt")
    RequisitionViews.ReceiptDetail detail(@PathVariable long request, @PathVariable long allocation) {
        return service.receiptDetail(actors.capture(), request, allocation);
    }

    @PostMapping("/{request}/allocations/{allocation}/grant")
    ResponseEntity<RequisitionViews.Grant> grant(
            @PathVariable long request, @PathVariable long allocation) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.grant(actors.capture(), request, allocation));
    }

    @PostMapping("/{request}/allocations/{allocation}/deliver")
    RequisitionViews.Detail deliver(
            @PathVariable long request,
            @PathVariable long allocation,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody HandoverInput.Delivery input) {
        return service.deliver(actors.capture(), request, allocation, version, key, input);
    }

    @PostMapping("/{request}/allocations/{allocation}/cancel")
    RequisitionViews.Detail cancel(
            @PathVariable long request,
            @PathVariable long allocation,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody HandoverInput.WarehouseAction input) {
        return service.warehouseAction(
                actors.capture(), request, allocation, version, key, "cancel", input);
    }

    @PostMapping("/{request}/allocations/{allocation}/recovery")
    RequisitionViews.Detail recovery(
            @PathVariable long request,
            @PathVariable long allocation,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody HandoverInput.WarehouseAction input) {
        return service.warehouseAction(
                actors.capture(), request, allocation, version, key, "recovery", input);
    }

    @PostMapping("/{request}/allocations/{allocation}/recover")
    RequisitionViews.Detail recover(
            @PathVariable long request,
            @PathVariable long allocation,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody HandoverInput.WarehouseAction input) {
        return service.warehouseAction(
                actors.capture(), request, allocation, version, key, "recover", input);
    }

    @PostMapping("/{request}/allocations/{allocation}/{action:receive|refuse}")
    RequisitionViews.ReceiptResult receipt(
            @PathVariable long request,
            @PathVariable long allocation,
            @PathVariable String action,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody HandoverInput.Receipt input) {
        if (!List.of("receive", "refuse").contains(action)) {
            throw RequisitionFailure.unavailable();
        }
        return service.receipt(
                actors.capture(),
                request,
                allocation,
                version,
                key,
                input,
                "refuse".equals(action));
    }
}
