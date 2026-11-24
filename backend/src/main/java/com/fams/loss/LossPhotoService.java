package com.fams.loss;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Scanning occurs without a business transaction; registration rechecks current authority. */
@Service
@Profile("fams-product")
@ConditionalOnProperty(name = "fams.attachments.quarantine-root")
public class LossPhotoService {
    private static final Logger LOG = LoggerFactory.getLogger(LossPhotoService.class);
    private final LossService reports;
    private final AttachmentWriter writer;
    private final AttachmentStorage storage;
    private final AttachmentEvidencePort evidence;

    /**
     * Wires the photo service to the loss domain checks and the attachment infrastructure.
     *
     * @param reports loss service providing uploader/access authority checks and photo registration
     * @param writer attachment writer performing quarantine storage and malware scanning
     * @param storage attachment storage used to delete orphaned objects after a failed registration
     * @param evidence attachment evidence port issuing download grants and reading content
     */
    public LossPhotoService(
            LossService reports,
            AttachmentWriter writer,
            AttachmentStorage storage,
            AttachmentEvidencePort evidence) {
        this.reports = reports;
        this.writer = writer;
        this.storage = storage;
        this.evidence = evidence;
    }

    /**
     * Uploads one photo for a loss report awaiting the admin decision. The creator check runs in
     * its own transaction first; the bytes are then quarantined and scanned outside any business
     * transaction, and the registration callback re-checks authority and report state under lock.
     * When the returned receipt references a different object (an idempotent replay of an earlier
     * upload), the freshly stored duplicate is deleted so no orphan remains; the writer itself
     * removes quarantined bytes when registration rejects them.
     *
     * @param actor captured actor, must be the report creator
     * @param id loss report id
     * @param version expected report version (If-Match)
     * @param key idempotency key identifying this upload attempt
     * @param filename original filename as supplied by the uploader
     * @param mime declared content type of the upload
     * @param bytes stream of the uploaded file, consumed by the quarantine writer
     * @return receipt with the report id, attachment object id and the new report version
     * @throws IOException if reading the upload stream or writing to quarantine storage fails
     * @throws LossFailure with the authority, state, scan or format codes raised by
     *         {@link LossService#registerPhoto}
     */
    public LossViews.PhotoReceipt upload(
            Actor actor,
            long id,
            long version,
            UUID key,
            String filename,
            String mime,
            InputStream bytes)
            throws IOException {
        reports.photoUploader(actor, id);
        var received = new AtomicReference<UUID>();
        var result =
                writer.write(
                        filename,
                        mime,
                        bytes,
                        attachment -> {
                            received.set(attachment.file().id());
                            return reports.registerPhoto(
                                    actor, id, version, key, filename, attachment);
                        });
        if (!received.get().toString().equals(result.objectId())) {
            storage.delete(received.get());
        }
        LOG.info("Loss photo uploaded for report {} by actor {}.", id, actor.id());
        return result;
    }

    /**
     * Issues a short-lived download grant for one photo after checking that the report is visible
     * to the caller. The grant identity binds the photo object together with the caller's account
     * id, auth epoch and authorization version, so the evidence port can tie the token to the
     * session state it was issued under.
     *
     * @param actor captured actor whose report visibility is revalidated
     * @param id loss report id owning the photo
     * @param object attachment object id of the photo
     * @return grant token with its expiry epoch second
     * @throws LossFailure with code {@code LOSS_NOT_FOUND} or {@code LOSS_NOT_VISIBLE} when the
     *         report is unknown or outside the caller's visibility scope
     */
    @Transactional
    public AttachmentGrantSigner.Grant grant(Actor actor, long id, UUID object) {
        LOG.debug("Loss photo grant requested for report {}.", id);
        reports.photoAccess(actor, id);
        return evidence.grant("LOST_REPORT", id, identity(actor, object));
    }

    /**
     * Reads one photo's bytes under a previously issued grant, after re-checking that the report
     * is still visible to the caller. Grant validation and byte retrieval are delegated to the
     * attachment evidence port.
     *
     * @param actor captured actor whose report visibility is revalidated
     * @param id loss report id owning the photo
     * @param object attachment object id of the photo
     * @param token grant token issued by {@link #grant}
     * @return content bundle with the bytes, mime type and filename for the response
     * @throws IOException if reading the object from storage fails
     * @throws LossFailure with code {@code LOSS_NOT_FOUND} or {@code LOSS_NOT_VISIBLE} when the
     *         report is unknown or outside the caller's visibility scope
     */
    @Transactional
    public AttachmentReadService.Content read(Actor actor, long id, UUID object, String token)
            throws IOException {
        LOG.debug("Loss photo read requested for report {}.", id);
        reports.photoAccess(actor, id);
        return evidence.read("LOST_REPORT", id, identity(actor, object), token);
    }

    private AttachmentGrantSigner.Identity identity(Actor actor, UUID object) {
        return new AttachmentGrantSigner.Identity(
                object, actor.id(), actor.authEpoch(), actor.authorizationVersion());
    }
}
