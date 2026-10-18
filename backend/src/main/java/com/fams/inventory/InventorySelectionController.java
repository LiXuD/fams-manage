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
class InventorySelectionController {
    private final InventorySelectionService selections;
    private final RequisitionActorContext actors;

    InventorySelectionController(
            InventorySelectionService selections, RequisitionActorContext actors) {
        this.selections = selections;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}/items/{item}/selection")
    Map<String, Object> detail(
            @PathVariable @Positive long task,
            @PathVariable @Positive long item,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return selections.detail(actors.capture(), task, item, page, pageSize);
    }

    @PostMapping("/tasks/{task}/items/{item}/selection")
    Map<String, Object> select(
            @PathVariable @Positive long task,
            @PathVariable @Positive long item,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventorySelectionInput input) {
        return selections.select(actors.capture(), task, item, version, key, input);
    }
}
