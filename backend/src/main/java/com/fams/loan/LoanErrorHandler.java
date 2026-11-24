package com.fams.loan;

import com.fams.product.contract.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {
    LoanController.class, AssetReturnController.class, AssetReturnReassignmentController.class,
    AssetReturnUnavailabilityController.class
})
@Order(-100)
class LoanErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LoanErrorHandler.class);

    @ExceptionHandler(com.fams.asset.AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> assetFailure(com.fams.asset.AssetHandoverPort.Failure failure,
            HttpServletRequest request) {
        if (failure.status() >= 500) {
            LOG.error("Loan asset handover failed: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status(), failure);
        } else {
            LOG.warn("Loan asset handover rejected: path={} code={} status={}", request.getRequestURI(),
                    failure.getMessage(), failure.status());
        }
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), java.util.UUID.fromString(requestId(request)), Map.of()));
    }
    @ExceptionHandler(LoanFailure.class)
    ResponseEntity<ErrorResponse> failure(LoanFailure failure, HttpServletRequest request) {
        LOG.warn("Loan request rejected: path={} code={} status={}", request.getRequestURI(),
                failure.getMessage(), failure.status());
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), java.util.UUID.fromString(requestId(request)), Map.of()));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(HttpServletRequest request) {
        LOG.warn("Loan write conflict: path={}", request.getRequestURI());
        return ResponseEntity.status(409).body(new ErrorResponse("LOAN_WRITE_CONFLICT", "LOAN_WRITE_CONFLICT",
                java.util.UUID.fromString(requestId(request)), Map.of()));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        return (value instanceof java.util.UUID id ? id : java.util.UUID.randomUUID()).toString();
    }
}
