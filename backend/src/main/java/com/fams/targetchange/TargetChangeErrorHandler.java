package com.fams.targetchange;

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
@Order(-2)
@RestControllerAdvice(basePackages = "com.fams.targetchange")
class TargetChangeErrorHandler {
    @ExceptionHandler(TargetChangeFailure.class)
    ResponseEntity<ErrorResponse> failure(TargetChangeFailure error, HttpServletRequest request) {
        return response(error.status(), error.getMessage(), request);
    }
    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException error, HttpServletRequest request) {
        return response(409, "TARGET_CHANGE_WRITE_CONFLICT", request);
    }
    private ResponseEntity<ErrorResponse> response(int status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, code,
                request.getAttribute("fams.request.id") instanceof UUID id ? id : UUID.randomUUID(), Map.of()));
    }
}
