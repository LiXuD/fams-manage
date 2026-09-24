package com.fams.inbound;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import com.fams.product.contract.model.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("fams-product")
@Order(0)
@RestControllerAdvice(basePackages = "com.fams.inbound")
class InboundErrorHandler {
    @ExceptionHandler(InboundFailure.class)
    ResponseEntity<ErrorResponse> failure(InboundFailure error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(new ErrorResponse(
                error.code(), error.getMessage(), requestId(request), Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException error,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(
                "INBOUND_WRITE_CONFLICT", "Inbound data changed", requestId(request), Map.of()));
    }

    private UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
