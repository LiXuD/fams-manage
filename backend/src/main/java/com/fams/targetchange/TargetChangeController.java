package com.fams.targetchange;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/v1/item-target-changes")
class TargetChangeController {
    private final TargetChangeWorkflow service;
    private final RequisitionActorContext actors;
    TargetChangeController(TargetChangeWorkflow service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }
    @GetMapping("/context")
    Map<String, Object> context(@RequestParam @Pattern(regexp = "REQUISITION|TRANSFER") String businessType,
            @RequestParam @Min(1) long businessId, @RequestParam @Min(1) long itemId) {
        return service.context(actors.capture(), businessType, businessId, itemId);
    }
    @GetMapping
    Map<String, Object> list(@RequestParam @Pattern(regexp = "REQUISITION|TRANSFER") String businessType,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.list(actors.capture(), businessType, page, pageSize);
    }
    @PostMapping
    Map<String, Object> propose(@RequestParam @Pattern(regexp = "REQUISITION|TRANSFER") String businessType,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody TargetChangeInput.Proposal input) {
        return service.propose(actors.capture(), businessType, version, key, input);
    }
    @PostMapping("/{id}/decision")
    Map<String, Object> decide(@RequestParam @Pattern(regexp = "REQUISITION|TRANSFER") String businessType,
            @PathVariable long id, @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody TargetChangeInput.Decision input) {
        return service.decide(actors.capture(), businessType, id, version, key, input);
    }
}
