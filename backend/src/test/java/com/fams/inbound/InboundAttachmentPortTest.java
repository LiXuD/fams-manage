package com.fams.inbound;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InboundAttachmentPortTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final InboundAttachmentPort port = new InboundAttachmentPort(db);

    @Test
    void onlyCreatorMayWriteDraftAndVersionMustMatch() {
        receipt("DRAFT", 7, 4);
        port.requireWritable(7, 10, 4, true);
        assertThrows(InboundFailure.class, () -> port.requireWritable(8, 10, 4, true));
        assertThrows(InboundFailure.class, () -> port.requireWritable(7, 10, 3, true));
    }

    @Test
    void submittedApprovedAndCompletedEvidenceCannotBeEdited() {
        for (String state : List.of("PENDING_ADMIN_DECISION", "WAITING_WAREHOUSE_CLAIM", "COMPLETED",
                "REJECTED", "CANCELLED")) {
            receipt(state, 7, 4);
            assertThrows(InboundFailure.class, () -> port.requireWritable(7, 10, 4, true));
        }
    }

    @Test
    void currentExclusiveProcessorMayAppendEvidence() {
        receipt("WAREHOUSE_PROCESSING", 7, 4);
        when(db.queryForList("SELECT processor_account_id FROM fams_inbound_processing_ownership "
                + "WHERE receipt_id=? AND archived_at IS NULL FOR UPDATE", 10L))
                .thenReturn(List.of(Map.of("processor_account_id", 8L)));
        port.requireWritable(8, 10, 4, true);
        assertThrows(InboundFailure.class, () -> port.requireWritable(7, 10, 4, true));
    }

    @Test
    void missingReceiptAndFailedVersionUpdateAreRejected() {
        when(db.queryForList(anyString(), eq(10L))).thenReturn(List.of());
        assertThrows(InboundFailure.class, () -> port.requireWritable(7, 10, 4, true));
        assertThrows(InboundFailure.class, () -> port.registered(7, 10, 4));
    }

    private void receipt(String state, long creator, long version) {
        when(db.queryForList("SELECT creator_account_id,receipt_status,version FROM fams_inbound_receipt "
                + "WHERE id=? FOR UPDATE", 10L)).thenReturn(List.of(Map.of(
                    "creator_account_id", creator, "receipt_status", state, "version", version)));
    }
}
