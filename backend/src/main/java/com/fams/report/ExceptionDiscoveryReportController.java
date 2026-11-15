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
class ExceptionDiscoveryReportController {
    private final AssetActorContext actors;
    private final ExceptionDiscoveryReportService report;

    ExceptionDiscoveryReportController(AssetActorContext actors, ExceptionDiscoveryReportService report) {
        this.actors = actors;
        this.report = report;
    }

    @GetMapping("/exception-discovery")
    ResponseEntity<ExceptionDiscoveryReportService.Summary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive) {
        actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(report.summary(from, toExclusive));
    }

    @GetMapping("/exception-discovery.csv")
    ResponseEntity<String> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive) {
        var actor = actors.requireAssetAdmin();
        if (!validWindow(from, toExclusive)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=exception-discovery.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(report.export(actor, from, toExclusive));
        } catch (ExceptionDiscoveryReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }

    private static boolean validWindow(LocalDate from, LocalDate until) {
        return from.getYear() >= 1000 && until.getYear() <= 9999
                && from.isBefore(until) && ChronoUnit.DAYS.between(from, until) <= 366;
    }
}
