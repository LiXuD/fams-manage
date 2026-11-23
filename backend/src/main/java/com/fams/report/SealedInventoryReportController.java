package com.fams.report;

import com.fams.auth.AssetActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@Validated
class SealedInventoryReportController {
    private final AssetActorContext actors;
    private final SealedInventoryReportService reports;

    SealedInventoryReportController(AssetActorContext actors, SealedInventoryReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping("/api/v1/operations-reports/inventory-progress")
    ResponseEntity<SealedInventoryReportService.ProgressPage> progress(
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.progress(before, pageSize));
    }

    @GetMapping("/api/v1/operations-reports/inventory")
    ResponseEntity<SealedInventoryReportService.Page> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.list(from, toExclusive, before, pageSize));
    }

    @GetMapping("/api/v1/operations-reports/inventory-detail.csv")
    ResponseEntity<String> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive) {
        var actor = actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        return ReportCsvExport.csv("inventory-detail.csv",
                () -> reports.export(actor, from, toExclusive),
                SealedInventoryReportService.ExportLimit.class::isInstance);
    }

    @GetMapping("/api/v1/operations-reports/inventory/{taskId}/executors")
    ResponseEntity<SealedInventoryReportService.ExecutorBreakdown> executors(@PathVariable @Positive long taskId) {
        actors.requireAssetAdmin();
        var result = reports.executors(taskId);
        return result == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(result);
    }

    @GetMapping("/api/v1/operations-reports/inventory/{taskId}/executors.csv")
    ResponseEntity<String> exportExecutors(@PathVariable @Positive long taskId) {
        var actor = actors.requireAssetAdmin();
        var result = reports.exportExecutors(actor, taskId);
        return result == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-executors.csv")
                        .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8").body(result);
    }

    @GetMapping("/api/v1/operations-reports/inventory-traceability")
    ResponseEntity<SealedInventoryReportService.TraceabilityPage> traceability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.traceability(from, toExclusive, before, pageSize));
    }

    @GetMapping("/api/v1/operations-reports/inventory-traceability.csv")
    ResponseEntity<String> exportTraceability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive) {
        var actor = actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        return ReportCsvExport.csv("inventory-traceability.csv",
                () -> reports.exportTraceability(actor, from, toExclusive),
                SealedInventoryReportService.ExportLimit.class::isInstance);
    }

    private static boolean validWindow(LocalDate from, LocalDate until) {
        return from.getYear() >= 1000 && until.getYear() <= 9999
                && from.isBefore(until) && ChronoUnit.DAYS.between(from, until) <= 366;
    }
}
