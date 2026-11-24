package com.fams.assetimport;

import com.fams.product.contract.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Profile("fams-product")
@RestControllerAdvice(basePackages = "com.fams.assetimport")
class AssetImportErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AssetImportErrorHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure, HttpServletRequest request) {
        String code = known(failure.getMessage()) ? failure.getMessage() : "ASSET_IMPORT_REQUEST_INVALID";
        LOG.warn("Asset import request rejected: path={} code={}", request.getRequestURI(), code);
        return response(400, code, request);
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> unreadableUpload(IOException failure, HttpServletRequest request) {
        LOG.warn("Asset import upload rejected as invalid xlsx: path={}", request.getRequestURI());
        return response(400, "ASSET_IMPORT_INVALID_XLSX", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> conflict(IllegalStateException failure, HttpServletRequest request) {
        String code = failure.getMessage();
        if ("ASSET_IMPORT_NOT_FOUND".equals(code)
                || "ASSET_IMPORT_WINDOW_NOT_FOUND".equals(code)
                || "ASSET_IMPORT_REVIEW_NOT_FOUND".equals(code)
                || "ASSET_IMPORT_ROLLBACK_NOT_FOUND".equals(code)) {
            LOG.warn("Asset import subject not found: path={} code={}", request.getRequestURI(), code);
            return response(404, code, request);
        }
        if ("ASSET_IMPORT_UPLOAD_CONFLICT".equals(code) || "ASSET_IMPORT_LOCKED".equals(code)
                || "ASSET_IMPORT_VERSION_CONFLICT".equals(code)
                || "ASSET_IMPORT_WINDOW_OPEN".equals(code)
                || "ASSET_IMPORT_WINDOW_CLOSED".equals(code)
                || "ASSET_IMPORT_NOT_READY".equals(code)
                || "ASSET_IMPORT_REVIEW_LOCKED".equals(code)
                || "ASSET_IMPORT_ROLLBACK_NOT_PUBLISHED".equals(code)
                || "ASSET_IMPORT_ROLLBACK_LOCKED".equals(code)
                || "ASSET_IMPORT_ROLLBACK_DOWNSTREAM".equals(code)) {
            LOG.warn("Asset import state conflict: path={} code={}", request.getRequestURI(), code);
            return response(409, code, request);
        }
        LOG.error("Asset import failed with an unexpected state: path={} code={}",
                request.getRequestURI(), code, failure);
        return response(500, "ASSET_IMPORT_INTERNAL", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(HttpServletRequest request) {
        LOG.warn("Asset import write conflict: path={}", request.getRequestURI());
        return response(409, "ASSET_IMPORT_WRITE_CONFLICT", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(HttpServletRequest request) {
        LOG.warn("Asset import upload exceeded the size limit: path={}", request.getRequestURI());
        return response(413, "ASSET_IMPORT_FILE_LIMIT", request);
    }

    private boolean known(String code) {
        return code != null && code.matches("ASSET_IMPORT_[A-Z_]+");
    }

    private ResponseEntity<ErrorResponse> response(int status, String code, HttpServletRequest request) {
        Object value = request.getAttribute("fams.request.id");
        UUID requestId = value instanceof UUID uuid ? uuid : UUID.randomUUID();
        return ResponseEntity.status(status).body(new ErrorResponse(code, code, requestId, Map.of()));
    }
}
