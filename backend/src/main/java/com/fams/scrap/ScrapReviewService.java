package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.loss.LossScrapSourcePort;
import com.fams.loan.LoanRepairPort;
import com.fams.repair.RepairService;
import com.fams.repair.RepairFactoryService.ScrapEvidence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every state transition a scrap request goes through after submission: the administrative
 * decision, the warehouse claim, the three execution flavours, cancellation, the follow-up completion
 * of a physical disposal, and the three follow-up flows that undo or repair an executed scrap —
 * correction, reversal and recovery. Submission itself lives in {@link ScrapService} and reads live in
 * {@link ScrapReadService}; the asset lifecycle changes are delegated to {@link AssetScrapPort}, which
 * is why this service never writes {@code fams_asset} directly.
 *
 * <p>All twenty-one entry points follow one fixed sequence, and the order is load-bearing. The actor is
 * revalidated under the identity lock and the required role is asserted first, so an unauthorised caller
 * cannot probe a row. The request row is then taken {@code SELECT ... FOR UPDATE} before any sub-record,
 * which makes the disposition, correction, reversal and recovery flows serialise against each other and
 * against evidence registration in the same order. Only then is idempotency resolved: a SHA-256
 * fingerprint of the pinned version plus the whole input payload is compared with the receipt stored for
 * this actor, operation and client key, and a match replays the stored receipt without touching a row
 * while a mismatch is a conflict. Status and version guards come next, then the cross-domain calls, then
 * the row updates, the todo maintenance, the audit event and finally the new receipt.
 *
 * <p>The request status vocabulary is {@code PENDING_ADMIN_DECISION}, {@code WAITING_WAREHOUSE_CLAIM},
 * {@code PROCESSING} and {@code EXECUTED}, plus the terminal {@code REJECTED}, {@code CANCELLED},
 * {@code RESTORE_REQUIRED} and {@code CORRECTED}. A repair-sourced or factory-loss-sourced request
 * inherits its processor from the repair domain and therefore skips the claim step, going straight from
 * an approval to {@code PROCESSING}. {@code RESTORE_REQUIRED} is the escape hatch for a rejection or
 * cancellation whose asset could not be restored automatically — because a loan opened in between, or the
 * repair side no longer matches — and it is the only state from which a correction may be requested.
 *
 * <p>Physical reversibility is enforced at three levels. {@code physicalSource} limits the physical
 * execution, the disposition follow-up and the reversal to {@code STANDALONE} and {@code REPAIR}
 * requests, since a loss write-off and a factory replacement have no physical asset left to move. Every
 * physical step additionally demands a verified identity, an evidence text of at least ten characters and
 * an occurrence instant that is millisecond-precise and falls inside the window opened by the row it
 * completes; and a disposal method that differs from the approved one is always refused as outside the
 * approval rather than being silently accepted.
 */
@Service
@Profile("fams-product")
public class ScrapReviewService {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapReviewService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;
    private final com.fams.loan.LoanLossPort loans;
    private final LossScrapSourcePort losses;
    private final RepairService repairs;
    private final com.fams.auth.ScrapSensitiveAccess sensitive;
    private final ObjectProvider<AttachmentEvidencePort> evidence;

    /**
     * Wires the review flow to its store, its cross-domain ports and the optional evidence port. The
     * attachment port is injected as an {@link ObjectProvider} rather than directly because the whole
     * attachment surface is conditional on {@code fams.attachments.quarantine-root}; a deployment without
     * quarantine storage must still be able to review and execute scrap requests.
     *
     * @param db JDBC template owning the request, correction, reversal, recovery, disposition, lock, todo,
     *     event and receipt writes
     * @param json mapper serialising decision, approval and execution snapshots and the fingerprint payloads
     * @param clock time source for the truncated millisecond timestamp shared by every row of one transition
     * @param actors actor context supplying the locked revalidation used by every entry point
     * @param assets asset-side scrap port performing the lifecycle transitions and the version assertions
     * @param loans loan-domain port asserting and terminating borrowings affected by a loss write-off
     * @param losses loss-domain port re-reading the confirmed loss evidence at execution time
     * @param repairs repair-domain port closing, restoring and validating repair and factory sources
     * @param sensitive gate consulted before attachment evidence is folded into a decision snapshot
     * @param evidence optional attachment evidence port, absent when quarantine storage is not configured
     */
    public ScrapReviewService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, AssetScrapPort assets, com.fams.loan.LoanLossPort loans,
            LossScrapSourcePort losses, RepairService repairs, com.fams.auth.ScrapSensitiveAccess sensitive,
            ObjectProvider<AttachmentEvidencePort> evidence) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.loans = loans;
        this.losses = losses;
        this.repairs = repairs;
        this.sensitive = sensitive;
        this.evidence = evidence;
    }

    /**
     * Administrative decision payload for a pending scrap request. The result is validated against the
     * two accepted literals inside {@link #decide} rather than by an annotation, because the additional
     * rule — an approval must carry a disposition — depends on which literal was chosen.
     *
     * @param assetVersion pinned fams_asset version the decision expects, compared under lock
     * @param lockVersion pinned version of the unreleased scrap business lock the decision expects
     * @param result either {@code APPROVE} or {@code REJECT}
     * @param reason justification of the decision, at most 1000 characters
     * @param approvedDisposition disposal method the administrator approves, at most 500 characters;
     *     required on approval, ignored on rejection, and for a loss source it must be a write-off
     */
    public record Decision(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 500) String approvedDisposition) {}

    /**
     * Warehouse claim payload. It carries no field of its own beyond the two pinned versions, because
     * claiming only records who will process the request; everything else is read from the locked row.
     *
     * @param assetVersion pinned fams_asset version the claim expects, compared under lock
     * @param lockVersion pinned version of the unreleased scrap business lock the claim expects
     */
    public record Claim(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion) {}

    /**
     * Cancellation payload. Cancellation is the only way out of a pending request that leaves an audit
     * reason behind without a decision, so the reason is mandatory even though nothing is being judged.
     *
     * @param assetVersion pinned fams_asset version the cancellation expects, compared under lock
     * @param lockVersion pinned version of the unreleased scrap business lock to release
     * @param reason justification of the cancellation, at most 1000 characters
     */
    public record Cancellation(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Acknowledgement of any review transition, serialised into {@code fams_scrap_receipt.response_json}
     * and replayed verbatim on an idempotent retry. It reports the version of the row the transition
     * acted on — the request row for a request-level step, the sub-record for a correction, reversal or
     * recovery step — which is why a client can chain the returned version straight into the next call.
     *
     * @param id stringified fams_scrap_request primary key the transition belongs to
     * @param assetId stringified fams_asset primary key the request targets
     * @param version version of the acted-on row after the transition
     * @param assetVersion asset version after the transition, bumped only when the asset itself moved
     * @param ownershipVersion ownership version after the transition, bumped by a claim
     * @param status resulting status of the acted-on row
     * @param processorAccountId stringified account now processing the request, null when it is unassigned
     */
    public record Receipt(String id, String assetId, long version, long assetVersion,
            long ownershipVersion, String status, String processorAccountId) {}

    static boolean mayCancel(Current current, Map<String, Object> request) {
        if (!"STANDALONE".equals(request.get("source_type"))) {
            return false;
        }
        Object status = request.get("request_status");
        if ("PENDING_ADMIN_DECISION".equals(status)) {
            return current.has(Role.ASSET_ADMIN) || Objects.equals(current.id(), request.get("creator_account_id"));
        }
        return current.has(Role.ASSET_ADMIN)
                && List.of("WAITING_WAREHOUSE_CLAIM", "PROCESSING").contains(status);
    }

    /**
     * Cancels a standalone scrap request and puts its asset back. Only a {@code STANDALONE} request can
     * be cancelled, because a loss-sourced or repair-sourced one is the only scrap record of an event
     * that already happened elsewhere and must instead be rejected so the source flow is restored. While
     * the request is still awaiting a decision the creator may cancel it themselves; once it has been
     * claimed or is being processed only an asset administrator may, since a warehouse operator is
     * already working on it.
     *
     * <p>Restoring the asset is attempted rather than assumed. When a borrowing opened against the asset
     * after the scrap froze it, or the asset side cannot restore the frozen custody, the cancellation
     * still succeeds but the request ends in {@code RESTORE_REQUIRED} with an administrator todo instead
     * of {@code CANCELLED}, and the business lock is deliberately left unreleased so the asset stays
     * blocked until the manual restore happens. Only a fully restored cancellation releases the lock, and
     * that release compares the pinned lock version, so a concurrent lock change is a conflict.
     *
     * <p>The approval, claim and execute todos are closed in every branch, so a cancelled request never
     * leaves work items behind.
     *
     * @param actor cancelling actor, who must be an asset administrator or the request creator
     * @param id fams_scrap_request primary key to cancel
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code CANCEL:<id>}
     * @param input cancellation payload carrying the pinned asset and lock versions and the reason
     * @return receipt reporting the request version after cancellation, the asset version, the resulting
     *     {@code CANCELLED} or {@code RESTORE_REQUIRED} status and any inherited processor
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_CANCEL_FORBIDDEN} when the actor is neither an
     *     administrator nor the creator, 404 {@code SCRAP_NOT_FOUND}, 409
     *     {@code SCRAP_CANCEL_NOT_ALLOWED} when the source, status or a pinned version does not permit
     *     cancellation, 409 {@code SCRAP_ASSET_OR_LOCK_CHANGED} when the lock release does not affect
     *     exactly one row, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key with a different
     *     fingerprint, or any refusal {@link AssetScrapPort#requirePending} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt cancel(Actor actor, long id, long version, UUID key, Cancellation input) {
        var current = actors.revalidateLocked(actor);
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        boolean admin = current.has(Role.ASSET_ADMIN);
        if (!admin && !Objects.equals(current.id(), request.get("creator_account_id"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CANCEL_FORBIDDEN", 403);
        }
        String operation = "CANCEL:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!mayCancel(current, request) || number(request, "version") != version
                || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CANCEL_NOT_ALLOWED", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requirePending(asset, input.assetVersion(), id, input.lockVersion());
        var source = json.readTree(request.get("source_snapshot_json").toString());
        boolean restored = !loans.hasOpenBorrowing(asset)
                && assets.restoreStandaloneCancellation(asset, input.assetVersion(), id, source, current.id(), time);
        long assetVersion = restored ? input.assetVersion() + 1 : input.assetVersion();
        String status = restored ? "CANCELLED" : "RESTORE_REQUIRED";
        if (restored) {
            if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_CANCELLED',"
                    + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                    + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                    time, asset, id, id, input.lockVersion()) != 1) {
                throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
            }
        } else {
            todo(id, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        db.update("UPDATE fams_scrap_request SET request_status=?,asset_version=?,version=version+1,updated_at=? "
                + "WHERE id=?", status, assetVersion, time, id);
        for (String task : List.of("APPROVAL", "CLAIM", "EXECUTE")) {
            closeTodo(id, task, time);
        }
        Role role = admin ? Role.ASSET_ADMIN : current.roles().stream().sorted().findFirst()
                .orElseThrow(() -> new AssetHandoverPort.Failure("SCRAP_CANCEL_FORBIDDEN", 403));
        audit(actor, current, role, id, version, restored ? "SCRAP_CANCELLED" : "SCRAP_CANCEL_RESTORE_REQUIRED",
                Map.of("reason", input.reason().strip()), time);
        LOG.info("Cancelled scrap actor={} requestId={} asset={} restored={}", current.id(), id, asset, restored);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, number(request, "ownership_version"), status,
                Objects.toString(request.get("processor_account_id"), null)), time);
    }

    /**
     * Correction initiation payload. A correction exists because a cancelled or rejected scrap could not
     * restore its asset automatically, leaving the request in {@code RESTORE_REQUIRED}; the administrator
     * therefore names the bin the asset is to be returned to at initiation time, and that choice is
     * pinned by version and re-checked at approval and at execution.
     *
     * @param assetVersion pinned fams_asset version, still held by the unreleased scrap business lock
     * @param lockVersion pinned version of that lock, which the completed correction releases
     * @param locationId bin location to return the asset to
     * @param locationVersion pinned version of that bin, compared again before each later step
     * @param reason justification for the correction, at most 1000 characters
     */
    public record CorrectionRequest(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion, @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Correction decision payload. The target bin is not part of it: an administrator approves or rejects
     * the bin the initiator already pinned, and a different bin requires a fresh correction request.
     *
     * @param assetVersion pinned fams_asset version the decision expects
     * @param lockVersion pinned version of the scrap business lock the decision expects
     * @param result either {@code APPROVE} or {@code REJECT}, validated inside {@link #decideCorrection}
     * @param reason justification of the decision, at most 1000 characters
     */
    public record CorrectionDecision(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion, @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Correction claim payload, and also the internal shape the other correction steps reuse to carry a
     * pair of pinned versions into the shared guard. Claiming assigns the correction to the calling
     * warehouse operator and bumps its ownership version; nothing else is supplied by the client.
     *
     * @param assetVersion pinned fams_asset version the claim expects
     * @param lockVersion pinned version of the scrap business lock the claim expects
     */
    public record CorrectionClaim(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion) {}

    /**
     * Correction execution payload, carrying the physical facts the warehouse operator observed while
     * returning the asset to the pinned bin. This is the step that actually moves the asset, so it pins
     * four versions and demands evidence: the observed code and serial must match the card, the identity
     * must have been verified physically, and the occurrence instant must be millisecond-precise and fall
     * between the correction's creation and now plus two seconds of clock tolerance.
     *
     * @param assetVersion pinned fams_asset version the execution expects
     * @param lockVersion pinned version of the scrap business lock to release on success
     * @param ownershipVersion ownership version returned by the claim, proving the caller still holds it
     * @param locationVersion pinned version of the target bin, re-read from the correction row and compared
     * @param observedCode asset code read off the physical asset, at most 64 characters
     * @param observedSerial serial number read off the physical asset, at most 128 characters, optional
     * @param occurredAt instant the physical return happened, inside the window described above
     * @param physicalIdentityVerified must be {@code true}; a false or null value is a 400
     * @param evidence free-text description of what was verified, between 10 and 2000 characters
     */
    public record CorrectionExecution(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion, @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 64) String observedCode,
            @Size(max = 128) String observedSerial, @NotNull Instant occurredAt,
            @NotNull Boolean physicalIdentityVerified, @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    /**
     * Opens a correction for a scrap request stuck in {@code RESTORE_REQUIRED}. Only an asset
     * administrator may initiate one, only for a {@code STANDALONE} request, and only while no active
     * correction exists — the {@code active_guard} lookup enforces that, so two administrators racing
     * produce one correction and one 409. The {@code RESTORE_REQUIRED} todo is closed and replaced by a
     * decision todo, moving the work from "restore this somehow" to "decide this correction".
     *
     * <p>The request row itself is not bumped: the correction is a separate record with its own version
     * chain, and the receipt therefore reports the unchanged request version together with the pinned
     * asset version. The target bin must already be an active bin at the pinned version, so a correction
     * cannot be opened against a location that was deactivated meanwhile.
     *
     * @param actor initiating asset administrator, recorded as the correction creator
     * @param id fams_scrap_request primary key in {@code RESTORE_REQUIRED}
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code CORRECTION:<id>}
     * @param input correction payload pinning the asset and lock versions and naming the target bin
     * @return receipt reporting the unchanged request version, the pinned asset version and the
     *     {@code PENDING_ADMIN_DECISION} correction status with no processor yet
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the administrator role,
     *     404 {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_CORRECTION_NOT_ALLOWED} when the source, status
     *     or a pinned version does not permit a correction, 409 {@code SCRAP_CORRECTION_OPEN} when an
     *     active correction already exists, 409 {@code SCRAP_CORRECTION_TARGET_CHANGED} when the bin is
     *     no longer active at the pinned version, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key,
     *     or any refusal {@link AssetScrapPort#requirePending} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt requestCorrection(Actor actor, long id, long version, UUID key, CorrectionRequest input) {
        var current = current(actor, Role.ASSET_ADMIN);
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        String operation = "CORRECTION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!"STANDALONE".equals(request.get("source_type"))
                || !"RESTORE_REQUIRED".equals(request.get("request_status"))
                || number(request, "version") != version || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_NOT_ALLOWED", 409);
        }
        if (!db.queryForList("SELECT id FROM fams_scrap_correction WHERE request_id=? AND active_guard=1"
                + " FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_OPEN", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requirePending(asset, input.assetVersion(), id, input.lockVersion());
        if (!assets.matchesActiveBin(input.locationId(), input.locationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        db.update("INSERT INTO fams_scrap_correction(request_id,asset_id,creator_account_id,reason,asset_version,"
                + "lock_version,target_location_id,target_location_version,correction_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,'PENDING_ADMIN_DECISION',?,?,?)",
                id, asset, current.id(), input.reason().strip(), input.assetVersion(), input.lockVersion(),
                input.locationId(), input.locationVersion(), json.writeValueAsString(input), time, time);
        long correction = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        closeTodo(id, "RESTORE_REQUIRED", time);
        todo(id, "CORRECTION_DECIDE", "ASSET_ADMIN", null, time);
        audit(actor, current, Role.ASSET_ADMIN, id, number(request, "version"), "SCRAP_CORRECTION_REQUESTED",
                Map.of("correctionId", Long.toString(correction), "input", input), time);
        LOG.info("Requested scrap correction actor={} requestId={} correctionId={}", current.id(), id, correction);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                number(request, "version"), input.assetVersion(), number(request, "ownership_version"),
                "PENDING_ADMIN_DECISION", null), time);
    }

    private Map<String, Object> correctionGuarded(long id, long version, CorrectionClaim input) {
        var rows = db.queryForList("SELECT * FROM fams_scrap_correction WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_NOT_FOUND", 404);
        }
        var correction = rows.getFirst();
        if (number(correction, "version") != version || number(correction, "asset_version") != input.assetVersion()
                || number(correction, "lock_version") != input.lockVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        var requests = db.queryForList("SELECT request_status FROM fams_scrap_request WHERE id=? FOR UPDATE",
                number(correction, "request_id"));
        if (requests.isEmpty() || !"RESTORE_REQUIRED".equals(requests.getFirst().get("request_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        assets.requirePending(number(correction, "asset_id"), input.assetVersion(),
                number(correction, "request_id"), input.lockVersion());
        return correction;
    }

    /**
     * Approves or rejects an open correction. The identifier is the correction row, not the request,
     * which is why the receipt reports the parent request id read back from the locked correction.
     *
     * <p>The shared guard runs before the status check and re-asserts the whole chain: the correction at
     * the pinned version, asset version and lock version, the parent request still in
     * {@code RESTORE_REQUIRED}, and the asset still held by that lock at that version. An approval
     * additionally re-checks that the target bin is still an active bin at the version pinned at
     * initiation, so a location deactivated while the correction waited cannot be approved into.
     *
     * <p>The two outcomes move the todo in opposite directions: an approval closes the decision item and
     * opens a claim item for the warehouse pool, while a rejection closes the decision item and reopens
     * {@code RESTORE_REQUIRED} for administrators, because rejecting a correction leaves the asset just
     * as unrestored as before.
     *
     * @param actor deciding asset administrator, recorded in the approval snapshot
     * @param id fams_scrap_correction primary key to decide
     * @param version correction row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code CORRECTION_DECISION:<id>}
     * @param input decision payload pinning the asset and lock versions and carrying the result and reason
     * @return receipt naming the parent request, the correction version after the decision, the unchanged
     *     asset version and the resulting {@code WAITING_WAREHOUSE_CLAIM} or {@code REJECTED} status
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_CORRECTION_INPUT_INVALID} for a result that is
     *     neither {@code APPROVE} nor {@code REJECT}, 403 {@code SCRAP_ROLE_REQUIRED} without the
     *     administrator role, 404 {@code SCRAP_CORRECTION_NOT_FOUND}, 409
     *     {@code SCRAP_CORRECTION_CHANGED} on any version, parent-status or correction-status mismatch,
     *     409 {@code SCRAP_CORRECTION_TARGET_CHANGED} when the pinned bin is no longer active, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requirePending} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt decideCorrection(Actor actor, long id, long version, UUID key, CorrectionDecision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_INPUT_INVALID", 400);
        }
        String operation = "CORRECTION_DECISION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var correction = correctionGuarded(id, version, new CorrectionClaim(input.assetVersion(), input.lockVersion()));
        if (!"PENDING_ADMIN_DECISION".equals(correction.get("correction_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        long request = number(correction, "request_id"), asset = number(correction, "asset_id");
        var time = now();
        if (approve && !assets.matchesActiveBin(number(correction, "target_location_id"),
                number(correction, "target_location_version"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_correction SET correction_status=?,approval_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), time, id);
        closeTodo(request, "CORRECTION_DECIDE", time);
        if (approve) {
            todo(request, "CORRECTION_CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else {
            todo(request, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        audit(actor, current, Role.ASSET_ADMIN, request, version,
                approve ? "SCRAP_CORRECTION_APPROVED" : "SCRAP_CORRECTION_REJECTED",
                Map.of("correctionId", Long.toString(id), "input", input), time);
        LOG.info("Decided scrap correction actor={} correctionId={} approved={}", current.id(), id, approve);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), number(correction, "ownership_version"),
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED", null), time);
    }

    /**
     * Assigns an approved correction to the calling warehouse operator. Claiming is what makes the later
     * execution single-writer: the processor column is set here and the execution step refuses anybody
     * else, and the ownership version is bumped so the executor must prove it still holds the claim.
     *
     * <p>The claim todo is closed and an execute todo is opened against this specific operator, so the
     * work item follows the person rather than staying in the pool.
     *
     * @param actor claiming warehouse operator, recorded as the correction processor
     * @param id fams_scrap_correction primary key to claim
     * @param version correction row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code CORRECTION_CLAIM:<id>}
     * @param input claim payload pinning the asset and lock versions
     * @return receipt naming the parent request, the correction version after the claim, the bumped
     *     ownership version, the {@code PROCESSING} status and the claiming operator
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the warehouse operator
     *     role, 404 {@code SCRAP_CORRECTION_NOT_FOUND}, 409 {@code SCRAP_CORRECTION_CHANGED} when the
     *     correction is not awaiting a claim or any pinned version moved, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requirePending} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt claimCorrection(Actor actor, long id, long version, UUID key, CorrectionClaim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "CORRECTION_CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var correction = correctionGuarded(id, version, input);
        if (!"WAITING_WAREHOUSE_CLAIM".equals(correction.get("correction_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        long request = number(correction, "request_id"), asset = number(correction, "asset_id");
        var time = now();
        long ownership = number(correction, "ownership_version") + 1;
        db.update("UPDATE fams_scrap_correction SET correction_status='PROCESSING',processor_account_id=?,"
                + "ownership_version=?,version=version+1,updated_at=? WHERE id=?", current.id(), ownership, time, id);
        closeTodo(request, "CORRECTION_CLAIM", time);
        todo(request, "CORRECTION_EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_CORRECTION_CLAIMED", input, time);
        LOG.info("Claimed scrap correction actor={} correctionId={} requestId={}", current.id(), id, request);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    /**
     * Returns the asset to the pinned bin and closes both the correction and the scrap request. The
     * processor check runs before the idempotency lookup, so an operator who never claimed this
     * correction cannot replay somebody else's receipt.
     *
     * <p>This is the only step that ends a {@code RESTORE_REQUIRED} request successfully: the asset side
     * restores the corrected custody and lifecycle, the scrap business lock is released with reason
     * {@code SCRAP_CORRECTED} under the pinned lock version, the request moves to the terminal
     * {@code CORRECTED} status and the correction to {@code COMPLETED} with the execution snapshot
     * attached. The lock release is asserted to affect exactly one row, because a release that matched
     * nothing would mean the asset is still blocked by a lock nobody can name.
     *
     * <p>The physical evidence window is anchored on the correction's own creation time, not on the
     * request's, so an operator cannot backdate a return to before the correction existed. The target bin
     * version is re-read from the correction row and compared with the payload, which catches a client
     * that refreshed the location without re-pinning it.
     *
     * @param actor executing warehouse operator, who must hold the correction as processor
     * @param id fams_scrap_correction primary key to execute
     * @param version correction row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code CORRECTION_EXECUTE:<id>}
     * @param input execution payload pinning four versions and carrying the observed physical facts
     * @return receipt naming the parent request, the correction version after execution, the asset
     *     version after the restore, the {@code COMPLETED} status and the executing operator
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_PHYSICAL_EVIDENCE_INVALID} when the identity was
     *     not verified, the evidence is too short or the occurrence instant is outside the window, 403
     *     {@code SCRAP_ROLE_REQUIRED} or 403 {@code SCRAP_PROCESSOR_REQUIRED}, 404
     *     {@code SCRAP_CORRECTION_NOT_FOUND}, 409 {@code SCRAP_CORRECTION_CHANGED} on a version or status
     *     mismatch, 409 {@code SCRAP_CORRECTION_TARGET_CHANGED} when the pinned bin version differs, 409
     *     {@code SCRAP_ASSET_OR_LOCK_CHANGED} when the lock release does not affect exactly one row, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal the asset port raises while
     *     restoring the corrected asset
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt executeCorrection(Actor actor, long id, long version, UUID key, CorrectionExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        var processor = db.queryForList(
                "SELECT processor_account_id FROM fams_scrap_correction WHERE id=? FOR UPDATE", id);
        if (processor.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_NOT_FOUND", 404);
        }
        if (!Objects.equals(processor.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        String operation = "CORRECTION_EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var correction = correctionGuarded(id, version, new CorrectionClaim(input.assetVersion(), input.lockVersion()));
        if (!"PROCESSING".equals(correction.get("correction_status"))
                || number(correction, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_CHANGED", 409);
        }
        long request = number(correction, "request_id"), asset = number(correction, "asset_id");
        var time = now();
        Instant created = instant(correction.get("created_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(created) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (number(correction, "target_location_version") != input.locationVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_CORRECTION_TARGET_CHANGED", 409);
        }
        long assetVersion = assets.restoreCorrected(asset, input.assetVersion(), request,
                number(correction, "target_location_id"), input.locationVersion(),
                input.observedCode(), input.observedSerial(), current.id(), time);
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_CORRECTED',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                time, asset, request, request, input.lockVersion()) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_request SET request_status='CORRECTED',asset_version=?,"
                + "version=version+1,updated_at=? WHERE id=?", assetVersion, time, request);
        db.update("UPDATE fams_scrap_correction SET correction_status='COMPLETED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(input), assetVersion, time, id);
        closeTodo(request, "CORRECTION_EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_CORRECTION_EXECUTED", input, time);
        LOG.info("Executed scrap correction actor={} correctionId={} asset={}", current.id(), id, asset);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "COMPLETED", Long.toString(current.id())), time);
    }

    /**
     * Reversal initiation payload. A reversal is opened against an executed physical scrap whose
     * disposition is still pending, so unlike a correction it pins no lock version: the scrap business
     * lock was already released when the scrap was executed, and the asset is reversible precisely
     * because nothing else has taken it since.
     *
     * @param assetVersion pinned fams_asset version, which the asset side asserts is still reversible
     * @param locationId bin location to return the asset to
     * @param locationVersion pinned version of that bin, compared again before each later step
     * @param reason justification for undoing the scrap, at most 1000 characters
     */
    public record ReversalRequest(@NotNull @PositiveOrZero Long assetVersion, @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Reversal decision payload. As with a correction, the target bin is not re-choosable here; the
     * administrator judges the reversal the initiator pinned.
     *
     * @param assetVersion pinned fams_asset version the decision expects
     * @param result either {@code APPROVE} or {@code REJECT}, validated inside {@link #decideReversal}
     * @param reason justification of the decision, at most 1000 characters
     */
    public record ReversalDecision(@NotNull @PositiveOrZero Long assetVersion, @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Reversal claim payload, and the internal shape the other reversal steps reuse to carry the single
     * pinned asset version into the shared guard. Claiming assigns the reversal to the calling warehouse
     * operator and bumps its ownership version.
     *
     * @param assetVersion pinned fams_asset version the claim expects
     */
    public record ReversalClaim(@NotNull @PositiveOrZero Long assetVersion) {}

    /**
     * Reversal execution payload, carrying the physical facts observed while putting the scrapped asset
     * back into stock. The window for {@code occurredAt} is anchored on the reversal's creation time and
     * the same evidence minimum applies as for a correction, because a reversal is equally a claim that a
     * physical asset was found and identified.
     *
     * @param assetVersion pinned fams_asset version the execution expects
     * @param ownershipVersion ownership version returned by the claim, proving the caller still holds it
     * @param locationVersion pinned version of the target bin, re-read from the reversal row and compared
     * @param observedCode asset code read off the physical asset, at most 64 characters
     * @param observedSerial serial number read off the physical asset, at most 128 characters, optional
     * @param occurredAt instant the physical return happened, inside the window described above
     * @param physicalIdentityVerified must be {@code true}; a false or null value is a 400
     * @param evidence free-text description of what was verified, between 10 and 2000 characters
     */
    public record ReversalExecution(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion, @NotNull @PositiveOrZero Long locationVersion,
            @NotBlank @Size(max = 64) String observedCode, @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt, @NotNull Boolean physicalIdentityVerified,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    private static final List<Role> FOLLOW_UP_INITIATORS = List.of(Role.ASSET_ADMIN, Role.WAREHOUSE_OPERATOR,
            Role.DEPARTMENT_ASSET_MANAGER, Role.EMPLOYEE);

    /**
     * Opens a reversal against an executed physical scrap. Any of the four follow-up initiator roles may
     * do so, because the person who notices that an asset was scrapped by mistake is often the one who
     * originally reported it; an employee or a department asset manager is additionally restricted to
     * requests they created, while administrators and warehouse operators may act on any. The role that
     * matched first in the fixed order is the one recorded on the audit event.
     *
     * <p>Three conditions define the narrow window a reversal lives in: the request must be
     * {@code EXECUTED} from a physical source, its disposition must still be {@code PENDING_DISPOSITION},
     * and no active reversal may already exist. Once the disposal has been completed the asset is gone
     * and only a recovery — not a reversal — can bring it back. Opening the reversal closes the
     * disposition todo, so the warehouse pool is not simultaneously asked to dispose of an asset that is
     * being given back.
     *
     * @param actor initiating actor, whose role and creator identity are both checked
     * @param id fams_scrap_request primary key to reverse
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code REVERSAL:<id>}
     * @param input reversal payload pinning the asset version and naming the target bin
     * @return receipt naming the request, the unchanged request version, the pinned asset version and the
     *     {@code PENDING_ADMIN_DECISION} reversal status with no processor yet
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_REVERSAL_FORBIDDEN} when the actor holds none of
     *     the initiator roles or is not the creator of the request, 404 {@code SCRAP_NOT_FOUND}, 409
     *     {@code SCRAP_REVERSAL_NOT_ALLOWED} when the source, status, disposition or a pinned version does
     *     not permit a reversal, 409 {@code SCRAP_REVERSAL_OPEN} when one is already active, 409
     *     {@code SCRAP_REVERSAL_TARGET_CHANGED} when the bin is not active at the pinned version, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requireReversibleAsset} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt requestReversal(Actor actor, long id, long version, UUID key, ReversalRequest input) {
        var current = actors.revalidateLocked(actor);
        var initiator = FOLLOW_UP_INITIATORS.stream().filter(current::has).findFirst()
                .orElseThrow(() -> new AssetHandoverPort.Failure("SCRAP_REVERSAL_FORBIDDEN", 403));
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        if (initiator == Role.EMPLOYEE || initiator == Role.DEPARTMENT_ASSET_MANAGER) {
            if (!Objects.equals(current.id(), request.get("creator_account_id"))) {
                throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_FORBIDDEN", 403);
            }
        }
        String operation = "REVERSAL:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!physicalSource(request.get("source_type")) || !"EXECUTED".equals(request.get("request_status"))
                || number(request, "version") != version || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_ALLOWED", 409);
        }
        if (!db.queryForList("SELECT id FROM fams_scrap_reversal WHERE request_id=? AND active_guard=1"
                + " FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_OPEN", 409);
        }
        var disposition = db.queryForList("SELECT disposition_status FROM fams_scrap_disposition"
                + " WHERE request_id=? FOR UPDATE", id);
        if (disposition.isEmpty() || !"PENDING_DISPOSITION".equals(disposition.getFirst().get("disposition_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_ALLOWED", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requireReversibleAsset(asset, input.assetVersion());
        if (!assets.matchesActiveBin(input.locationId(), input.locationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        db.update("INSERT INTO fams_scrap_reversal(request_id,asset_id,creator_account_id,reason,asset_version,"
                + "target_location_id,target_location_version,reversal_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?, ?, ?, ?, ?, ?, ?,'PENDING_ADMIN_DECISION',?,?,?)",
                id, asset, current.id(), input.reason().strip(), input.assetVersion(),
                input.locationId(), input.locationVersion(), json.writeValueAsString(input), time, time);
        long reversal = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        closeTodo(id, "DISPOSITION", time);
        todo(id, "REVERSAL_DECIDE", "ASSET_ADMIN", null, time);
        audit(actor, current, initiator, id, number(request, "version"), "SCRAP_REVERSAL_REQUESTED",
                Map.of("reversalId", Long.toString(reversal), "input", input), time);
        LOG.info("Requested scrap reversal actor={} requestId={} reversalId={}", current.id(), id, reversal);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                number(request, "version"), input.assetVersion(), number(request, "ownership_version"),
                "PENDING_ADMIN_DECISION", null), time);
    }

    private Map<String, Object> reversalGuarded(long id, long version, long assetVersion) {
        var parents = db.queryForList("SELECT request_id FROM fams_scrap_reversal WHERE id=?", id);
        if (parents.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_FOUND", 404);
        }
        long request = number(parents.getFirst(), "request_id");
        // Lock the parent before the sub-record so disposition completion and reversal serialize identically.
        var scraps = db.queryForList("SELECT source_type FROM fams_scrap_request"
                + " WHERE id=? AND request_status='EXECUTED' FOR UPDATE", request);
        if (scraps.isEmpty() || !physicalSource(scraps.getFirst().get("source_type"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        var rows = db.queryForList("SELECT * FROM fams_scrap_reversal WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_NOT_FOUND", 404);
        }
        var reversal = rows.getFirst();
        if (number(reversal, "version") != version || number(reversal, "asset_version") != assetVersion) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        if (db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                + " AND disposition_status='PENDING_DISPOSITION' FOR UPDATE", request).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        assets.requireReversibleAsset(number(reversal, "asset_id"), assetVersion);
        return reversal;
    }

    /**
     * Approves or rejects an open reversal. The identifier is the reversal row, not the request, and the
     * shared guard locks the parent request before the reversal so this flow serialises identically with
     * disposition completion — the two operations that compete for the same pending disposition.
     *
     * <p>An approval re-checks that the target bin is still active at the pinned version and moves the
     * reversal to the warehouse claim queue. A rejection restores the disposition todo it displaced, and
     * restores it against the operator already recorded as the disposition processor rather than against
     * the pool, so the disposal work returns to the person who was holding it.
     *
     * @param actor deciding asset administrator, recorded in the approval snapshot
     * @param id fams_scrap_reversal primary key to decide
     * @param version reversal row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code REVERSAL_DECISION:<id>}
     * @param input decision payload pinning the asset version and carrying the result and reason
     * @return receipt naming the parent request, the reversal version after the decision, the unchanged
     *     asset version and the resulting {@code WAITING_WAREHOUSE_CLAIM} or {@code REJECTED} status
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_REVERSAL_INPUT_INVALID} for a result that is
     *     neither {@code APPROVE} nor {@code REJECT}, 403 {@code SCRAP_ROLE_REQUIRED} without the
     *     administrator role, 404 {@code SCRAP_REVERSAL_NOT_FOUND}, 409 {@code SCRAP_REVERSAL_CHANGED}
     *     when the parent is no longer an executed physical scrap, the pending disposition disappeared or
     *     any pinned version moved, 409 {@code SCRAP_REVERSAL_TARGET_CHANGED} when the pinned bin is no
     *     longer active, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requireReversibleAsset} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt decideReversal(Actor actor, long id, long version, UUID key, ReversalDecision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_INPUT_INVALID", 400);
        }
        String operation = "REVERSAL_DECISION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var reversal = reversalGuarded(id, version, input.assetVersion());
        if (!"PENDING_ADMIN_DECISION".equals(reversal.get("reversal_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        long request = number(reversal, "request_id"), asset = number(reversal, "asset_id");
        var time = now();
        if (approve && !assets.matchesActiveBin(number(reversal, "target_location_id"),
                number(reversal, "target_location_version"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_reversal SET reversal_status=?,approval_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), time, id);
        closeTodo(request, "REVERSAL_DECIDE", time);
        if (approve) {
            todo(request, "REVERSAL_CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else {
            var processor = db.queryForObject("SELECT processor_account_id FROM fams_scrap_disposition"
                    + " WHERE request_id=?", Long.class, request);
            todo(request, "DISPOSITION", "WAREHOUSE_OPERATOR", processor, time);
        }
        audit(actor, current, Role.ASSET_ADMIN, request, version,
                approve ? "SCRAP_REVERSAL_APPROVED" : "SCRAP_REVERSAL_REJECTED",
                Map.of("reversalId", Long.toString(id), "input", input), time);
        LOG.info("Decided scrap reversal actor={} reversalId={} approved={}", current.id(), id, approve);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), number(reversal, "ownership_version"),
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED", null), time);
    }

    /**
     * Assigns an approved reversal to the calling warehouse operator and bumps its ownership version, so
     * the execution step can require both the processor identity and the ownership version the claim
     * returned. The claim todo is closed and an execute todo is opened against this operator.
     *
     * @param actor claiming warehouse operator, recorded as the reversal processor
     * @param id fams_scrap_reversal primary key to claim
     * @param version reversal row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code REVERSAL_CLAIM:<id>}
     * @param input claim payload pinning the asset version
     * @return receipt naming the parent request, the reversal version after the claim, the bumped
     *     ownership version, the {@code PROCESSING} status and the claiming operator
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the warehouse operator
     *     role, 404 {@code SCRAP_REVERSAL_NOT_FOUND}, 409 {@code SCRAP_REVERSAL_CHANGED} when the
     *     reversal is not awaiting a claim or any guard condition moved, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requireReversibleAsset} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt claimReversal(Actor actor, long id, long version, UUID key, ReversalClaim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "REVERSAL_CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var reversal = reversalGuarded(id, version, input.assetVersion());
        if (!"WAITING_WAREHOUSE_CLAIM".equals(reversal.get("reversal_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        long request = number(reversal, "request_id"), asset = number(reversal, "asset_id");
        var time = now();
        long ownership = number(reversal, "ownership_version") + 1;
        db.update("UPDATE fams_scrap_reversal SET reversal_status='PROCESSING',processor_account_id=?,"
                + "ownership_version=?,version=version+1,updated_at=? WHERE id=?", current.id(), ownership, time, id);
        closeTodo(request, "REVERSAL_CLAIM", time);
        todo(request, "REVERSAL_EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_REVERSAL_CLAIMED", input, time);
        LOG.info("Claimed scrap reversal actor={} reversalId={} requestId={}", current.id(), id, request);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    /**
     * Puts a scrapped asset back into stock and marks its pending disposition reversed. The asset side
     * performs the lifecycle change; this method then requires the disposition update to affect exactly
     * one row, which is what guarantees the reversal and a concurrent disposition completion cannot both
     * succeed — whichever transaction loses the parent lock finds the disposition no longer pending.
     *
     * <p>The request row itself is deliberately left at {@code EXECUTED}: the scrap did happen and its
     * history must stay intact, so the reversal is recorded on its own row and on the disposition rather
     * than by rewriting the request status. Consequently the version in the receipt is the reversal row
     * version, not the request version.
     *
     * <p>Both the execute todo and the disposition todo are closed, since a reversal removes the work the
     * disposition todo was tracking.
     *
     * @param actor executing warehouse operator, who must hold the reversal as processor
     * @param id fams_scrap_reversal primary key to execute
     * @param version reversal row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code REVERSAL_EXECUTE:<id>}
     * @param input execution payload pinning three versions and carrying the observed physical facts
     * @return receipt naming the parent request, the reversal version after execution, the asset version
     *     after the return to stock, the {@code COMPLETED} status and the executing operator
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_PHYSICAL_EVIDENCE_INVALID} when the identity was
     *     not verified, the evidence is too short or the occurrence instant is outside the window, 403
     *     {@code SCRAP_ROLE_REQUIRED} or 403 {@code SCRAP_PROCESSOR_REQUIRED}, 404
     *     {@code SCRAP_REVERSAL_NOT_FOUND}, 409 {@code SCRAP_REVERSAL_CHANGED} when a guard condition
     *     moved or the disposition was no longer pending to reverse, 409
     *     {@code SCRAP_REVERSAL_TARGET_CHANGED} when the pinned bin version differs, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#reverseToStock} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt executeReversal(Actor actor, long id, long version, UUID key, ReversalExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "REVERSAL_EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var reversal = reversalGuarded(id, version, input.assetVersion());
        if (!"PROCESSING".equals(reversal.get("reversal_status"))
                || number(reversal, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        if (!Objects.equals(reversal.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        long request = number(reversal, "request_id"), asset = number(reversal, "asset_id");
        var time = now();
        Instant created = instant(reversal.get("created_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(created) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (number(reversal, "target_location_version") != input.locationVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_TARGET_CHANGED", 409);
        }
        long assetVersion = assets.reverseToStock(asset, input.assetVersion(), request,
                number(reversal, "target_location_id"), input.locationVersion(),
                input.observedCode(), input.observedSerial(), current.id(), time);
        if (db.update("UPDATE fams_scrap_disposition SET disposition_status='REVERSED',updated_at=?,"
                + "version=version+1 WHERE request_id=? AND disposition_status='PENDING_DISPOSITION'",
                time, request) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_REVERSAL_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_reversal SET reversal_status='COMPLETED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(input), assetVersion, time, id);
        closeTodo(request, "REVERSAL_EXECUTE", time);
        closeTodo(request, "DISPOSITION", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_REVERSAL_EXECUTED", input, time);
        LOG.info("Executed scrap reversal actor={} reversalId={} asset={}", current.id(), id, asset);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "COMPLETED", Long.toString(current.id())), time);
    }

    /**
     * Recovery initiation payload. A recovery follows a loss write-off: the asset was financially removed
     * and then physically found, so it has to be re-registered. That is a two-stage move and the payload
     * names both stages up front — a temporary bin with a named temporary custodian where the asset is
     * parked while the recovery is being approved, and the final target bin it will be returned to.
     *
     * @param assetVersion pinned fams_asset version, which the asset side asserts is still recoverable
     * @param foundAt instant the asset was physically found; must not precede the write-off, must not be
     *     more than two seconds in the future and must be millisecond-precise
     * @param temporaryLocationId location the asset is parked in while the recovery is open
     * @param temporaryLocationVersion pinned version of that temporary location
     * @param temporaryCustodianEmployeeId employee made accountable for the parked asset, who must be
     *     active inside an active organization
     * @param locationId final bin location the asset is to be returned to
     * @param locationVersion pinned version of that final bin, compared again before each later step
     * @param reason justification for the recovery, at most 1000 characters
     */
    public record RecoveryRequest(@NotNull @PositiveOrZero Long assetVersion, @NotNull Instant foundAt,
            @NotNull @Positive Long temporaryLocationId, @NotNull @PositiveOrZero Long temporaryLocationVersion,
            @NotNull @Positive Long temporaryCustodianEmployeeId, @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion, @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Recovery decision payload. Neither the temporary parking arrangement nor the final bin is
     * re-choosable here; the administrator judges the recovery the initiator pinned.
     *
     * @param assetVersion pinned fams_asset version the decision expects
     * @param result either {@code APPROVE} or {@code REJECT}, validated inside {@link #decideRecovery}
     * @param reason justification of the decision, at most 1000 characters
     */
    public record RecoveryDecision(@NotNull @PositiveOrZero Long assetVersion, @NotBlank String result,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Recovery claim payload, and the internal shape the other recovery steps reuse to carry the single
     * pinned asset version into the shared guard. Claiming assigns the recovery to the calling warehouse
     * operator and bumps its ownership version.
     *
     * @param assetVersion pinned fams_asset version the claim expects
     */
    public record RecoveryClaim(@NotNull @PositiveOrZero Long assetVersion) {}

    /**
     * Recovery execution payload, carrying the physical facts observed while re-registering the found
     * asset. The evidence window is anchored on the recorded {@code foundAt} rather than on the recovery's
     * creation time, so an operator cannot report a physical handling that predates the discovery.
     *
     * @param assetVersion pinned fams_asset version the execution expects
     * @param ownershipVersion ownership version returned by the claim, proving the caller still holds it
     * @param locationVersion pinned version of the final target bin, re-read from the recovery row
     * @param observedCode asset code read off the recovered physical asset, at most 64 characters
     * @param observedSerial serial number read off the recovered asset, at most 128 characters, optional
     * @param occurredAt instant the physical re-registration happened, at or after {@code foundAt}
     * @param physicalIdentityVerified must be {@code true}; a false or null value is a 400
     * @param evidence free-text description of what was verified, between 10 and 2000 characters
     */
    public record RecoveryExecution(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion, @NotNull @PositiveOrZero Long locationVersion,
            @NotBlank @Size(max = 64) String observedCode, @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt, @NotNull Boolean physicalIdentityVerified,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    /**
     * Opens a recovery against a scrap whose disposition is a loss write-off. The same four initiator
     * roles as for a reversal may open one, with employees and department asset managers again restricted
     * to requests they created, because the person who finds a written-off asset is usually its former
     * holder.
     *
     * <p>The write-off instant is read back out of the stored completion snapshot and used as the lower
     * bound for {@code foundAt}; since both clients submit second-precision instants, the bound is floored
     * to seconds so an asset found in the same second as its write-off is not rejected. Every reference
     * the recovery will later depend on is validated at initiation — the temporary location, the
     * temporary custodian's active employment inside an active organization, and the final bin — so a
     * recovery cannot be approved against a target that no longer exists.
     *
     * <p>An active or already completed recovery blocks a second one, which is stricter than the reversal
     * guard: a recovered asset must not be recoverable twice.
     *
     * @param actor initiating actor, whose role and creator identity are both checked
     * @param id fams_scrap_request primary key whose loss was written off
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code RECOVERY:<id>}
     * @param input recovery payload pinning the asset version and naming both locations and the custodian
     * @return receipt naming the request, the unchanged request version, the pinned asset version and the
     *     {@code PENDING_ADMIN_DECISION} recovery status with no processor yet
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_RECOVERY_FACTS_INVALID} when {@code foundAt} is
     *     outside the write-off window or is not millisecond-precise, 403
     *     {@code SCRAP_RECOVERY_FORBIDDEN} when the actor holds none of the initiator roles or is not the
     *     creator, 404 {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_RECOVERY_NOT_ALLOWED} when the request is
     *     not executed or its disposition is not a loss write-off, 409 {@code SCRAP_RECOVERY_OPEN} when a
     *     recovery already exists, 409 {@code SCRAP_RECOVERY_TEMPORARY_CHANGED},
     *     {@code SCRAP_RECOVERY_CUSTODIAN_CHANGED} or {@code SCRAP_RECOVERY_TARGET_CHANGED} when a pinned
     *     reference is no longer usable, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any
     *     refusal {@link AssetScrapPort#requireRecoverableAsset} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt requestRecovery(Actor actor, long id, long version, UUID key, RecoveryRequest input) {
        var current = actors.revalidateLocked(actor);
        var initiator = FOLLOW_UP_INITIATORS.stream().filter(current::has).findFirst()
                .orElseThrow(() -> new AssetHandoverPort.Failure("SCRAP_RECOVERY_FORBIDDEN", 403));
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = rows.getFirst();
        if ((initiator == Role.EMPLOYEE || initiator == Role.DEPARTMENT_ASSET_MANAGER)
                && !Objects.equals(current.id(), request.get("creator_account_id"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_FORBIDDEN", 403);
        }
        String operation = "RECOVERY:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!"EXECUTED".equals(request.get("request_status")) || number(request, "version") != version
                || number(request, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_ALLOWED", 409);
        }
        if (!db.queryForList("SELECT id FROM fams_scrap_recovery WHERE request_id=? AND active_guard=1"
                + " FOR UPDATE", id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_OPEN", 409);
        }
        var dispositions = db.queryForList("SELECT disposition_status,completion_snapshot_json"
                + " FROM fams_scrap_disposition WHERE request_id=? FOR UPDATE", id);
        if (dispositions.isEmpty()
                || !"LOSS_WRITTEN_OFF".equals(dispositions.getFirst().get("disposition_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_ALLOWED", 409);
        }
        long asset = number(request, "asset_id");
        var time = now();
        assets.requireRecoverableAsset(asset, input.assetVersion());
        Instant writtenOff = Instant.parse(json.readTree(dispositions.getFirst()
                .get("completion_snapshot_json").toString()).path("writtenOffAt").asText(null));
        // Both clients submit recovered instants with second precision, so floor the write-off bound.
        if (input.foundAt().isBefore(writtenOff.truncatedTo(ChronoUnit.SECONDS))
                || input.foundAt().isAfter(time.plusSeconds(2))
                || !input.foundAt().equals(input.foundAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_FACTS_INVALID", 400);
        }
        if (!assets.matchesTemporaryLocation(input.temporaryLocationId(), input.temporaryLocationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TEMPORARY_CHANGED", 409);
        }
        if (db.queryForList("SELECT e.id FROM fams_employee e JOIN fams_organization o ON o.id=e.organization_id"
                + " WHERE e.id=? AND e.status='ACTIVE' AND o.status='ACTIVE' FOR UPDATE",
                input.temporaryCustodianEmployeeId()).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CUSTODIAN_CHANGED", 409);
        }
        if (!assets.matchesActiveBin(input.locationId(), input.locationVersion())) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        db.update("INSERT INTO fams_scrap_recovery(request_id,asset_id,creator_account_id,reason,found_at,"
                + "temporary_location_id,temporary_location_version,temporary_custodian_employee_id,asset_version,"
                + "target_location_id,target_location_version,recovery_status,request_snapshot_json,"
                + "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING_ADMIN_DECISION',?,?,?)",
                id, asset, current.id(), input.reason().strip(), input.foundAt(), input.temporaryLocationId(),
                input.temporaryLocationVersion(), input.temporaryCustodianEmployeeId(), input.assetVersion(),
                input.locationId(), input.locationVersion(), json.writeValueAsString(input), time, time);
        long recovery = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        todo(id, "RECOVERY_DECIDE", "ASSET_ADMIN", null, time);
        audit(actor, current, initiator, id, number(request, "version"), "SCRAP_RECOVERY_REQUESTED",
                Map.of("recoveryId", Long.toString(recovery), "input", input), time);
        LOG.info("Requested scrap recovery actor={} requestId={} recoveryId={}", current.id(), id, recovery);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                number(request, "version"), input.assetVersion(), number(request, "ownership_version"),
                "PENDING_ADMIN_DECISION", null), time);
    }

    private Map<String, Object> recoveryGuarded(long id, long version, long assetVersion) {
        var parents = db.queryForList("SELECT request_id FROM fams_scrap_recovery WHERE id=?", id);
        if (parents.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_FOUND", 404);
        }
        long request = number(parents.getFirst(), "request_id");
        // Lock the parent before the sub-record so write-off follow-ups and recovery serialize identically.
        if (db.queryForList("SELECT id FROM fams_scrap_request WHERE id=? AND request_status='EXECUTED'"
                + " FOR UPDATE", request).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        var rows = db.queryForList("SELECT * FROM fams_scrap_recovery WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_NOT_FOUND", 404);
        }
        var recovery = rows.getFirst();
        if (number(recovery, "version") != version || number(recovery, "asset_version") != assetVersion) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        if (db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                + " AND disposition_status='LOSS_WRITTEN_OFF' FOR UPDATE", request).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        assets.requireRecoverableAsset(number(recovery, "asset_id"), assetVersion);
        return recovery;
    }

    /**
     * Approves or rejects an open recovery. The identifier is the recovery row, and the shared guard locks
     * the parent request before the recovery so this flow serialises identically with any other write-off
     * follow-up touching the same disposition.
     *
     * <p>An approval re-checks that the final target bin is still active at the pinned version and moves
     * the recovery to the warehouse claim queue. A rejection simply closes the decision todo and records
     * {@code REJECTED}: unlike a reversal rejection there is no displaced work item to restore, because a
     * written-off disposition has no pending disposal waiting behind the recovery.
     *
     * @param actor deciding asset administrator, recorded in the approval snapshot
     * @param id fams_scrap_recovery primary key to decide
     * @param version recovery row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code RECOVERY_DECISION:<id>}
     * @param input decision payload pinning the asset version and carrying the result and reason
     * @return receipt naming the parent request, the recovery version after the decision, the unchanged
     *     asset version and the resulting {@code WAITING_WAREHOUSE_CLAIM} or {@code REJECTED} status
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_RECOVERY_INPUT_INVALID} for a result that is
     *     neither {@code APPROVE} nor {@code REJECT}, 403 {@code SCRAP_ROLE_REQUIRED} without the
     *     administrator role, 404 {@code SCRAP_RECOVERY_NOT_FOUND}, 409 {@code SCRAP_RECOVERY_CHANGED}
     *     when the parent is no longer executed, the write-off disposition disappeared, the recovery is
     *     not awaiting a decision or a pinned version moved, 409
     *     {@code SCRAP_RECOVERY_TARGET_CHANGED} when the pinned bin is no longer active, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requireRecoverableAsset} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt decideRecovery(Actor actor, long id, long version, UUID key, RecoveryDecision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_INPUT_INVALID", 400);
        }
        String operation = "RECOVERY_DECISION:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var recovery = recoveryGuarded(id, version, input.assetVersion());
        if (!"PENDING_ADMIN_DECISION".equals(recovery.get("recovery_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        long request = number(recovery, "request_id"), asset = number(recovery, "asset_id");
        var time = now();
        if (approve && !assets.matchesActiveBin(number(recovery, "target_location_id"),
                number(recovery, "target_location_version"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        db.update("UPDATE fams_scrap_recovery SET recovery_status=?,approval_snapshot_json=?,"
                + "version=version+1,updated_at=? WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time)), time, id);
        closeTodo(request, "RECOVERY_DECIDE", time);
        if (approve) {
            todo(request, "RECOVERY_CLAIM", "WAREHOUSE_OPERATOR", null, time);
        }
        audit(actor, current, Role.ASSET_ADMIN, request, version,
                approve ? "SCRAP_RECOVERY_APPROVED" : "SCRAP_RECOVERY_REJECTED",
                Map.of("recoveryId", Long.toString(id), "input", input), time);
        LOG.info("Decided scrap recovery actor={} recoveryId={} approved={}", current.id(), id, approve);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), number(recovery, "ownership_version"),
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED", null), time);
    }

    /**
     * Assigns an approved recovery to the calling warehouse operator and bumps its ownership version, so
     * the execution step can require both the processor identity and the ownership version the claim
     * returned. The claim todo is closed and an execute todo is opened against this operator.
     *
     * @param actor claiming warehouse operator, recorded as the recovery processor
     * @param id fams_scrap_recovery primary key to claim
     * @param version recovery row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code RECOVERY_CLAIM:<id>}
     * @param input claim payload pinning the asset version
     * @return receipt naming the parent request, the recovery version after the claim, the bumped
     *     ownership version, the {@code PROCESSING} status and the claiming operator
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the warehouse operator
     *     role, 404 {@code SCRAP_RECOVERY_NOT_FOUND}, 409 {@code SCRAP_RECOVERY_CHANGED} when the
     *     recovery is not awaiting a claim or any guard condition moved, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#requireRecoverableAsset} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt claimRecovery(Actor actor, long id, long version, UUID key, RecoveryClaim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var recovery = recoveryGuarded(id, version, input.assetVersion());
        if (!"WAITING_WAREHOUSE_CLAIM".equals(recovery.get("recovery_status"))) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        long request = number(recovery, "request_id"), asset = number(recovery, "asset_id");
        var time = now();
        long ownership = number(recovery, "ownership_version") + 1;
        db.update("UPDATE fams_scrap_recovery SET recovery_status='PROCESSING',processor_account_id=?,"
                + "ownership_version=?,version=version+1,updated_at=? WHERE id=?", current.id(), ownership, time, id);
        closeTodo(request, "RECOVERY_CLAIM", time);
        todo(request, "RECOVERY_EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_RECOVERY_CLAIMED", input, time);
        LOG.info("Claimed scrap recovery actor={} recoveryId={} requestId={}", current.id(), id, request);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, input.assetVersion(), ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    /**
     * Re-registers a recovered asset and completes the recovery. The asset side performs the move from the
     * temporary parking location to the final target bin in one step, using the temporary location pinned
     * at initiation and the target version pinned in the payload, so the operator cannot redirect the
     * asset to a third place.
     *
     * <p>Neither the request row nor its disposition is touched: the scrap stays {@code EXECUTED} and the
     * disposition stays {@code LOSS_WRITTEN_OFF}, because the financial write-off really happened and must
     * remain visible in the history. The recovery is the record that the asset came back, and the guard
     * that refuses a second recovery once one is completed is what keeps that record singular.
     *
     * @param actor executing warehouse operator, who must hold the recovery as processor
     * @param id fams_scrap_recovery primary key to execute
     * @param version recovery row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code RECOVERY_EXECUTE:<id>}
     * @param input execution payload pinning three versions and carrying the observed physical facts
     * @return receipt naming the parent request, the recovery version after execution, the asset version
     *     after re-registration, the {@code COMPLETED} status and the executing operator
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_PHYSICAL_EVIDENCE_INVALID} when the identity was
     *     not verified, the evidence is too short or the occurrence instant predates the recorded
     *     discovery, 403 {@code SCRAP_ROLE_REQUIRED} or 403 {@code SCRAP_PROCESSOR_REQUIRED}, 404
     *     {@code SCRAP_RECOVERY_NOT_FOUND}, 409 {@code SCRAP_RECOVERY_CHANGED} on a version, status or
     *     parent-state mismatch, 409 {@code SCRAP_RECOVERY_TARGET_CHANGED} when the pinned bin version
     *     differs, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal
     *     {@link AssetScrapPort#reregisterToStock} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt executeRecovery(Actor actor, long id, long version, UUID key, RecoveryExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var recovery = recoveryGuarded(id, version, input.assetVersion());
        if (!"PROCESSING".equals(recovery.get("recovery_status"))
                || number(recovery, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_CHANGED", 409);
        }
        if (!Objects.equals(recovery.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        long request = number(recovery, "request_id"), asset = number(recovery, "asset_id");
        var time = now();
        Instant found = instant(recovery.get("found_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(found) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (number(recovery, "target_location_version") != input.locationVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_RECOVERY_TARGET_CHANGED", 409);
        }
        long assetVersion = assets.reregisterToStock(asset, input.assetVersion(), request,
                number(recovery, "temporary_location_id"), number(recovery, "target_location_id"),
                input.locationVersion(), input.observedCode(), input.observedSerial(), current.id(), time);
        db.update("UPDATE fams_scrap_recovery SET recovery_status='COMPLETED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                json.writeValueAsString(input), assetVersion, time, id);
        closeTodo(request, "RECOVERY_EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, request, version, "SCRAP_RECOVERY_EXECUTED", input, time);
        LOG.info("Executed scrap recovery actor={} recoveryId={} asset={}", current.id(), id, asset);
        return remember(current, operation, key, hash, new Receipt(Long.toString(request), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "COMPLETED", Long.toString(current.id())), time);
    }

    /**
     * Decides a pending scrap request, which is the single step where the request leaves the
     * administrator's queue. An approval moves it to {@code WAITING_WAREHOUSE_CLAIM}, or straight to
     * {@code PROCESSING} when the request inherits its processor from a repair or factory-loss source and
     * the warehouse operator who handled that source is therefore already known. A rejection tries to
     * restore the asset to the state the frozen source snapshot recorded and ends in {@code REJECTED}, or
     * in {@code RESTORE_REQUIRED} when restoration was not possible.
     *
     * <p>Restoration is refused rather than forced whenever the world moved on: for a loss source, an open
     * borrowing that is not the one frozen in the evidence, or a factory-loss round the repair side no
     * longer confirms; for a repair source, a repair order that no longer matches the frozen repair
     * version, processor and borrowing. In those cases the request stays blocked in
     * {@code RESTORE_REQUIRED} with an administrator todo and the business lock unreleased, which is the
     * state a correction is later opened against. When restoration does succeed the lock is released with
     * reason {@code SCRAP_REJECTED}, except for repair sources, whose lock belongs to the repair flow that
     * takes it back.
     *
     * <p>Two approval-only re-validations protect the frozen evidence. A repair-sourced approval asks the
     * repair domain whether the order could still be restored from this scrap, and a factory replacement
     * additionally requires the approved disposition to equal the method the factory round approved, so an
     * administrator cannot approve a different disposal than the one the replacement authorised. A
     * factory-loss approval requires the recorded processor to still match and the round to still confirm.
     *
     * <p>Evidence uploaded against the request is folded into the decision snapshot as the frozen
     * {@code attachmentEvidence} array. Because that discloses attachment detail to everybody who later
     * reads the decision, an approval with any attachment first requires the caller to hold the scrap
     * attachment sensitive read permission; the events are read {@code FOR UPDATE} so a concurrent upload
     * cannot slip in after the permission check.
     *
     * @param actor deciding asset administrator, recorded in the decision snapshot and the audit event
     * @param id fams_scrap_request primary key to decide
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code DECIDE:<id>}
     * @param input decision payload pinning the asset and lock versions and carrying the result, reason
     *     and, on approval, the approved disposition
     * @return receipt reporting the request version after the decision, the asset version, the resulting
     *     status and the processor when one was inherited
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_DECISION_INVALID} for an unknown result or an
     *     approval without a disposition, 403 {@code SCRAP_ROLE_REQUIRED} without the administrator role,
     *     404 {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_REQUEST_CHANGED} when the request is not pending
     *     or a pinned version moved, 409 {@code SCRAP_DISPOSITION_OUTSIDE_APPROVAL} when a loss approval
     *     is not a write-off or a factory approval differs from the approved method, 409
     *     {@code SCRAP_SOURCE_OR_OWNERSHIP_CHANGED} when the repair or factory-loss side no longer
     *     confirms the frozen evidence, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any
     *     refusal the asset, repair or loan ports raise
     * @throws com.fams.auth.AuthOperationException 403 when an approval carries attachments and the actor
     *     lacks the scrap attachment sensitive read permission, or when the identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt decide(Actor actor, long id, long version, UUID key, Decision input) {
        var current = current(actor, Role.ASSET_ADMIN);
        String operation = "DECIDE:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())
                || approve && (input.approvedDisposition() == null || input.approvedDisposition().isBlank())) {
            throw new AssetHandoverPort.Failure("SCRAP_DECISION_INVALID", 400);
        }
        var request = guarded(id, version, input.assetVersion(), input.lockVersion(), "PENDING_ADMIN_DECISION");
        if (approve && "LOSS".equals(request.get("source_type")) && !lossWriteOff(input.approvedDisposition())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        // Match the locked request version even if an earlier read established a MySQL snapshot.
        var attachments = db.queryForList("SELECT detail_json FROM fams_scrap_event"
                + " WHERE request_id=? AND action='SCRAP_ATTACHMENT_ADDED' ORDER BY id FOR UPDATE", String.class, id)
                .stream().map(json::readTree).toList();
        if (approve && !attachments.isEmpty()) {
            sensitive.requireRead(actor);
        }
        long asset = number(request, "asset_id");
        var time = now();
        var source = json.readTree(request.get("source_snapshot_json").toString());
        var factoryLoss = factoryLossRepair(source.path("lossEvidence"));
        boolean factory = "FACTORY_REPLACEMENT".equals(request.get("source_type"));
        boolean repairSource = factory || "REPAIR".equals(request.get("source_type"));
        boolean inheritedProcessor = repairSource || factoryLoss != null;
        if (approve && repairSource) {
            var proof = source.path("repairEvidence");
            if (request.get("processor_account_id") == null || !proof.has("repairVersion")
                    || !repairs.canRestoreFromScrap(number(request, "source_business_id"),
                            proof.path("repairVersion").asLong(-1), asset, input.assetVersion(),
                            number(request, "processor_account_id"), repairBorrowing(proof))) {
                throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
            }
            if (factory) {
                var evidence = factoryEvidence(request, proof);
                if (evidence == null || !evidence.method().equals(input.approvedDisposition().strip())) {
                    throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
                }
            }
        }
        if (approve && factoryLoss != null
                && (!Objects.equals(request.get("processor_account_id"), factoryLoss.processorAccountId())
                        || !validFactoryLossRepair(asset, factoryLoss))) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        String status = inheritedProcessor ? "PROCESSING" : "WAITING_WAREHOUSE_CLAIM";
        long assetVersion = input.assetVersion();
        if (!approve) {
            boolean restored = switch (request.get("source_type").toString()) {
                case "STANDALONE" -> assets.restoreStandalone(asset, assetVersion, id,
                        source.path("lifecycle_status").asText(), current.id(), time);
                case "LOSS" -> restoreLoss(asset, assetVersion, id, source, current.id(), time);
                case "REPAIR", "FACTORY_REPLACEMENT" ->
                    restoreRepair(actor, current, request, assetVersion, input.lockVersion(), source, time, factory);
                default -> false;
            };
            status = restored ? "REJECTED" : "RESTORE_REQUIRED";
            if (restored) {
                assetVersion++;
            }
            if (restored && !repairSource) {
                db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_REJECTED',"
                        + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP'"
                        + " AND business_id=? AND business_item_id=? AND released_at IS NULL",
                        time, asset, id, id);
            }
        }
        db.update("UPDATE fams_scrap_request SET request_status=?,decision_snapshot_json=?,asset_version=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                status, json.writeValueAsString(Map.of("decision", input, "decidedBy", Long.toString(current.id()),
                        "decidedAt", time, "attachmentEvidence", attachments)), assetVersion, time, id);
        closeTodo(id, "APPROVAL", time);
        if (approve && inheritedProcessor) {
            todo(id, "EXECUTE", "WAREHOUSE_OPERATOR", number(request, "processor_account_id"), time);
        } else if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null, time);
        } else if ("RESTORE_REQUIRED".equals(status)) {
            todo(id, "RESTORE_REQUIRED", "ASSET_ADMIN", null, time);
        }
        String action = approve ? "SCRAP_APPROVED" : "SCRAP_REJECTED";
        audit(actor, current, Role.ASSET_ADMIN, id, version, action, input, time);
        LOG.info("Decided scrap request actor={} requestId={} asset={} approved={} status={}",
                current.id(), id, asset, approve, status);
        return remember(current, operation, key, hash,
                new Receipt(Long.toString(id), Long.toString(asset), version + 1, assetVersion,
                        number(request, "ownership_version"), status,
                        inheritedProcessor && request.get("processor_account_id") != null
                                ? request.get("processor_account_id").toString() : null), time);
    }

    private boolean restoreRepair(Actor actor, Current current, Map<String, Object> request,
            long assetVersion, long lockVersion, tools.jackson.databind.JsonNode source,
            Instant time, boolean factory) {
        var proof = source.path("repairEvidence");
        if (!proof.has("repairVersion") || request.get("processor_account_id") == null) {
            return false;
        }
        long asset = number(request, "asset_id"), id = number(request, "id"),
                repair = number(request, "source_business_id");
        long repairVersion = proof.path("repairVersion").asLong(-1),
                processor = number(request, "processor_account_id");
        if (!repairs.canRestoreFromScrap(repair, repairVersion, asset, assetVersion, processor, repairBorrowing(proof))
                || factory && factoryEvidence(request, proof) == null
                || !assets.restoreRepair(asset, assetVersion, id, source, current.id(), time, factory)) {
            return false;
        }
        repairs.restoreFromScrap(actor, current, repair, repairVersion, asset, id, lockVersion, assetVersion + 1,
                processor, factory);
        return true;
    }

    private LoanRepairPort.Context repairBorrowing(tools.jackson.databind.JsonNode proof) {
        var borrowing = proof.path("borrowing");
        return borrowing.isMissingNode() || borrowing.isNull() ? null
                : json.readValue(borrowing.toString(), LoanRepairPort.Context.class);
    }

    private ScrapEvidence factoryEvidence(Map<String, Object> request, tools.jackson.databind.JsonNode proof) {
        if (request.get("initial_batch_id") != null) {
            return initialFactoryEvidence(request);
        }
        if (!proof.path("context").has("replacementId") || request.get("source_item_id") == null) {
            return null;
        }
        var expected = json.readValue(proof.path("context").toString(), ScrapEvidence.class);
        return expected.replacementId() == number(request, "source_item_id")
                && repairs.matchesFactoryScrapEvidence(number(request, "source_business_id"),
                        number(request, "asset_id"), expected) ? expected : null;
    }

    private ScrapEvidence initialFactoryEvidence(Map<String, Object> request) {
        var rows = db.queryForList("""
                SELECT f.id AS round_id,f.version,f.provider_name,f.dispatched_at,d.method,d.occurred_at
                FROM fams_initial_factory_disposition d JOIN fams_repair_factory_round f ON f.id=d.round_id
                JOIN fams_repair_request r ON r.id=f.repair_id
                WHERE d.id=? AND r.id=? AND f.asset_id=? AND r.initial_batch_id=? AND r.initial_sheet_row=?
                  AND f.initial_source AND f.round_status='DISPATCHED' AND f.received_at IS NULL
                FOR UPDATE
                """, request.get("source_item_id"), request.get("source_business_id"), request.get("asset_id"),
                request.get("initial_batch_id"), request.get("initial_sheet_row"));
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        return new ScrapEvidence(number(row, "round_id"), number(row, "version"), number(request, "source_item_id"),
                row.get("method").toString(), row.get("provider_name").toString(), instant(row.get("dispatched_at")),
                instant(row.get("occurred_at")), number(request, "creator_account_id"), "[]");
    }

    static boolean physicalSource(Object source) {
        return "STANDALONE".equals(source) || "REPAIR".equals(source);
    }

    private boolean restoreLoss(long asset, long version, long id, tools.jackson.databind.JsonNode source,
            long actor, Instant time) {
        if ("FACTORY_REPAIR".equals(source.path("lifecycle_status").asText())) {
            var factory = factoryLossRepair(source.path("lossEvidence"));
            if (factory == null || !validFactoryLossRepair(asset, factory)) {
                return false;
            }
        } else if ("BORROWED".equals(source.path("lifecycle_status").asText())) {
            var proof = source.path("lossEvidence");
            var loan = loans.openBorrowing(asset, source.path("responsible_employee_id").asLong(),
                    source.path("using_organization_id").asLong()).orElse(null);
            if (loan == null || !Long.toString(loan.id()).equals(proof.path("loanId").asText())
                    || loan.version() != proof.path("loanVersion").asLong(-1)) {
                return false;
            }
        } else if (loans.hasOpenBorrowing(asset)) {
            return false;
        }
        return assets.restoreLoss(asset, version, id, source, actor, time);
    }

    private RepairService.FactoryLossScrap factoryLossRepair(tools.jackson.databind.JsonNode proof) {
        var factory = proof.path("factoryRepair");
        return factory.isMissingNode() ? null
                : json.readValue(factory.toString(), RepairService.FactoryLossScrap.class);
    }

    private boolean validFactoryLossRepair(long asset, RepairService.FactoryLossScrap source) {
        return source.equals(
                repairs.factoryLossScrap(source.repairId(), source.roundId(), asset, source.processorAccountId()));
    }

    /**
     * Assigns an approved scrap request to the calling warehouse operator. Only requests that went through
     * the claim queue reach this step; a repair-sourced or factory-loss-sourced request inherited its
     * processor at decision time and is already {@code PROCESSING}, so claiming it is a version conflict.
     *
     * <p>Claiming bumps the ownership version and records the processor, and it is the ownership version
     * that the execution step requires back. That indirection is what makes a re-claim by a second
     * operator invalidate the first operator's in-flight execution payload instead of letting both write.
     *
     * @param actor claiming warehouse operator, recorded as the request processor
     * @param id fams_scrap_request primary key to claim
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code CLAIM:<id>}
     * @param input claim payload pinning the asset and lock versions
     * @return receipt reporting the request version after the claim, the unchanged asset version, the
     *     bumped ownership version, the {@code PROCESSING} status and the claiming operator
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the warehouse operator
     *     role, 404 {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_REQUEST_CHANGED} when the request is not
     *     awaiting a claim or a pinned version moved, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused
     *     key, or any refusal {@link AssetScrapPort#requirePending} raises
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt claim(Actor actor, long id, long version, UUID key, Claim input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "CLAIM:" + id;
        String hash = fingerprint(version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var request = guarded(id, version, input.assetVersion(), input.lockVersion(), "WAITING_WAREHOUSE_CLAIM");
        long ownership = number(request, "ownership_version") + 1;
        var time = now();
        db.update("UPDATE fams_scrap_request SET request_status='PROCESSING',processor_account_id=?,"
                        + "ownership_version=?,version=version+1,updated_at=? WHERE id=?",
                current.id(), ownership, time, id);
        closeTodo(id, "CLAIM", time);
        todo(id, "EXECUTE", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_CLAIMED", input, time);
        LOG.info("Claimed scrap request actor={} requestId={}", current.id(), id);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id),
                Long.toString(number(request, "asset_id")), version + 1, input.assetVersion(),
                ownership, "PROCESSING", Long.toString(current.id())), time);
    }

    /**
     * Physical scrap execution payload for a {@code STANDALONE} or {@code REPAIR} source. It is the
     * richest payload in the domain because executing a scrap is the point where the register is told
     * what was physically observed: the asset is moved to a scrap custody under a named custodian, and a
     * disposal deadline is opened for a follow-up that has not happened yet.
     *
     * @param assetVersion pinned fams_asset version the execution expects
     * @param lockVersion pinned version of the scrap business lock, released once execution succeeds
     * @param ownershipVersion ownership version returned by the claim or the inherited decision
     * @param observedCode asset code read off the physical asset, at most 64 characters
     * @param observedSerial serial number read off the physical asset, at most 128 characters, optional
     * @param locationId location the scrapped asset is moved to
     * @param locationVersion pinned version of that location, compared by the asset port
     * @param custodianEmployeeId employee made accountable for the scrapped asset, who must be active at
     *     the pinned version inside an active organization
     * @param custodianVersion pinned version of that employee record
     * @param occurredAt instant the physical scrap happened; must be millisecond-precise and fall between
     *     the request's last update and now plus two seconds of clock tolerance
     * @param physicalIdentityVerified must be {@code true}; a false or null value is a 400
     * @param evidence free-text description of what was verified, between 10 and 2000 characters
     * @param dispositionMethod disposal method actually applied, at most 500 characters; must equal the
     *     approved disposition, whitespace aside
     * @param plannedDisposalDate intended disposal date, used as the disposition due date; when null the
     *     due date defaults to thirty days after today in the configured business timezone
     */
    public record Execution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotBlank @Size(max = 64) String observedCode,
            @Size(max = 128) String observedSerial,
            @NotNull @Positive Long locationId,
            @NotNull @PositiveOrZero Long locationVersion,
            @NotNull @Positive Long custodianEmployeeId,
            @NotNull @PositiveOrZero Long custodianVersion,
            @NotNull Instant occurredAt,
            @NotNull Boolean physicalIdentityVerified,
            @NotBlank @Size(min = 10, max = 2000) String evidence,
            @NotBlank @Size(max = 500) String dispositionMethod,
            LocalDate plannedDisposalDate) {}

    /**
     * Executes a physical scrap for a {@code STANDALONE} or {@code REPAIR} source: the asset is moved into
     * scrap custody, the request becomes {@code EXECUTED}, the business lock is released and a pending
     * disposition is opened with a due date. The processor identity is checked before the idempotency
     * lookup, so an operator who never claimed the request cannot replay the receipt of the one who did.
     *
     * <p>A repair-sourced execution has to unwind the repair first. The asset side asserts the frozen
     * repair source still matches, the repair order is closed from the scrap, and any borrowing recorded
     * in the frozen evidence is terminated as lost — the asset is not coming back to its borrower. A
     * standalone execution instead only has to pass the loan-side assertion that no borrowing is open.
     *
     * <p>The disposal deadline is computed in the business timezone taken from the active parameter
     * snapshot, which is read {@code FOR SHARE} so a concurrent parameter change cannot produce a due date
     * that disagrees with the timezone stored on the disposition row. An explicit planned date is used as
     * given and must not lie in the past; otherwise the due date defaults to thirty days out. The stored
     * due instant is the start of the day after the due date in that zone, so the overdue queue flips over
     * at a business midnight rather than at an arbitrary time.
     *
     * <p>The custody snapshot written to the disposition row is the execution payload itself, which is
     * what a later reversal or completion compares the physical facts against.
     *
     * @param actor executing warehouse operator, who must hold the request as processor
     * @param id fams_scrap_request primary key to execute
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code EXECUTE:<id>}
     * @param input execution payload pinning three versions and carrying the observed physical facts, the
     *     destination, the custodian and the disposal plan
     * @return receipt reporting the request version after execution, the asset version after the move, the
     *     {@code EXECUTED} status and the executing operator
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_PHYSICAL_EVIDENCE_INVALID} when the identity was
     *     not verified, the evidence is too short or the occurrence instant is outside the window, 400
     *     {@code SCRAP_DISPOSITION_DATE_INVALID} for a planned disposal date in the past, 403
     *     {@code SCRAP_ROLE_REQUIRED} or 403 {@code SCRAP_PROCESSOR_REQUIRED}, 404
     *     {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_REQUEST_CHANGED} when the request is not processing or
     *     a pinned version moved, 409 {@code SCRAP_SOURCE_OR_OWNERSHIP_CHANGED} for a non-physical source
     *     or a stale ownership version, 409 {@code SCRAP_DISPOSITION_OUTSIDE_APPROVAL} when the method
     *     differs from the approved disposition, 409 {@code SCRAP_DISPOSITION_CUSTODIAN_CHANGED} when the
     *     custodian is not active at the pinned version, 409 {@code SCRAP_ASSET_OR_LOCK_CHANGED} when the
     *     lock release does not affect exactly one row, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused
     *     key, or any refusal the asset, repair or loan ports raise
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt execute(Actor actor, long id, long version, UUID key, Execution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "EXECUTE:" + id;
        String hash = fingerprint(version, input);
        var request = db.queryForList("SELECT processor_account_id FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (request.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        if (!java.util.Objects.equals(request.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = guarded(id, version, input.assetVersion(), input.lockVersion(), "PROCESSING");
        if (!physicalSource(row.get("source_type"))
                || number(row, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        var approval = json.readTree(row.get("decision_snapshot_json").toString()).path("decision");
        if (!input.dispositionMethod().strip().equals(approval.path("approvedDisposition").asText().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        var time = now();
        Instant created = instant(row.get("updated_at"));
        if (!Boolean.TRUE.equals(input.physicalIdentityVerified()) || input.evidence().strip().length() < 10
                || input.occurredAt().isBefore(created) || input.occurredAt().isAfter(time.plusSeconds(2))
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_PHYSICAL_EVIDENCE_INVALID", 400);
        }
        if (db.queryForList("SELECT e.id FROM fams_employee e JOIN fams_organization o ON o.id=e.organization_id"
                + " WHERE e.id=? AND e.version=? AND e.status='ACTIVE' AND o.status='ACTIVE' FOR UPDATE",
                input.custodianEmployeeId(), input.custodianVersion()).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CUSTODIAN_CHANGED", 409);
        }
        String parameters = db.queryForObject("SELECT v.snapshot_json FROM fams_business_parameter_state s"
                + " JOIN fams_business_parameter_version v ON v.id=s.current_version_id"
                + " WHERE s.singleton_id=1 AND v.status='ACTIVE' FOR SHARE", String.class);
        var settings = json.readTree(parameters);
        ZoneId zone = ZoneId.of(settings.path("timezone").asText());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate due = input.plannedDisposalDate() == null ? today.plusDays(30) : input.plannedDisposalDate();
        if (due.isBefore(today)) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_DATE_INVALID", 400);
        }
        long asset = number(row, "asset_id");
        if ("REPAIR".equals(row.get("source_type"))) {
            var source = json.readTree(row.get("source_snapshot_json").toString());
            var proof = source.path("repairEvidence");
            var borrowing = repairBorrowing(proof);
            assets.requireFrozenRepair(asset, input.assetVersion(), source);
            repairs.closeFromScrap(actor, current, number(row, "source_business_id"),
                    proof.path("repairVersion").asLong(-1), asset, input.assetVersion(), id, borrowing);
            if (borrowing != null) {
                loans.terminateLost(asset, borrowing.borrowerEmployeeId(), borrowing.organizationId(),
                        borrowing.id(), borrowing.version(), time);
            }
        } else {
            loans.requireRecovery(asset, "IN_USE", null, null);
        }
        long assetVersion = assets.executeStandalone(asset, input.assetVersion(), id, input.lockVersion(),
                input.observedCode(), input.observedSerial(), input.locationId(), input.locationVersion(),
                current.id(), time);
        String execution = json.writeValueAsString(input);
        db.update("UPDATE fams_scrap_request SET request_status='EXECUTED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                execution, assetVersion, time, id);
        db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                + "due_date,due_at,due_timezone,custody_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,'PENDING_DISPOSITION',?,?,?,?,?,?)",
                id, current.id(), due, due.plusDays(1).atStartOfDay(zone).toInstant(), zone.getId(), execution,
                time, time);
        releaseExecutedLock(id, asset, input.lockVersion(), time);
        closeTodo(id, "EXECUTE", time);
        todo(id, "DISPOSITION", "WAREHOUSE_OPERATOR", current.id(), time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_EXECUTED", input, time);
        LOG.info("Executed scrap request actor={} requestId={} asset={}", current.id(), id, asset);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
    }

    /**
     * Factory-replacement execution payload. It is far smaller than {@link Execution} because a factory
     * replacement has no physical asset left to observe: the original was handed to the service partner
     * and disposed of there, so the method, the recipient and the evidence were all fixed when the
     * replacement round was approved. The operator only confirms when that disposal happened.
     *
     * @param assetVersion pinned fams_asset version the execution expects
     * @param lockVersion pinned version of the scrap business lock, released once execution succeeds
     * @param ownershipVersion ownership version inherited from the decision, which assigned the processor
     * @param occurredAt instant the factory disposal happened; must be millisecond-precise and fall between
     *     the round's dispatch and its recorded observation, and for an initial-batch replacement it must
     *     equal that recorded observation exactly
     */
    public record FactoryExecution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotNull Instant occurredAt) {}

    /**
     * Executes a factory-replacement scrap, which closes the whole flow in one step: the request becomes
     * {@code EXECUTED} and its disposition is inserted already {@code DISPOSED}, with no pending disposal
     * and therefore no disposition todo. There is nothing left to dispose of, because the service partner
     * took the original asset under the method the replacement round approved.
     *
     * <p>The disposal facts are not supplied by the operator. They are re-derived from the approved factory
     * evidence — method, provider as recipient, and the account that recorded it — and the operator only
     * confirms the instant. That instant is constrained to the window between the round's dispatch and its
     * recorded observation; for a replacement that came out of an initial publication batch it must equal
     * the recorded observation exactly, because that disposition was already documented at import time and
     * there is no second physical event to date.
     *
     * <p>The evidence attached to the completion snapshot differs by origin: an initial-batch disposal
     * carries the textual evidence reference frozen in the source snapshot, while a normal replacement
     * carries the identifiers of the attachment objects the repair domain holds, and those objects are
     * re-validated before use so a deleted attachment cannot be cited.
     *
     * <p>The repair order is closed from the scrap and any frozen borrowing is terminated as lost, exactly
     * as in the physical execution path, before the asset side writes the replacement outcome.
     *
     * @param actor executing warehouse operator, who must hold the request as inherited processor
     * @param id fams_scrap_request primary key to execute
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code EXECUTE_FACTORY:<id>}
     * @param input execution payload pinning three versions and confirming the disposal instant
     * @return receipt reporting the request version after execution, the asset version after the factory
     *     outcome, the {@code EXECUTED} status and the executing operator
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_FACTORY_DISPOSITION_TIME_INVALID} when the
     *     confirmed instant falls outside the approved window or is not millisecond-precise, 403
     *     {@code SCRAP_ROLE_REQUIRED} or 403 {@code SCRAP_PROCESSOR_REQUIRED}, 404
     *     {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_REQUEST_CHANGED} when the request is not processing or
     *     a pinned version moved, 409 {@code SCRAP_SOURCE_OR_OWNERSHIP_CHANGED} for a non-factory source
     *     or a stale ownership version, 409 {@code SCRAP_DISPOSITION_OUTSIDE_APPROVAL} when the approved
     *     factory evidence is gone or its method differs from the approved disposition, 409
     *     {@code SCRAP_ASSET_OR_LOCK_CHANGED} when the lock release does not affect exactly one row, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal the asset, repair or loan
     *     ports raise
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt executeFactory(Actor actor, long id, long version, UUID key, FactoryExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "EXECUTE_FACTORY:" + id;
        String hash = fingerprint(version, input);
        var rows = db.queryForList("SELECT processor_account_id FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        if (!Objects.equals(rows.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = guarded(id, version, input.assetVersion(), input.lockVersion(), "PROCESSING");
        if (!"FACTORY_REPLACEMENT".equals(row.get("source_type"))
                || number(row, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        var source = json.readTree(row.get("source_snapshot_json").toString());
        var proof = source.path("repairEvidence");
        long repair = number(row, "source_business_id"), asset = number(row, "asset_id");
        var time = now();
        var borrowing = repairBorrowing(proof);
        repairs.closeFromScrap(actor, current, repair, proof.path("repairVersion").asLong(-1),
                asset, input.assetVersion(), id, borrowing);
        var factory = factoryEvidence(row, proof);
        var approval = json.readTree(row.get("decision_snapshot_json").toString()).path("decision");
        if (factory == null || !factory.method().equals(approval.path("approvedDisposition").asText().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        boolean initial = row.get("initial_batch_id") != null;
        if (input.occurredAt().isBefore(factory.dispatchedAt()) || input.occurredAt().isAfter(factory.observedAt())
                || initial && !input.occurredAt().equals(factory.observedAt())
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_FACTORY_DISPOSITION_TIME_INVALID", 400);
        }
        if (!initial) {
            repairs.requireFactoryScrapEvidenceObjects(current, repair, factory);
        }
        long assetVersion = assets.executeFactory(asset, input.assetVersion(), id, input.lockVersion(),
                source, current.id(), time);
        if (borrowing != null) {
            loans.terminateLost(asset, borrowing.borrowerEmployeeId(), borrowing.organizationId(),
                    borrowing.id(), borrowing.version(), time);
        }
        var facts = new java.util.LinkedHashMap<String, Object>(Map.of("occurredAt", input.occurredAt(),
                "method", factory.method(), "recipient", factory.provider(), "recordedBy", factory.recordedBy()));
        if (initial) {
            facts.put("initialEvidence", source.path("repairEvidence").path("context").path("evidence").asText());
        } else {
            facts.put("evidenceIds", json.readTree(factory.evidenceIdsJson()));
        }
        String completion = json.writeValueAsString(Map.of("facts", facts, "verifiedBy", current.id()));
        String execution = json.writeValueAsString(Map.of("executedAt", time, "method", factory.method()));
        db.update("UPDATE fams_scrap_request SET request_status='EXECUTED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?", execution, assetVersion, time, id);
        db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                + "custody_snapshot_json,completion_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,'DISPOSED',?,?,?,?)", id, current.id(), source.toString(), completion, time, time);
        releaseExecutedLock(id, asset, input.lockVersion(), time);
        closeTodo(id, "EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_FACTORY_EXECUTED",
                Map.of("method", factory.method(), initial ? "initialDispositionId" : "replacementId",
                        Long.toString(factory.replacementId())), time);
        LOG.info("Executed factory scrap disposition actor={} requestId={} asset={}", current.id(), id, asset);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
    }

    /**
     * Loss write-off execution payload. A lost asset is not physically present, so the payload carries no
     * observed code, serial, location or custodian — only the versions to pin and the evidence text that
     * justifies removing the asset from the books.
     *
     * @param assetVersion pinned fams_asset version the execution expects
     * @param lockVersion pinned version of the scrap business lock, released once execution succeeds
     * @param ownershipVersion ownership version inherited from the decision, which assigned the processor
     * @param evidence free-text justification of the write-off, between 10 and 2000 characters
     */
    public record LossExecution(
            @NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long lockVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotBlank @Size(min = 10, max = 2000) String evidence) {}

    /**
     * Writes off a lost asset, which is the financial end of a loss-sourced scrap. The request becomes
     * {@code EXECUTED} and its disposition is inserted directly as {@code LOSS_WRITTEN_OFF} with the
     * write-off instant recorded in the completion snapshot — the single fact the read side keeps, since
     * everything else in the execution snapshot is internal evidence.
     *
     * <p>Before anything is written, the loss report is re-read at the version frozen into the source
     * snapshot and compared field by field: same asset, same lifecycle status, the same six custody fields
     * under their two different key vocabularies, and a byte-equal confirmation tree. Any difference means
     * the loss the administrator approved is not the loss being written off, and the execution is refused
     * rather than proceeding on stale evidence.
     *
     * <p>The three loan situations frozen at submission are then unwound in the same order. A
     * {@code BORROWED} loss terminates the open borrowing as lost using the loan id and version frozen in
     * the evidence. A loss that happened during a factory repair round re-validates that the round still
     * matches on repair, round and processor, closes the repair from the factory-loss scrap and terminates
     * that round's borrowing if it had one. Any other lifecycle only has to pass the loan-side recovery
     * assertion. Finally the loss report's own follow-up is closed, so the loss domain stops waiting for a
     * scrap that has now happened.
     *
     * @param actor executing warehouse operator, who must hold the request as inherited processor
     * @param id fams_scrap_request primary key to write off
     * @param version request row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code EXECUTE_LOSS:<id>}
     * @param input execution payload pinning three versions and carrying the write-off evidence text
     * @return receipt reporting the request version after execution, the asset version after the write-off,
     *     the {@code EXECUTED} status and the executing operator
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_LOSS_EVIDENCE_REQUIRED} when the evidence text is
     *     shorter than ten characters, 403 {@code SCRAP_ROLE_REQUIRED} or 403
     *     {@code SCRAP_PROCESSOR_REQUIRED}, 404 {@code SCRAP_NOT_FOUND}, 409
     *     {@code SCRAP_REQUEST_CHANGED} when the request is not processing or a pinned version moved, 409
     *     {@code SCRAP_SOURCE_OR_OWNERSHIP_CHANGED} for a non-loss source or a stale ownership version,
     *     409 {@code SCRAP_DISPOSITION_OUTSIDE_APPROVAL} when the approved disposition is not a write-off,
     *     409 {@code SCRAP_LOSS_SOURCE_CHANGED} when the loss report, its custody, its confirmation or its
     *     factory round no longer matches the frozen evidence, 409
     *     {@code SCRAP_ASSET_OR_LOCK_CHANGED} when the lock release does not affect exactly one row, 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, or any refusal the asset, loss, repair or
     *     loan ports raise
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt executeLoss(Actor actor, long id, long version, UUID key, LossExecution input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        String operation = "EXECUTE_LOSS:" + id;
        String hash = fingerprint(version, input);
        var requests = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (requests.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var row = requests.getFirst();
        if (!Objects.equals(row.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        if (!"PROCESSING".equals(row.get("request_status")) || number(row, "version") != version
                || number(row, "asset_version") != input.assetVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_REQUEST_CHANGED", 409);
        }
        if (!"LOSS".equals(row.get("source_type")) || number(row, "ownership_version") != input.ownershipVersion()) {
            throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
        }
        var approval = json.readTree(row.get("decision_snapshot_json").toString()).path("decision");
        if (!lossWriteOff(approval.path("approvedDisposition").asText())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        if (input.evidence().strip().length() < 10) {
            throw new AssetHandoverPort.Failure("SCRAP_LOSS_EVIDENCE_REQUIRED", 400);
        }
        long reportId = number(row, "source_business_id"), asset = number(row, "asset_id");
        var source = json.readTree(row.get("source_snapshot_json").toString());
        var proof = source.path("lossEvidence");
        var loss = losses.requireExecutionEvidence(reportId, proof.path("reportVersion").asLong(-1));
        if (loss.assetId() != asset || !loss.lifecycle().equals(source.path("lifecycle_status").asText())
                || !assets.matchesOriginalLossCustody(source, json.readTree(loss.sourceJson()))
                || !json.readTree(loss.confirmationJson()).equals(proof.path("confirmation"))) {
            throw new AssetHandoverPort.Failure("SCRAP_LOSS_SOURCE_CHANGED", 409);
        }
        var time = now();
        long assetVersion = assets.executeLoss(asset, input.assetVersion(), id, input.lockVersion(),
                source, current.id(), time);
        if ("BORROWED".equals(loss.lifecycle())) {
            loans.terminateLost(asset, source.path("responsible_employee_id").asLong(),
                    source.path("using_organization_id").asLong(), proof.path("loanId").asLong(),
                    proof.path("loanVersion").asLong(-1), time);
        } else if (loss.factory() != null) {
            var factory = factoryLossRepair(proof);
            if (factory == null || factory.repairId() != loss.factory().repairId()
                    || factory.roundId() != loss.factory().roundId()
                    || factory.processorAccountId() != loss.factory().processorAccountId()) {
                throw new AssetHandoverPort.Failure("SCRAP_LOSS_SOURCE_CHANGED", 409);
            }
            repairs.closeFromFactoryLossScrap(actor, current, factory, input.assetVersion(), id);
            var borrowing = factory.borrowing();
            if (borrowing != null) {
                loans.terminateLost(asset, borrowing.borrowerEmployeeId(), borrowing.organizationId(),
                        borrowing.id(), borrowing.version(), time);
            }
        } else {
            loans.requireRecovery(asset, loss.lifecycle(), null, null);
        }
        losses.closeFollowUp(reportId, time);
        var facts = Map.of("lossEvidence", proof, "evidence", input.evidence().strip(), "writtenOffAt", time);
        String execution = json.writeValueAsString(facts);
        db.update("UPDATE fams_scrap_request SET request_status='EXECUTED',execution_snapshot_json=?,"
                + "asset_version=?,version=version+1,updated_at=? WHERE id=?",
                execution, assetVersion, time, id);
        db.update("INSERT INTO fams_scrap_disposition(request_id,processor_account_id,disposition_status,"
                + "custody_snapshot_json,completion_snapshot_json,created_at,updated_at)"
                + " VALUES (?,?,'LOSS_WRITTEN_OFF',?,?,?,?)",
                id, current.id(), source.toString(), execution, time, time);
        releaseExecutedLock(id, asset, input.lockVersion(), time);
        closeTodo(id, "EXECUTE", time);
        audit(actor, current, Role.WAREHOUSE_OPERATOR, id, version, "SCRAP_LOSS_WRITTEN_OFF", facts, time);
        LOG.info("Wrote off scrap loss actor={} requestId={} asset={} reportId={}", current.id(), id, asset, reportId);
        return remember(current, operation, key, hash, new Receipt(Long.toString(id), Long.toString(asset),
                version + 1, assetVersion, input.ownershipVersion(), "EXECUTED", Long.toString(current.id())), time);
    }

    private boolean lossWriteOff(String method) {
        String value = method.strip();
        return "LOSS_WRITTEN_OFF".equals(value) || "遗失核销".equals(value);
    }

    private void releaseExecutedLock(long id, long asset, long lockVersion, Instant time) {
        if (db.update("UPDATE fams_asset_business_lock SET released_at=?,release_reason='SCRAP_EXECUTED',"
                + "lock_version=lock_version+1 WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND lock_version=? AND released_at IS NULL",
                time, asset, id, id, lockVersion) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_ASSET_OR_LOCK_CHANGED", 409);
        }
    }

    /**
     * Physical disposal completion payload, the follow-up that closes an executed scrap. The asset was
     * already moved into scrap custody by {@link #execute}; this records that it has now actually left,
     * to whom, when, on whose hands and with which evidence objects attached.
     *
     * <p>The method is not free text: it must equal the {@code dispositionMethod} frozen in the execution
     * snapshot, so the follow-up cannot quietly change the disposal that was approved and executed. The
     * operator employee is separate from the request processor and is pinned by version, because the person
     * who physically handed the asset over is recorded by name in the completion snapshot and must be an
     * active employee of an active organization at that moment.
     *
     * @param requestVersion pinned fams_scrap_request version, which must still be {@code EXECUTED}
     * @param ownershipVersion pinned ownership version of that request
     * @param observedCode asset code read off the physical asset, at most 64 characters, asserted against
     *     the scrapped custody by the asset port
     * @param observedSerial serial number read off the physical asset, at most 128 characters, optional
     * @param occurredAt instant the disposal happened; must be millisecond-precise and fall between the
     *     execution instant and now
     * @param method disposal method actually applied, at most 500 characters, which must equal the method
     *     frozen in the execution snapshot
     * @param recipient party that received the asset, at most 500 characters
     * @param operatorEmployeeId employee who physically performed the disposal
     * @param operatorVersion pinned version of that employee record
     * @param evidenceObjectIds one to fourteen attachment objects uploaded under the
     *     {@code SCRAP_DISPOSITION} type by this operator, each of which must have a clean scan result
     */
    public record DispositionCompletion(
            @NotNull @PositiveOrZero Long requestVersion,
            @NotNull @PositiveOrZero Long ownershipVersion,
            @NotBlank @Size(max = 64) String observedCode,
            @Size(max = 128) String observedSerial,
            @NotNull Instant occurredAt,
            @NotBlank @Size(max = 500) String method,
            @NotBlank @Size(max = 500) String recipient,
            @NotNull @Positive Long operatorEmployeeId,
            @NotNull @PositiveOrZero Long operatorVersion,
            @NotNull @Size(min = 1, max = 14) List<@NotNull UUID> evidenceObjectIds) {
        /**
         * Copies the evidence object identifiers into an unmodifiable list so the payload that is written
         * into the completion snapshot cannot be mutated afterwards by its caller. A null list is preserved
         * as null rather than being normalised, leaving the {@code @NotNull} constraint to reject it.
         *
         * @param requestVersion pinned fams_scrap_request version expected to be {@code EXECUTED}
         * @param ownershipVersion pinned ownership version of that request
         * @param observedCode asset code observed on the physical asset
         * @param observedSerial serial number observed on the physical asset, optional
         * @param occurredAt instant the disposal happened
         * @param method disposal method, which must match the executed one
         * @param recipient party that received the asset
         * @param operatorEmployeeId employee who performed the disposal
         * @param operatorVersion pinned version of that employee record
         * @param evidenceObjectIds attachment object identifiers, replaced by an immutable copy when non-null
         */
        public DispositionCompletion {
            evidenceObjectIds = evidenceObjectIds == null ? null : List.copyOf(evidenceObjectIds);
        }
    }

    /**
     * Acknowledgement of a completed disposition. It is a separate, narrower shape than {@link Receipt}
     * because the disposition step touches no asset and no request row: the asset version is unchanged and
     * the ownership version is not involved, so reporting them would imply a movement that did not happen.
     * Like every other receipt it is stored in {@code fams_scrap_receipt} and replayed verbatim on retry.
     *
     * @param id stringified fams_scrap_request primary key whose disposition was completed
     * @param version disposition row version after the completion
     * @param status resulting disposition status, always {@code DISPOSED}
     */
    public record DispositionReceipt(String id, long version, String status) {}

    /**
     * Records that a scrapped asset has physically left, closing the disposition the execution opened.
     * This is the last step of a physical scrap and the only entry point that returns a
     * {@link DispositionReceipt}; it bumps the disposition row, never the request row, which stays
     * {@code EXECUTED} with its history intact.
     *
     * <p>The lock order is deliberate and shared with evidence registration and with the reversal flow:
     * the request row is taken {@code FOR UPDATE} first, then any active reversal is checked, then the
     * disposition row. Reversal and completion are the two operations that compete for one pending
     * disposition, and taking the parent first makes them serialise the same way in both directions. An
     * active reversal therefore blocks the completion with a conflict instead of the two writing over each
     * other. The disposition processor must be the caller, and the {@code DISPOSITION} todo assigned to
     * that caller must close exactly one row, so a completion cannot succeed against a todo somebody else
     * already took.
     *
     * <p>Every reference the completion cites is re-validated rather than trusted. The method must equal
     * the executed one, the instant must fall between the execution and now, the asset port must accept the
     * observed code and serial against the scrapped custody, and the operator employee must be active at
     * the pinned version. Evidence objects must be distinct, must belong to this disposition scope and
     * this operator, and must each carry a clean scan result; the caller must also hold the scrap
     * attachment sensitive read permission, since the completion snapshot names the operator and cites the
     * objects. A deployment without quarantine storage cannot satisfy that last requirement and reports
     * the attachment surface as unavailable rather than completing without its evidence.
     *
     * <p>The completion snapshot stores the whole payload as {@code facts} alongside the resolved operator
     * name, the recording account and the recording instant, which is exactly the shape the read side
     * projects into {@link ScrapViews.Completion}.
     *
     * @param actor completing warehouse operator, who must hold the disposition as processor
     * @param id fams_scrap_request primary key whose disposition is completed; the disposition is keyed by
     *     request, so this is also the disposition identity
     * @param version disposition row version the caller saw, echoed as {@code If-Match-Version}
     * @param key client idempotency key scoping the receipt row for {@code COMPLETE_DISPOSITION:<id>}
     * @param input completion payload pinning the request and ownership versions and carrying the physical
     *     disposal facts and evidence objects
     * @return receipt naming the request, the disposition version after completion and the
     *     {@code DISPOSED} status
     * @throws AssetHandoverPort.Failure 400 {@code SCRAP_DISPOSITION_DATE_INVALID} when the disposal
     *     instant is outside the window opened by the execution or is not millisecond-precise, 403
     *     {@code SCRAP_ROLE_REQUIRED} or 403 {@code SCRAP_PROCESSOR_REQUIRED}, 404
     *     {@code SCRAP_NOT_FOUND}, 409 {@code SCRAP_DISPOSITION_REVERSAL_OPEN} while a reversal is active,
     *     409 {@code SCRAP_DISPOSITION_CHANGED} when the request or disposition left the expected state or
     *     the update did not affect exactly one row, 409 {@code SCRAP_DISPOSITION_OUTSIDE_APPROVAL} when
     *     the method differs from the executed one, 409
     *     {@code SCRAP_DISPOSITION_OPERATOR_CHANGED} when the operator employee is not active at the
     *     pinned version, 409 {@code SCRAP_DISPOSITION_EVIDENCE_INVALID} for duplicated, foreign or
     *     unclean evidence objects, 409 {@code SCRAP_DISPOSITION_TODO_CHANGED} when the disposition todo
     *     was not the caller's to close, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key, 503
     *     {@code SCRAP_ATTACHMENT_STORAGE_UNAVAILABLE} when the evidence port is absent, or any refusal
     *     {@link AssetScrapPort#requireDispositionAsset} raises
     * @throws com.fams.auth.AuthOperationException 403 when the actor lacks the scrap attachment sensitive
     *     read permission, or when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public DispositionReceipt completeDisposition(Actor actor, long id, long version, UUID key,
            DispositionCompletion input) {
        var current = current(actor, Role.WAREHOUSE_OPERATOR);
        // Keep the request/disposition lock order shared with evidence registration and later reversal.
        var requests = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (requests.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var request = requests.getFirst();
        if (!db.queryForList("SELECT id FROM fams_scrap_reversal WHERE request_id=? AND active_guard=1 FOR UPDATE",
                id).isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_REVERSAL_OPEN", 409);
        }
        var dispositions = db.queryForList("SELECT processor_account_id,disposition_status,version"
                + " FROM fams_scrap_disposition WHERE request_id=? FOR UPDATE", id);
        if (dispositions.isEmpty()
                || !Objects.equals(dispositions.getFirst().get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        var disposition = dispositions.getFirst();
        String operation = "COMPLETE_DISPOSITION:" + id;
        String hash = fingerprint(version, input);
        var prior = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt"
                + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                current.id(), operation, key.toString());
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
                throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
            }
            return json.readValue(prior.getFirst().get("response_json").toString(), DispositionReceipt.class);
        }
        if (!"EXECUTED".equals(request.get("request_status")) || !physicalSource(request.get("source_type"))
                || number(request, "version") != input.requestVersion()
                || number(request, "ownership_version") != input.ownershipVersion()
                || !"PENDING_DISPOSITION".equals(disposition.get("disposition_status"))
                || number(disposition, "version") != version) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CHANGED", 409);
        }
        var executed = json.readTree(request.get("execution_snapshot_json").toString());
        if (!input.method().strip().equals(executed.path("dispositionMethod").asText().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        var time = now();
        if (input.occurredAt().isBefore(Instant.parse(executed.path("occurredAt").asText()))
                || input.occurredAt().isAfter(time)
                || !input.occurredAt().equals(input.occurredAt().truncatedTo(ChronoUnit.MILLIS))) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_DATE_INVALID", 400);
        }
        assets.requireDispositionAsset(number(request, "asset_id"), number(request, "asset_version"),
                input.observedCode(), input.observedSerial());
        var operators = db.queryForList(
                "SELECT e.name FROM fams_employee e JOIN fams_organization o ON o.id=e.organization_id"
                + " WHERE e.id=? AND e.version=? AND e.status='ACTIVE' AND o.status='ACTIVE' FOR UPDATE",
                input.operatorEmployeeId(), input.operatorVersion());
        if (operators.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OPERATOR_CHANGED", 409);
        }
        sensitive.requireRead(actor);
        var attachments = evidence.getIfAvailable();
        if (attachments == null) {
            throw new AssetHandoverPort.Failure("SCRAP_ATTACHMENT_STORAGE_UNAVAILABLE", 503);
        }
        if (Set.copyOf(input.evidenceObjectIds()).size() != input.evidenceObjectIds().size()
                || input.evidenceObjectIds().stream()
                        .anyMatch(object -> !attachments.clean("SCRAP_DISPOSITION", id, current.id(), object))) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_EVIDENCE_INVALID", 409);
        }
        String detail = json.writeValueAsString(Map.of("facts", input, "operatorName", operators.getFirst().get("name"),
                "recordedBy", Long.toString(current.id()), "recordedAt", time));
        if (db.update("UPDATE fams_scrap_disposition SET disposition_status='DISPOSED',"
                + "completion_snapshot_json=?,version=version+1,updated_at=? WHERE request_id=?"
                + " AND disposition_status='PENDING_DISPOSITION'", detail, time, id) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_CHANGED", 409);
        }
        if (db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='SCRAP'"
                + " AND business_id=? AND todo_type='DISPOSITION' AND assignee_account_id=? AND closed_at IS NULL",
                time, id, current.id()) != 1) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_TODO_CHANGED", 409);
        }
        db.update("INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,"
                + "resource_id,before_version,after_version,occurred_at,detail_json)"
                + " VALUES (?,?,'WAREHOUSE_OPERATOR','SCRAP_DISPOSITION_COMPLETED','SCRAP_DISPOSITION',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), id, version, version + 1, time, detail);
        var receipt = new DispositionReceipt(Long.toString(id), version + 1, "DISPOSED");
        db.update("INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), id, hash, json.writeValueAsString(receipt), time);
        LOG.info("Completed scrap disposition actor={} requestId={} newVersion={}", current.id(), id, version + 1);
        return receipt;
    }

    private Map<String, Object> guarded(long id, long version, long assetVersion, long lockVersion,
            String expectedStatus) {
        var rows = db.queryForList("SELECT * FROM fams_scrap_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (!expectedStatus.equals(row.get("request_status")) || number(row, "version") != version
                || number(row, "asset_version") != assetVersion) {
            throw new AssetHandoverPort.Failure("SCRAP_REQUEST_CHANGED", 409);
        }
        assets.requirePending(number(row, "asset_id"), assetVersion, id, lockVersion);
        return row;
    }

    private Current current(Actor actor, Role role) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(role)) {
            throw new AssetHandoverPort.Failure("SCRAP_ROLE_REQUIRED", 403);
        }
        return current;
    }

    private Receipt replay(Current current, String operation, UUID key, String hash) {
        var rows = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt"
                        + " WHERE actor_account_id=? AND operation=? AND request_id=? FOR UPDATE",
                current.id(), operation, key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        if (!hash.equals(row.get("payload_sha256"))) {
            throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
        }
        return json.readValue(row.get("response_json").toString(), Receipt.class);
    }

    private Receipt remember(Current current, String operation, UUID key, String hash, Receipt receipt,
            Instant time) {
        db.update("INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                        + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), Long.parseLong(receipt.id()), hash,
                json.writeValueAsString(receipt), time);
        return receipt;
    }

    private void todo(long id, String kind, String pool, Long owner, Instant time) {
        db.update("INSERT INTO fams_todo(pool_type,assignee_account_id,business_type,business_id,"
                        + "todo_type,todo_status,opened_at) VALUES (?,?,'SCRAP',?,?,'OPEN',?)",
                pool, owner, id, kind, time);
    }

    private void closeTodo(long id, String kind, Instant time) {
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='SCRAP'"
                        + " AND business_id=? AND todo_type=? AND closed_at IS NULL",
                time, id, kind);
    }

    private void audit(Actor actor, Current current, Role role, long id, long version, String action,
            Object input, Instant time) {
        String detail = json.writeValueAsString(input);
        db.update("INSERT INTO fams_scrap_event(request_id,action,before_version,after_version,"
                        + "actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                id, action, version, version + 1, current.id(), current.username(), role.name(), detail, time);
        db.update("INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                        + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                        + " VALUES (?,?,?,?,'SCRAP',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), role.name(), action, id, version, version + 1, time,
                detail);
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private String fingerprint(long version, Object input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((version + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
