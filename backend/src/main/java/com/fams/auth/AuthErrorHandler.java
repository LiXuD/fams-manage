package com.fams.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.model.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("fams-product")
@RestControllerAdvice
final class AuthErrorHandler {

    private final AuthApplicationService authService;

    AuthErrorHandler(AuthApplicationService authService) {
        this.authService = authService;
    }

    @ExceptionHandler(AuthOperationException.class)
    ResponseEntity<ErrorResponse> handleAuthOperation(
            AuthOperationException exception,
            HttpServletRequest request) {
        if ("AUTH_FORBIDDEN".equals(exception.code())) {
            Object currentAccount = request.getAttribute(AuthRequestContext.ACCOUNT_ATTRIBUTE);
            Long accountId = currentAccount instanceof AuthAccount account ? account.id() : null;
            authService.audit(
                    accountId,
                    SecurityEventType.AUTHORIZATION_REJECTED,
                    AuthRequestContext.requestId(request),
                    exception.code());
        }
        return ResponseEntity.status(exception.status()).body(error(
                exception.code(),
                exception.getMessage(),
                AuthRequestContext.requestId(request),
                Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fieldError -> fieldError.getField(),
                        fieldError -> fieldError.getDefaultMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return ResponseEntity.badRequest().body(error(
                "INVALID_REQUEST",
                "Request validation failed.",
                AuthRequestContext.requestId(request),
                fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = exception.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return ResponseEntity.badRequest().body(error(
                "INVALID_REQUEST",
                "Request validation failed.",
                AuthRequestContext.requestId(request),
                fieldErrors));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleWriteConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "AUTH_WRITE_CONFLICT",
                "The account or authorization write conflicted with current state.",
                AuthRequestContext.requestId(request),
                Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(
                "INTERNAL_ERROR",
                "The request could not be completed.",
                AuthRequestContext.requestId(request),
                Map.of()));
    }

    private ErrorResponse error(
            String code,
            String message,
            UUID requestId,
            Map<String, String> fieldErrors) {
        return new ErrorResponse(code, message, requestId, fieldErrors);
    }
}
