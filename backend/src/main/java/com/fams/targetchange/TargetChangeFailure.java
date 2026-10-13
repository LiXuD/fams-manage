package com.fams.targetchange;

/** Stable approval-workflow refusal; domain adapters preserve their owner's codes. */
public final class TargetChangeFailure extends RuntimeException {
    private final int status;
    public TargetChangeFailure(String code, int status) {
        super(code);
        this.status = status;
    }
    public int status() { return status; }
    public static TargetChangeFailure invalid(String code) { return new TargetChangeFailure(code, 400); }
    public static TargetChangeFailure conflict(String code) { return new TargetChangeFailure(code, 409); }
    public static TargetChangeFailure unavailable() { return new TargetChangeFailure("TARGET_CHANGE_UNAVAILABLE", 404); }
    public static TargetChangeFailure forbidden() { return new TargetChangeFailure("TARGET_CHANGE_FORBIDDEN", 403); }
}
