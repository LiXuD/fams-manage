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
@RequestMapping({"/api/v1/inventory-late", "/api/v1/mini/inventory-late"})
class InventoryLateDamageController {
    private final InventoryLateDamageService damage;
    private final RequisitionActorContext actors;

    InventoryLateDamageController(
            InventoryLateDamageService damage, RequisitionActorContext actors) {
        this.damage = damage;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}/observations/{observation}/damage-repair")
    Map<String, Object> detail(
            @PathVariable @Positive long task,
            @PathVariable @Positive long observation,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return damage.detail(actors.capture(), task, observation, page, pageSize);
    }

    @PostMapping("/tasks/{task}/observations/{observation}/damage-repair")
    Map<String, Object> decide(
            @PathVariable @Positive long task,
            @PathVariable @Positive long observation,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventoryLateDamageInput input) {
        var actor = actors.capture();
        for (int attempt = 0; ; attempt++) {
            try {
                return damage.decide(actor, task, observation, version, key, input);
            } catch (org.springframework.dao.CannotAcquireLockException conflict) {
                if (attempt >= 1) {
                    throw conflict;
                }
            }
        }
    }
}
