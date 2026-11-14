package com.fams.reminder;

final class ReminderFailure extends RuntimeException {
    private final int status;

    public ReminderFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
