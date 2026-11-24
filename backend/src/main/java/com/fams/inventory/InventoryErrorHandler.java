package com.fams.inventory;

import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@Order(-2)
@RestControllerAdvice(basePackages = "com.fams.inventory")
class InventoryErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryErrorHandler.class);

    @ExceptionHandler(InventoryFailure.class)
    ResponseEntity<ErrorResponse> failure(InventoryFailure error, HttpServletRequest request) {
        log(error.status(), error.getMessage(), request, error);
        return response(error.status(), error.getMessage(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException error, HttpServletRequest request) {
        log(409, "INVENTORY_WRITE_CONFLICT", request, error);
        return response(409, "INVENTORY_WRITE_CONFLICT", request);
    }

    private void log(int status, String code, HttpServletRequest request, Throwable error) {
        if (status >= 500) {
            LOG.error("Inventory request failed: path={}, code={}", request.getRequestURI(), code, error);
        } else {
            LOG.warn("Inventory request rejected: path={}, code={}", request.getRequestURI(), code);
        }
    }

    private ResponseEntity<ErrorResponse> response(
            int status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                code,
                                code,
                                request.getAttribute("fams.request.id") instanceof UUID id
                                        ? id
                                        : UUID.randomUUID(),
                                Map.of()));
    }
}
