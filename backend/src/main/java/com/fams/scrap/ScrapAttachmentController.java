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
import java.util.Set;
import java.util.UUID;

/**
 * REST surface for scrap request evidence, mounted on both the operator prefix and the WeChat mini
 * program prefix {@code /api/v1/mini} so one implementation serves both clients. The controller is
 * only created when {@code fams.attachments.quarantine-root} is configured, because without a
 * quarantine directory the shared attachment intake cannot store bytes at all.
 *
 * <p>Actor resolution is uniform: every handler captures the caller through
 * {@link RequisitionActorContext} and passes it down, so authorisation never depends on the URL
 * prefix used. The four operations are capability discovery, listing uploaded objects of one kind,
 * uploading, and the two-step download of a signed grant followed by the content fetch.
 *
 * <p>Upload accepts an optional declared filename and content type alongside the multipart part. WeChat
 * clients may send an opaque part name and a generic MIME type, so when either declaration is present
 * the pair is validated as a whole — filename non-blank, at most 255 characters, free of path
 * separators and ISO control characters, content type inside the fixed image/PDF allow-list, and the
 * part MIME either {@code application/octet-stream} or equal to the declaration — before replacing the
 * part values. Any violation is rejected with 422 {@code SCRAP_ATTACHMENT_INVALID}; the intake still
 * inspects the real bytes afterwards, so the declaration is a label and never a trust decision.
 */
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@Validated
@RestController
@RequestMapping({"/api/v1/scrap-requests", "/api/v1/mini/scrap-requests"})
public class ScrapAttachmentController {
    private final ScrapAttachmentService photos;
    private final RequisitionActorContext actors;

    /**
     * Wires the evidence service and the actor context. Both are singletons shared with the rest of the
     * scrap API; the controller keeps no state of its own beyond these references.
     *
     * @param photos scrap attachment service performing the authorisation, intake and read work
     * @param actors request-scoped actor context used to capture the caller for every handler
     */
    public ScrapAttachmentController(ScrapAttachmentService photos, RequisitionActorContext actors) {
        this.photos = photos;
        this.actors = actors;
    }

    @GetMapping("/{id}/attachment-capabilities")
    ScrapViews.AttachmentCapabilities capabilities(@PathVariable @Positive long id) {
        return photos.capabilities(actors.capture(), id);
    }

    @GetMapping("/{id}/attachments/{kind}")
    java.util.List<com.fams.attachment.AttachmentEvidencePort.Metadata> list(
            @PathVariable @Positive long id, @PathVariable String kind) {
        return photos.list(actors.capture(), id, kind);
    }

    @PostMapping("/{id}/attachments/{kind}")
    ScrapViews.AttachmentReceipt upload(
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
    ScrapViews.AttachmentGrant grant(
            @PathVariable @Positive long id,
            @PathVariable String kind,
            @PathVariable UUID object,
            HttpServletRequest request) {
        var grant = photos.grant(actors.capture(), id, kind, object);
        String prefix =
                request.getRequestURI().startsWith("/api/v1/mini/") ? "/api/v1/mini" : "/api/v1";
        return new ScrapViews.AttachmentGrant(
                prefix
                        + "/scrap-requests/"
                        + id
                        + "/attachments/"
                        + kind
                        + "/"
                        + object
                        + "/content?grant="
                        + grant.token(),
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
