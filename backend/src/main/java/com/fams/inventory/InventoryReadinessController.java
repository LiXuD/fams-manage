package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

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

import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@Profile("fams-product")
@RequestMapping({"/api/v1/inventory-readiness", "/api/v1/mini/inventory-readiness"})
class InventoryReadinessController {
    private final InventoryReadinessService readiness;
    private final RequisitionActorContext actors;

    InventoryReadinessController(
            InventoryReadinessService readiness, RequisitionActorContext actors) {
        this.readiness = readiness;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}")
    Map<String, Object> detail(
            @PathVariable @Positive long task,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return readiness.detail(actors.capture(), task, page, pageSize);
    }

    @GetMapping("/tasks/{task}/items/{item}/unobserved-history")
    Map<String, Object> history(
            @PathVariable @Positive long task,
            @PathVariable @Positive long item,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return readiness.history(actors.capture(), task, item, page, pageSize);
    }

    @PostMapping("/tasks/{task}/items/{item}/unobserved")
    Map<String, Object> record(
            @PathVariable @Positive long task,
            @PathVariable @Positive long item,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventoryReadinessInput.Unobserved input) {
        return readiness.record(actors.capture(), task, item, version, key, input);
    }

    @PostMapping("/tasks/{task}/waiting-review")
    Map<String, Object> waiting(
            @PathVariable @Positive long task,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventoryReadinessInput.Waiting input) {
        return readiness.waiting(actors.capture(), task, version, key, input);
    }
}
