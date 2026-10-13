package com.fams.requisition;

import com.fams.product.contract.model.ErrorResponse;
import com.fams.targetchange.TargetChangeFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@Order(-2)
@RestControllerAdvice(assignableTypes = ItemTargetChangeController.class)
class TargetChangeCompatibilityErrorHandler {
    @ExceptionHandler(TargetChangeFailure.class)
    ResponseEntity<ErrorResponse> failure(TargetChangeFailure error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(new ErrorResponse(error.getMessage(), error.getMessage(),
                request.getAttribute("fams.request.id") instanceof UUID id ? id : UUID.randomUUID(), Map.of()));
    }
}
