package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AttachmentUploadKeysTest {
    @Test
    void sameContentReplaysOriginalScanResultAndObjectWhileDifferentRequestConflicts() {
        var db = mock(JdbcTemplate.class);
        var id = UUID.randomUUID();
        var record = Map.<String, Object>of("business_type", "INBOUND", "business_id", 10L,
                "expected_business_version", 4L, "original_filename", "doc.pdf", "content_type", "application/pdf",
                "content_sha256", "a".repeat(64), "size_bytes", 8L, "object_id", id.toString(),
                "file_format", "PDF", "scan_status", "UNAVAILABLE");
        when(db.queryForList(anyString(), eq(7L), eq("key-012345678901234"))).thenReturn(List.of(record));
        var keys = new AttachmentUploadKeys(db);
        var incoming = new AttachmentIntake.QuarantinedAttachment(
                new AttachmentStorage.StoredFile(UUID.randomUUID(), 8, "a".repeat(64)),
                AttachmentFormat.PDF, MalwareScanner.Result.CLEAN);
        var replay = assertThrows(AttachmentUploadKeys.Replay.class, () -> keys.replay(7, "key-012345678901234",
                "INBOUND", 10, 4, "doc.pdf", "application/pdf", incoming));
        assertEquals(id, replay.attachment().file().id());
        assertEquals(MalwareScanner.Result.UNAVAILABLE, replay.attachment().scanResult());
        assertThrows(AttachmentUploadConflict.class, () -> keys.replay(7, "key-012345678901234",
                "INBOUND", 10, 5, "doc.pdf", "application/pdf", incoming));
        assertThrows(AttachmentUploadConflict.class, () -> keys.replay(7, "key-012345678901234",
                "INBOUND", 11, 4, "doc.pdf", "application/pdf", incoming));
        assertThrows(AttachmentUploadConflict.class, () -> keys.replay(7, "key-012345678901234",
                "INBOUND", 10, 4, "renamed.pdf", "application/pdf", incoming));
    }
}
