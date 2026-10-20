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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@Profile("fams-product")
class InventorySynchronizationController {
    private final InventorySynchronizationService synchronization;
    private final RequisitionActorContext actors;

    InventorySynchronizationController(
            InventorySynchronizationService synchronization, RequisitionActorContext actors) {
        this.synchronization = synchronization;
        this.actors = actors;
    }

    @PostMapping("/api/v1/inventory-synchronization/tasks/{task}/declarations")
    void denyPcDeclaration(@PathVariable @Positive long task) {
        throw new InventoryFailure(403, "INVENTORY_MINI_BINDING_REQUIRED");
    }

    @GetMapping({
        "/api/v1/inventory-synchronization/tasks/{task}",
        "/api/v1/mini/inventory-synchronization/tasks/{task}"
    })
    Map<String, Object> status(
            @PathVariable @Positive long task,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return synchronization.status(actors.capture(), task, page, pageSize);
    }

    @GetMapping("/api/v1/mini/inventory-synchronization/tasks/{task}/devices/{device}")
    Map<String, Object> own(@PathVariable @Positive long task, @PathVariable UUID device) {
        return synchronization.own(actors.capture(), task, device);
    }

    @PostMapping("/api/v1/mini/inventory-synchronization/tasks/{task}/declarations")
    Map<String, Object> declare(
            @PathVariable @Positive long task,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventorySynchronizationInput.Declaration input) {
        return synchronization.declare(actors.capture(), task, version, key, input);
    }
}
