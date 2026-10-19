package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@Profile("fams-product")
@RequestMapping({"/api/v1/inventory-reviews", "/api/v1/mini/inventory-reviews"})
class InventoryFindingController {
    private final InventoryFindingService findings;
    private final RequisitionActorContext actors;

    InventoryFindingController(InventoryFindingService findings, RequisitionActorContext actors) {
        this.findings = findings;
        this.actors = actors;
    }

    @GetMapping("/tasks/{task}/finding-targets")
    Map<String, Object> targets(
            @PathVariable @Positive long task,
            @RequestParam @NotBlank @Size(max = 128) String assetCode,
            @RequestParam(required = false) @Size(max = 128) String receiptNumber) {
        return findings.targets(actors.capture(), task, assetCode, receiptNumber);
    }

    @GetMapping("/tasks/{task}/findings/{observation}")
    Map<String, Object> detail(
            @PathVariable @Positive long task, @PathVariable @Positive long observation) {
        return findings.detail(actors.capture(), task, observation);
    }

    @PostMapping("/tasks/{task}/findings/{observation}/decisions")
    Map<String, Object> decide(
            @PathVariable @Positive long task,
            @PathVariable @Positive long observation,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @Valid @RequestBody InventoryFindingInput.Decision input) {
        return findings.decide(actors.capture(), task, observation, version, key, input);
    }
}
