package com.fams.inventory;

import com.fams.auth.RequisitionActorContext;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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

@Validated
@RestController
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
@RequestMapping({"/api/v1/inventory-observations", "/api/v1/mini/inventory-observations"})
class InventoryPhotoController {
    private final InventoryPhotoService service;
    private final RequisitionActorContext actors;
    private final InventoryPhotoReadService reads;

    InventoryPhotoController(
            InventoryPhotoService service,
            RequisitionActorContext actors,
            InventoryPhotoReadService reads) {
        this.service = service;
        this.actors = actors;
        this.reads = reads;
    }

    @GetMapping("/tasks/{taskId}/events/{eventId}/photos")
    Map<String, Object> list(@PathVariable @Positive long taskId, @PathVariable UUID eventId) {
        var items =
                reads.list(actors.capture(), taskId, eventId).stream()
                        .map(
                                row -> {
                                    var item = new java.util.LinkedHashMap<String, Object>(row);
                                    Object created = item.get("createdAt");
                                    if (created instanceof java.sql.Timestamp time) {
                                        item.put("createdAt", time.toInstant().toString());
                                    } else if (created instanceof java.time.LocalDateTime time) {
                                        item.put(
                                                "createdAt",
                                                time.toInstant(java.time.ZoneOffset.UTC)
                                                        .toString());
                                    }
                                    return item;
                                })
                        .toList();
        return Map.of("items", items);
    }

    @PostMapping("/tasks/{taskId}/events/{eventId}/photos/{objectId}/download-grant")
    Map<String, Object> grant(
            @PathVariable @Positive long taskId,
            @PathVariable UUID eventId,
            @PathVariable UUID objectId,
            jakarta.servlet.http.HttpServletRequest request) {
        var grant = reads.grant(actors.capture(), taskId, eventId, objectId);
        String prefix =
                request.getRequestURI().startsWith("/api/v1/mini/") ? "/api/v1/mini" : "/api/v1";
        return Map.of(
                "url",
                prefix
                        + "/inventory-observations/tasks/"
                        + taskId
                        + "/events/"
                        + eventId
                        + "/photos/"
                        + objectId
                        + "/content?grant="
                        + grant.token(),
                "expiresAtEpochSecond",
                grant.expiresAtEpochSecond());
    }

    @GetMapping("/tasks/{taskId}/events/{eventId}/photos/{objectId}/content")
    org.springframework.http.ResponseEntity<byte[]> content(
            @PathVariable @Positive long taskId,
            @PathVariable UUID eventId,
            @PathVariable UUID objectId,
            @RequestParam("grant") @jakarta.validation.constraints.Size(max = 256) String token)
            throws IOException {
        var content = reads.read(actors.capture(), taskId, eventId, objectId, token);
        return org.springframework.http.ResponseEntity.ok()
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

    @PostMapping("/tasks/{taskId}/events/{eventId}/photos")
    Map<String, Object> upload(
            @PathVariable @Positive long taskId,
            @PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") UUID photoId,
            @RequestHeader("If-Match-Version")
                    @jakarta.validation.constraints.Min(0)
                    @jakarta.validation.constraints.Max(0)
                    long version,
            @RequestHeader("X-FAMS-Device-Id") UUID deviceId,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        try (var stream = file.getInputStream()) {
            return service.upload(
                    actors.capture(),
                    taskId,
                    eventId,
                    photoId,
                    deviceId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    stream);
        }
    }
}
