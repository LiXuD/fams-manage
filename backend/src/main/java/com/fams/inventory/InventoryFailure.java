package com.fams.inventory;

final class InventoryFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    InventoryFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    int status() {
        return status;
    }
}
