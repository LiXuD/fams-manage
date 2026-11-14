package com.fams.report;

import com.fams.auth.AssetActorContext;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@RequestMapping("/api/v1/operations-reports")
class OperationsReportController {
    private final AssetActorContext actors;
    private final OperationsReportService reports;

    OperationsReportController(AssetActorContext actors, OperationsReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping
    ResponseEntity<OperationsReportService.Summary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive) {
        actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.summary(from, toExclusive));
    }

    @GetMapping("/inventory.csv")
    ResponseEntity<String> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive) {
        var actor = actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-report.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(reports.exportInventory(actor, from, toExclusive));
        } catch (IllegalArgumentException limit) {
            if ("REPORT_EXPORT_LIMIT".equals(limit.getMessage())) {
                return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
            }
            throw limit;
        }
    }

    private static boolean validWindow(LocalDate from, LocalDate toExclusive) {
        return from.getYear() >= 1000 && toExclusive.getYear() <= 9999
                && from.isBefore(toExclusive) && ChronoUnit.DAYS.between(from, toExclusive) <= 366;
    }
}
