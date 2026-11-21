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
    com.fams.asset.AssetLossPort.EligiblePage eligible(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reports.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping
    LossViews.Page<LossViews.LossReport> list(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reports.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/recovery-locations")
    com.fams.asset.AssetLossPort.RecoveryLocationPage recoveryLocations(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "100") @Positive @Max(100) int pageSize) {
        return reports.recoveryLocations(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    LossViews.LossDetail get(@PathVariable @Positive long id) {
        return reports.get(actors.capture(), id);
    }

    @PostMapping
    LossViews.LossReport submit(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Submission input) {
        var actor = actors.capture();
        return retry(() -> reports.submit(actor, key, input));
    }

    @PostMapping("/factory/{repairId}/rounds/{roundId}")
    LossViews.LossReport submitFactory(
            @PathVariable @Positive long repairId,
            @PathVariable @Positive long roundId,
            @RequestHeader("If-Match-Version") @PositiveOrZero long repairVersion,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.FactorySubmission input) {
        var actor = actors.capture();
        return retry(() -> reports.submitFactory(actor, repairId, roundId, repairVersion, key, input));
    }

    @PostMapping("/internal-repairs/{repairId}")
    LossViews.LossReport submitInternalRepair(@PathVariable @Positive long repairId,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.InternalRepairSubmission input) {
        var actor = actors.capture();
        return retry(() -> reports.submitInternalRepair(actor, repairId, version, key, input));
    }

    @PostMapping("/{id}/decision")
    LossViews.LossReport decide(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Decision input) {
        var actor = actors.capture();
        return retry(() -> reports.decide(actor, id, version, key, input));
    }

    @PostMapping("/{id}/cancel")
    LossViews.LossReport cancel(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> reports.cancel(actor, id, version, key, input));
    }

    @PostMapping("/{id}/claim")
    LossViews.LossReport claim(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key) {
        var actor = actors.capture();
        return retry(() -> reports.claim(actor, id, version, key));
    }

    @PostMapping("/{id}/search")
    LossViews.LossReport search(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Search input) {
        var actor = actors.capture();
        return retry(() -> reports.search(actor, id, version, key, input));
    }

    @GetMapping("/follow-ups")
    LossViews.Page<LossViews.LossReport> followUps(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reports.followUps(actors.capture(), page, pageSize);
    }

    @PostMapping("/{id}/confirm-lost")
    LossViews.LossReport confirmLost(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Confirmation input) {
        var actor = actors.capture();
        return retry(() -> reports.confirmLost(actor, id, version, key, input));
    }

    @PostMapping("/{id}/follow-up")
    LossViews.LossReport followUp(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.FollowUp input) {
        var actor = actors.capture();
        return retry(() -> reports.followUp(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-original")
    LossViews.LossReport recoverOriginal(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverSearching(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-repair")
    LossViews.LossReport recoverToRepair(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RepairRecovery input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToRepair(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recovery-requests")
    LossViews.LossReportWithRecovery requestRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestRecovery(actor, id, version, key, input));
    }

    @PostMapping("/{id}/repair-recovery-requests")
    LossViews.LossReportWithRecovery requestRepairRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.DamagedRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestRepairRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-repair")
    LossViews.LossReportWithRecovery executeRecoveryToRepair(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ClosedRepairRecovery input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToRepair(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/decision")
    LossViews.LossReportWithRecovery decideRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Decision input) {
        var actor = actors.capture();
        return retry(() -> reports.decideRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/cancel")
    LossViews.LossReportWithRecovery cancelRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> reports.cancelRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/claim")
    LossViews.LossReportWithRecovery claimRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key) {
        var actor = actors.capture();
        return retry(() -> reports.claimRecovery(actor, id, version, key));
    }

    @PostMapping("/recoveries/{id}/execute")
    LossViews.LossReportWithRecovery executeRecovery(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.RecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecovery(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-return")
    LossViews.LossReport recoverToReturn(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToReturn(actor, id, version, key, input));
    }

    @PostMapping("/{id}/return-recovery-requests")
    LossViews.LossReportWithRecovery requestReturnRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ReturnRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestReturnRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-return")
    LossViews.LossReportWithRecovery executeRecoveryToReturn(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToReturn(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-transfer")
    LossViews.LossReport recoverToTransfer(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.TransferRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToTransfer(actor, id, version, key, input));
    }

    @PostMapping("/{id}/transfer-recovery-requests")
    LossViews.LossReportWithRecovery requestTransferRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ReturnRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestTransferRecovery(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-transfer")
    LossViews.LossReportWithRecovery executeRecoveryToTransfer(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.TransferRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToTransfer(actor, id, version, key, input));
    }

    @PostMapping("/{id}/factory-recovery-requests")
    LossViews.LossReportWithRecovery requestFactoryRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ReturnRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestFactoryRecovery(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-factory")
    LossViews.LossReport recoverToFactory(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToFactory(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-factory")
    LossViews.LossReportWithRecovery executeRecoveryToFactory(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToFactory(actor, id, version, key, input));
    }

    @PostMapping("/{id}/internal-repair-recovery-requests")
    LossViews.LossReportWithRecovery requestInternalRepairRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ReturnRecoveryRequest input) {
        var actor = actors.capture();
        return retry(() -> reports.requestInternalRepairRecovery(actor, id, version, key, input));
    }

    @PostMapping("/{id}/recover-to-internal-repair")
    LossViews.LossReport recoverToInternalRepair(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.recoverToInternalRepair(actor, id, version, key, input));
    }

    @PostMapping("/recoveries/{id}/execute-to-internal-repair")
    LossViews.LossReportWithRecovery executeRecoveryToInternalRepair(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody LossInput.ExplicitRecoveryExecution input) {
        var actor = actors.capture();
        return retry(() -> reports.executeRecoveryToInternalRepair(actor, id, version, key, input));
    }

    private <T> T retry(Supplier<T> operation) {
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
