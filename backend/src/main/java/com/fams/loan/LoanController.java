package com.fams.loan;

import com.fams.auth.RequisitionActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.time.LocalDate;
import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping({"/api/v1/loans", "/api/v1/mini/loans"})
class LoanController {
    record Extension(@NotNull LocalDate expectedReturnDate, @NotBlank @Size(max = 1000) String reason) {}
    record Decision(@NotNull Boolean approved, @NotBlank @Size(max = 1000) String reason) {}

    private final LoanService service;
    private final RequisitionActorContext actors;
    LoanController(LoanService service, RequisitionActorContext actors) { this.service = service;
        this.actors = actors; }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/borrow-requests")
    Map<String, Object> eligibleRequests(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.eligibleRequests(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable long id) { return service.get(actors.capture(), id); }

    @GetMapping("/extensions")
    Map<String, Object> extensions(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize) {
        return service.extensions(actors.capture(), page, pageSize);
    }

    @PostMapping("/{id}/extensions")
    Map<String, Object> propose(@PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Extension input) {
        return service.propose(actors.capture(), id, false, version, key, input);
    }

    @PostMapping("/borrow-requests/{id}/extensions")
    Map<String, Object> proposeBeforeReceipt(@PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Extension input) {
        return service.propose(actors.capture(), id, true, version, key, input);
    }

    @PostMapping("/extensions/{id}/decision")
    Map<String, Object> decide(@PathVariable long id,
            @RequestHeader("If-Match-Version") @Min(0) long version,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
            @Valid @RequestBody Decision input) {
        return service.decide(actors.capture(), id, version, key, input);
    }
}
