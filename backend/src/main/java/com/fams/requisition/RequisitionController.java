package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
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



@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/requisitions", "/api/v1/mini/requisitions"})
class RequisitionController {
    private final RequisitionService service;
    private final RequisitionActorContext actors;

    RequisitionController(RequisitionService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/catalog/{kind}")
    RequisitionViews.CatalogPage catalog(
            @PathVariable String kind,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int pageSize) {
        return service.catalog(actors.capture(), kind, page, pageSize);
    }

    @GetMapping
    RequisitionViews.Page<RequisitionViews.Summary> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize,
            @RequestParam(required = false) String status) {
        return service.list(actors.capture(), page, pageSize, status);
    }

    @GetMapping("/{id}")
    RequisitionViews.Detail get(@PathVariable long id) {
        return service.getDetail(actors.capture(), id);
    }

    @PostMapping
    ResponseEntity<RequisitionViews.Detail> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Draft input) {
        return ResponseEntity.status(201).body(service.create(actors.capture(), key, input));
    }

    @PatchMapping("/{id}")
    RequisitionViews.Detail revise(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Draft input) {
        return service.revise(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/submit")
    RequisitionViews.Detail submit(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.submit(actors.capture(), id, version, key);
    }

    @PostMapping("/{id}/changes")
    RequisitionViews.Detail change(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Draft input) {
        return service.proposeChange(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/cancel")
    RequisitionViews.Detail cancel(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Reason input) {
        return service.cancel(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/decision")
    RequisitionViews.Detail decide(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Decision input) {
        return service.decide(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/claim")
    RequisitionViews.Detail claim(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.claim(actors.capture(), id, version, key);
    }

    @GetMapping("/{id}/demands/{demandId}/inventory")
    RequisitionViews.Page<RequisitionViews.InventoryAsset> inventory(
            @PathVariable long id,
            @PathVariable long demandId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int pageSize) {
        return service.inventory(actors.capture(), id, demandId, page, pageSize);
    }

    @PostMapping("/{id}/demands/{demandId}/allocations")
    RequisitionViews.Detail allocate(
            @PathVariable long id,
            @PathVariable long demandId,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Allocation input) {
        return service.allocate(actors.capture(), id, demandId, version, key, input);
    }

    @PostMapping("/{id}/demands/{demandId}/unfulfillable")
    RequisitionViews.Detail unfulfillable(
            @PathVariable long id,
            @PathVariable long demandId,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Unfulfillable input) {
        return service.unfulfillable(actors.capture(), id, demandId, version, key, input);
    }

    @PostMapping("/{id}/dispute")
    RequisitionViews.Detail dispute(
            @PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody RequisitionInput.Reason input) {
        return service.dispute(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/demands/{demandId}/followup")
    RequisitionViews.Detail followup(
            @PathVariable long id,
            @PathVariable long demandId,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.followup(actors.capture(), id, demandId, version, key);
    }
}
