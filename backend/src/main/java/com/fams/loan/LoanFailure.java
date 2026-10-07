package com.fams.loan;

final class LoanFailure extends RuntimeException {
    private final int status;
    LoanFailure(String code, int status) { super(code);
        this.status = status; }
    int status() { return status; }
}
