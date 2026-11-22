package com.fams.repair;

import com.fams.auth.RequisitionActorContext;
import com.fams.loan.LoanViews;

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
@RequestMapping({"/api/v1/repairs", "/api/v1/mini/repairs"})
public class RepairController {
    private final RepairService repairs;
    private final RequisitionActorContext actors;

    public RepairController(RepairService repairs, RequisitionActorContext actors) {
        this.repairs = repairs;
        this.actors = actors;
    }

    @GetMapping("/eligible")
    com.fams.asset.AssetRepairPort.EligiblePage eligible(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return repairs.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping("/return-sources")
    LoanViews.Page<LoanViews.RepairFollowupSource> returnSources(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return repairs.returnSources(actors.capture(), page, pageSize);
    }

    @GetMapping("/return-sources/{id}")
    RepairViews.ReturnSourceDetail returnSource(@PathVariable @Positive long id) {
        return repairs.returnSource(actors.capture(), id);
    }

    @PostMapping("/return-sources/{id}")
    RepairViews.RepairSummary submitReturned(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.ReturnSourceSubmission input) {
        var actor = actors.capture();
        return retry(() -> repairs.submitReturned(actor, id, version, key, input));
    }

    @GetMapping
    RepairViews.Page<RepairViews.RepairSummary> list(
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return repairs.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    RepairViews.RepairDetail get(@PathVariable @Positive long id) {
        return repairs.getDetail(actors.capture(), id);
    }

    @PostMapping
    RepairViews.RepairSummary submit(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Submission input) {
        var actor = actors.capture();
        return retry(() -> repairs.submitSummary(actor, key, input));
    }

    @PostMapping("/{id}/decision")
    RepairViews.RepairSummary decide(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Decision input) {
        var actor = actors.capture();
        return retry(() -> repairs.decide(actor, id, version, key, input));
    }

    @PostMapping("/{id}/cancel")
    RepairViews.RepairSummary cancel(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Reason input) {
        var actor = actors.capture();
        return retry(() -> repairs.cancel(actor, id, version, key, input));
    }

    @PostMapping("/{id}/claim")
    RepairViews.RepairSummary claim(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key) {
        var actor = actors.capture();
        return retry(() -> repairs.claim(actor, id, version, key));
    }

    @PostMapping("/{id}/accept-in-place")
    RepairViews.RepairSummary accept(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.InPlace input) {
        var actor = actors.capture();
        return retry(() -> repairs.acceptInPlace(actor, id, version, key, input));
    }

    @PostMapping("/{id}/complete-in-place")
    RepairViews.RepairSummary complete(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.InPlace input) {
        var actor = actors.capture();
        return retry(() -> repairs.completeInPlace(actor, id, version, key, input));
    }

    @GetMapping("/physical-catalog/{kind}")
    com.fams.asset.AssetRepairPort.PhysicalPage physicalCatalog(
            @PathVariable String kind,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return repairs.physicalCatalog(actors.capture(), kind, page, pageSize);
    }

    @PostMapping("/{id}/accept-away")
    RepairViews.RepairSummary acceptAway(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.AwayAcceptance input) {
        var actor = actors.capture();
        return retry(() -> repairs.acceptAway(actor, id, version, key, input));
    }

    @PostMapping("/{id}/close-inbound")
    RepairViews.RepairSummary closeInbound(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.Inbound input) {
        var actor = actors.capture();
        return retry(() -> repairs.closeInbound(actor, id, version, key, input));
    }

    @GetMapping("/{id}/return-target")
    com.fams.asset.AssetRepairPort.ReturnTarget returnTarget(@PathVariable @Positive long id) {
        return repairs.returnTarget(actors.capture(), id);
    }

    @PostMapping("/{id}/return-delivery")
    RepairViews.RepairSummary deliverReturn(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.ReturnDelivery input) {
        var actor = actors.capture();
        return retry(() -> repairs.deliverReturn(actor, id, version, key, input));
    }

    @PostMapping("/{id}/return-receipt")
    RepairViews.RepairSummary receiveReturn(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.ReturnReceipt input) {
        var actor = actors.capture();
        return retry(() -> repairs.receiveReturn(actor, id, version, key, input, false));
    }

    @PostMapping("/{id}/return-refusal")
    RepairViews.RepairSummary refuseReturn(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.ReturnReceipt input) {
        var actor = actors.capture();
        return retry(() -> repairs.receiveReturn(actor, id, version, key, input, true));
    }

    @PostMapping("/{id}/return-recollection")
    RepairViews.RepairSummary recollectReturn(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RepairInput.ReturnRecollection input) {
        var actor = actors.capture();
        return retry(() -> repairs.recollectReturn(actor, id, version, key, input));
    }

    private <T> T retry(Supplier<T> operation) {
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
