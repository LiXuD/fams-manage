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

/**
 * Application service for scrap evidence, mediating between {@link ScrapAttachmentController} and the
 * shared attachment infrastructure. It owns two things the shared components cannot know: which scrap
 * roles may upload or read a given kind, and how the scrap {@code kind} path segment maps onto the
 * attachment business type. Everything else — byte intake, malware scanning, quarantine storage, grant
 * signing and content reading — is delegated unchanged.
 *
 * <p>Transaction placement is asymmetric on purpose. The four read-side methods are
 * {@code @Transactional} because they only touch the database. {@link #upload} is deliberately not
 * transactional: {@link AttachmentWriter#write} refuses to run inside an active transaction so that a
 * later rollback cannot orphan stored bytes, and it opens its own short transaction around the
 * registration callback, which is why {@link ScrapAttachmentTransactions#register} declares
 * {@code Propagation.MANDATORY}.
 *
 * <p>The bean is conditional on {@code fams.attachments.quarantine-root}, mirroring the controller, so
 * the whole scrap evidence surface disappears when no quarantine directory is configured.
 */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class ScrapAttachmentService {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapAttachmentService.class);

    private final ScrapAttachmentTransactions transactions;
    private final AttachmentWriter writer;
    private final AttachmentStorage storage;
    private final AttachmentEvidencePort evidence;

    /**
     * Wires the scrap-side transaction boundary and the three shared attachment components.
     *
     * @param transactions scrap attachment transaction helper owning authorisation and registration
     * @param writer intake orchestrator that quarantines bytes and commits metadata in one transaction
     * @param storage quarantine store, used here only to delete bytes an idempotent replay orphaned
     * @param evidence shared attachment port providing list, grant and content read by business type
     */
    public ScrapAttachmentService(ScrapAttachmentTransactions transactions, AttachmentWriter writer,
            AttachmentStorage storage, AttachmentEvidencePort evidence) {
        this.transactions = transactions;
        this.writer = writer;
        this.storage = storage;
        this.evidence = evidence;
    }

    /**
     * Uploads one evidence object for a scrap request or its disposition. The uploader check runs
     * first, outside the writer transaction, so an unauthorised caller is rejected before any bytes are
     * read. The writer then quarantines the stream, scans it and calls back into
     * {@link ScrapAttachmentTransactions#register} inside its own transaction.
     *
     * <p>After the writer returns, the object the callback actually registered is compared with the
     * object the receipt points at. They differ only on an idempotent replay, where the stored receipt
     * names the object of the first attempt; the freshly stored bytes of this attempt are then deleted
     * so the replay does not leave an orphan in quarantine.
     *
     * @param actor uploading actor, revalidated inside the registration transaction
     * @param id fams_scrap_request primary key the evidence is attached to
     * @param kind either {@code request} or {@code disposition}, selecting the business type and the
     *     upload precondition
     * @param version expected version of the request row or disposition row, compared under lock
     * @param key client idempotency key scoping the receipt lookup for this actor and operation
     * @param filename declared or multipart-supplied original filename, stored as the display label
     * @param mime declared or multipart-supplied content type, validated again by the intake
     * @param bytes request body stream, consumed and closed by the writer rather than by this method
     * @return receipt naming the request, the kind, the registered object id and the resulting version
     * @throws IOException when the request stream cannot be read or quarantine storage cannot be written
     * @throws AssetHandoverPort.Failure for an invalid kind (400), a non-creator or non-processor
     *     uploader (403), an unknown request (404), a changed request or disposition (409), an
     *     idempotency key reused with a different payload (409), a rejected scan (422) or an
     *     unavailable scanner (503)
     */
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

    /**
     * Reports what the caller may do with the evidence of one scrap request, so a client can hide
     * upload affordances instead of discovering the refusal on submit. The capability flags and the two
     * version numbers are computed together under the same lock, which lets the client send the
     * returned version straight back as {@code If-Match-Version}.
     *
     * @param actor reading actor whose sensitive-field permission decides {@code canRead}
     * @param id fams_scrap_request primary key whose capabilities are reported
     * @return capability flags plus the current request version and, when a disposition row exists, its
     *     version
     * @throws AssetHandoverPort.Failure 404 {@code SCRAP_NOT_FOUND} when the request does not exist
     */
    @Transactional
    public ScrapViews.AttachmentCapabilities capabilities(Actor actor, long id) {
        LOG.debug("Reading scrap attachment capabilities actor={} scrapId={}", actor.id(), id);
        return transactions.capabilities(actor, id);
    }

    /**
     * Lists the metadata of the evidence already registered for one kind of one scrap request. Reading
     * the request first applies the ordinary scrap visibility rules, and the sensitive-access check is
     * applied on top of them because attachment names and sizes are treated as sensitive fields.
     *
     * @param actor reading actor, required to hold the scrap attachment field permission
     * @param id fams_scrap_request primary key the evidence belongs to
     * @param kind either {@code request} or {@code disposition}, mapped to the attachment business type
     * @return metadata rows of that business type and scope, in registry order, without object content
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_ATTACHMENT_KIND_INVALID} for any other kind, or
     *     404 {@code SCRAP_NOT_FOUND} when the request does not exist
     * @throws com.fams.auth.AuthOperationException 403 when the actor lacks the sensitive read permission
     */
    @Transactional
    public List<AttachmentEvidencePort.Metadata> list(Actor actor, long id, String kind) {
        LOG.debug("Listing scrap attachments actor={} scrapId={} kind={}", actor.id(), id, kind);
        transactions.reader(actor, id);
        return evidence.list(ScrapAttachmentTransactions.type(kind), id);
    }

    /**
     * Issues a short-lived download grant for one stored object. The grant binds the object id to the
     * caller's account id, authentication epoch and authorization version, so it stops working when the
     * session or the permission set changes even inside its one-minute lifetime. The controller turns
     * the token into an absolute content URL under the prefix the caller used.
     *
     * @param actor reading actor whose identity is baked into the signed grant
     * @param id fams_scrap_request primary key the evidence belongs to
     * @param kind either {@code request} or {@code disposition}, mapped to the attachment business type
     * @param object attachment object id to grant access to
     * @return signed grant carrying the token and its expiry as an epoch second
     * @throws AssetHandoverPort.Failure 400 for an invalid kind or 404 when the request does not exist
     * @throws com.fams.attachment.AttachmentAccessFailure 404 {@code ATTACHMENT_NOT_FOUND} when the
     *     object is not registered under that type and scope, or 409 {@code ATTACHMENT_NOT_CLEAN} when
     *     its scan result does not permit disclosure
     * @throws com.fams.auth.AuthOperationException 403 when the actor lacks the sensitive read permission
     */
    @Transactional
    public AttachmentGrantSigner.Grant grant(Actor actor, long id, String kind, UUID object) {
        LOG.debug("Granting scrap attachment actor={} scrapId={} kind={} object={}", actor.id(), id, kind, object);
        transactions.reader(actor, id);
        return evidence.grant(ScrapAttachmentTransactions.type(kind), id, identity(actor, object));
    }

    /**
     * Reads the bytes of one stored object against a previously issued grant. The scrap-side reader
     * check runs again here rather than being trusted from the grant step, so a permission revoked
     * between the two calls takes effect immediately.
     *
     * @param actor reading actor, whose identity must match the one the grant was issued to
     * @param id fams_scrap_request primary key the evidence belongs to
     * @param kind either {@code request} or {@code disposition}, mapped to the attachment business type
     * @param object attachment object id whose content is requested
     * @param token signed grant token presented by the client as the {@code grant} query parameter
     * @return content bundle carrying the bytes, the stored MIME type and the stored display filename
     * @throws IOException when quarantine storage cannot be read
     * @throws AssetHandoverPort.Failure 400 for an invalid kind or 404 when the request does not exist
     * @throws com.fams.attachment.AttachmentAccessFailure 403 {@code ATTACHMENT_GRANT_INVALID} for a
     *     malformed, expired or identity-mismatched token, 404 when the object is not registered, or
     *     409 on {@code ATTACHMENT_CONTENT_CHANGED} and {@code ATTACHMENT_NOT_CLEAN}
     * @throws com.fams.auth.AuthOperationException 403 when the actor lacks the sensitive read permission
     */
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
