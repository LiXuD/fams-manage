package com.fams.attachment;

public final class AttachmentUploadConflict extends RuntimeException {
    public AttachmentUploadConflict() { super("Idempotency key already used for another attachment request"); }
}
