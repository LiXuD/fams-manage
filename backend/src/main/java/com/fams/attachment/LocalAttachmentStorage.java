package com.fams.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Private quarantine storage; this adapter grants no business download permission. */
public final class LocalAttachmentStorage implements AttachmentStorage {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final Path root;

    public LocalAttachmentStorage(Path directory) throws IOException {
        root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) { throw new IOException("Quarantine must not be a symbolic link"); }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Override
    public StoredFile quarantine(InputStream source) throws IOException {
        UUID id = UUID.randomUUID();
        Path target = root.resolve(id.toString());
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        long size = 0;
        boolean created = false;
        try {
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_FILE_BYTES) { throw new IOException("Attachment exceeds 20 MiB"); }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                if (size == 0) { throw new IOException("Empty attachment"); }
            }
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--------"));
            }
            return new StoredFile(id, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException error) {
            if (created) { Files.deleteIfExists(target); }
            throw error;
        }
    }

    @Override
    public InputStream open(UUID id) throws IOException {
        return Files.newInputStream(root.resolve(id.toString()), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void delete(UUID id) throws IOException {
        Files.deleteIfExists(root.resolve(id.toString()));
    }
}
