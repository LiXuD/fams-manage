package com.fams.loss;

public final class LossFailure extends RuntimeException {
    private final int status;

    public LossFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
