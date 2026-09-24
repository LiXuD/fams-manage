package com.fams.asset;

final class AssetOperationException extends RuntimeException {
    private final String code;
    private final int status;

    AssetOperationException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }

    static AssetOperationException notFound() {
        return new AssetOperationException("ASSET_NOT_FOUND", "The asset is not available.", 404);
    }

    static AssetOperationException invalid(String code, String message) {
        return new AssetOperationException(code, message, 400);
    }

    static AssetOperationException conflict(String code, String message) {
        return new AssetOperationException(code, message, 409);
    }
}
