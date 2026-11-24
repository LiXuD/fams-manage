package com.fams.contract.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fams.contract.model.ErrorResponse;

@RestControllerAdvice
public class ContractProbeErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ContractProbeErrorHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        LOG.warn("Contract probe request validation failed with 400: fields={}", fieldErrors.keySet());
        return badRequest(fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        LOG.warn("Contract probe request body unreadable, rejected with 400: cause={}",
                exception.getClass().getName());
        return badRequest(Map.of("_request", "Request body is malformed."));
    }

    private ResponseEntity<ErrorResponse> badRequest(Map<String, String> fieldErrors) {
        ErrorResponse response = new ErrorResponse()
                .code("CONTRACT_PROBE_INVALID")
                .message("Request validation failed.")
                .requestId(UUID.randomUUID().toString())
                .fieldErrors(fieldErrors);
        return ResponseEntity.badRequest().body(response);
    }
}
