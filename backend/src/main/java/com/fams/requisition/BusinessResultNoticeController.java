package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Results belong to the responsible employee; reading cannot alter asset responsibility. */
@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/business-result-notices", "/api/v1/mini/business-result-notices"})
class BusinessResultNoticeController {
    private final BusinessResultNoticeService service;
    private final RequisitionActorContext actors;

    BusinessResultNoticeController(BusinessResultNoticeService service, RequisitionActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/review-requests")
    Map<String, Object> reviewRequests(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return service.reviewRequests(actors.capture(), page, pageSize);
    }

    @PostMapping("/{id}/read")
    Map<String, Object> read(@PathVariable @Min(1) long id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key) {
        return service.read(actors.capture(), id);
    }
    record Objection(@NotBlank @Size(max = 1000) String reason) {}

    @PostMapping("/{id}/objection")
    Map<String, Object> objection(@PathVariable @Min(1) long id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Objection input) {
        return service.objection(actors.capture(), id, key, input.reason());
    }

}
