package com.fams.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal coordinator: the callback must authorize the business and register metadata in one transaction. */
public final class AttachmentWriter {
    private final AttachmentStorage storage;
    private final AttachmentIntake intake;
    private final TransactionTemplate transaction;
    private final java.util.function.Consumer<java.util.UUID> beforeRegistration;

    public AttachmentWriter(AttachmentStorage storage, AttachmentIntake intake,
            PlatformTransactionManager transactionManager) {
        this(storage, intake, transactionManager, id -> { });
    }

    public AttachmentWriter(AttachmentStorage storage, AttachmentIntake intake,
            PlatformTransactionManager transactionManager, java.util.function.Consumer<java.util.UUID> beforeRegistration) {
        this.beforeRegistration = Objects.requireNonNull(beforeRegistration);
        this.storage = Objects.requireNonNull(storage);
        this.intake = Objects.requireNonNull(intake);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public <T> T write(String filename, String mime, InputStream source,
            Function<AttachmentIntake.QuarantinedAttachment, T> registration) throws IOException {
        Objects.requireNonNull(registration);
        // An enclosing transaction could roll back after this method returned and orphan the file.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Attachment writing must start outside a transaction");
        }
        var attachment = intake.receive(filename, mime, source);
        boolean[] rejected = {false};
        try {
            return transaction.execute(status -> {
                try {
                    beforeRegistration.accept(attachment.file().id());
                    return registration.apply(attachment);
                }
                catch (RuntimeException error) { rejected[0] = true;
                throw error;
                }
            });
        } catch (TransactionSystemException uncertain) {
            // A lost commit acknowledgement can mean metadata committed; retain the bytes for reconciliation.
            throw uncertain;
        } catch (RuntimeException error) {
            if (rejected[0] || error instanceof UnexpectedRollbackException) {
                try { storage.delete(attachment.file().id()); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }
}
