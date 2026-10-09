package com.fams.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class TransferCalendarTest {
    @Test
    void actualDeliveryDeadlineUsesEnterpriseCalendarAcrossBothDstTransitions() {
        assertEquals(
                Instant.parse("2026-11-03T13:00:00Z"),
                TransferService.receiptDeadline(
                        Instant.parse("2026-10-31T12:00:00Z"), "America/New_York", 3));
        assertEquals(
                Instant.parse("2026-03-10T11:00:00Z"),
                TransferService.receiptDeadline(
                        Instant.parse("2026-03-07T12:00:00Z"), "America/New_York", 3));
        assertEquals(
                Instant.parse("2026-10-12T12:00:00Z"),
                TransferService.receiptDeadline(
                        Instant.parse("2026-10-09T12:00:00Z"), "Asia/Shanghai", 3));
        assertEquals(
                409,
                assertThrows(
                                TransferFailure.class,
                                () ->
                                        TransferService.receiptDeadline(
                                                Instant.EPOCH, "Asia/Shanghai", 0))
                        .status());
        assertEquals(
                409,
                assertThrows(
                                TransferFailure.class,
                                () ->
                                        TransferService.receiptDeadline(
                                                Instant.EPOCH, "Asia/Shanghai", 366))
                        .status());
    }
}
