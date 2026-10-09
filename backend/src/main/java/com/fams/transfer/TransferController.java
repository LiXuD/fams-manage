package com.fams.transfer;

import com.fams.asset.AssetTransferPort;
import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/transfers", "/api/v1/mini/transfers"})
class TransferController {
    record Item(
            @Min(1) long assetId,
            @Min(0) long assetVersion,
            @NotNull @Valid AssetTransferPort.Target target) {}

    record Draft(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull @Size(min = 1, max = 200) List<@Valid Item> items) {}

    record Decision(@NotNull Boolean approved, @NotBlank @Size(max = 1000) String reason) {}

    record Physical(
            @NotBlank @Size(max = 256) String observedCode,
            @NotBlank @Size(max = 500) String destination,
            @NotBlank @Size(max = 1000) String evidence,
            @Min(0) long itemVersion,
            @Size(max = 200) String actualReceiver,
            Instant receivedAt,
            Boolean physicalReturned) {}

    record Receipt(
            @NotBlank @Size(max = 128) String grant,
            @NotBlank @Size(max = 256) String observedCode,
            @NotNull Boolean physicalChecked,
            @Size(max = 1000) String reason) {}

    private final TransferService service;
    private final RequisitionActorContext actors;

    TransferController(TransferService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable long id) {
        return service.get(actors.capture(), id);
    }

    @GetMapping("/eligible-assets")
    Map<String, Object> eligible(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping("/catalog/{kind}")
    Map<String, Object> catalog(
            @PathVariable String kind,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int pageSize) {
        return service.catalog(actors.capture(), kind, organizationId, page, pageSize);
    }

    @GetMapping("/personnel-handover")
    Map<String, Object> personnel(@RequestParam @Min(1) long employeeId) {
        return service.personnel(actors.capture(), employeeId);
    }

    @PostMapping
    Map<String, Object> create(
            @Valid @RequestBody Draft input,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.create(actors.capture(), key, input);
    }

    @PostMapping("/{id}/{action:submit|cancel|claim}")
    Map<String, Object> action(
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.action(actors.capture(), id, version, key, action);
    }

    @PostMapping("/{id}/decision")
    Map<String, Object> decide(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Decision input) {
        return service.decide(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/items/{itemId}/{action:deliver|verify|cancel|recovery|recover}")
    Map<String, Object> physical(
            @PathVariable long id,
            @PathVariable long itemId,
            @PathVariable String action,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Physical input) {
        return service.physical(actors.capture(), id, itemId, version, key, action, input);
    }

    @GetMapping("/receipts/pending")
    Map<String, Object> pending(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.pending(actors.capture(), page, pageSize);
    }

    @PostMapping("/receipts/{itemId}/grant")
    Map<String, Object> grant(@PathVariable long itemId) {
        return service.grant(actors.capture(), itemId);
    }

    @PostMapping("/receipts/{itemId}/{action:receive|refuse}")
    Map<String, Object> receive(
            @PathVariable long itemId,
            @PathVariable String action,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Receipt input) {
        return service.receipt(actors.capture(), itemId, version, key, action, input);
    }
}
