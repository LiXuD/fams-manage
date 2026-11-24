package com.fams.scrap;

import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentGrantSigner;
import com.fams.attachment.AttachmentReadService;
import com.fams.attachment.AttachmentStorage;
import com.fams.attachment.AttachmentWriter;
import com.fams.auth.RequisitionActorContext.Actor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class ScrapAttachmentService {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapAttachmentService.class);

    private final ScrapAttachmentTransactions transactions;
    private final AttachmentWriter writer;
    private final AttachmentStorage storage;
    private final AttachmentEvidencePort evidence;

    public ScrapAttachmentService(ScrapAttachmentTransactions transactions, AttachmentWriter writer,
            AttachmentStorage storage, AttachmentEvidencePort evidence) {
        this.transactions = transactions;
        this.writer = writer;
        this.storage = storage;
        this.evidence = evidence;
    }

    public ScrapViews.AttachmentReceipt upload(Actor actor, long id, String kind, long version, UUID key,
            String filename, String mime, InputStream bytes) throws IOException {
        transactions.uploader(actor, id, kind);
        var received = new AtomicReference<UUID>();
        var result = writer.write(filename, mime, bytes, attachment -> {
            received.set(attachment.file().id());
            return transactions.register(actor, id, kind, version, key, filename, attachment);
        });
        if (!received.get().toString().equals(result.objectId())) {
            storage.delete(received.get());
        }
        LOG.info("Uploaded scrap attachment actor={} scrapId={} kind={} version={}", actor.id(), id, kind, version);
        return result;
    }

    @Transactional
    public ScrapViews.AttachmentCapabilities capabilities(Actor actor, long id) {
        LOG.debug("Reading scrap attachment capabilities actor={} scrapId={}", actor.id(), id);
        return transactions.capabilities(actor, id);
    }

    @Transactional
    public List<AttachmentEvidencePort.Metadata> list(Actor actor, long id, String kind) {
        LOG.debug("Listing scrap attachments actor={} scrapId={} kind={}", actor.id(), id, kind);
        transactions.reader(actor, id);
        return evidence.list(ScrapAttachmentTransactions.type(kind), id);
    }

    @Transactional
    public AttachmentGrantSigner.Grant grant(Actor actor, long id, String kind, UUID object) {
        LOG.debug("Granting scrap attachment actor={} scrapId={} kind={} object={}", actor.id(), id, kind, object);
        transactions.reader(actor, id);
        return evidence.grant(ScrapAttachmentTransactions.type(kind), id, identity(actor, object));
    }

    @Transactional
    public AttachmentReadService.Content read(Actor actor, long id, String kind, UUID object, String token)
            throws IOException {
        LOG.debug("Reading scrap attachment actor={} scrapId={} kind={} object={}", actor.id(), id, kind, object);
        transactions.reader(actor, id);
        return evidence.read(ScrapAttachmentTransactions.type(kind), id, identity(actor, object), token);
    }

    private AttachmentGrantSigner.Identity identity(Actor actor, UUID object) {
        return new AttachmentGrantSigner.Identity(object, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
}
