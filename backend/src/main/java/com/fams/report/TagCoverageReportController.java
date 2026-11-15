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
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tag-coverage.csv")
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8").body(report.export(actor));
        } catch (TagCoverageReportService.ExportLimit failure) {
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }
}
