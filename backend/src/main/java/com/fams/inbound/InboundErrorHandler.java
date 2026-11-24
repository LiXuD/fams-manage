package com.fams.inbound;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import com.fams.product.contract.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("fams-product")
@Order(0)
@RestControllerAdvice(basePackages = "com.fams.inbound")
class InboundErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(InboundErrorHandler.class);

    @ExceptionHandler(InboundFailure.class)
    ResponseEntity<ErrorResponse> failure(InboundFailure error, HttpServletRequest request) {
        LOG.warn("Inbound request rejected: path={}, code={}", request.getRequestURI(), error.code());
        return ResponseEntity.status(error.status()).body(new ErrorResponse(
                error.code(), error.getMessage(), requestId(request), Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException error,
            HttpServletRequest request) {
        LOG.warn("Inbound data integrity conflict: path={}, code={}", request.getRequestURI(),
                "INBOUND_WRITE_CONFLICT");
        return ResponseEntity.status(409).body(new ErrorResponse(
                "INBOUND_WRITE_CONFLICT", "Inbound data changed", requestId(request), Map.of()));
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<ErrorResponse> lockConflict(CannotAcquireLockException error,
            HttpServletRequest request) {
        LOG.warn("Inbound lock conflict: path={}, code={}", request.getRequestURI(),
                "INBOUND_WRITE_CONFLICT");
        return ResponseEntity.status(409).body(new ErrorResponse(
                "INBOUND_WRITE_CONFLICT", "Inbound data changed", requestId(request), Map.of()));
    }

    private UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
