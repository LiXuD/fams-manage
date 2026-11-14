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

    @GetMapping("/api/v1/operations-reports/inventory")
    ResponseEntity<SealedInventoryReportService.Page> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toExclusive,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        if (from.getYear() < 1000 || toExclusive.getYear() > 9999
                || !from.isBefore(toExclusive) || ChronoUnit.DAYS.between(from, toExclusive) > 366) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.list(from, toExclusive, before, pageSize));
    }
}
