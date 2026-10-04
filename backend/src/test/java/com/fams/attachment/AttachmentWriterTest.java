package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AttachmentWriterTest {
    @TempDir Path directory;

    @Test
    void committedRegistrationKeepsFileWhileRejectedRegistrationRemovesIt() throws Exception {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var writer = writer(manager);
        var id = writer.write("doc.pdf", "application/pdf", source(), attachment -> attachment.file().id());
        assertEquals(true, Files.exists(directory.resolve(id.toString())));
        var rejection = new IllegalArgumentException("quota exceeded");
        assertSame(rejection, assertThrows(IllegalArgumentException.class,
                () -> writer.write("doc.pdf", "application/pdf", source(), attachment -> { throw rejection; })));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test
    void uncertainCommitRetainsQuarantineFileForReconciliation() throws Exception {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doThrow(new TransactionSystemException("commit failed")).when(manager).commit(any());
        assertThrows(TransactionSystemException.class,
                () -> writer(manager).write("doc.pdf", "application/pdf", source(), attachment -> "registered"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test
    void enclosingTransactionIsRejectedBeforeWriting() throws Exception {
        var writer = writer(mock(PlatformTransactionManager.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class,
                    () -> writer.write("doc.pdf", "application/pdf", source(), attachment -> "registered"));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    private AttachmentWriter writer(PlatformTransactionManager manager) throws Exception {
        var storage = new LocalAttachmentStorage(directory);
        return new AttachmentWriter(storage,
                new AttachmentIntake(storage, content -> MalwareScanner.Result.CLEAN), manager);
    }

    private ByteArrayInputStream source() { return new ByteArrayInputStream("%PDF-1.7".getBytes()); }
}
