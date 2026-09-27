package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fams.auth.AttachmentActorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AttachmentCorrectionTest {
    @TempDir Path directory;
    @Test
    void replayBindsOriginalAndReasonAndCannotBecomeAnOrdinaryUpload() {
        var db = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        var row = new HashMap<String, Object>(Map.of("business_type", "INBOUND", "business_id", 10L,
                "expected_business_version", 4L, "original_filename", "doc.pdf", "content_type", "application/pdf",
                "content_sha256", "a".repeat(64), "size_bytes", 8L, "object_id", id.toString(),
                "file_format", "PDF", "scan_status", "UNAVAILABLE"));
        row.put("previous_object_id", previous.toString());
        row.put("correction_reason", "Missing page");
        when(db.queryForList(anyString(), eq(7L), eq("correction-key-0001"))).thenReturn(List.of(row));
        var keys = new AttachmentUploadKeys(db);
        var incoming = new AttachmentIntake.QuarantinedAttachment(
                new AttachmentStorage.StoredFile(UUID.randomUUID(), 8, "a".repeat(64)),
                AttachmentFormat.PDF, MalwareScanner.Result.CLEAN);
        var replay = assertThrows(AttachmentUploadKeys.Replay.class, () -> keys.replay(7, "correction-key-0001",
                "INBOUND", 10, 4, "doc.pdf", "application/pdf", incoming, previous, "Missing page"));
        assertEquals(id, replay.attachment().file().id());
        assertEquals(MalwareScanner.Result.UNAVAILABLE, replay.attachment().scanResult());
        assertThrows(AttachmentUploadConflict.class, () -> keys.replay(7, "correction-key-0001",
                "INBOUND", 10, 4, "doc.pdf", "application/pdf", incoming, previous, "Changed reason"));
        assertThrows(AttachmentUploadConflict.class, () -> keys.replay(7, "correction-key-0001",
                "INBOUND", 10, 4, "doc.pdf", "application/pdf", incoming, UUID.randomUUID(), "Missing page"));
        assertThrows(AttachmentUploadConflict.class, () -> keys.replay(7, "correction-key-0001",
                "INBOUND", 10, 4, "doc.pdf", "application/pdf", incoming));
    }

    @Test
    void correctionRechecksPermissionAndRegistersAuditAndReplayInOrder() throws Exception {
        var actors = mock(AttachmentActorContext.class);
        var actor = new AttachmentActorContext.Actor(7, 0, 0, Instant.MAX);
        when(actors.requireWarehouse()).thenReturn(actor);
        var business = mock(AttachmentBusinessPort.class);
        when(business.businessType()).thenReturn("INBOUND");
        var registry = mock(AttachmentRegistry.class);
        var keys = mock(AttachmentUploadKeys.class);
        var storage = new LocalAttachmentStorage(directory);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service = new AttachmentUploadService(new AttachmentWriter(storage,
                new AttachmentIntake(storage, bytes -> MalwareScanner.Result.CLEAN), manager), registry, actors, business);
        UUID previous = UUID.randomUUID();
        var result = service.correctIdempotent(keys, "correction-key-0001", 10, 4, previous, " Missing page ",
                "doc.pdf", "application/pdf", new ByteArrayInputStream("%PDF-1.7".getBytes()));
        var ordered = inOrder(actors, business, registry, keys);
        ordered.verify(business).requireUploadAccess(7, 10);
        ordered.verify(actors).revalidateLocked(actor);
        ordered.verify(keys).replay(7, "correction-key-0001", "INBOUND", 10, 4, "doc.pdf",
                "application/pdf", result, previous, "Missing page");
        ordered.verify(business).requireWritable(7, 10, 4, true);
        ordered.verify(registry).registerCorrection("INBOUND", 10, 4, 7, "doc.pdf", result, previous, "Missing page");
        ordered.verify(business).registered(7, 10, 4);
        ordered.verify(registry).auditCorrection(7, actor.requestId(), "INBOUND", 10, 4, result, previous, "Missing page");
        ordered.verify(keys).save(7, "correction-key-0001", 4, result.file().id());

        reset(registry);
        doThrow(new AttachmentUploadKeys.Replay(result)).when(keys).replay(anyLong(), anyString(), anyString(),
                anyLong(), anyLong(), anyString(), anyString(), any(), eq(previous), eq("Missing page"));
        assertSame(result, service.correctIdempotent(keys, "correction-key-0001", 10, 4, previous, "Missing page",
                "doc.pdf", "application/pdf", new ByteArrayInputStream("%PDF-1.7".getBytes())));
        verifyNoInteractions(registry);
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
