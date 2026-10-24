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
@RequestMapping({"/api/v1/inventory-reviews", "/api/v1/mini/inventory-reviews"})
class InventoryDifferenceController {
    private final InventoryDifferenceService differences;
    private final RequisitionActorContext actors;

    InventoryDifferenceController(
            InventoryDifferenceService differences, RequisitionActorContext actors) {
        this.differences = differences;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}/differences")
    Map<String, Object> list(
            @PathVariable @Positive long task,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return differences.list(actors.capture(), task, page, pageSize);
    }

    @PostMapping("/tasks/{task}/differences/preparation")
    Map<String, Object> prepare(
            @PathVariable @Positive long task,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventoryDifferenceInput.Prepare input) {
        return differences.prepare(actors.capture(), task, version, key, input);
    }

    @GetMapping("/tasks/{task}/differences/{observation}/repair-context")
    Map<String, Object> repairContext(
            @PathVariable @Positive long task, @PathVariable @Positive long observation) {
        return differences.repairContext(actors.capture(), task, observation);
    }

    @PostMapping("/tasks/{task}/differences/decisions")
    Map<String, Object> decide(
            @PathVariable @Positive long task,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventoryDifferenceInput.Decision input) {
        return differences.decide(actors.capture(), task, version, key, input);
    }
}
