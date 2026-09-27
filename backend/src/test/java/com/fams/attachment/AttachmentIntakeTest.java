package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentIntakeTest {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    @TempDir Path directory;

    @Test
    void randomStorageNamesNeverUseOriginalPathsAndRemainPrivate() throws Exception {
        LocalAttachmentStorage storage = new LocalAttachmentStorage(directory);
        AttachmentIntake intake = new AttachmentIntake(storage, content -> MalwareScanner.Result.CLEAN);
        var result = intake.receive("../../outside.png", "image/png", new ByteArrayInputStream(PNG));
        assertEquals(AttachmentFormat.PNG, result.format());
        assertEquals(MalwareScanner.Result.CLEAN, result.scanResult());
        assertEquals(PNG.length, result.file().size());
        assertEquals(64, result.file().sha256().length());
        try (InputStream stream = storage.open(result.file().id())) { assertArrayEquals(PNG, stream.readAllBytes()); }
        Path path = directory.resolve(result.file().id().toString());
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ), Files.getPosixFilePermissions(path));
        }
        assertFalse(Files.exists(directory.resolve("outside.png")));
    }

    @Test
    void disguisedContentIsRemovedWithoutScanning() throws Exception {
        AtomicBoolean scanned = new AtomicBoolean();
        AttachmentIntake intake = new AttachmentIntake(new LocalAttachmentStorage(directory), content -> {
            scanned.set(true); return MalwareScanner.Result.CLEAN;
        });
        assertThrows(IllegalArgumentException.class,
                () -> intake.receive("script.png", "image/png", new ByteArrayInputStream("<script>".getBytes())));
        assertFalse(scanned.get());
        try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
    }

    @Test
    void failedUploadLeavesNoPartialFile() throws Exception {
        LocalAttachmentStorage storage = new LocalAttachmentStorage(directory);
        InputStream source = new InputStream() {
            private int remaining = 10;
            @Override public int read() throws IOException {
                if (remaining-- <= 0) { throw new IOException("Disconnected upload"); }
                return 1;
            }
        };
        assertThrows(IOException.class, () -> storage.quarantine(source));
        assertThrows(IOException.class, () -> storage.quarantine(new ByteArrayInputStream(new byte[0])));
        try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
    }

    @Test
    void scannerFailureAndInfectionRemainDistinctQuarantineResults() throws Exception {
        LocalAttachmentStorage storage = new LocalAttachmentStorage(directory);
        for (MalwareScanner.Result state : java.util.List.of(
                MalwareScanner.Result.INFECTED, MalwareScanner.Result.UNAVAILABLE)) {
            var result = new AttachmentIntake(storage, content -> state)
                    .receive("photo.png", "image/png", new ByteArrayInputStream(PNG));
            assertEquals(state, result.scanResult());
        }
    }

    @Test
    void unexpectedScannerFailureReturnsUnavailableMetadata() throws Exception {
        LocalAttachmentStorage storage = new LocalAttachmentStorage(directory);
        var result = new AttachmentIntake(storage, content -> {
            throw new IllegalStateException("Scanner unavailable");
        }).receive("photo.png", "image/png", new ByteArrayInputStream(PNG));
        assertEquals(MalwareScanner.Result.UNAVAILABLE, result.scanResult());
        try (InputStream bytes = storage.open(result.file().id())) {
            assertArrayEquals(PNG, bytes.readAllBytes());
        }
    }

    @Test
    void validationFailureKeepsOriginalErrorWhenCleanupFails() throws Exception {
        LocalAttachmentStorage delegate = new LocalAttachmentStorage(directory);
        AttachmentStorage failingCleanup = new AttachmentStorage() {
            @Override public StoredFile quarantine(InputStream source) throws IOException {
                return delegate.quarantine(source);
            }
            @Override public InputStream open(java.util.UUID id) throws IOException { return delegate.open(id); }
            @Override public void delete(java.util.UUID id) throws IOException { throw new IOException("Delete failed"); }
        };
        var intake = new AttachmentIntake(failingCleanup, content -> MalwareScanner.Result.CLEAN);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> intake.receive("script.png", "image/png", new ByteArrayInputStream("<script>".getBytes())));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("Delete failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void extensionMimeContentAndPerFileLimitsMustAgree() {
        assertEquals(AttachmentFormat.JPG, AttachmentFormat.validate("photo.JPEG", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 10L * 1024 * 1024));
        assertEquals(AttachmentFormat.PDF, AttachmentFormat.validate("doc.pdf", "application/pdf",
                "%PDF-1.7".getBytes(), 20L * 1024 * 1024));
        assertEquals(AttachmentFormat.WEBP, AttachmentFormat.validate("photo.webp", "image/webp",
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, 12));
        assertThrows(IllegalArgumentException.class,
                () -> AttachmentFormat.validate("image.png", "application/pdf", PNG, 8));
        assertThrows(IllegalArgumentException.class,
                () -> AttachmentFormat.validate("image.png", "image/png", PNG, 10L * 1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class,
                () -> AttachmentFormat.validate("file.zip", "application/zip", PNG, 8));
    }
}
