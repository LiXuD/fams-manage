package com.fams.requisition;

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
import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/proxy-confirmations", "/api/v1/mini/proxy-confirmations"})
class RequisitionProxyConfirmationController {

    record Draft(
            @Min(1) long businessId,
            @Min(1) long itemId,
            @NotNull Map<String, Object> snapshot,
            @NotBlank @Size(max = 128) String observedAssetCode,
            @NotBlank @Size(max = 200) String actualSigner,
            Long actualSignerEmployeeId,
            @NotNull Instant actualReceivedAt,
            @NotBlank @Size(max = 1000) String cannotConfirmReason,
            @NotBlank @Size(max = 1000) String contactRecord,
            boolean delegatedReceipt,
            @Size(max = 1000) String delegationRelationship,
            @Size(max = 1000) String delegationBasis,
            @NotBlank String evidenceKind,
            @NotBlank String collectionMethod,
            @Size(max = 1000) String manualReason,
            boolean targetOrAuthorizedProxyReceived,
            Long previousConfirmationId) {}

    record Decision(
            boolean approved,
            @NotBlank @Size(max = 1000) String reason,
            boolean physicalEvidenceChecked,
            boolean noDisputeChecked) {}

    private final RequisitionProxyConfirmationService service;

    private final RequisitionActorContext actors;

    RequisitionProxyConfirmationController(
            RequisitionProxyConfirmationService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/context")
    Map<String, Object> context(
            @RequestParam(defaultValue = "REQUISITION") String businessType,
            @RequestParam @Min(1) long businessId, @RequestParam @Min(1) long itemId) {
        return service.context(actors.capture(), businessType, businessId, itemId);
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable @Min(1) long id) {
        return service.get(actors.capture(), id);
    }

    @PostMapping
    Map<String, Object> create(
            @Valid @RequestBody Draft input,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.create(actors.capture(), key, input);
    }

    @PostMapping("/{id}/submit")
    Map<String, Object> submit(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.submit(actors.capture(), id, version, key);
    }

    @PostMapping("/{id}/decision")
    Map<String, Object> decision(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Decision input) {
        return service.decision(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/execute")
    Map<String, Object> execute(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.execute(actors.capture(), id, version, key);
    }
}
