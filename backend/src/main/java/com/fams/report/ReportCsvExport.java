package com.fams.report;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

final class ReportCsvExport {
    private ReportCsvExport() {
    }

    static ResponseEntity<String> csv(String filename, Supplier<String> body,
            Predicate<RuntimeException> exportLimit) {
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .body(body.get());
        } catch (RuntimeException failure) {
            if (!exportLimit.test(failure)) {
                throw failure;
            }
            return ResponseEntity.unprocessableContent().body("REPORT_EXPORT_LIMIT");
        }
    }
}
