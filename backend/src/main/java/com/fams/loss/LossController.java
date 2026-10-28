package com.fams.loss;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
import java.util.function.Supplier;

@Profile("fams-product")
@Validated
@RestController
@RequestMapping({"/api/v1/loss-reports", "/api/v1/mini/loss-reports"})
public class LossController {
    private final LossService reports;
    private final RequisitionActorContext actors;

    public LossController(LossService reports, RequisitionActorContext actors) {
        this.reports = reports;
        this.actors = actors;
    }

    @GetMapping("/eligible")
    Map<String, Object> eligible(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reports.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reports.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable @Positive long id) {
        return reports.get(actors.capture(), id);
    }

    @PostMapping
    Map<String, Object> submit(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Submission input) {
        var actor = actors.capture();
        return retry(() -> reports.submit(actor, key, input));
    }

    @PostMapping("/{id}/decision")
    Map<String, Object> decide(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Decision input) {
        var actor = actors.capture();
        return retry(() -> reports.decide(actor, id, version, key, input));
    }

    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> reports.cancel(actor, id, version, key, input));
    }

    @PostMapping("/{id}/claim")
    Map<String, Object> claim(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key) {
        var actor = actors.capture();
        return retry(() -> reports.claim(actor, id, version, key));
    }

    @PostMapping("/{id}/search")
    Map<String, Object> search(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Search input) {
        var actor = actors.capture();
        return retry(() -> reports.search(actor, id, version, key, input));
    }

    private Map<String, Object> retry(Supplier<Map<String, Object>> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (org.springframework.dao.CannotAcquireLockException conflict) {
                if (attempt >= 1) {
                    throw conflict;
                }
            }
        }
    }
}
