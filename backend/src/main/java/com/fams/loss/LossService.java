package com.fams.loss;

import com.fams.asset.AssetLossPort;
import com.fams.attachment.AttachmentEvidencePort;
import com.fams.attachment.AttachmentIntake;
import com.fams.attachment.AttachmentRegistry;
import com.fams.attachment.MalwareScanner;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Independent loss reporting. Approval authorizes verification; it never confirms a loss. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class LossService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final AssetLossPort assets;
    private final com.fams.loan.LoanLossPort loans;
    private final ObjectProvider<AttachmentRegistry> attachments;
    private final ObjectProvider<AttachmentEvidencePort> evidence;

    public LossService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            AssetLossPort assets,
            com.fams.loan.LoanLossPort loans,
            ObjectProvider<AttachmentRegistry> attachments,
            ObjectProvider<AttachmentEvidencePort> evidence) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.assets = assets;
        this.loans = loans;
        this.attachments = attachments;
        this.evidence = evidence;
    }

    public Map<String, Object> eligible(Actor actor, int page, int size) {
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    public Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_loss_report WHERE (? OR creator_account_id=? OR"
                    + " responsible_employee_id=? OR shared_responsible_employee_id=? OR (? AND"
                    + " using_organization_id=?) OR (? AND approval_snapshot_json IS NOT NULL))";
        Object[] scope = {
            current.has(Role.ASSET_ADMIN),
            current.id(),
            current.employeeId(),
            current.employeeId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER),
            current.organizationId(),
            current.has(Role.WAREHOUSE_OPERATOR)
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        return Map.of(
                "items",
                db
                        .queryForList(
                                "SELECT *" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(this::view)
                        .toList(),
                "total",
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        visible(current, row);
        var result = new LinkedHashMap<>(view(row));
        var locks =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='LOST_REPORT' AND business_id=? AND released_at IS"
                            + " NULL",
                        row.get("asset_id"),
                        id);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        var photos = evidence.getIfAvailable();
        result.put("photosAvailable", photos != null);
        result.put("photos", photos == null ? List.of() : wire(photos.list("LOST_REPORT", id)));
        result.put(
                "history",
                wire(
                        db.queryForList(
                                "SELECT * FROM fams_loss_event WHERE report_id=? ORDER BY id DESC"
                                        + " LIMIT 100",
                                id)));
        result.put(
                "historyTotal",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_event WHERE report_id=?", Long.class, id));
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("report_status"));
        result.put(
                "confirmation",
                row.get("confirmation_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("confirmation_snapshot_json").toString()));
        boolean recoveryOpen =
                row.get("recovered_at") == null
                        && List.of("SEARCHING", "CONFIRMED_LOST")
                                .contains(row.get("report_status"));
        var recoveryAsset = recoveryOpen ? assets.recoveryView(number(row, "asset_id")) : null;
        result.put("recoveryAsset", recoveryAsset);
        var recoveries =
                db.queryForList(
                        "SELECT * FROM fams_loss_recovery WHERE report_id=?"
                                + " ORDER BY (active_guard IS NOT NULL) DESC,id DESC LIMIT 20",
                        id);
        result.put(
                "recoveries",
                recoveries.stream().map(item -> recoveryView(current, item)).toList());
        result.put(
                "recoveriesTotal",
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_loss_recovery WHERE report_id=?",
                        Long.class,
                        id));
        result.put(
                "actions",
                Map.of(
                        "recoverOriginal",
                        "SEARCHING".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        "requestRecovery",
                        "CONFIRMED_LOST".equals(row.get("report_status"))
                                && recoveryOpen
                                && applicant(current)
                                && Boolean.TRUE.equals(recoveryAsset.get("eligible"))
                                && !Boolean.TRUE.equals(recoveryAsset.get("locked")),
                        "confirmLost",
                        "SEARCHING".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        "followUp",
                        "CONFIRMED_LOST".equals(row.get("report_status"))
                                && row.get("recovered_at") == null
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id")),
                        "decide",
                        pending && current.has(Role.ASSET_ADMIN),
                        "cancel",
                        pending
                                && (current.has(Role.ASSET_ADMIN)
                                        || current.id() == number(row, "creator_account_id")),
                        "addPhoto",
                        pending
                                && photos != null
                                && current.id() == number(row, "creator_account_id"),
                        "claim",
                        "WAITING_WAREHOUSE_CLAIM".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR),
                        "search",
                        "VERIFYING".equals(row.get("report_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id"))));
        return result;
    }

    public Map<String, Object> submit(Actor actor, UUID key, LossInput.Submission input) {
        var current = actors.revalidateLocked(actor);
        String hash = LossPayloads.fingerprint(json, "SUBMIT", 0, input);
        var prior = replay(current, "SUBMIT", key, hash);
        if (prior != null) {
            return prior;
        }
        validTime(input.lastSeenAt());
        String source = assets.submission(current, input.assetId(), input.assetVersion());
        var snapshot = json.readTree(source);
        db.update(
                "INSERT INTO"
                    + " fams_loss_report(asset_id,creator_account_id,using_organization_id,responsible_employee_id,shared_responsible_employee_id,asset_version,source_snapshot_json,last_seen_at,last_location,circumstances,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                input.assetId(),
                current.id(),
                reference(snapshot, "usingOrganizationId"),
                reference(snapshot, "responsibleEmployeeId"),
                reference(snapshot, "departmentResponsibleEmployeeId"),
                input.assetVersion(),
                source,
                input.lastSeenAt(),
                input.lastLocation().strip(),
                input.circumstances().strip(),
                now(),
                now());
        long id =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (?,'LOST_REPORT',?,?,?)",
                input.assetId(),
                id,
                id,
                now());
        todo(id, "APPROVAL", "ASSET_ADMIN", null);
        audit(actor, current, header(id), "LOSS_REPORTED", 0, input);
        return remember(current, "SUBMIT", key, hash, header(id));
    }

    public Map<String, Object> decide(
            Actor actor, long id, long version, UUID key, LossInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "DECIDE:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        if (!List.of("APPROVE", "REJECT").contains(input.result())) {
            throw new LossFailure(400, "LOSS_DECISION_INVALID");
        }
        boolean approve = "APPROVE".equals(input.result());
        var photos = evidence.getIfAvailable();
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("report", view(row));
        frozen.put("reason", input.reason());
        frozen.put("photos", photos == null ? List.of() : wire(photos.list("LOST_REPORT", id)));
        if (approve) {
            assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"));
            if (photos == null || photos.list("LOST_REPORT", id).isEmpty()) {
                throw new LossFailure(409, "LOSS_PHOTO_REQUIRED");
            }
        }
        db.update(
                "UPDATE fams_loss_report SET"
                        + " report_status=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                        + " WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                approve ? json.writeValueAsString(frozen) : null,
                now(),
                id);
        closeTodos(id);
        if (approve) {
            todo(id, "CLAIM", "WAREHOUSE_OPERATOR", null);
        } else {
            release(row, "LOSS_REJECTED");
        }
        audit(actor, current, row, "LOSS_DECIDED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> cancel(
            Actor actor, long id, long version, UUID key, LossInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (current.id() != number(row, "creator_account_id") && !current.has(Role.ASSET_ADMIN)) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        String operation = "CANCEL:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        release(row, "LOSS_CANCELLED");
        closeTodos(id);
        db.update(
                "UPDATE fams_loss_report SET"
                        + " report_status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
                now(),
                id);
        audit(actor, current, row, "LOSS_CANCELLED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> claim(Actor actor, long id, long version, UUID key) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "CLAIM:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, Map.of());
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "WAITING_WAREHOUSE_CLAIM");
        lock(row, null);
        assets.requireSubmitted(number(row, "asset_id"), number(row, "asset_version"));
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='VERIFYING',processor_account_id=?,ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                id);
        closeTodos(id);
        todo(id, "VERIFY", "WAREHOUSE_OPERATOR", current.id());
        audit(
                actor,
                current,
                row,
                "LOSS_CLAIMED",
                version + 1,
                Map.of("processorAccountId", Long.toString(current.id())));
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> search(
            Actor actor, long id, long version, UUID key, LossInput.Search input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "SEARCH:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "VERIFYING");
        if (!java.util.Objects.equals(current.id(), row.get("processor_account_id"))
                || input.ownershipVersion() != number(row, "ownership_version")
                || input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        lock(row, input.lockVersion());
        validTime(input.searchedAt());
        if (input.searchedAt().isBefore(instant(row.get("last_seen_at")))
                || input.nextReviewDate().isBefore(today())) {
            throw new LossFailure(400, "LOSS_SEARCH_DATE_INVALID");
        }
        long assetVersion =
                assets.searching(
                        number(row, "asset_id"), input.assetVersion(), id, current.id(), now());
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='SEARCHING',asset_version=?,next_review_date=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                assetVersion,
                input.nextReviewDate(),
                now(),
                id);
        closeTodos(id);
        todo(id, "SEARCH", "WAREHOUSE_OPERATOR", current.id());
        audit(actor, current, row, "LOSS_SEARCH_STARTED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> followUps(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        String where =
                " FROM fams_loss_report r JOIN fams_asset a ON a.id=r.asset_id"
                        + " WHERE r.report_status='CONFIRMED_LOST' AND a.exception_status='LOST'"
                        + " AND a.lifecycle_status<>'SCRAPPED' AND (? OR r.creator_account_id=?"
                        + " OR r.responsible_employee_id=? OR r.shared_responsible_employee_id=?"
                        + " OR (? AND r.using_organization_id=?) OR ?)";
        Object[] scope = {
            current.has(Role.ASSET_ADMIN),
            current.id(),
            current.employeeId(),
            current.employeeId(),
            current.has(Role.DEPARTMENT_ASSET_MANAGER),
            current.organizationId(),
            current.has(Role.WAREHOUSE_OPERATOR)
        };
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        return Map.of(
                "items",
                db
                        .queryForList(
                                "SELECT r.*"
                                        + where
                                        + " ORDER BY r.next_review_date,r.id LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(this::view)
                        .toList(),
                "total",
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope)),
                "page",
                page,
                "pageSize",
                size);
    }

    public Map<String, Object> confirmLost(
            Actor actor, long id, long version, UUID key, LossInput.Confirmation input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "CONFIRM_LOST:" + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "SEARCHING");
        processor(current, row, input.ownershipVersion());
        if (input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        if (List.of(
                        input.custodianVerification(),
                        input.searchedScope(),
                        input.relatedClues(),
                        input.inabilityReason(),
                        input.responsibilityEvidence())
                .stream()
                .anyMatch(value -> value.strip().length() < 10)) {
            throw new LossFailure(400, "LOSS_CONFIRMATION_EVIDENCE_REQUIRED");
        }
        lock(row, input.lockVersion());
        validTime(input.searchedAt());
        validTime(input.confirmedAt());
        if (input.searchedAt().isBefore(instant(row.get("last_seen_at")))
                || input.confirmedAt().isBefore(input.searchedAt())) {
            throw new LossFailure(400, "LOSS_CONFIRMATION_DATE_INVALID");
        }
        long next =
                assets.confirmedLost(
                        number(row, "asset_id"), input.assetVersion(), id, current.id(), now());
        db.update(
                "UPDATE fams_loss_report SET report_status='CONFIRMED_LOST',asset_version=?,"
                        + "confirmed_lost_at=?,confirmation_snapshot_json=?,next_review_date=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                next,
                input.confirmedAt(),
                json.writeValueAsString(input),
                today().plusDays(30),
                now(),
                id);
        release(row, "LOSS_CONFIRMED");
        closeTodos(id);
        todo(id, "LOST_FOLLOW_UP", "WAREHOUSE_OPERATOR", current.id());
        loans.refreshLiability(number(row, "asset_id"));
        audit(actor, current, row, "LOSS_CONFIRMED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> followUp(
            Actor actor, long id, long version, UUID key, LossInput.FollowUp input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "FOLLOW_UP:" + id;
        String hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "CONFIRMED_LOST");
        processor(current, row, input.ownershipVersion());
        validTime(input.reviewedAt());
        if (input.findings().strip().length() < 10 || input.reason().strip().length() < 3) {
            throw new LossFailure(400, "LOSS_FOLLOW_UP_EVIDENCE_REQUIRED");
        }
        LocalDate next =
                input.nextReviewDate() == null ? today().plusDays(30) : input.nextReviewDate();
        if (input.reviewedAt().isBefore(instant(row.get("confirmed_lost_at")))
                || row.get("last_follow_up_at") != null
                        && input.reviewedAt().isBefore(instant(row.get("last_follow_up_at")))
                || next.isBefore(today())) {
            throw new LossFailure(400, "LOSS_REVIEW_DATE_INVALID");
        }
        assets.requireLost(number(row, "asset_id"));
        db.update(
                "UPDATE fams_loss_report SET last_follow_up_at=?,next_review_date=?,"
                        + "version=version+1,updated_at=? WHERE id=?",
                input.reviewedAt(),
                next,
                now(),
                id);
        audit(actor, current, row, "LOSS_FOLLOWED_UP", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    private void processor(Current current, Map<String, Object> row, long version) {
        if (!java.util.Objects.equals(current.id(), row.get("processor_account_id"))
                || version != number(row, "ownership_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
    }

    public Map<String, Object> recoverSearching(
            Actor actor, long id, long version, UUID key, LossInput.RecoveryExecution input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVER_ORIGINAL:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        guard(row, version, "SEARCHING");
        processor(current, row, input.ownershipVersion());
        if (input.assetVersion() != number(row, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        lock(row, input.lockVersion());
        recoveryFacts(row, input.facts());
        long next = restoreOriginal(row, input.assetVersion(), "SEARCHING", current, input.facts());
        db.update(
                "UPDATE fams_loss_report SET"
                    + " report_status='RECOVERED',asset_version=?,recovered_at=?,recovery_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                next,
                input.facts().foundAt(),
                json.writeValueAsString(input),
                now(),
                id);
        release(row, "LOSS_RECOVERED");
        closeTodos(id);
        audit(actor, current, row, "LOSS_RECOVERED", version + 1, input);
        return remember(current, operation, key, hash, header(id));
    }

    public Map<String, Object> requestRecovery(
            Actor actor, long id, long version, UUID key, LossInput.RecoveryRequest input) {
        var current = actors.revalidateLocked(actor);
        if (!applicant(current)) {
            throw new LossFailure(403, "LOSS_ROLE_REQUIRED");
        }
        String operation = "RECOVERY_REQUEST:" + id,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = header(id);
        visible(current, row);
        guard(row, version, "CONFIRMED_LOST");
        if (row.get("recovered_at") != null) {
            throw new LossFailure(409, "LOSS_ALREADY_RECOVERED");
        }
        recoveryFacts(row, input.facts());
        var asset =
                assets.validateOriginalRecovery(
                        number(row, "asset_id"),
                        input.assetVersion(),
                        "LOST",
                        row.get("source_snapshot_json").toString(),
                        input.facts().observedAssetCode(),
                        input.facts().observedSerialNumber(),
                        input.facts().locationId(),
                        input.facts().locationVersion());
        loans.requireRecovery(
                number(row, "asset_id"),
                asset.get("lifecycle_status").toString(),
                (Long) asset.get("responsible_employee_id"),
                (Long) asset.get("using_organization_id"));
        if (!db.queryForList(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND released_at"
                                + " IS NULL FOR UPDATE",
                        row.get("asset_id"))
                .isEmpty()) {
            throw new LossFailure(409, "LOSS_ASSET_LOCKED");
        }
        db.update(
                "INSERT INTO"
                    + " fams_loss_recovery(report_id,asset_id,creator_account_id,asset_version,request_snapshot_json,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                id,
                row.get("asset_id"),
                current.id(),
                input.assetVersion(),
                json.writeValueAsString(input),
                now(),
                now());
        long recovery =
                java.util.Objects.requireNonNull(
                        db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update(
                "INSERT INTO"
                    + " fams_asset_business_lock(asset_id,business_type,business_id,business_item_id,acquired_at)"
                    + " VALUES (?,'LOST_REPORT',?,?,?)",
                row.get("asset_id"),
                id,
                recovery,
                now());
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_REQUESTED",
                Map.of("recoveryId", Long.toString(recovery), "facts", input));
        todo(id, "RECOVERY_DECIDE", "ASSET_ADMIN", null);
        return rememberRecovery(current, operation, key, hash, id, recovery);
    }

    public Map<String, Object> decideRecovery(
            Actor actor, long recovery, long version, UUID key, LossInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.ASSET_ADMIN);
        String operation = "RECOVERY_DECIDE:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "PENDING_ADMIN_DECISION");
        recoveryLock(row, recovery, null);
        boolean approve = "APPROVE".equals(input.result());
        if (!approve && !"REJECT".equals(input.result())) {
            throw new LossFailure(400, "LOSS_DECISION_INVALID");
        }
        if (approve) {
            assets.requireOriginalRecovery(
                    number(row, "asset_id"),
                    number(request, "asset_version"),
                    "LOST",
                    row.get("source_snapshot_json").toString());
        }
        db.update(
                "UPDATE fams_loss_recovery SET"
                    + " recovery_status=?,approval_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                approve ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED",
                json.writeValueAsString(
                        Map.of(
                                "actorAccountId",
                                Long.toString(current.id()),
                                "reason",
                                input.reason().strip(),
                                "request",
                                json.readTree(request.get("request_snapshot_json").toString()),
                                "recordedAt",
                                now().toString())),
                now(),
                recovery);
        closeRecoveryTodos(number(row, "id"));
        if (approve) {
            todo(number(row, "id"), "RECOVERY_CLAIM", "WAREHOUSE_OPERATOR", null);
        } else {
            release(row, "LOSS_RECOVERY_REJECTED");
        }
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_DECIDED",
                Map.of("recoveryId", Long.toString(recovery), "decision", input));
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    public Map<String, Object> cancelRecovery(
            Actor actor, long recovery, long version, UUID key, LossInput.Reason input) {
        var current = actors.revalidateLocked(actor);
        String operation = "RECOVERY_CANCEL:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        visible(current, row);
        if (current.id() != number(request, "creator_account_id")
                && !current.has(Role.ASSET_ADMIN)) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        recoveryGuard(request, version, "PENDING_ADMIN_DECISION");
        recoveryLock(row, recovery, null);
        db.update(
                "UPDATE fams_loss_recovery SET"
                        + " recovery_status='CANCELLED',version=version+1,updated_at=? WHERE id=?",
                now(),
                recovery);
        release(row, "LOSS_RECOVERY_CANCELLED");
        closeRecoveryTodos(number(row, "id"));
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_CANCELLED",
                Map.of("recoveryId", Long.toString(recovery), "reason", input.reason()));
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    public Map<String, Object> claimRecovery(Actor actor, long recovery, long version, UUID key) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_CLAIM:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, null);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "WAITING_WAREHOUSE_CLAIM");
        recoveryLock(row, recovery, null);
        assets.requireOriginalRecovery(
                number(row, "asset_id"),
                number(request, "asset_version"),
                "LOST",
                row.get("source_snapshot_json").toString());
        db.update(
                "UPDATE fams_loss_recovery SET"
                    + " recovery_status='VERIFYING',processor_account_id=?,ownership_version=ownership_version+1,version=version+1,updated_at=?"
                    + " WHERE id=?",
                current.id(),
                now(),
                recovery);
        closeRecoveryTodos(number(row, "id"));
        todo(number(row, "id"), "RECOVERY_VERIFY", "WAREHOUSE_OPERATOR", current.id());
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERY_CLAIMED",
                Map.of(
                        "recoveryId",
                        Long.toString(recovery),
                        "processorAccountId",
                        Long.toString(current.id())));
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    public Map<String, Object> executeRecovery(
            Actor actor, long recovery, long version, UUID key, LossInput.RecoveryExecution input) {
        var current = actors.revalidateLocked(actor);
        require(current, Role.WAREHOUSE_OPERATOR);
        String operation = "RECOVERY_EXECUTE:" + recovery,
                hash = LossPayloads.fingerprint(json, operation, version, input);
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        var row = recoveryParent(recovery);
        var request = recoveryHeader(recovery);
        recoveryGuard(request, version, "VERIFYING");
        processor(current, request, input.ownershipVersion());
        if (input.assetVersion() != number(request, "asset_version")) {
            throw new LossFailure(409, "LOSS_PROCESSOR_OR_ASSET_CHANGED");
        }
        recoveryLock(row, recovery, input.lockVersion());
        recoveryFacts(row, input.facts());
        long next = restoreOriginal(row, input.assetVersion(), "LOST", current, input.facts());
        db.update(
                "UPDATE fams_loss_recovery SET"
                    + " recovery_status='COMPLETED',execution_snapshot_json=?,version=version+1,updated_at=?"
                    + " WHERE id=?",
                json.writeValueAsString(input),
                now(),
                recovery);
        db.update(
                "UPDATE fams_loss_report SET"
                        + " asset_version=?,recovered_at=?,recovery_snapshot_json=? WHERE id=?",
                next,
                input.facts().foundAt(),
                json.writeValueAsString(input),
                row.get("id"));
        release(row, "LOSS_RECOVERY_COMPLETED");
        closeTodos(number(row, "id"));
        advanceRecoveryReport(
                actor,
                current,
                row,
                "LOSS_RECOVERED_AFTER_CONFIRMATION",
                Map.of("recoveryId", Long.toString(recovery), "execution", input));
        return rememberRecovery(current, operation, key, hash, number(row, "id"), recovery);
    }

    private boolean applicant(Current current) {
        return current.has(Role.EMPLOYEE)
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                || current.has(Role.ASSET_ADMIN)
                || current.has(Role.WAREHOUSE_OPERATOR);
    }

    private void recoveryFacts(Map<String, Object> row, LossInput.RecoveryFacts facts) {
        validTime(facts.foundAt());
        if (facts.foundAt().isBefore(instant(row.get("last_seen_at")))
                || row.get("confirmed_lost_at") != null
                        && facts.foundAt().isBefore(instant(row.get("confirmed_lost_at")))) {
            throw new LossFailure(400, "LOSS_RECOVERY_TIME_INVALID");
        }
        if (!facts.physicalIdentityVerified() || facts.evidence().strip().length() < 10) {
            throw new LossFailure(400, "LOSS_RECOVERY_EVIDENCE_REQUIRED");
        }
        if (!"INTACT".equals(facts.condition()) || !facts.stillOriginalCustodian()) {
            throw new LossFailure(409, "LOSS_LINKED_RECOVERY_REQUIRED");
        }
    }

    private long restoreOriginal(
            Map<String, Object> row,
            long version,
            String exception,
            Current actor,
            LossInput.RecoveryFacts facts) {
        var asset =
                assets.requireOriginalRecovery(
                        number(row, "asset_id"),
                        version,
                        exception,
                        row.get("source_snapshot_json").toString());
        loans.requireRecovery(
                number(row, "asset_id"),
                asset.get("lifecycle_status").toString(),
                (Long) asset.get("responsible_employee_id"),
                (Long) asset.get("using_organization_id"));
        long next =
                assets.recoverOriginal(
                        number(row, "asset_id"),
                        version,
                        exception,
                        row.get("source_snapshot_json").toString(),
                        facts.observedAssetCode(),
                        facts.observedSerialNumber(),
                        facts.locationId(),
                        facts.locationVersion(),
                        number(row, "id"),
                        actor.id(),
                        now());
        loans.refreshLiability(number(row, "asset_id"));
        return next;
    }

    private Map<String, Object> recoveryParent(long recovery) {
        var refs = db.queryForList("SELECT report_id FROM fams_loss_recovery WHERE id=?", recovery);
        if (refs.isEmpty()) {
            throw new LossFailure(404, "LOSS_RECOVERY_NOT_FOUND");
        }
        var row = header(number(refs.getFirst(), "report_id"));
        guard(row, number(row, "version"), "CONFIRMED_LOST");
        if (row.get("recovered_at") != null) {
            throw new LossFailure(409, "LOSS_ALREADY_RECOVERED");
        }
        return row;
    }

    private Map<String, Object> recoveryHeader(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loss_recovery WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_RECOVERY_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void recoveryGuard(Map<String, Object> row, long version, String status) {
        if (number(row, "version") != version || !status.equals(row.get("recovery_status"))) {
            throw new LossFailure(409, "LOSS_RECOVERY_CHANGED");
        }
    }

    private void recoveryLock(Map<String, Object> row, long recovery, Long version) {
        var locks =
                db.queryForList(
                        "SELECT business_item_id,lock_version FROM fams_asset_business_lock WHERE"
                            + " asset_id=? AND business_type='LOST_REPORT' AND business_id=? AND"
                            + " released_at IS NULL FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"));
        if (locks.size() != 1
                || number(locks.getFirst(), "business_item_id") != recovery
                || version != null && number(locks.getFirst(), "lock_version") != version) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void closeRecoveryTodos(long report) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='LOST_REPORT' AND business_id=? AND todo_type IN"
                        + " ('RECOVERY_DECIDE','RECOVERY_CLAIM','RECOVERY_VERIFY') AND closed_at IS"
                        + " NULL",
                now(),
                report);
    }

    private void advanceRecoveryReport(
            Actor actor, Current current, Map<String, Object> row, String action, Object detail) {
        db.update(
                "UPDATE fams_loss_report SET version=version+1,updated_at=? WHERE id=?",
                now(),
                row.get("id"));
        audit(actor, current, row, action, number(row, "version") + 1, detail);
    }

    private Map<String, Object> rememberRecovery(
            Current current, String operation, UUID key, String hash, long report, long recovery) {
        var result = new LinkedHashMap<>(view(header(report)));
        var attempt = recoveryHeader(recovery);
        result.put("recoveryId", Long.toString(recovery));
        result.put("recoveryVersion", attempt.get("version"));
        result.put("recoveryStatus", attempt.get("recovery_status"));
        saveReceipt(current, operation, key, hash, report, result);
        return result;
    }

    private Map<String, Object> recoveryView(Current current, Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String field :
                List.of(
                        "id",
                        "version",
                        "asset_version",
                        "recovery_status",
                        "creator_account_id",
                        "processor_account_id",
                        "ownership_version")) {
            String key =
                    switch (field) {
                        case "asset_version" -> "assetVersion";
                        case "recovery_status" -> "status";
                        case "creator_account_id" -> "creatorAccountId";
                        case "processor_account_id" -> "processorAccountId";
                        case "ownership_version" -> "ownershipVersion";
                        default -> field;
                    };
            Object value = row.get(field);
            result.put(
                    key,
                    field.equals("id") || field.endsWith("_id")
                            ? value == null ? null : value.toString()
                            : value);
        }
        result.put("request", json.readTree(row.get("request_snapshot_json").toString()));
        result.put(
                "approval",
                row.get("approval_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("approval_snapshot_json").toString()));
        result.put(
                "execution",
                row.get("execution_snapshot_json") == null
                        ? null
                        : json.readTree(row.get("execution_snapshot_json").toString()));
        boolean pending = "PENDING_ADMIN_DECISION".equals(row.get("recovery_status"));
        result.put(
                "actions",
                Map.of(
                        "decide",
                        pending && current.has(Role.ASSET_ADMIN),
                        "cancel",
                        pending
                                && (current.has(Role.ASSET_ADMIN)
                                        || current.id() == number(row, "creator_account_id")),
                        "claim",
                        "WAITING_WAREHOUSE_CLAIM".equals(row.get("recovery_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR),
                        "execute",
                        "VERIFYING".equals(row.get("recovery_status"))
                                && current.has(Role.WAREHOUSE_OPERATOR)
                                && java.util.Objects.equals(
                                        current.id(), row.get("processor_account_id"))));
        return result;
    }

    public void photoUploader(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        if (current.id() != number(header(id), "creator_account_id")) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void photoAccess(Actor actor, long id) {
        visible(actors.revalidateLocked(actor), header(id));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> registerPhoto(
            Actor actor,
            long id,
            long version,
            UUID key,
            String filename,
            AttachmentIntake.QuarantinedAttachment attachment) {
        var current = actors.revalidateLocked(actor);
        var row = header(id);
        if (current.id() != number(row, "creator_account_id")) {
            throw new LossFailure(403, "LOSS_CREATOR_REQUIRED");
        }
        String operation = "PHOTO:" + id,
                hash =
                        LossPayloads.fingerprint(
                                json,
                                operation,
                                version,
                                Map.of(
                                        "filename",
                                        filename,
                                        "sha256",
                                        attachment.file().sha256(),
                                        "mime",
                                        attachment.format().mime(),
                                        "size",
                                        attachment.file().size()));
        var prior = replay(current, operation, key, hash);
        if (prior != null) {
            return prior;
        }
        guard(row, version, "PENDING_ADMIN_DECISION");
        lock(row, null);
        if (attachment.scanResult() == MalwareScanner.Result.UNAVAILABLE) {
            throw new LossFailure(503, "LOSS_PHOTO_SCAN_UNAVAILABLE");
        }
        if (attachment.scanResult() != MalwareScanner.Result.CLEAN) {
            throw new LossFailure(422, "LOSS_PHOTO_REJECTED");
        }
        if (!attachment.format().image()) {
            throw new LossFailure(400, "LOSS_PHOTO_IMAGE_REQUIRED");
        }
        var registry = attachments.getIfAvailable();
        if (registry == null) {
            throw new LossFailure(503, "LOSS_PHOTOS_UNAVAILABLE");
        }
        registry.register("LOST_REPORT", id, version, current.id(), filename, attachment);
        db.update(
                "UPDATE fams_loss_report SET version=version+1,updated_at=? WHERE id=?", now(), id);
        var result =
                Map.<String, Object>of(
                        "id",
                        Long.toString(id),
                        "objectId",
                        attachment.file().id().toString(),
                        "version",
                        version + 1);
        audit(
                actor,
                current,
                row,
                "LOSS_PHOTO_ADDED",
                version + 1,
                Map.of(
                        "objectId",
                        attachment.file().id().toString(),
                        "sha256",
                        attachment.file().sha256()));
        saveReceipt(current, operation, key, hash, id, result);
        return result;
    }

    private Map<String, Object> replay(Current current, String operation, UUID key, String hash) {
        var rows =
                db.queryForList(
                        "SELECT * FROM fams_loss_receipt WHERE actor_account_id=? AND operation=?"
                                + " AND request_id=? FOR UPDATE",
                        current.id(),
                        operation,
                        key.toString());
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.getFirst();
        visible(current, header(number(row, "report_id")));
        if (!hash.equals(row.get("payload_sha256"))) {
            throw new LossFailure(409, "LOSS_IDEMPOTENCY_CONFLICT");
        }
        return json.readValue(
                row.get("response_json").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> remember(
            Current current, String operation, UUID key, String hash, Map<String, Object> row) {
        var result = view(row);
        saveReceipt(current, operation, key, hash, number(row, "id"), result);
        return result;
    }

    private void saveReceipt(
            Current current,
            String operation,
            UUID key,
            String hash,
            long id,
            Map<String, Object> result) {
        db.update(
                "INSERT INTO"
                    + " fams_loss_receipt(actor_account_id,operation,request_id,report_id,payload_sha256,response_json,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                current.id(),
                operation,
                key.toString(),
                id,
                hash,
                json.writeValueAsString(result),
                now());
    }

    private Map<String, Object> header(long id) {
        var rows = db.queryForList("SELECT * FROM fams_loss_report WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private void visible(Current current, Map<String, Object> row) {
        if (!(current.has(Role.ASSET_ADMIN)
                || current.id() == number(row, "creator_account_id")
                || java.util.Objects.equals(
                        current.employeeId(), row.get("responsible_employee_id"))
                || java.util.Objects.equals(
                        current.employeeId(), row.get("shared_responsible_employee_id"))
                || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        && java.util.Objects.equals(
                                current.organizationId(), row.get("using_organization_id"))
                || current.has(Role.WAREHOUSE_OPERATOR)
                        && row.get("approval_snapshot_json") != null)) {
            throw new LossFailure(403, "LOSS_NOT_VISIBLE");
        }
    }

    private void require(Current current, Role role) {
        if (!current.has(role)) {
            throw new LossFailure(403, "LOSS_ROLE_REQUIRED");
        }
    }

    private void guard(Map<String, Object> row, long version, String status) {
        if (version != number(row, "version") || !status.equals(row.get("report_status"))) {
            throw new LossFailure(409, "LOSS_REPORT_CHANGED");
        }
    }

    private void lock(Map<String, Object> row, Long version) {
        var rows =
                db.queryForList(
                        "SELECT lock_version FROM fams_asset_business_lock WHERE asset_id=? AND"
                            + " business_type='LOST_REPORT' AND business_id=? AND released_at IS"
                            + " NULL FOR UPDATE",
                        row.get("asset_id"),
                        row.get("id"));
        if (rows.size() != 1
                || version != null
                        && version.longValue() != number(rows.getFirst(), "lock_version")) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void release(Map<String, Object> row, String reason) {
        if (db.update(
                        "UPDATE fams_asset_business_lock SET"
                            + " released_at=?,release_reason=?,lock_version=lock_version+1 WHERE"
                            + " asset_id=? AND business_type='LOST_REPORT' AND business_id=? AND"
                            + " released_at IS NULL",
                        now(),
                        reason,
                        row.get("asset_id"),
                        row.get("id"))
                != 1) {
            throw new LossFailure(409, "LOSS_LOCK_CHANGED");
        }
    }

    private void todo(long id, String kind, String pool, Long owner) {
        db.update(
                "INSERT INTO"
                    + " fams_todo(pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'LOST_REPORT',?,?,'OPEN',?)",
                pool,
                owner,
                id,
                kind,
                now());
    }

    private void closeTodos(long id) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                        + " business_type='LOST_REPORT' AND business_id=? AND closed_at IS NULL",
                now(),
                id);
    }

    private void audit(
            Actor actor,
            Current current,
            Map<String, Object> row,
            String action,
            long after,
            Object detail) {
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        String body = json.writeValueAsString(detail);
        db.update(
                "INSERT INTO"
                    + " fams_loss_event(report_id,action,before_version,after_version,actor_account_id,actor_username,actor_role,detail_json,occurred_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("id"),
                action,
                row.get("version"),
                after,
                current.id(),
                current.username(),
                role,
                body,
                now());
        db.update(
                "INSERT INTO"
                    + " fams_audit_event(request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'LOST_REPORT',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role,
                action,
                row.get("id"),
                row.get("version"),
                after,
                now(),
                body);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field :
                Map.ofEntries(
                                Map.entry("id", "id"),
                                Map.entry("asset_id", "assetId"),
                                Map.entry("creator_account_id", "creatorAccountId"),
                                Map.entry("version", "version"),
                                Map.entry("asset_version", "assetVersion"),
                                Map.entry("report_status", "status"),
                                Map.entry("last_seen_at", "lastSeenAt"),
                                Map.entry("last_location", "lastLocation"),
                                Map.entry("circumstances", "circumstances"),
                                Map.entry("processor_account_id", "processorAccountId"),
                                Map.entry("ownership_version", "ownershipVersion"),
                                Map.entry("recovered_at", "recoveredAt"),
                                Map.entry("confirmed_lost_at", "confirmedLostAt"),
                                Map.entry("last_follow_up_at", "lastFollowUpAt"),
                                Map.entry("next_review_date", "nextReviewDate"))
                        .entrySet()) {
            var value = row.get(field.getKey());
            result.put(
                    field.getValue(),
                    field.getKey().equals("id") || field.getKey().endsWith("_id")
                            ? value == null ? null : value.toString()
                            : wire(value));
        }
        result.put("source", json.readTree(row.get("source_snapshot_json").toString()));
        return result;
    }

    private Object wire(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (k, v) ->
                            result.put(
                                    k.toString(),
                                    k.toString().equals("id") || k.toString().endsWith("_id")
                                            ? v == null ? null : v.toString()
                                            : wire(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::wire).toList();
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toInstant(java.time.ZoneOffset.UTC).toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return value;
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        return ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private void validTime(Instant time) {
        if (!time.equals(time.truncatedTo(ChronoUnit.MILLIS))
                || time.isAfter(clock.instant().plusSeconds(2))) {
            throw new LossFailure(400, "LOSS_TIME_INVALID");
        }
    }

    private LocalDate today() {
        String snapshot =
                db.queryForObject(
                        "SELECT v.snapshot_json FROM fams_business_parameter_state s JOIN"
                                + " fams_business_parameter_version v ON v.id=s.current_version_id"
                                + " WHERE s.singleton_id=1 AND v.status='ACTIVE' FOR SHARE",
                        String.class);
        return LocalDate.now(
                clock.withZone(ZoneId.of(json.readTree(snapshot).path("timezone").asText())));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Long reference(tools.jackson.databind.JsonNode row, String key) {
        return row.path(key).isNull() ? null : row.path(key).asLong();
    }
}
