package com.fams.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface AttachmentStorage {
    record StoredFile(UUID id, long size, String sha256) { }

    StoredFile quarantine(InputStream source) throws IOException;
    InputStream open(UUID id) throws IOException;
    void delete(UUID id) throws IOException;
}
