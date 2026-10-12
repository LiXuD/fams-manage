package com.fams.loan;

import com.fams.product.contract.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {LoanController.class, AssetReturnController.class, AssetReturnReassignmentController.class})
@Order(-100)
class LoanErrorHandler {
    @ExceptionHandler(com.fams.asset.AssetHandoverPort.Failure.class)
    ResponseEntity<ErrorResponse> assetFailure(com.fams.asset.AssetHandoverPort.Failure failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(), failure.getMessage(),
                java.util.UUID.fromString(requestId(request)), Map.of()));
    }
    @ExceptionHandler(LoanFailure.class)
    ResponseEntity<ErrorResponse> failure(LoanFailure failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage(),
                failure.getMessage(), java.util.UUID.fromString(requestId(request)), Map.of()));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse("LOAN_WRITE_CONFLICT", "LOAN_WRITE_CONFLICT",
                java.util.UUID.fromString(requestId(request)), Map.of()));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        return (value instanceof java.util.UUID id ? id : java.util.UUID.randomUUID()).toString();
    }
}
