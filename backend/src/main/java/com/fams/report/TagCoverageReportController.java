package com.fams.report;

import com.fams.auth.AssetActorContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@RequestMapping("/api/v1/operations-reports")
class TagCoverageReportController {
    private final AssetActorContext actors;
    private final TagCoverageReportService report;

    TagCoverageReportController(AssetActorContext actors, TagCoverageReportService report) {
        this.actors = actors;
        this.report = report;
    }

    @GetMapping("/tag-coverage")
    ResponseEntity<TagCoverageReportService.Summary> summary() {
        actors.requireAssetAdmin();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(report.summary());
    }

    @GetMapping("/tag-coverage.csv")
    ResponseEntity<String> export() {
        var actor = actors.requireAssetAdmin();
        return ReportCsvExport.csv("tag-coverage.csv",
                () -> report.export(actor),
                TagCoverageReportService.ExportLimit.class::isInstance);
    }
}
