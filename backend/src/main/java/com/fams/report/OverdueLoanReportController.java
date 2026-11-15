package com.fams.report;

import com.fams.auth.AssetActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

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
class OverdueLoanReportController {
    private final AssetActorContext actors;
    private final OverdueLoanReportService reports;

    OverdueLoanReportController(AssetActorContext actors, OverdueLoanReportService reports) {
        this.actors = actors;
        this.reports = reports;
    }

    @GetMapping("/overdue-loans")
    ResponseEntity<OverdueLoanReportService.Page> list(
            @RequestParam(required = false) @Positive Long organizationId,
            @RequestParam(required = false) @Positive Long borrowerId,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reports.list(new OverdueLoanReportService.Filter(organizationId, borrowerId), before, pageSize));
    }

    @GetMapping("/overdue-loans.csv")
    ResponseEntity<String> export(
            @RequestParam(required = false) @Positive Long organizationId,
            @RequestParam(required = false) @Positive Long borrowerId) {
        var actor = actors.requireAssetAdmin();
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=overdue-loans.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(reports.export(actor, new OverdueLoanReportService.Filter(organizationId, borrowerId)));
        } catch (OverdueLoanReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }
}
