package com.fams.masterdata;

final class MasterDataOperationException extends RuntimeException {

    private final String code;
    private final int status;

    MasterDataOperationException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }

    static MasterDataOperationException notFound(String subject) {
        return new MasterDataOperationException(
                "MASTER_DATA_NOT_FOUND", subject + " does not exist.", 404);
    }

    static MasterDataOperationException invalid(String message) {
        return new MasterDataOperationException("MASTER_DATA_INVALID", message, 400);
    }

    static MasterDataOperationException dependencyConflict(String message) {
        return new MasterDataOperationException("MASTER_DATA_DEPENDENCY_CONFLICT", message, 409);
    }

    static MasterDataOperationException versionConflict() {
        return new MasterDataOperationException(
                "MASTER_DATA_VERSION_CONFLICT", "The supplied version is no longer current.", 409);
    }

    static MasterDataOperationException writeConflict(String message) {
        return new MasterDataOperationException("MASTER_DATA_WRITE_CONFLICT", message, 409);
    }

    static MasterDataOperationException idempotencyConflict() {
        return new MasterDataOperationException(
                "MASTER_DATA_IDEMPOTENCY_CONFLICT",
                "The idempotency key was already used for another request.",
                409);
    }
}
