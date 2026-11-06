package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
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

@Profile("fams-product")
@RestController
@RequestMapping({"/api/v1/scrap-requests", "/api/v1/mini/scrap-requests"})
public class ScrapController {
    private final ScrapService scrap;
    private final ScrapReadService reads;
    private final ScrapReviewService review;
    private final RequisitionActorContext actors;

    public ScrapController(ScrapService scrap, ScrapReviewService review, ScrapReadService reads, RequisitionActorContext actors) {
        this.scrap = scrap;
        this.reads = reads;
        this.review = review;
        this.actors = actors;
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reads.list(actors.capture(), page, pageSize);
    }

    @GetMapping("/dispositions/overdue")
    Map<String, Object> overdueDispositions(@RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reads.overdueDispositions(actors.capture(), page, pageSize);
    }

    @GetMapping("/eligible")
    Map<String, Object> eligible(@RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize) {
        return reads.eligible(actors.capture(), page, pageSize);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable @Positive long id) {
        return reads.get(actors.capture(), id);
    }

    @GetMapping("/{id}/execution-catalog/{kind}")
    Map<String, Object> catalog(@PathVariable @Positive long id, @PathVariable String kind,
            @RequestParam(defaultValue = "1") @Positive @Max(1000000) int page,
            @RequestParam(defaultValue = "100") @Positive @Max(100) int pageSize) {
        return reads.catalog(actors.capture(), id, kind, page, pageSize);
    }

    @PostMapping
    ScrapService.Receipt submit(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapService.Submission input) {
        return scrap.submit(actors.capture(), key, input);
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

    @PostMapping("/{id}/disposition/complete")
    ScrapReviewService.DispositionReceipt completeDisposition(@PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ScrapReviewService.DispositionCompletion input) {
        return review.completeDisposition(actors.capture(), id, version, key, input);
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
}
