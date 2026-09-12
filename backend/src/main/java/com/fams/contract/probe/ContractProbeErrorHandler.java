package com.fams.contract.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fams.contract.model.ErrorResponse;

@RestControllerAdvice
public class ContractProbeErrorHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return badRequest(fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
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
