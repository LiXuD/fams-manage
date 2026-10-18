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
class InventoryReviewController {
    private final InventoryReviewService reviews;
    private final RequisitionActorContext actors;

    InventoryReviewController(InventoryReviewService reviews, RequisitionActorContext actors) {
        this.reviews = reviews;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}")
    Map<String, Object> detail(@PathVariable @Positive long task,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return reviews.detail(actors.capture(), task, page, pageSize);
    }

    @GetMapping("/tasks/{task}/items/{item}/observations")
    Map<String, Object> observations(@PathVariable @Positive long task, @PathVariable @Positive long item,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return reviews.observations(actors.capture(), task, item, page, pageSize);
    }

    @GetMapping("/tasks/{task}/findings")
    Map<String, Object> findings(@PathVariable @Positive long task,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return reviews.observations(actors.capture(), task, null, page, pageSize);
    }

    @PostMapping("/tasks/{task}/normal-acceptance")
    Map<String, Object> accept(@PathVariable @Positive long task,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long taskVersion,
            @Valid @RequestBody InventoryReviewInput.NormalBatch input) {
        return reviews.acceptNormal(actors.capture(), task, taskVersion, key, input);
    }
}
