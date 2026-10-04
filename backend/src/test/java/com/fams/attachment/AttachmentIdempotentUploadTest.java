package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import com.fams.auth.AttachmentActorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AttachmentIdempotentUploadTest {
    @TempDir Path directory;
    private final AttachmentUploadKeys keys = mock(AttachmentUploadKeys.class);
    private final AttachmentBusinessPort business = mock(AttachmentBusinessPort.class);
    private final AttachmentRegistry registry = mock(AttachmentRegistry.class);

    @Test
    void replaySkipsChangedBusinessVersionAndDeletesRetryFile() throws Exception {
        var original = new AttachmentIntake.QuarantinedAttachment(
                new AttachmentStorage.StoredFile(UUID.randomUUID(), 8, "a".repeat(64)),
                AttachmentFormat.PDF, MalwareScanner.Result.CLEAN);
        var service = service();
        when(keys.exists(7, "key-012345678901234")).thenReturn(true);
        doThrow(new AttachmentUploadKeys.Replay(original)).when(keys).replay(anyLong(), anyString(),
                anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
        assertSame(original, service.uploadIdempotent(keys, "key-012345678901234", 10, 4,
                "doc.pdf", "application/pdf", source()));
        verifyNoInteractions(registry);
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void conflictingReplayRemovesRetryBytesAndDoesNotRegisterAgain() throws Exception {
        var service = service();
        when(keys.exists(7, "key-012345678901234")).thenReturn(true);
        doThrow(new AttachmentUploadConflict()).when(keys).replay(anyLong(), anyString(),
                anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
        assertThrows(AttachmentUploadConflict.class, () -> service.uploadIdempotent(keys,
                "key-012345678901234", 10, 4, "doc.pdf", "application/pdf", source()));
        verifyNoInteractions(registry);
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    private AttachmentUploadService service() throws Exception {
        var actors = mock(AttachmentActorContext.class);
        when(actors.requireWarehouse()).thenReturn(new AttachmentActorContext.Actor(7, 0, 0, Instant.MAX));
        when(business.businessType()).thenReturn("INBOUND");
        var storage = new LocalAttachmentStorage(directory);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AttachmentUploadService(new AttachmentWriter(storage,
                new AttachmentIntake(storage, content -> MalwareScanner.Result.CLEAN), manager), registry, actors, business);
    }
    private ByteArrayInputStream source() { return new ByteArrayInputStream("%PDF-1.7".getBytes()); }
}
