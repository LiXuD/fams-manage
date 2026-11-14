package com.fams.report;

import com.fams.auth.AssetActorContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Profile("fams-product")
@RequestMapping("/api/v1/operations-reports")
class AssetMovementReportController {
    private final AssetActorContext actors;
    private final AssetMovementReportService reports;

    AssetMovementReportController(AssetActorContext actors, AssetMovementReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping("/movements")
    ResponseEntity<AssetMovementReportService.Page> list(@Valid @ModelAttribute Filters filters,
            @RequestParam(required = false) Instant beforeAt,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        var filter = filters.toFilter();
        if (!filter.valid() || (beforeAt == null) != (beforeId == null)) {
            return ResponseEntity.badRequest().build();
        }
        if (beforeAt != null && (beforeAt.isBefore(filter.from().atStartOfDay().toInstant(ZoneOffset.UTC))
                || !beforeAt.isBefore(filter.toExclusive().atStartOfDay().toInstant(ZoneOffset.UTC)))) {
            return ResponseEntity.badRequest().build();
        }
        var before = beforeAt == null ? null : new AssetMovementReportService.Cursor(beforeAt, beforeId.toString());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(reports.list(filter, before, pageSize));
    }

    @GetMapping("/movements.csv")
    ResponseEntity<String> export(@Valid @ModelAttribute Filters filters) {
        var actor = actors.requireAssetAdmin();
        var filter = filters.toFilter();
        if (!filter.valid()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=asset-movement-report.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8").body(reports.export(actor, filter));
        } catch (AssetMovementReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }

    public record Filters(@NotNull AssetMovementReportService.Kind kind,
                          @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive,
                          @Positive Long assetId) {
        AssetMovementReportService.Filter toFilter() {
            return new AssetMovementReportService.Filter(kind, from, toExclusive, assetId);
        }
    }
}
