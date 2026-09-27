package com.fams.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public enum AttachmentFormat {
    JPG("image/jpeg", 10L * 1024 * 1024),
    PNG("image/png", 10L * 1024 * 1024),
    WEBP("image/webp", 10L * 1024 * 1024),
    PDF("application/pdf", 20L * 1024 * 1024);

    private final String mime;
    private final long maxBytes;

    AttachmentFormat(String mime, long maxBytes) { this.mime = mime;
    this.maxBytes = maxBytes;
    }
    public String mime() { return mime; }
    public boolean image() { return this != PDF; }

    public static AttachmentFormat validate(String filename, String declaredMime, byte[] prefix, long size) {
        if (filename == null || declaredMime == null || prefix == null || size <= 0) {
            throw new IllegalArgumentException("Attachment metadata is incomplete"); }
        String name = filename.toLowerCase(Locale.ROOT);
        AttachmentFormat format;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) { format = JPG; }
        else if (name.endsWith(".png")) { format = PNG; }
        else if (name.endsWith(".webp")) { format = WEBP; }
        else if (name.endsWith(".pdf")) { format = PDF; }
        else { throw new IllegalArgumentException("Attachment format is not allowed"); }
        if (!format.mime.equals(declaredMime) || size > format.maxBytes || !format.matches(prefix)) {
            throw new IllegalArgumentException("Attachment extension, MIME, content or size does not match"); }
        return format;
    }

    private boolean matches(byte[] bytes) {
        return switch (this) {
            case JPG -> starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case PNG -> starts(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case PDF -> starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case WEBP -> bytes.length >= 12 && starts(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII));
        };
    }

    private static boolean starts(byte[] bytes, byte[] signature) {
        return bytes.length >= signature.length
                && Arrays.equals(Arrays.copyOf(bytes, signature.length), signature);
    }
}
