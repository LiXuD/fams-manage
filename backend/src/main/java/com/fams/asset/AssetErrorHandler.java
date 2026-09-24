package com.fams.asset;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import com.fams.product.contract.model.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("fams-product")
@RestControllerAdvice(basePackages = "com.fams.asset")
class AssetErrorHandler {
    @ExceptionHandler(AssetOperationException.class)
    ResponseEntity<ErrorResponse> operation(AssetOperationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(
                exception.code(), exception.getMessage(), requestId(request), Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(
                "ASSET_WRITE_CONFLICT", "The asset write conflicts with current data.",
                requestId(request), Map.of()));
    }

    private UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
