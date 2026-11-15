package com.fams.report;

import com.fams.auth.AssetActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Profile("fams-product")
@RequestMapping("/api/v1/operations-reports")
class AssetTimelineReportController {
    private final AssetActorContext actors;
    private final AssetTimelineReportService reports;

    AssetTimelineReportController(AssetActorContext actors, AssetTimelineReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping("/asset-timeline")
    ResponseEntity<AssetTimelineReportService.Page> list(@RequestParam @Positive long assetId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String eventType,
            @RequestParam(required = false) Instant beforeAt,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        if ((beforeAt == null) != (beforeId == null)) {
            return ResponseEntity.badRequest().build();
        }
        var cursor = beforeId == null ? null : new AssetTimelineReportService.Cursor(beforeAt, beforeId.toString());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.list(new AssetTimelineReportService.Filter(assetId, eventType), cursor, pageSize));
    }

    @GetMapping("/asset-timeline.csv")
    ResponseEntity<String> export(@RequestParam @Positive long assetId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String eventType) {
        var actor = actors.requireAssetAdmin();
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=asset-timeline.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(reports.export(actor, new AssetTimelineReportService.Filter(assetId, eventType)));
        } catch (AssetTimelineReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }
}
