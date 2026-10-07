package com.fams.requisition;

final class RequisitionFailure extends RuntimeException {
    private final int status;
    private final String code;

    private RequisitionFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }

    static RequisitionFailure invalid(String code) {
        return new RequisitionFailure(400, code);
    }

    static RequisitionFailure conflict(String code) {
        return new RequisitionFailure(409, code);
    }

    static RequisitionFailure unavailable() {
        return new RequisitionFailure(404, "REQUISITION_UNAVAILABLE");
    }

    static RequisitionFailure forbidden() {
        return new RequisitionFailure(403, "REQUISITION_FORBIDDEN");
    }
}
