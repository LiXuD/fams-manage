package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.loan.LoanLossPort;
import com.fams.loss.LossScrapSourcePort;
import com.fams.repair.RepairService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns scrap documents and their locks. The asset port owns lifecycle transitions. */
@Service
@Profile("fams-product")
public class ScrapService {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;
    private final LossScrapSourcePort losses;
    private final LoanLossPort loans;
    private final RepairService repairs;

    /**
     * Wires the scrap document store to the four cross-domain ports a submission can originate from. The
     * service never decides an asset lifecycle transition itself — it writes the request row, the lock
     * and the receipt, and asks {@link AssetScrapPort} to freeze the asset. The three source ports exist
     * because a loss report, a repair order and a loan each hold evidence the scrap request must carry
     * into its frozen snapshot, and each of them validates that evidence under its own lock.
     *
     * @param db JDBC template writing the request, lock, todo, event, audit and receipt rows
     * @param json mapper serialising the frozen source snapshot and the idempotency fingerprint payloads
     * @param clock time source for the truncated millisecond timestamp shared by every row of one submission
     * @param actors actor context supplying {@code revalidateLocked} for each submission entry point
     * @param assets asset-side scrap port building the stored source and freezing the asset lifecycle
     * @param losses loss-domain port supplying the confirmed loss evidence a loss-sourced scrap carries
     * @param loans loan-domain port asserting the borrowing or recovery state a loss-sourced scrap needs
     * @param repairs repair-domain port supplying repair and factory-replacement sources and the lock handoff
     */
    public ScrapService(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, AssetScrapPort assets, LossScrapSourcePort losses, LoanLossPort loans, RepairService repairs) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.losses = losses;
        this.loans = loans;
        this.repairs = repairs;
    }

    /**
     * Standalone scrap submission payload, and also the normalised internal shape every other source is
     * converted into before the request row is written. The three text fields are stored stripped, and
     * their size limits are enforced here rather than in the database so an oversized submission is a
     * 400 at the boundary instead of a truncation.
     *
     * @param assetId fams_asset primary key to scrap, supplied by the client only for a standalone request
     * @param assetVersion version of the asset the caller saw, pinned and compared under lock
     * @param reason justification for scrapping, at most 2000 characters
     * @param observedCondition reported physical condition, at most 2000 characters
     * @param proposedDisposition disposition the requester proposes, at most 500 characters; the decision
     *     may still choose another one
     */
    public record Submission(
            @Positive long assetId,
            @NotNull @PositiveOrZero Long assetVersion,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2000) String observedCondition,
            @NotBlank @Size(max = 500) String proposedDisposition) {}

    /**
     * Submission acknowledgement, serialised into {@code fams_scrap_receipt.response_json} and replayed
     * verbatim whenever the same actor presents the same idempotency key inside the receipt window. A
     * replay therefore returns the version and status recorded at the original submission, not the
     * current row state, which is exactly what makes the retry safe.
     *
     * @param id stringified fams_scrap_request primary key created by the submission
     * @param assetId stringified fams_asset primary key the request targets
     * @param assetVersion asset version after the freeze, which is one above the pinned input version
     * @param version request row version, always 0 at submission because no mutation has happened yet
     * @param status request status after submission, always {@code PENDING_ADMIN_DECISION}
     */
    public record Receipt(String id, String assetId, long assetVersion, long version, String status) {}

    /**
     * Loss-sourced scrap submission payload. It omits the asset identifier on purpose: the asset is taken
     * from the confirmed loss report named in the path, so a client cannot scrap a different asset than
     * the one that was lost. The report version travels in the {@code If-Match-Version} header and joins
     * this payload in the idempotency fingerprint, which means reusing a key with a different report
     * version is a conflict rather than a replay.
     *
     * @param assetVersion version of the lost asset the caller saw, pinned and compared under lock
     * @param reason justification for scrapping the lost asset, at most 2000 characters
     * @param observedCondition reported physical condition, at most 2000 characters
     * @param proposedDisposition disposition the requester proposes, at most 500 characters
     */
    public record LossSubmission(@NotNull @PositiveOrZero Long assetVersion,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2000) String observedCondition,
            @NotBlank @Size(max = 500) String proposedDisposition) {}

    private record LossIntent(long reportVersion, LossSubmission input) {}

    /**
     * Creates a standalone scrap request for an asset that is not already the subject of a loss report,
     * a repair order or a factory replacement. The actor is revalidated under the identity lock, the
     * idempotency fingerprint is computed over the whole payload, and a matching prior receipt short
     * circuits the rest of the method so no row is written twice.
     *
     * <p>The asset side supplies the stored source and then freezes the asset lifecycle. Because no other
     * flow holds this asset, the shared creation path inserts a fresh {@code SCRAP} business lock keyed by
     * the request id in both the business and the item column, so from that point the asset cannot be
     * moved by any other flow until the request is decided, cancelled or executed. A submission event and
     * an audit event are written with the payload facts, and an {@code ASSET_ADMIN} approval todo is opened.
     *
     * @param actor submitting actor, whose account becomes the request creator
     * @param key client idempotency key scoping the receipt row for the {@code SUBMIT} operation
     * @param input standalone submission payload carrying the asset and the three text fields
     * @return receipt naming the created request, the asset, the frozen asset version and the pending status
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ASSET_NOT_ELIGIBLE} when the actor is neither a
     *     global applicant nor the responsible employee nor the using organization's asset manager, 404
     *     {@code ASSET_NOT_FOUND}, 409 {@code SCRAP_ASSET_CHANGED} on a version mismatch, 409
     *     {@code SCRAP_SOURCE_NOT_ELIGIBLE} when the card is not active and normal in stock or in use, 409
     *     {@code SCRAP_ASSET_LOCKED} when the asset already carries an unreleased business lock, or 409
     *     {@code SCRAP_IDEMPOTENCY_CONFLICT} when the key is reused with a different fingerprint
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt submit(Actor actor, UUID key, Submission input) {
        var current = actors.revalidateLocked(actor);
        String hash = fingerprint(input);
        var prior = replay(current, "SUBMIT", key, hash);
        if (prior != null) {
            return prior;
        }
        var source = assets.standaloneSource(current, input.assetId(), input.assetVersion());
        return create(actor, current, "SUBMIT", key, hash, input, "STANDALONE", null,
                new AssetScrapPort.StoredSource(source, null, null, null), input, null, null);
    }

    /**
     * Creates a scrap request from a confirmed loss report, so that a lost asset can be written off
     * financially. The report is re-read under its own lock at the pinned version and its confirmation
     * evidence is copied into the frozen snapshot, which means the request keeps proving its origin even
     * after the report itself moves on.
     *
     * <p>Three loan and repair situations are distinguished, because each leaves a different trace that
     * the write-off must account for. A {@code BORROWED} loss still has an open borrowing, which is
     * resolved and recorded by id and version. A loss that occurred during a factory repair round is
     * resolved through the repair domain, and the repair processor becomes the scrap processor, so the
     * request starts already assigned; when the repair side can no longer confirm the round, the
     * submission is refused as a source change rather than being created without its proof. Any other
     * lifecycle only has to pass the loan-side recovery assertion.
     *
     * <p>The asset is then frozen through the loss branch and a fresh {@code SCRAP} business lock is
     * inserted, since no other flow was holding the asset.
     *
     * @param actor submitting actor, whose account becomes the request creator
     * @param reportId fams_loss_report primary key the scrap originates from
     * @param version loss report version the caller saw, pinned and joined into the idempotency fingerprint
     * @param key client idempotency key scoping the receipt row for {@code SUBMIT_LOSS:<reportId>}
     * @param input loss submission payload carrying the pinned asset version and the three text fields
     * @return receipt naming the created request, the lost asset, the frozen asset version and the
     *     pending status
     * @throws AssetHandoverPort.Failure 409 {@code SCRAP_SOURCE_OR_OWNERSHIP_CHANGED} when the factory
     *     repair round no longer confirms the loss, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key
     *     with a different fingerprint, or any refusal the asset port raises while freezing the loss source
     * @throws com.fams.loss.LossFailure 403 {@code SCRAP_LOSS_SOURCE_FORBIDDEN} when the actor may not use
     *     this report as a source, 404 {@code LOSS_NOT_FOUND}, or 409 {@code SCRAP_LOSS_SOURCE_CHANGED}
     *     when the report moved past the pinned version
     * @throws com.fams.loan.LoanLossPort.Failure {@code LOSS_LOAN_IDENTITY_CHANGED} when the borrower or
     *     using organization recorded on the loss no longer matches the loan side, reported as 409
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt submitFromLoss(Actor actor, long reportId, long version, UUID key, LossSubmission input) {
        var current = actors.revalidateLocked(actor);
        String operation = "SUBMIT_LOSS:" + reportId;
        String hash = fingerprint(new LossIntent(version, input));
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var loss = losses.requireSubmissionEvidence(current, reportId, version);
        var source = assets.lossSource(loss.assetId(), input.assetVersion(),
                loss.lifecycle(), json.readTree(loss.sourceJson()));
        var proof = new LinkedHashMap<String, Object>();
        proof.put("reportId", Long.toString(reportId));
        proof.put("reportVersion", version);
        proof.put("confirmedAt", loss.confirmedAt());
        proof.put("confirmation", json.readTree(loss.confirmationJson()));
        Long processor = null;
        if ("BORROWED".equals(loss.lifecycle())) {
            var borrowing = loans.requireOpenBorrowing(loss.assetId(),
                    source.responsibleEmployeeId(), source.usingOrganizationId());
            proof.put("loanId", Long.toString(borrowing.id()));
            proof.put("loanVersion", borrowing.version());
        } else if (loss.factory() != null) {
            var factory = loss.factory();
            var repair = repairs.factoryLossScrap(factory.repairId(), factory.roundId(), loss.assetId(),
                    factory.processorAccountId());
            if (repair == null) {
                throw new AssetHandoverPort.Failure("SCRAP_SOURCE_OR_OWNERSHIP_CHANGED", 409);
            }
            proof.put("factoryRepair", repair);
            processor = repair.processorAccountId();
        } else {
            loans.requireRecovery(loss.assetId(), loss.lifecycle(), null, null);
        }
        var submission = new Submission(loss.assetId(), input.assetVersion(), input.reason(),
                input.observedCondition(), input.proposedDisposition());
        return create(actor, current, operation, key, hash, submission, "LOSS", reportId,
                new AssetScrapPort.StoredSource(source, proof, null, null), input, null, processor);
    }

    /**
     * Repair-sourced scrap submission payload, used both for an internal repair that ended in scrapping
     * the asset and for a factory replacement. It pins three independent versions instead of one, because
     * three different rows have to agree before the handoff is safe: the asset, the asset ownership
     * projection and the repair business lock that is about to be transferred to the new scrap request.
     *
     * @param assetVersion version of the asset the caller saw, compared by the repair and asset domains
     * @param ownershipVersion version of the asset ownership projection the caller saw
     * @param lockVersion version of the repair business lock to hand over; a mismatch means another flow
     *     already touched the lock and the submission is refused
     * @param reason justification for scrapping, at most 2000 characters
     * @param observedCondition reported physical condition, at most 2000 characters
     * @param proposedDisposition disposition the requester proposes, at most 500 characters; for a
     *     factory replacement it must equal the method already approved in the repair round
     */
    public record RepairSubmission(@NotNull @PositiveOrZero Long assetVersion,
            @NotNull @PositiveOrZero Long ownershipVersion, @NotNull @PositiveOrZero Long lockVersion,
            @NotBlank @Size(max = 2000) String reason, @NotBlank @Size(max = 2000) String observedCondition,
            @NotBlank @Size(max = 500) String proposedDisposition) {}

    private record RepairLink(RepairService.ScrapSource source, long lockVersion, Long replacementId) {}

    /**
     * Creates a scrap request from an internal repair order that physically accepted the asset and then
     * concluded it must be scrapped. The warehouse operator role is asserted before the idempotency
     * lookup, so a caller without the role cannot even probe whether a key was used.
     *
     * <p>The repair domain re-reads the order under its own lock at all four pinned versions and returns
     * the evidence bundle — original snapshot, approval and physical-handling context, and any open
     * borrowing — which is copied into the frozen source snapshot. Because the repair already holds a
     * business lock on the asset, the shared creation path does not insert a new one; the repair lock is
     * transferred to the new request and the repair order is closed and bumped in the same transaction.
     *
     * @param actor submitting warehouse operator, who must also own the repair order
     * @param repairId fams_repair_request primary key the scrap originates from
     * @param version repair order version the caller saw, pinned and joined into the idempotency fingerprint
     * @param key client idempotency key scoping the receipt row for {@code SUBMIT_REPAIR:<repairId>}
     * @param input repair submission payload carrying the three pinned versions and the three text fields
     * @return receipt naming the created request, the asset, the frozen asset version and the pending
     *     status, with the repair processor recorded as the scrap processor
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the warehouse operator
     *     role, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key with a different fingerprint, or
     *     any refusal the asset port raises while freezing the repair source
     * @throws com.fams.repair.RepairFailure as raised by the repair domain, for example 409
     *     {@code REPAIR_PHYSICAL_ACCEPTANCE_REQUIRED} when the order never accepted the asset physically
     * @throws com.fams.loan.LoanRepairPort.Failure when the open borrowing no longer matches the loan the
     *     repair order was created against
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt submitFromRepair(Actor actor, long repairId, long version, UUID key, RepairSubmission input) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(com.fams.auth.Role.WAREHOUSE_OPERATOR)) {
            throw new AssetHandoverPort.Failure("SCRAP_ROLE_REQUIRED", 403);
        }
        String operation = "SUBMIT_REPAIR:" + repairId;
        String hash = fingerprint(new RepairIntent(version, input));
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var repair = repairs.scrapSource(actor, repairId, version, input.assetVersion(), input.ownershipVersion(), input.lockVersion());
        return createFromRepair(actor, current, operation, key, hash, input, repair, null);
    }

    /**
     * Creates a scrap request for the original asset of one factory replacement. A factory repair
     * replaces an asset at a service partner, and the asset that came back is scrapped under the disposal
     * method that the replacement round already approved; this method therefore pins two more versions
     * than the internal repair path — the repair order and the round — and identifies the request by the
     * replacement rather than by the repair, since one repair can have several rounds and replacements.
     *
     * <p>The proposed disposition is compared against the approved {@code method} inside the repair
     * context, after stripping surrounding whitespace, and any difference is refused: the scrap may only
     * execute a disposal that already went through factory approval, so a caller cannot widen the
     * approved outcome by wording the proposal differently.
     *
     * <p>The created request records the replacement id in its source item column, which is what lets the
     * read side and the asset-side trace walk between the original and its replacement. The repair lock
     * is handed over exactly as in the internal repair path, and the repair processor becomes the scrap
     * processor.
     *
     * @param actor submitting warehouse operator, who must also own the repair order
     * @param repairId fams_repair_request primary key the factory round belongs to
     * @param roundId fams_repair_factory_round primary key the replacement belongs to
     * @param replacementId fams_repair_factory_replacement primary key whose original asset is scrapped
     * @param version repair order version the caller saw, joined into the idempotency fingerprint
     * @param roundVersion factory round version the caller saw, joined into the idempotency fingerprint
     * @param key client idempotency key scoping the receipt row for {@code SUBMIT_FACTORY:<replacementId>}
     * @param input repair submission payload carrying the three pinned versions and the three text fields
     * @return receipt naming the created request, the original asset, the frozen asset version and the
     *     pending status, with the repair processor recorded as the scrap processor
     * @throws AssetHandoverPort.Failure 403 {@code SCRAP_ROLE_REQUIRED} without the warehouse operator
     *     role, 409 {@code SCRAP_DISPOSITION_OUTSIDE_APPROVAL} when the proposed disposition differs from
     *     the approved factory method, 409 {@code SCRAP_IDEMPOTENCY_CONFLICT} on a reused key with a
     *     different fingerprint, or any refusal the asset port raises while freezing the repair source
     * @throws com.fams.repair.RepairFailure as raised by the repair domain when the order, round or
     *     replacement does not match the pinned versions or the actor may not act on it
     * @throws com.fams.loan.LoanRepairPort.Failure when the open borrowing no longer matches the loan the
     *     repair order was created against
     * @throws com.fams.auth.AuthOperationException when the actor identity is no longer valid
     */
    @Transactional(rollbackFor = Exception.class)
    public Receipt submitFromFactory(Actor actor, long repairId, long roundId, long replacementId, long version,
            long roundVersion, UUID key, RepairSubmission input) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(com.fams.auth.Role.WAREHOUSE_OPERATOR)) {
            throw new AssetHandoverPort.Failure("SCRAP_ROLE_REQUIRED", 403);
        }
        String operation = "SUBMIT_FACTORY:" + replacementId;
        String hash = fingerprint(new FactoryIntent(repairId, roundId, version, roundVersion, input));
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var repair = repairs.factoryScrapSource(actor, repairId, roundId, replacementId, version, roundVersion,
                input.assetVersion(), input.ownershipVersion(), input.lockVersion());
        if (!json.readTree(repair.contextJson()).path("method").asText().equals(input.proposedDisposition().strip())) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_OUTSIDE_APPROVAL", 409);
        }
        return createFromRepair(actor, current, operation, key, hash, input, repair, replacementId);
    }

    private Receipt createFromRepair(Actor actor, Current current, String operation, UUID key, String hash,
            RepairSubmission input, RepairService.ScrapSource repair, Long replacementId) {
        boolean factory = replacementId != null;
        var source = assets.repairSource(repair.assetId(), input.assetVersion(), factory);
        var proof = new LinkedHashMap<String, Object>();
        proof.put("repairVersion", repair.repairVersion() + 1);
        proof.put("original", json.readTree(repair.originalSnapshot()));
        proof.put("context", json.readTree(repair.contextJson()));
        proof.put("borrowing", repair.borrowing());
        var submission = new Submission(repair.assetId(), input.assetVersion(), input.reason(),
                input.observedCondition(), input.proposedDisposition());
        return create(actor, current, operation, key, hash, submission, factory ? "FACTORY_REPLACEMENT" : "REPAIR",
                repair.repairId(), new AssetScrapPort.StoredSource(source, null, proof, null), input,
                new RepairLink(repair, input.lockVersion(), replacementId),
                repair.processorAccountId());
    }

    private record RepairIntent(long repairVersion, RepairSubmission input) {}
    private record FactoryIntent(long repairId, long roundId, long repairVersion, long roundVersion,
            RepairSubmission input) {}

    private Receipt replay(Current current, String operation, UUID key, String hash) {
        var prior = db.queryForList("SELECT payload_sha256,response_json FROM fams_scrap_receipt WHERE actor_account_id=?"
                + " AND operation=? AND request_id=? FOR UPDATE", current.id(), operation, key.toString());
        if (prior.isEmpty()) {
            return null;
        }
        if (!hash.equals(prior.getFirst().get("payload_sha256"))) {
            throw new AssetHandoverPort.Failure("SCRAP_IDEMPOTENCY_CONFLICT", 409);
        }
        return json.readValue(prior.getFirst().get("response_json").toString(), Receipt.class);
    }

    private Receipt create(Actor actor, Current current, String operation, UUID key, String hash,
            Submission input, String sourceType, Long sourceId, AssetScrapPort.StoredSource source,
            Object facts, RepairLink link, Long processor) {
        var time = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update(
                "INSERT INTO fams_scrap_request(asset_id,creator_account_id,source_type,source_business_id,source_item_id,"
                        + "source_snapshot_json,reason,observed_condition,proposed_disposition,"
                        + "asset_version,created_at,updated_at,processor_account_id,ownership_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                input.assetId(), current.id(), sourceType, sourceId, link == null ? null : link.replacementId(),
                json.writeValueAsString(source), input.reason().strip(),
                input.observedCondition().strip(), input.proposedDisposition().strip(), input.assetVersion() + 1,
                time, time, processor, processor == null ? 0 : 1);
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        if (link == null) {
            db.update("INSERT INTO fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,"
                    + "acquired_at) VALUES (?,'SCRAP',?,?,?)", input.assetId(), id, id, time);
        } else {
            repairs.handoffToScrap(actor, current, link.source(), id, link.lockVersion(), input.assetVersion() + 1);
        }
        long assetVersion = switch (sourceType) {
            case "LOSS" -> assets.freezeLoss(input.assetId(), input.assetVersion(), id,
                    source.row().lifecycleStatus(), current.id(), time);
            case "REPAIR", "FACTORY_REPLACEMENT" -> assets.freezeRepair(input.assetId(), input.assetVersion(), id,
                    current.id(), time, "FACTORY_REPLACEMENT".equals(sourceType));
            default -> assets.freezeStandalone(input.assetId(), input.assetVersion(), id, current.id(), time);
        };
        db.update(
                "INSERT INTO fams_todo(pool_type,business_type,business_id,todo_type,todo_status,opened_at)"
                        + " VALUES ('ASSET_ADMIN','SCRAP',?,'APPROVAL','OPEN',?)",
                id, time);
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        String detail = sourceId == null ? json.writeValueAsString(facts)
                : json.writeValueAsString(Map.of("sourceType", sourceType, "facts", facts, "sourceId", sourceId.toString()));
        db.update(
                "INSERT INTO fams_scrap_event(request_id,action,before_version,after_version,"
                        + "actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                        + " VALUES (?,'SCRAP_SUBMITTED',0,0,?,?,?,?,?)",
                id, current.id(), current.username(), role, detail, time);
        db.update(
                "INSERT INTO fams_audit_event(request_id,actor_account_id,actor_role,action,"
                        + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                        + " VALUES (?,?,?,'SCRAP_SUBMITTED','SCRAP',?,0,0,?,?)",
                actor.requestId().toString(), current.id(), role, id, time, detail);
        var result = new Receipt(Long.toString(id), Long.toString(input.assetId()), assetVersion,
                0, "PENDING_ADMIN_DECISION");
        LOG.info("Scrap request submitted actor={} scrapId={} assetId={} sourceType={} sourceId={}",
                current.id(), id, input.assetId(), sourceType, sourceId);
        db.update(
                "INSERT INTO fams_scrap_receipt(actor_account_id,operation,request_id,scrap_request_id,"
                        + "payload_sha256,response_json,created_at) VALUES (?,?,?,?,?,?,?)",
                current.id(), operation, key.toString(), id, hash, json.writeValueAsString(result), time);
        return result;
    }

    private String fingerprint(Object input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
