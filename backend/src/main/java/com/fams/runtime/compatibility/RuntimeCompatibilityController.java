package com.fams.runtime.compatibility;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("runtime-compatibility")
@RestController
@RequestMapping("/runtime-compatibility")
final class RuntimeCompatibilityController {

    private static final String SESSION_PROOF_ATTRIBUTE = "d6-02b-session-proof";

    private final RuntimeCompatibilityService compatibilityService;

    RuntimeCompatibilityController(RuntimeCompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @GetMapping("/health")
    ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("runtime-compatibility", "ready"));
    }

    @PostMapping("/probes")
    ResponseEntity<ProbeResponse> write(@Valid @RequestBody ProbeRequest request) {
        compatibilityService.writeCommitted(request.probeKey(), request.probeValue());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ProbeResponse(request.probeKey(), request.probeValue()));
    }

    @GetMapping("/probes/{probeKey}")
    ResponseEntity<ProbeResponse> read(@PathVariable String probeKey) {
        String value = compatibilityService.read(probeKey);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ProbeResponse(probeKey, value));
    }

    @PostMapping("/probes/rollback")
    ResponseEntity<RollbackResponse> rollback(@Valid @RequestBody RollbackRequest request) {
        compatibilityService.writeAndReject(request.probeKey());
        throw new IllegalStateException("rollback probe unexpectedly committed");
    }

    @PostMapping("/session/proof")
    ResponseEntity<SessionProofResponse> setSessionProof(
            @Valid @RequestBody SessionProofRequest request,
            HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(SESSION_PROOF_ATTRIBUTE, request.proofValue());
        return ResponseEntity.ok(new SessionProofResponse(request.proofValue()));
    }

    @GetMapping("/session/proof")
    ResponseEntity<SessionProofResponse> getSessionProof(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            throw new RuntimeSessionUnavailableException();
        }
        Object proof = session.getAttribute(SESSION_PROOF_ATTRIBUTE);
        if (!(proof instanceof String proofValue) || proofValue.isBlank()) {
            throw new RuntimeSessionUnavailableException();
        }
        return ResponseEntity.ok(new SessionProofResponse(proofValue));
    }

    record HealthResponse(String profile, String status) {
    }

    record ProbeRequest(
            @NotBlank @Size(max = 128) String probeKey,
            @NotBlank @Size(max = 512) String probeValue) {
    }

    record ProbeResponse(String probeKey, String probeValue) {
    }

    record RollbackRequest(@NotBlank @Size(max = 96) String probeKey) {
    }

    record RollbackResponse(String code, String message, String probeKey, Map<String, String> fieldErrors) {
    }

    record SessionProofRequest(@NotBlank @Size(max = 256) String proofValue) {
    }

    record SessionProofResponse(String proofValue) {
    }
}
