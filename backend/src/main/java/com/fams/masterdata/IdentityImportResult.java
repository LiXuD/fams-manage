package com.fams.masterdata;

public record IdentityImportResult(
        int processedRows,
        int createdRows,
        int updatedRows,
        int disabledRows) {
}
