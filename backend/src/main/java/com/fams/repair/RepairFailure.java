package com.fams.repair;

public class RepairFailure extends RuntimeException {
    private final int status;

    public RepairFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
