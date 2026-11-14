package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@Validated
@RestController
@RequestMapping({"/api/v1/scrap-requests", "/api/v1/mini/scrap-requests"})
public class ScrapAttachmentController {
    private final ScrapAttachmentService photos;
    private final RequisitionActorContext actors;

    public ScrapAttachmentController(ScrapAttachmentService photos, RequisitionActorContext actors) {
        this.photos = photos;
        this.actors = actors;
    }

    @GetMapping("/{id}/attachment-capabilities")
    Map<String, Object> capabilities(@PathVariable @Positive long id) {
        return photos.capabilities(actors.capture(), id);
    }

    @GetMapping("/{id}/attachments/{kind}")
    java.util.List<Map<String, Object>> list(@PathVariable @Positive long id, @PathVariable String kind) {
        return photos.list(actors.capture(), id, kind);
    }

    @PostMapping("/{id}/attachments/{kind}")
    Map<String, Object> upload(
            @PathVariable @Positive long id,
            @PathVariable String kind,
            @RequestHeader("If-Match-Version") @PositiveOrZero long version,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestParam(required = false) String originalFilename,
            @RequestParam(required = false) String declaredContentType,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        var actor = actors.capture();
        String filename = file.getOriginalFilename();
        String mime = file.getContentType();
        if (originalFilename != null || declaredContentType != null) {
            if (originalFilename == null || originalFilename.isBlank() || originalFilename.length() > 255
                    || originalFilename.contains("/") || originalFilename.contains("\\")
                    || originalFilename.codePoints().anyMatch(Character::isISOControl)
                    || declaredContentType == null
                    || !Set.of("image/png", "image/jpeg", "image/webp", "application/pdf").contains(declaredContentType)
                    || mime != null && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(mime)
                            && !mime.equals(declaredContentType)) {
                throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_INVALID", 422);
            }
            // WeChat may send an opaque part name and MIME. The intake still validates actual bytes.
            filename = originalFilename;
            mime = declaredContentType;
        }
        try (var bytes = file.getInputStream()) {
            return photos.upload(
                    actor,
                    id,
                    kind,
                    version,
                    key,
                    filename,
                    mime,
                    bytes);
        }
    }

    @PostMapping("/{id}/attachments/{kind}/{object}/download-grant")
    Map<String, Object> grant(
            @PathVariable @Positive long id,
            @PathVariable String kind,
            @PathVariable UUID object,
            HttpServletRequest request) {
        var grant = photos.grant(actors.capture(), id, kind, object);
        String prefix =
                request.getRequestURI().startsWith("/api/v1/mini/") ? "/api/v1/mini" : "/api/v1";
        return Map.of(
                "url",
                prefix
                        + "/scrap-requests/"
                        + id
                        + "/attachments/"
                        + kind
                        + "/"
                        + object
                        + "/content?grant="
                        + grant.token(),
                "expiresAtEpochSecond",
                grant.expiresAtEpochSecond());
    }

    @GetMapping("/{id}/attachments/{kind}/{object}/content")
    ResponseEntity<byte[]> content(
            @PathVariable @Positive long id,
            @PathVariable String kind,
            @PathVariable UUID object,
            @RequestParam("grant") @Size(max = 256) String token)
            throws IOException {
        var content = photos.read(actors.capture(), id, kind, object, token);
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
