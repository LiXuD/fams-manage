package com.fams.auth;

import jakarta.servlet.http.HttpServletRequest;

import com.fams.product.contract.api.MiniIdentitySupportApi;
import com.fams.product.contract.model.MiniPhoneCorrectionRequest;
import com.fams.product.contract.model.MiniPhoneCorrectionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
class MiniPhoneCorrectionController implements MiniIdentitySupportApi {
    private final MiniPhoneCorrectionService service;
    private final HttpServletRequest request;

    MiniPhoneCorrectionController(MiniPhoneCorrectionService service, HttpServletRequest request) {
        this.service = service;
        this.request = request;
    }

    @Override
    public ResponseEntity<MiniPhoneCorrectionResult> correctMiniEmployeePhone(String employeeId, String key,
            String csrf, Long version, MiniPhoneCorrectionRequest input) {
        if (input.getReason().isBlank() || input.getVerificationMethod().isBlank()) {
            throw new AuthOperationException("INVALID_REQUEST", "Identity verification and reason are required.", 400);
        }
        long id;
        try {
            id = Long.parseLong(employeeId);
            if (id <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw AuthOperationException.employeeNotFound();
        }
        long updatedVersion = service.correct(AuthRequestContext.account(request).id(), id, version,
                input.getPhone(), input.getVerificationMethod().strip(), input.getReason().strip(), key,
                AuthRequestContext.session(request), AuthRequestContext.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new MiniPhoneCorrectionResult(employeeId, updatedVersion));
    }
}
