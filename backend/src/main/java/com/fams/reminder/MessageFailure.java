package com.fams.reminder;

final class MessageFailure extends RuntimeException {
    private final int status;

    public MessageFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
