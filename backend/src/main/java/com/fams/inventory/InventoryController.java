package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.util.List;
import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/inventory-tasks", "/api/v1/mini/inventory-tasks"})
class InventoryController {
    private final InventoryService service;
    private final RequisitionActorContext actors;

    InventoryController(InventoryService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/preview")
    Map<String, Object> preview(@RequestBody @Valid InventoryInput.Range input) {
        return service.preview(actors.capture(), input);
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/executors")
    List<Map<String, Object>> executors(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize) {
        return service.executorChoices(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(
            @PathVariable @Min(1) long id,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.detail(actors.capture(), id, page, pageSize);
    }

    @PostMapping
    Map<String, Object> create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody @Valid InventoryInput.Draft input) {
        return service.create(actors.capture(), key, input);
    }

    @PostMapping("/{id}/copy")
    Map<String, Object> copy(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody @Valid InventoryInput.Draft input) {
        return service.copy(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/revision")
    Map<String, Object> revise(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody @Valid InventoryInput.Draft input) {
        return service.revise(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/publication")
    Map<String, Object> publish(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody @Valid InventoryInput.Publish input) {
        return service.publish(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/start")
    Map<String, Object> start(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.start(actors.capture(), id, version, key);
    }

    @PostMapping("/{id}/assignments")
    Map<String, Object> assign(
            @PathVariable @Min(1) long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody @Valid InventoryInput.Assign input) {
        return service.assign(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/{action:delete|cancel|terminate}")
    Map<String, Object> end(
            @PathVariable @Min(1) long id,
            @PathVariable String action,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @RequestBody @Valid InventoryInput.Reason input) {
        return service.end(actors.capture(), id, version, key, action, input);
    }
}
