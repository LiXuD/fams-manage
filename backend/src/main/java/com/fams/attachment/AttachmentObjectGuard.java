package com.fams.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes metadata registration with removal of files that have no metadata. */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class AttachmentObjectGuard {
    private final JdbcTemplate db;
    private final AttachmentStorage storage;
    public AttachmentObjectGuard(JdbcTemplate db, AttachmentStorage storage) {
        this.db = db;
        this.storage = storage;
    }
    public void lock(UUID id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Object guard requires a transaction");
        }
        db.update("INSERT INTO fams_attachment_object_guard (object_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE object_id=object_id", id.toString());
        db.queryForList("SELECT object_id FROM fams_attachment_object_guard WHERE object_id=? FOR UPDATE", id.toString());
    }
    public void requireStored(UUID id) {
        lock(id);
        try (var bytes = storage.open(id)) { bytes.read(); }
        catch (IOException error) { throw new UncheckedIOException("Quarantined file unavailable", error); }
    }
}
