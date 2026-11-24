package com.fams.requisition;

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
@Order(0)
@RestControllerAdvice(basePackages = "com.fams.requisition")
class RequisitionErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RequisitionErrorHandler.class);

    @ExceptionHandler(com.fams.proxy.ProxyReceiptFailure.class)
    ResponseEntity<ErrorResponse> proxyFailure(
            com.fams.proxy.ProxyReceiptFailure error, HttpServletRequest request) {
        log(error.status(), error.getMessage(), request, error);
        return response(error.status(), error.getMessage(), request);
    }

    @ExceptionHandler(RequisitionFailure.class)
    ResponseEntity<ErrorResponse> failure(RequisitionFailure error, HttpServletRequest request) {
        log(error.status(), error.code(), request, error);
        return response(error.status(), error.code(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException error, HttpServletRequest request) {
        log(409, "REQUISITION_WRITE_CONFLICT", request, error);
        return response(409, "REQUISITION_WRITE_CONFLICT", request);
    }

    private void log(int status, String code, HttpServletRequest request, Throwable error) {
        if (status >= 500) {
            LOG.error("Requisition request failed: path={}, code={}", request.getRequestURI(), code, error);
        } else {
            LOG.warn("Requisition request rejected: path={}, code={}", request.getRequestURI(), code);
        }
    }

    private ResponseEntity<ErrorResponse> response(
            int status, String code, HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        UUID id = value instanceof UUID uuid ? uuid : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, Map.of()));
    }
}
