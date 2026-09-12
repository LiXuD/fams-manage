package com.fams.runtime.compatibility;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("runtime-compatibility")
@RestControllerAdvice
final class RuntimeCompatibilityErrorHandler {

    @ExceptionHandler(RuntimeCompatibilityRejectedException.class)
    ResponseEntity<RuntimeCompatibilityController.RollbackResponse> handleRollback(
            RuntimeCompatibilityRejectedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new RuntimeCompatibilityController.RollbackResponse(
                        "RUNTIME_COMPATIBILITY_TRANSACTION_REJECTED",
                        exception.getMessage(),
                        exception.getProbeKey(),
                        Map.of()));
    }

    @ExceptionHandler(RuntimeSessionUnavailableException.class)
    ResponseEntity<RuntimeErrorResponse> handleSessionUnavailable(RuntimeSessionUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error("RUNTIME_COMPATIBILITY_SESSION_UNAVAILABLE", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<RuntimeErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return ResponseEntity.badRequest().body(error(
                "RUNTIME_COMPATIBILITY_INVALID_REQUEST", "Request validation failed.", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<RuntimeErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, String> fieldErrors = exception.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return ResponseEntity.badRequest().body(error(
                "RUNTIME_COMPATIBILITY_INVALID_REQUEST", "Request validation failed.", fieldErrors));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<RuntimeErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "RUNTIME_COMPATIBILITY_DUPLICATE_PROBE", "The probe key already exists.", Map.of()));
    }

    private RuntimeErrorResponse error(String code, String message, Map<String, String> fieldErrors) {
        return new RuntimeErrorResponse(code, message, UUID.randomUUID().toString(), fieldErrors);
    }

    record RuntimeErrorResponse(String code, String message, String requestId, Map<String, String> fieldErrors) {
    }
}
