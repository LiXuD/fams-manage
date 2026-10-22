package com.fams.repair;

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
@RequestMapping({"/api/v1/repairs", "/api/v1/mini/repairs"})
public class RepairController {
    private final RepairService repairs;
    private final RequisitionActorContext actors;

    public RepairController(RepairService repairs, RequisitionActorContext actors) {
        this.repairs = repairs;
        this.actors = actors;
    }

    @GetMapping("/eligible")
    Map<String, Object> eligible(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return repairs.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return repairs.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable @Positive long id) {
        return repairs.get(actors.capture(), id);
    }

    @PostMapping
    Map<String, Object> submit(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Submission input) {
        var actor = actors.capture();
        return retry(() -> repairs.submit(actor, key, input));
    }

    @PostMapping("/{id}/decision")
    Map<String, Object> decide(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Decision input) {
        var actor = actors.capture();
        return retry(() -> repairs.decide(actor, id, version, key, input));
    }

    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> repairs.cancel(actor, id, version, key, input));
    }

    @PostMapping("/{id}/claim")
    Map<String, Object> claim(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key) {
        var actor = actors.capture();
        return retry(() -> repairs.claim(actor, id, version, key));
    }

    @PostMapping("/{id}/accept-in-place")
    Map<String, Object> accept(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.InPlace input) {
        var actor = actors.capture();
        return retry(() -> repairs.acceptInPlace(actor, id, version, key, input));
    }

    @PostMapping("/{id}/complete-in-place")
    Map<String, Object> complete(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.InPlace input) {
        var actor = actors.capture();
        return retry(() -> repairs.completeInPlace(actor, id, version, key, input));
    }

    private Map<String, Object> retry(Supplier<Map<String, Object>> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (org.springframework.dao.CannotAcquireLockException transientConflict) {
                if (attempt >= 1) {
                    throw transientConflict;
                }
            }
        }
    }
}
