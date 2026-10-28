package com.fams.loss;

import com.fams.auth.RequisitionActorContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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
import java.util.Map;
import java.util.UUID;

@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@Validated
@RestController
@RequestMapping({"/api/v1/loss-reports", "/api/v1/mini/loss-reports"})
public class LossPhotoController {
    private final LossPhotoService photos;
    private final RequisitionActorContext actors;

    public LossPhotoController(LossPhotoService photos, RequisitionActorContext actors) {
        this.photos = photos;
        this.actors = actors;
    }

    @PostMapping("/{id}/photos")
    Map<String, Object> upload(
            @PathVariable @Positive long id,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        var actor = actors.capture();
        try (var bytes = file.getInputStream()) {
            return photos.upload(
                    actor,
                    id,
                    version,
                    key,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    bytes);
        }
    }

    @PostMapping("/{id}/photos/{object}/download-grant")
    Map<String, Object> grant(
            @PathVariable @Positive long id,
            @PathVariable UUID object,
            HttpServletRequest request) {
        var grant = photos.grant(actors.capture(), id, object);
        String prefix =
                request.getRequestURI().startsWith("/api/v1/mini/") ? "/api/v1/mini" : "/api/v1";
        return Map.of(
                "url",
                prefix
                        + "/loss-reports/"
                        + id
                        + "/photos/"
                        + object
                        + "/content?grant="
                        + grant.token(),
                "expiresAtEpochSecond",
                grant.expiresAtEpochSecond());
    }

    @GetMapping("/{id}/photos/{object}/content")
    ResponseEntity<byte[]> content(
            @PathVariable @Positive long id,
            @PathVariable UUID object,
            @RequestParam("grant") @Size(max = 256) String token)
            throws IOException {
        var content = photos.read(actors.capture(), id, object, token);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(content.mime()))
                .contentLength(content.bytes().length)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(
                                        content.filename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(content.bytes());
    }
}
