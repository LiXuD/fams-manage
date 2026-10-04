package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.fams.auth.AttachmentActorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AttachmentUploadServiceTest {
    @TempDir Path directory;
    private final AttachmentActorContext actors = mock(AttachmentActorContext.class);
    private final AttachmentBusinessPort business = mock(AttachmentBusinessPort.class);
    private final AttachmentRegistry registry = mock(AttachmentRegistry.class);
    private final AttachmentActorContext.Actor actor = new AttachmentActorContext.Actor(7, 0, 0, Instant.MAX);
    private final AtomicInteger scans = new AtomicInteger();

    @Test
    void rechecksAuthorizationAndVersionBeforeRegistrationAndBusinessVersionUpdate() throws Exception {
        var result = service().upload(10, 4, "doc.pdf", "application/pdf", source());
        var order = inOrder(actors, business, registry);
        order.verify(actors).requireWarehouse();
        order.verify(business).requireWritable(7, 10, 4, false);
        order.verify(actors).revalidateLocked(actor);
        order.verify(business).requireWritable(7, 10, 4, true);
        order.verify(business).businessType();
        order.verify(registry).register("INBOUND", 10, 4, 7, "doc.pdf", result);
        order.verify(business).registered(7, 10, 4);
        assertEquals(1, scans.get());
        assertEquals(true, Files.exists(directory.resolve(result.file().id().toString())));
    }

    @Test
    void permissionRevokedDuringScanRejectsRegistrationAndDeletesFile() throws Exception {
        var service = service();
        doThrow(new IllegalStateException("revoked")).when(actors).revalidateLocked(actor);
        assertThrows(IllegalStateException.class,
                () -> service.upload(10, 4, "doc.pdf", "application/pdf", source()));
        verifyNoInteractions(registry);
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void readOnlyBusinessIsRejectedBeforeFileWriteOrScan() throws Exception {
        var service = service();
        doThrow(new IllegalStateException("read only")).when(business).requireWritable(7, 10, 4, false);
        assertThrows(IllegalStateException.class,
                () -> service.upload(10, 4, "doc.pdf", "application/pdf", source()));
        assertEquals(0, scans.get());
        verifyNoInteractions(registry);
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    private AttachmentUploadService service() throws Exception {
        when(actors.requireWarehouse()).thenReturn(actor);
        when(business.businessType()).thenReturn("INBOUND");
        var storage = new LocalAttachmentStorage(directory);
        var intake = new AttachmentIntake(storage, content -> {
            scans.incrementAndGet();
            return MalwareScanner.Result.CLEAN;
        });
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AttachmentUploadService(new AttachmentWriter(storage, intake, transactions), registry, actors, business);
    }

    private ByteArrayInputStream source() { return new ByteArrayInputStream("%PDF-1.7".getBytes()); }
}
