package com.fams.inbound;

final class InboundFailure extends RuntimeException {
    private final String code;
    private final int status;

    InboundFailure(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }

    static InboundFailure conflict(String code) { return new InboundFailure(code, code, 409); }
    static InboundFailure invalid(String code) { return new InboundFailure(code, code, 400); }
    static InboundFailure missing() { return new InboundFailure("INBOUND_NOT_FOUND", "Receipt not found", 404); }
}
