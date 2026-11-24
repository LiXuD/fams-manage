package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST surface of the scrap domain, mounted on both the operator prefix and the WeChat mini program
 * prefix {@code /api/v1/mini}. Handlers stay deliberately thin: each one captures the actor through
 * {@link RequisitionActorContext} and delegates to {@link ScrapReadService} for queries,
 * {@link ScrapService} for submission, or {@link ScrapReviewService} for the decision, claim, execute,
 * correction, reversal, recovery and disposition steps. No authorisation or state rule lives here.
 *
 * <p>Every mutating handler requires the {@code Idempotency-Key} header and, where it acts on an
 * already persisted row, the {@code If-Match-Version} header; both are passed through untouched so the
 * services own the receipt replay and the optimistic-concurrency comparison. Paging parameters are
 * constrained at the boundary: {@code page} is positive up to 1000000 and {@code pageSize} positive up
 * to 100.
 *
 * <p>The handlers at the bottom of the class are local {@code @ExceptionHandler}s. They exist because
 * scrap execution reaches into the repair, loan and loss domains, whose own refusals must be reported
 * with their own codes rather than being flattened into a scrap error. The port failure types keep the
 * status they carry, while {@code LoanRepairPort.Failure} and {@code LoanLossPort.Failure} are pinned
 * to 409 because a loan-side refusal during scrap execution is always a conflict with the live loan.
 */
@Profile("fams-product")
@RestController
@RequestMapping({"/api/v1/scrap-requests", "/api/v1/mini/scrap-requests"})
public class ScrapController {
    private final ScrapService scrap;
    private final ScrapReadService reads;
    private final ScrapReviewService review;
    private final RequisitionActorContext actors;

    /**
     * Wires the three scrap services and the actor context. The field order differs from the parameter
     * order because {@code review} is declared before {@code reads} in the signature; assignment is by
     * name, so the mapping stays {@code reads} to the read service and {@code review} to the review
     * service.
     *
     * @param scrap submission service owning creation of standalone, loss, repair and factory requests
     * @param review review service owning decision, claim, execution, correction, reversal and recovery
     * @param reads read service owning list, overdue disposition, eligible, detail and catalog queries
     * @param actors request-scoped actor context used to capture the caller for every handler
     */
    public ScrapController(ScrapService scrap, ScrapReviewService review, ScrapReadService reads, RequisitionActorContext actors) {
        this.scrap = scrap;
        this.reads = reads;
        this.review = review;
        this.actors = actors;
    }

    @GetMapping
    ScrapViews.RequestPage list(@RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reads.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/dispositions/overdue")
    ScrapViews.RequestPage overdueDispositions(@RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reads.overdueDispositions(actors.capture(), page, pageSize);
    }

    @GetMapping("/eligible")
    AssetScrapPort.EligiblePage eligible(@RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reads.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    ScrapViews.Request get(@PathVariable @Positive long id) {
        return reads.get(actors.capture(), id);
    }

    @GetMapping("/{id}/execution-catalog/{kind}")
    ScrapViews.ChoicePage catalog(@PathVariable @Positive long id, @PathVariable String kind,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "100") @Positive @Max(100) int pageSize) {
        return reads.catalog(actors.capture(), id, kind, page, pageSize);
    }

    @PostMapping
    ScrapService.Receipt submit(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapService.Submission input) {
        return scrap.submit(actors.capture(), key, input);
    }

    @PostMapping("/from-loss/{reportId}")
    ScrapService.Receipt submitFromLoss(@PathVariable @Positive long reportId,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapService.LossSubmission input) {
        return scrap.submitFromLoss(actors.capture(), reportId, version, key, input);
    }

    @PostMapping("/from-repair/{repairId}")
    ScrapService.Receipt submitFromRepair(@PathVariable @Positive long repairId,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapService.RepairSubmission input) {
        return scrap.submitFromRepair(actors.capture(), repairId, version, key, input);
    }

    @PostMapping("/from-factory/{repairId}/rounds/{roundId}/replacements/{replacementId}")
    ScrapService.Receipt submitFromFactory(@PathVariable @Positive long repairId, @PathVariable @Positive long roundId,
            @PathVariable @Positive long replacementId,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody FactorySubmission input) {
        return scrap.submitFromFactory(actors.capture(), repairId, roundId, replacementId, version,
                input.roundVersion(), key, input.scrap());
    }

    record FactorySubmission(@jakarta.validation.constraints.NotNull @PositiveOrZero Long roundVersion,
            @jakarta.validation.constraints.NotNull @Valid ScrapService.RepairSubmission scrap) {}

    @PostMapping("/{id}/execute-factory")
    ScrapReviewService.Receipt executeFactory(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.FactoryExecution input) {
        return review.executeFactory(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/cancel")
    ScrapReviewService.Receipt cancel(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.Cancellation input) {
        return review.cancel(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/corrections")
    ScrapReviewService.Receipt requestCorrection(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.CorrectionRequest input) {
        return review.requestCorrection(actors.capture(), id, version, key, input);
    }

    @PostMapping("/corrections/{id}/decision")
    ScrapReviewService.Receipt decideCorrection(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.CorrectionDecision input) {
        return review.decideCorrection(actors.capture(), id, version, key, input);
    }

    @PostMapping("/corrections/{id}/claim")
    ScrapReviewService.Receipt claimCorrection(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.CorrectionClaim input) {
        return review.claimCorrection(actors.capture(), id, version, key, input);
    }

    @PostMapping("/corrections/{id}/execute")
    ScrapReviewService.Receipt executeCorrection(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.CorrectionExecution input) {
        return review.executeCorrection(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/reversals")
    ScrapReviewService.Receipt requestReversal(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.ReversalRequest input) {
        return review.requestReversal(actors.capture(), id, version, key, input);
    }

    @PostMapping("/reversals/{id}/decision")
    ScrapReviewService.Receipt decideReversal(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.ReversalDecision input) {
        return review.decideReversal(actors.capture(), id, version, key, input);
    }

    @PostMapping("/reversals/{id}/claim")
    ScrapReviewService.Receipt claimReversal(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.ReversalClaim input) {
        return review.claimReversal(actors.capture(), id, version, key, input);
    }

    @PostMapping("/reversals/{id}/execute")
    ScrapReviewService.Receipt executeReversal(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.ReversalExecution input) {
        return review.executeReversal(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/recoveries")
    ScrapReviewService.Receipt requestRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.RecoveryRequest input) {
        return review.requestRecovery(actors.capture(), id, version, key, input);
    }

    @PostMapping("/recoveries/{id}/decision")
    ScrapReviewService.Receipt decideRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.RecoveryDecision input) {
        return review.decideRecovery(actors.capture(), id, version, key, input);
    }

    @PostMapping("/recoveries/{id}/claim")
    ScrapReviewService.Receipt claimRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.RecoveryClaim input) {
        return review.claimRecovery(actors.capture(), id, version, key, input);
    }

    @PostMapping("/recoveries/{id}/execute")
    ScrapReviewService.Receipt executeRecovery(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.RecoveryExecution input) {
        return review.executeRecovery(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/decision")
    ScrapReviewService.Receipt decide(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.Decision input) {
        return review.decide(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/claim")
    ScrapReviewService.Receipt claim(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.Claim input) {
        return review.claim(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/execute")
    ScrapReviewService.Receipt execute(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.Execution input) {
        return review.execute(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/execute-loss")
    ScrapReviewService.Receipt executeLoss(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.LossExecution input) {
        return review.executeLoss(actors.capture(), id, version, key, input);
    }

    @PostMapping("/{id}/disposition/complete")
    ScrapReviewService.DispositionReceipt completeDisposition(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.DispositionCompletion input) {
        return review.completeDisposition(actors.capture(), id, version, key, input);
    }

    @ExceptionHandler(com.fams.repair.RepairFailure.class)
    ResponseEntity<ErrorResponse> repairRejection(com.fams.repair.RepairFailure failure, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }

    @ExceptionHandler(com.fams.loan.LoanRepairPort.Failure.class)
    ResponseEntity<ErrorResponse> repairLoanRejection(com.fams.loan.LoanRepairPort.Failure failure, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(409).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }

    @ExceptionHandler(com.fams.loss.LossFailure.class)
    ResponseEntity<ErrorResponse> lossRejection(com.fams.loss.LossFailure failure, HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }

    @ExceptionHandler(com.fams.loan.LoanLossPort.Failure.class)
    ResponseEntity<ErrorResponse> loanRejection(com.fams.loan.LoanLossPort.Failure failure,
            HttpServletRequest request) {
        UUID id = request.getAttribute("fams.request.id") instanceof UUID value ? value : UUID.randomUUID();
        return ResponseEntity.status(409).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), id, Map.of()));
    }

    @ExceptionHandler(AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> rejection(AssetHandoverPort.Failure failure, HttpServletRequest request) {
        UUID requestId = request.getAttribute("fams.request.id") instanceof UUID id ? id : UUID.randomUUID();
        String code = failure.getMessage();
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(code, code, requestId, Map.of()));
    }

    @ExceptionHandler({
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> invalidRequest(HttpServletRequest request) {
        UUID requestId = request.getAttribute("fams.request.id") instanceof UUID id ? id : UUID.randomUUID();
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST",
                "Request validation failed.", requestId, Map.of()));
    }
}
