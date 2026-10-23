package com.fams.requisition;

import com.fams.product.contract.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

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
    @ExceptionHandler(com.fams.proxy.ProxyReceiptFailure.class)
    ResponseEntity<ErrorResponse> proxyFailure(
            com.fams.proxy.ProxyReceiptFailure error, HttpServletRequest request) {
        return response(error.status(), error.getMessage(), request);
    }

    @ExceptionHandler(RequisitionFailure.class)
    ResponseEntity<ErrorResponse> failure(RequisitionFailure error, HttpServletRequest request) {
        return response(error.status(), error.code(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException error, HttpServletRequest request) {
        return response(409, "REQUISITION_WRITE_CONFLICT", request);
    }

    private ResponseEntity<ErrorResponse> response(
            int status, String code, HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        UUID id = value instanceof UUID uuid ? uuid : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, id, Map.of()));
    }
}
