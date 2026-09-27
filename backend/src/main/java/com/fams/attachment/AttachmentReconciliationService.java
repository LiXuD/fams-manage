package com.fams.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public final class AttachmentReconciliationService {
    private final Path root;
    private final JdbcTemplate db;
    private final AttachmentStorage storage;
    private final AttachmentObjectGuard guard;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AttachmentReconciliationService(@Value("${fams.attachments.quarantine-root}") String root,
            JdbcTemplate db, AttachmentStorage storage, AttachmentObjectGuard guard, Clock clock,
            PlatformTransactionManager transactions) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.db = db; this.storage = storage; this.guard = guard; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }
    public int removeOrphans(int limit) {
        if (limit < 1 || limit > 1000 || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalArgumentException("Reconciliation requires independent batches of 1..1000");
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(24));
        int removed = 0;
        try (var files = Files.list(root)) {
            var candidates = files.filter(path -> candidate(path, cutoff)).iterator();
            while (candidates.hasNext() && removed < limit) {
                Path path = candidates.next();
                UUID id = UUID.fromString(path.getFileName().toString());
                boolean deleted = Boolean.TRUE.equals(transaction.execute(status -> {
                    guard.lock(id);
                    // Current locking read sees a registration committed while the guard was awaited.
                    if (!db.queryForList("SELECT object_id FROM fams_attachment WHERE object_id=? FOR UPDATE",
                            id.toString()).isEmpty() || !candidate(path, cutoff)) { return false; }
                    try { storage.delete(id); return true; }
                    catch (IOException error) { return false; } // File remains eligible on the next scheduled pass.
                }));
                if (deleted) { removed++; }
            }
        } catch (IOException error) { throw new UncheckedIOException("Cannot enumerate quarantine", error); }
        return removed;
    }
    private boolean candidate(Path path, Instant cutoff) {
        String name = path.getFileName().toString();
        try {
            if (!UUID.fromString(name).toString().equals(name)) { return false; }
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.lastModifiedTime().toInstant().isAfter(cutoff);
        } catch (IOException | IllegalArgumentException error) { return false; }
    }
}
