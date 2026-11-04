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

    @GetMapping("/recovery-locations")
    Map<String, Object> recoveryLocations(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "100") @Positive @Max(100) int pageSize) {
        return reports.recoveryLocations(actors.capture(), page, pageSize);
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

    @GetMapping("/follow-ups")
    Map<String, Object> followUps(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reports.followUps(actors.capture(), page, pageSize);
    }

    @PostMapping("/{id}/confirm-lost")
    Map<String, Object> confirmLost(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Confirmation input) {
        var actor = actors.capture();
        return retry(() -> reports.confirmLost(actor, id, version, key, input));
    }

    @PostMapping("/{id}/follow-up")
    Map<String, Object> followUp(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.FollowUp input) {
        var actor = actors.capture();
        return retry(() -> reports.followUp(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-original")
    Map<String, Object> recoverOriginal(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverSearching(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-repair")
    Map<String, Object> recoverToRepair(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RepairRecovery input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToRepair(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recovery-requests")
    Map<String, Object> requestRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestRecovery(actor, id, version, key, input));
    }

    @PostMapping("/{id}/repair-recovery-requests")
    Map<String, Object> requestRepairRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.DamagedRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestRepairRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-repair")
    Map<String, Object> executeRecoveryToRepair(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ClosedRepairRecovery input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToRepair(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/decision")
    Map<String, Object> decideRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Decision input) {
        var actor = actors.capture();
        return retry(() -> reports.decideRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/cancel")
    Map<String, Object> cancelRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> reports.cancelRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/claim")
    Map<String, Object> claimRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key) {
        var actor = actors.capture();
        return retry(() -> reports.claimRecovery(actor, id, version, key));
    }

    @PostMapping("/recoveries/{id}/execute")
    Map<String, Object> executeRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecovery(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-return")
    Map<String, Object> recoverToReturn(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToReturn(actor, id, version, key, input));
    }

    @PostMapping("/{id}/return-recovery-requests")
    Map<String, Object> requestReturnRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ReturnRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestReturnRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-return")
    Map<String, Object> executeRecoveryToReturn(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToReturn(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-transfer")
    Map<String, Object> recoverToTransfer(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.TransferRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToTransfer(actor, id, version, key, input));
    }

    @PostMapping("/{id}/transfer-recovery-requests")
    Map<String, Object> requestTransferRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ReturnRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestTransferRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-transfer")
    Map<String, Object> executeRecoveryToTransfer(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.TransferRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToTransfer(actor, id, version, key, input));
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
