package com.fams.requisition;

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

import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping("/api/v1/requisition-target-changes")
class ItemTargetChangeController {
    private final ItemTargetChangeService service;
    private final RequisitionActorContext actors;
    ItemTargetChangeController(ItemTargetChangeService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }
    @GetMapping("/context")
    Map<String, Object> context(@RequestParam @Min(1) long businessId, @RequestParam @Min(1) long itemId) {
        return service.context(actors.capture(), businessId, itemId);
    }
    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }
    @PostMapping
    Map<String, Object> propose(@RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody ItemTargetChangeInput.Proposal input) {
        return service.propose(actors.capture(), version, key, input);
    }
    @PostMapping("/{id}/decision")
    Map<String, Object> decide(@PathVariable long id, @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody ItemTargetChangeInput.Decision input) {
        return service.decide(actors.capture(), id, version, key, input);
    }
}
