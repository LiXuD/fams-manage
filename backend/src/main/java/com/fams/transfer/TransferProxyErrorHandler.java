package com.fams.transfer;

import com.fams.product.contract.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/** Translate transfer-domain refusal when its receipt port is called through the shared proxy API. */
@RestControllerAdvice(basePackages = "com.fams.requisition")
@Order(-2)
class TransferProxyErrorHandler {
    @ExceptionHandler(TransferFailure.class)
    ResponseEntity<ErrorResponse> failure(TransferFailure failure, HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        UUID id = value instanceof UUID existing ? existing : UUID.randomUUID();
        return ResponseEntity.status(failure.status()).body(
                new ErrorResponse(failure.getMessage(), failure.getMessage(), id, Map.of()));
    }
}
