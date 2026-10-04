package com.fams.attachment;

public final class AttachmentAccessFailure extends RuntimeException {
    private final int status;
    private final String code;
    public AttachmentAccessFailure(int status, String code) { super(code);
    this.status = status;
    this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
