package com.fams.repair;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@Validated
@RestController
@RequestMapping({
    "/api/v1/repairs/{id}/factory/rounds/{round}/evidence",
    "/api/v1/mini/repairs/{id}/factory/rounds/{round}/evidence"
})
public class RepairFactoryEvidenceController {
    private final RequisitionActorContext actors;
    private final ObjectProvider<RepairFactoryEvidenceService> evidence;

    public RepairFactoryEvidenceController(
            RequisitionActorContext actors, ObjectProvider<RepairFactoryEvidenceService> evidence) {
        this.actors = actors;
        this.evidence = evidence;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestParam @PositiveOrZero long roundVersion,
            @RequestParam @PositiveOrZero long assetVersion,
            @RequestParam @PositiveOrZero long ownershipVersion,
            @RequestParam @PositiveOrZero long lockVersion,
            @RequestParam
                    @NotBlank
                    @Pattern(regexp = "FAULT|FACTORY_ACK|RECEIPT|QUOTE|COST_RECEIPT")
                    String purpose,
            @RequestParam(required = false) String originalFilename,
            @RequestParam(required = false) String declaredContentType,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        String filename =
                file.getOriginalFilename() == null ? "evidence" : file.getOriginalFilename();
        String mime = file.getContentType();
        if (originalFilename != null || declaredContentType != null) {
            if (originalFilename == null
                    || originalFilename.isBlank()
                    || originalFilename.length() > 255
                    || originalFilename.contains("/")
                    || originalFilename.contains("\\")
                    || originalFilename.codePoints().anyMatch(Character::isISOControl)
                    || !java.util.Set.of("image/jpeg", "image/png", "image/webp", "application/pdf")
                            .contains(declaredContentType == null ? "" : declaredContentType)
                    || mime != null
                            && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(mime)
                            && !mime.equals(declaredContentType)) {
                throw new RepairFailure(422, "REPAIR_FACTORY_EVIDENCE_REJECTED");
            }
            // wx.uploadFile cannot declare a part filename/MIME independently. The shared
            // quarantine still validates these hints against extension, bytes, size and scan.
            filename = originalFilename;
            mime = declaredContentType;
        }
        try (var bytes = file.getInputStream()) {
            return service()
                    .upload(
                            actors.capture(),
                            id,
                            round,
                            version,
                            roundVersion,
                            assetVersion,
                            ownershipVersion,
                            lockVersion,
                            key,
                            purpose,
                            filename,
                            mime,
                            bytes);
        }
    }

    @GetMapping("/{object}/grant")
    Object grant(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @PathVariable UUID object) {
        return service().grant(actors.capture(), id, round, object);
    }

    @GetMapping("/{object}/content")
    ResponseEntity<byte[]> content(
            @PathVariable @Positive long id,
            @PathVariable @Positive long round,
            @PathVariable UUID object,
            @RequestParam String token)
            throws IOException {
        var content = service().read(actors.capture(), id, round, object, token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(content.mime()))
                .body(content.bytes());
    }

    private RepairFactoryEvidenceService service() {
        var service = evidence.getIfAvailable();
        if (service == null) {
            throw new RepairFailure(503, "REPAIR_FACTORY_EVIDENCE_UNAVAILABLE");
        }
        return service;
    }
}
