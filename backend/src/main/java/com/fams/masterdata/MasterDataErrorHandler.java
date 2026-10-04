package com.fams.masterdata;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.model.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("fams-product")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.fams.masterdata")
final class MasterDataErrorHandler {

    @ExceptionHandler(MasterDataOperationException.class)
    ResponseEntity<ErrorResponse> handleOperation(
            MasterDataOperationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(
                exception.code(),
                exception.getMessage(),
                RequestIds.from(request),
                Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(
                "MASTER_DATA_WRITE_CONFLICT",
                "The write conflicts with current master data.",
                RequestIds.from(request),
                Map.of()));
    }
}
