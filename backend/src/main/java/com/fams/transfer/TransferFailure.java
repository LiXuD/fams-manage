package com.fams.transfer;

final class TransferFailure extends RuntimeException {
    private final int status;

    TransferFailure(String code, int status) {
        super(code);
        this.status = status;
    }

    int status() {
        return status;
    }
}
