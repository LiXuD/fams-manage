package com.fams.attachment;

import java.io.IOException;
import java.io.InputStream;

/** Intake produces quarantine metadata, not a URL or a business download grant. */
public final class AttachmentIntake {
    public record QuarantinedAttachment(AttachmentStorage.StoredFile file,
            AttachmentFormat format, MalwareScanner.Result scanResult) { }

    private final AttachmentStorage storage;
    private final MalwareScanner scanner;

    public AttachmentIntake(AttachmentStorage storage, MalwareScanner scanner) {
        this.storage = storage;
        this.scanner = scanner;
    }

    public QuarantinedAttachment receive(String filename, String mime, InputStream source) throws IOException {
        AttachmentStorage.StoredFile file = storage.quarantine(source);
        AttachmentFormat format;
        try (InputStream header = storage.open(file.id())) {
            format = AttachmentFormat.validate(filename, mime, header.readNBytes(32), file.size());
        } catch (IOException | IllegalArgumentException error) {
            storage.delete(file.id());
            throw error;
        }
        MalwareScanner.Result result;
        try (InputStream content = storage.open(file.id())) { result = scanner.scan(content); }
        catch (IOException error) { result = MalwareScanner.Result.UNAVAILABLE; }
        return new QuarantinedAttachment(file, format,
                result == null ? MalwareScanner.Result.UNAVAILABLE : result);
    }
}
