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
@RequestMapping({"/api/v1/inventory-sealing", "/api/v1/mini/inventory-sealing"})
class InventorySealController {
    private final InventorySealService sealing;
    private final RequisitionActorContext actors;

    InventorySealController(InventorySealService sealing, RequisitionActorContext actors) {
        this.sealing = sealing;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}")
    Map<String, Object> preflight(@PathVariable @Positive long task) {
        return sealing.preflight(actors.capture(), task);
    }

    @PostMapping("/tasks/{task}/seal")
    Map<String, Object> seal(
            @PathVariable @Positive long task,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventorySealInput input) {
        var actor = actors.capture();
        for (int attempt = 0; ; attempt++) {
            try {
                return sealing.seal(actor, task, version, key, input);
            } catch (org.springframework.dao.CannotAcquireLockException transientConflict) {
                // The proxied service has rolled back. Reauthorize and retry the exact operation
                // once.
                if (attempt >= 1) {
                    throw transientConflict;
                }
            }
        }
    }

    @GetMapping("/tasks/{task}/report")
    Map<String, Object> report(@PathVariable @Positive long task) {
        return sealing.report(actors.capture(), task);
    }

    @GetMapping("/tasks/{task}/report/{section}")
    Map<String, Object> rows(
            @PathVariable @Positive long task,
            @PathVariable String section,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return sealing.reportRows(actors.capture(), task, section, page, pageSize);
    }
}
