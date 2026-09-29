package com.fams.assetimport;

import com.fams.product.contract.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Profile("fams-product")
@RestControllerAdvice(basePackages = "com.fams.assetimport")
class AssetImportErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure, HttpServletRequest request) {
        String code = known(failure.getMessage()) ? failure.getMessage() : "ASSET_IMPORT_REQUEST_INVALID";
        return response(400, code, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> conflict(IllegalStateException failure, HttpServletRequest request) {
        String code = failure.getMessage();
        if ("ASSET_IMPORT_NOT_FOUND".equals(code)
                || "ASSET_IMPORT_WINDOW_NOT_FOUND".equals(code)
                || "ASSET_IMPORT_REVIEW_NOT_FOUND".equals(code)
                || "ASSET_IMPORT_ROLLBACK_NOT_FOUND".equals(code)) {
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
            return response(409, code, request);
        }
        return response(500, "ASSET_IMPORT_INTERNAL", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(HttpServletRequest request) {
        return response(409, "ASSET_IMPORT_WRITE_CONFLICT", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(HttpServletRequest request) {
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
