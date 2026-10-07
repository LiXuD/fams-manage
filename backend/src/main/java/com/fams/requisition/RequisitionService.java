package com.fams.requisition;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;
import com.fams.masterdata.RequisitionMasterDataPort;
import com.fams.requisition.RequisitionInput.Allocation;
import com.fams.requisition.RequisitionInput.Demand;
import com.fams.requisition.RequisitionInput.Draft;
import com.fams.requisition.RequisitionInput.Reference;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Short transactions: credential/account, request, references, then sorted asset locks. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
class RequisitionService {
    private static final List<String> STATUSES =
            List.of(
                    "DRAFT",
                    "PENDING_ADMIN_DECISION",
                    "REJECTED",
                    "WAITING_WAREHOUSE_CLAIM",
                    "WAREHOUSE_PROCESSING",
                    "WAITING_HANDOVER",
                    "PARTIALLY_COMPLETED",
                    "COMPLETED",
                    "UNFULFILLABLE",
                    "CANCELLED");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final RequisitionMasterDataPort references;

    RequisitionService(
            JdbcTemplate db,
            ObjectMapper json,
            Clock clock,
            RequisitionActorContext actors,
            RequisitionMasterDataPort references) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.references = references;
    }

    Map<String, Object> catalog(Actor actor, String kind, int page, int size) {
        Current current = actors.revalidateLocked(actor);
        if (!List.of("category", "organization", "employee", "location").contains(kind)) {
            throw RequisitionFailure.invalid("REQUISITION_CATALOG_INVALID");
        }
        Long org = current.globalApplicant() ? null : current.organizationId();
        Long employee =
                current.globalApplicant() || current.has(Role.DEPARTMENT_ASSET_MANAGER)
                        ? null
                        : current.employeeId();
        var items =
                references.choices(kind, employee, org, page, size).stream()
                        .map(this::clean)
                        .toList();
        return Map.of(
                "items",
                items,
                "page",
                page,
                "pageSize",
                size,
                "employeeId",
                Long.toString(current.employeeId()),
                "organizationId",
                Long.toString(current.organizationId()),
                "canApplyShared",
                current.globalApplicant() || current.has(Role.DEPARTMENT_ASSET_MANAGER));
    }

    Map<String, Object> list(Actor actor, int page, int size, String status) {
        Current current = actors.revalidateLocked(actor);
        if (status != null && !STATUSES.contains(status)) {
            throw RequisitionFailure.invalid("REQUISITION_FILTER_INVALID");
        }
        var args = new ArrayList<Object>();
        String scope = scope(current, args);
        args.add(status);
        args.add(status);
        String from =
                " FROM fams_requisition r WHERE ("
                        + scope
                        + ") AND (? IS NULL OR request_status=?)";
        long total =
                Objects.requireNonNull(
                        db.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray()));
        args.add(size);
        args.add((page - 1) * size);
        var items =
                db.queryForList(
                        "SELECT r.*"
                                + from
                                + " ORDER BY updated_at DESC,r.id DESC LIMIT ? OFFSET ?",
                        args.toArray());
        return Map.of(
                "items",
                items.stream().map(this::clean).toList(),
                "total",
                total,
                "page",
                page,
                "pageSize",
                size);
    }

    Map<String, Object> get(Actor actor, long id) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        return detail(id);
    }

    Map<String, Object> create(Actor actor, String key, Draft input) {
        Current current = actors.revalidateLocked(actor);
        String hash = digest("create", input);
        var replay = replay(current, "create", key, hash);
        if (replay != null) {
            return replay;
        }
        long id = createDraft(current, input, null, null, null);
        audit(actor, current, "REQUISITION_CREATED", id, null, 0L, Map.of());
        return finish(current, "create", key, hash, id, 201);
    }

    Map<String, Object> revise(Actor actor, long id, long version, String key, Draft input) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        String hash = digest("revise:" + id + ":" + version, input);
        var replay = replay(current, "revise", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        creator(current, header);
        if (!List.of("DRAFT", "REJECTED").contains(text(header, "request_status"))) {
            throw RequisitionFailure.conflict("REQUISITION_APPROVED_SCOPE_IMMUTABLE");
        }
        var resolved = resolve(current, input);
        borrowDate(input, text(header, "parameter_snapshot_json"));
        checkSource(header, input);
        // Only pre-execution rows can be revised;
        // Previous decisions keep their immutable JSON snapshot.
        db.update("DELETE FROM fams_requisition_demand WHERE request_id=?", id);
        insertDemands(id, resolved);
        db.update(
                "UPDATE fams_requisition SET"
                    + " request_version=request_version+1,request_status='DRAFT',version=version+1,request_type=?,expected_return_date=?,updated_at=?"
                    + " WHERE id=?",
                input.requestType(),
                input.expectedReturnDate(),
                now(),
                id);
        audit(actor, current, "REQUISITION_REVISED", id, version, version + 1, Map.of());
        return finish(current, "revise", key, hash, id, 200);
    }

    Map<String, Object> submit(Actor actor, long id, long version, String key) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        String hash = digest("submit:" + id + ":" + version, Map.of());
        var replay = replay(current, "submit", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        creator(current, header);
        if (!"DRAFT".equals(text(header, "request_status"))) {
            throw RequisitionFailure.conflict("REQUISITION_STATE_CONFLICT");
        }
        Draft input = draftFromRows(header, demands(id));
        borrowDate(input, text(header, "parameter_snapshot_json"));
        resolve(current, input);
        checkSource(header, input);
        changeStatus(id, "PENDING_ADMIN_DECISION");
        db.update("UPDATE fams_requisition SET submitted_at=? WHERE id=?", now(), id);
        todo(id, "ADMIN_DECISION", "ASSET_ADMIN", null);
        audit(actor, current, "REQUISITION_SUBMITTED", id, version, version + 1, Map.of());
        return finish(current, "submit", key, hash, id, 200);
    }

    Map<String, Object> decide(
            Actor actor, long id, long version, String key, RequisitionInput.Decision input) {
        Current current = actors.revalidateLocked(actor);
        role(current, Role.ASSET_ADMIN);
        requireVisible(current, id);
        String hash = digest("decide:" + id + ":" + version, input);
        var replay = replay(current, "decide", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        if (!"PENDING_ADMIN_DECISION".equals(text(header, "request_status"))) {
            throw RequisitionFailure.conflict("REQUISITION_ALREADY_DECIDED");
        }
        if (!List.of("APPROVED", "REJECTED").contains(input.result())
                || "REJECTED".equals(input.result()) && blank(input.comment())) {
            throw RequisitionFailure.invalid("REQUISITION_DECISION_INVALID");
        }
        var proposal =
                one(
                        "SELECT * FROM fams_requisition_change_proposal WHERE request_id=? "
                                + "AND request_version=? AND proposal_status='PENDING' FOR UPDATE",
                        id,
                        number(header, "request_version"));
        if (proposal != null) {
            return decideChange(actor, current, header, proposal, version, key, hash, input);
        }
        var demandRows = demands(id);
        if ("APPROVED".equals(input.result())) {
            borrowDate(draftFromRows(header, demandRows), text(header, "parameter_snapshot_json"));
            revalidateTargets(demandRows);
        }
        long audit =
                audit(
                        actor,
                        current,
                        "REQUISITION_" + input.result(),
                        id,
                        version,
                        version + 1,
                        Map.of());
        db.update(
                "INSERT INTO fams_requisition_decision"
                    + " (request_id,request_version,decision_result,approved_scope_json,comment_text,decided_by_account_id,decided_at,audit_event_id)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                id,
                number(header, "request_version"),
                input.result(),
                encode(cleanRows(demandRows)),
                input.comment(),
                current.id(),
                now(),
                audit);
        changeStatus(
                id, "APPROVED".equals(input.result()) ? "WAITING_WAREHOUSE_CLAIM" : "REJECTED");
        closeTodo(id, "ADMIN_DECISION");
        if ("APPROVED".equals(input.result())) {
            todo(id, "WAREHOUSE_CLAIM", "WAREHOUSE_OPERATOR", null);
        }
        return finish(current, "decide", key, hash, id, 200);
    }

    Map<String, Object> proposeChange(Actor actor, long id, long version, String key, Draft input) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        String hash = digest("change:" + id + ":" + version, input);
        var replay = replay(current, "change", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        if (!approved(header)) {
            throw RequisitionFailure.conflict("REQUISITION_NOT_APPROVED");
        }
        if (number(header, "creator_account_id") != current.id()) {
            role(current, Role.WAREHOUSE_OPERATOR);
            owner(current, header, false);
        }
        resolve(current, input);
        validateChange(id, input);
        checkSource(header, input);
        long revision =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT COALESCE(MAX(request_version),-1)+1 "
                                        + "FROM fams_requisition_decision WHERE request_id=?",
                                Long.class,
                                id));
        long audit =
                audit(
                        actor,
                        current,
                        "REQUISITION_CHANGE_PROPOSED",
                        id,
                        version,
                        version + 1,
                        Map.of());
        db.update(
                "INSERT INTO fams_requisition_change_proposal"
                    + " (request_id,request_version,previous_request_version,previous_request_status,proposal_json,proposal_status,proposed_by_account_id,proposed_at,audit_event_id)"
                    + " VALUES (?,?,?,?,?,'PENDING',?,?,?)",
                id,
                revision,
                number(header, "request_version"),
                text(header, "request_status"),
                encode(input),
                current.id(),
                now(),
                audit);
        db.update("UPDATE fams_requisition SET request_version=? WHERE id=?", revision, id);
        changeStatus(id, "PENDING_ADMIN_DECISION");
        todo(id, "ADMIN_DECISION", "ASSET_ADMIN", null);
        return finish(current, "change", key, hash, id, 200);
    }

    private void validateChange(long id, Draft input) {
        var header = header(id);
        if (!input.requestType().equals(text(header, "request_type"))
                || !Objects.equals(input.expectedReturnDate(), date(header, "expected_return_date"))) {
            throw RequisitionFailure.conflict("LOAN_DATE_REQUIRES_EXTENSION");
        }
        var rows = demands(id);
        if (rows.size() != input.demands().size()) {
            throw RequisitionFailure.invalid("REQUISITION_CHANGE_LINE_COUNT");
        }
        for (int i = 0; i < rows.size(); i++) {
            var old = rows.get(i);
            Demand next = input.demands().get(i);
            long immutable =
                    number(old, "pending_handover_quantity")
                            + number(old, "completed_quantity")
                            + number(old, "unfulfillable_quantity")
                            + number(old, "cancelled_quantity");
            Demand prior = inputFromRow(old, (int) number(old, "requested_quantity"));
            boolean sameTarget =
                    next.category().id() == prior.category().id()
                            && next.organization().id() == prior.organization().id()
                            && next.employee().id() == prior.employee().id()
                            && next.location().id() == prior.location().id()
                            && next.custodyType().equals(prior.custodyType());
            if (next.quantity() < immutable || immutable > 0 && !sameTarget) {
                throw RequisitionFailure.conflict("REQUISITION_CHANGE_IMMUTABLE_FULFILLMENT");
            }
        }
    }

    private Map<String, Object> decideChange(
            Actor actor,
            Current current,
            Map<String, Object> header,
            Map<String, Object> proposal,
            long version,
            String key,
            String hash,
            RequisitionInput.Decision input) {
        long id = number(header, "id");
        Draft draft = json.readValue(text(proposal, "proposal_json"), Draft.class);
        if ("APPROVED".equals(input.result())) {
            var resolved = resolve(current, draft);
            validateChange(id, draft);
            checkSource(header, draft);
            var rows = demands(id);
            for (int i = 0; i < rows.size(); i++) {
                var old = rows.get(i);
                var row = resolved.get(i);
                Demand next = draft.demands().get(i);
                long immutable =
                        number(old, "pending_handover_quantity")
                                + number(old, "completed_quantity")
                                + number(old, "unfulfillable_quantity")
                                + number(old, "cancelled_quantity");
                @SuppressWarnings("unchecked")
                var category = (Map<String, Object>) row.get("category");
                db.update(
                        "UPDATE fams_requisition_demand SET"
                            + " category_id=?,category_version_snapshot=?,category_name_snapshot=?,"
                            + "specification=?,preferred_brand=?,preferred_model=?,purpose=?,expected_date=?,custody_type=?,"
                            + "target_organization_id=?,organization_version_snapshot=?,target_employee_id=?,target_department_employee_id=?,"
                            + "employee_version_snapshot=?,target_location_id=?,location_version_snapshot=?,target_snapshot_json=?,requested_quantity=?,pending_allocation_quantity=?,version=version+1"
                            + " WHERE id=?",
                        next.category().id(),
                        next.category().version(),
                        text(category, "name"),
                        next.specification(),
                        next.preferredBrand(),
                        next.preferredModel(),
                        next.purpose(),
                        next.expectedDate(),
                        next.custodyType(),
                        next.organization().id(),
                        next.organization().version(),
                        "PERSONAL".equals(next.custodyType()) ? next.employee().id() : null,
                        "SHARED".equals(next.custodyType()) ? next.employee().id() : null,
                        next.employee().version(),
                        next.location().id(),
                        next.location().version(),
                        encode(
                                Map.of(
                                        "employee",
                                        row.get("employee"),
                                        "organization",
                                        row.get("organization"),
                                        "location",
                                        row.get("location"))),
                        next.quantity(),
                        next.quantity() - immutable,
                        number(old, "id"));
            }
        }
        long audit =
                audit(
                        actor,
                        current,
                        "REQUISITION_CHANGE_" + input.result(),
                        id,
                        version,
                        version + 1,
                        Map.of());
        db.update(
                "INSERT INTO fams_requisition_decision"
                    + " (request_id,request_version,decision_result,approved_scope_json,comment_text,decided_by_account_id,decided_at,audit_event_id)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                id,
                number(header, "request_version"),
                input.result(),
                encode(cleanRows(demands(id))),
                input.comment(),
                current.id(),
                now(),
                audit);
        db.update(
                "UPDATE fams_requisition_change_proposal SET proposal_status=?,decided_at=? WHERE"
                        + " id=?",
                input.result(),
                now(),
                number(proposal, "id"));
        if ("REJECTED".equals(input.result())) {
            db.update(
                    "UPDATE fams_requisition SET request_version=? WHERE id=?",
                    number(proposal, "previous_request_version"),
                    id);
        }
        boolean claimed =
                one(
                                "SELECT id FROM fams_requisition_ownership WHERE request_id=? AND"
                                        + " archived_at IS NULL",
                                id)
                        != null;
        changeStatus(id, claimed ? state(id) : "WAITING_WAREHOUSE_CLAIM");
        closeTodo(id, "ADMIN_DECISION");
        if (!claimed) {
            todo(id, "WAREHOUSE_CLAIM", "WAREHOUSE_OPERATOR", null);
        }
        return finish(current, "decide", key, hash, id, 200);
    }

    Map<String, Object> cancel(
            Actor actor, long id, long version, String key, RequisitionInput.Reason input) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        String hash = digest("cancel:" + id + ":" + version, input);
        var replay = replay(current, "cancel", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        creator(current, header);
        if (!List.of("DRAFT", "REJECTED", "PENDING_ADMIN_DECISION")
                .contains(text(header, "request_status"))) {
            throw RequisitionFailure.conflict("REQUISITION_STATE_CONFLICT");
        }
        if (one(
                        "SELECT id FROM fams_requisition_change_proposal WHERE request_id=? AND"
                                + " proposal_status='PENDING'",
                        id)
                != null) {
            throw RequisitionFailure.conflict("REQUISITION_APPROVED_CHANGE_PENDING");
        }
        db.update(
                "UPDATE fams_requisition_demand SET cancelled_quantity=requested_quantity,"
                        + "pending_allocation_quantity=0,version=version+1 WHERE request_id=?",
                id);
        changeStatus(id, "CANCELLED");
        closeTodo(id, "ADMIN_DECISION");
        audit(
                actor,
                current,
                "REQUISITION_CANCELLED",
                id,
                version,
                version + 1,
                Map.of("reason", input.reason()));
        return finish(current, "cancel", key, hash, id, 200);
    }

    Map<String, Object> claim(Actor actor, long id, long version, String key) {
        Current current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        requireVisible(current, id);
        String hash = digest("claim:" + id + ":" + version, Map.of());
        var replay = replay(current, "claim", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        owner(current, header, true);
        changeStatus(id, state(id));
        closeTodo(id, "WAREHOUSE_CLAIM");
        audit(actor, current, "REQUISITION_CLAIMED", id, version, version + 1, Map.of());
        return finish(current, "claim", key, hash, id, 200);
    }

    Map<String, Object> inventory(Actor actor, long id, long demandId, int page, int size) {
        Current current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        requireVisible(current, id);
        var header = header(id);
        var demand = demand(id, demandId);
        if (!approved(header)) {
            throw RequisitionFailure.conflict("REQUISITION_NOT_APPROVED");
        }
        var values =
                db.queryForList(
                        "SELECT a.id,a.asset_code,a.name,a.brand_id,a.model,a.version,a.category_id"
                                + " FROM fams_asset a WHERE "
                                + inventoryPredicate()
                                + " ORDER BY a.id LIMIT ? OFFSET ?",
                        number(demand, "category_id"),
                        size,
                        (page - 1) * size);
        return Map.of(
                "items",
                cleanRows(values),
                "total",
                available(number(demand, "category_id")),
                "page",
                page,
                "pageSize",
                size);
    }

    Map<String, Object> allocate(
            Actor actor, long id, long demandId, long version, String key, Allocation input) {
        Current current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        requireVisible(current, id);
        String hash = digest("allocate:" + id + ":" + demandId + ":" + version, input);
        var replay = replay(current, "allocate", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        long ownerId = owner(current, header, true);
        var demand = demand(id, demandId);
        revalidateTargets(List.of(demand));
        requireApprovedScope(header, demand);
        Map<String, Object> predecessor = null;
        if (input.predecessorAllocationId() != null) {
            predecessor =
                    one(
                            "SELECT * FROM fams_requisition_allocation WHERE id=? AND request_id=? "
                                    + "AND demand_id=? FOR UPDATE",
                            input.predecessorAllocationId(),
                            id,
                            demandId);
            if (predecessor == null
                    || !"WAITING_DELIVERY".equals(text(predecessor, "allocation_status"))
                    || blank(input.replacementReason())) {
                throw RequisitionFailure.conflict("REQUISITION_REPLACEMENT_INVALID");
            }
        } else if (number(demand, "pending_allocation_quantity") < 1) {
            throw RequisitionFailure.conflict("REQUISITION_QUANTITY_EXCEEDED");
        }
        var assetIds = new java.util.TreeSet<Long>();
        assetIds.add(input.asset().id());
        if (predecessor != null) {
            assetIds.add(number(predecessor, "asset_id"));
        }
        Map<String, Object> asset = null;
        for (long assetId : assetIds) {
            var row = one("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", assetId);
            if (assetId == input.asset().id()) {
                asset = row;
            }
        }
        if (asset == null
                || number(asset, "version") != input.asset().version()
                || number(asset, "category_id") != number(demand, "category_id")
                || !"ACTIVE".equals(text(asset, "card_stage"))
                || !"IN_STOCK".equals(text(asset, "lifecycle_status"))
                || !"NORMAL".equals(text(asset, "exception_status"))) {
            throw RequisitionFailure.conflict("REQUISITION_ASSET_UNAVAILABLE");
        }
        if (one(
                        "SELECT id FROM fams_asset_business_lock WHERE asset_id=? AND released_at"
                                + " IS NULL FOR UPDATE",
                        input.asset().id())
                != null) {
            throw RequisitionFailure.conflict("REQUISITION_ASSET_LOCKED");
        }
        long audit =
                audit(
                        actor,
                        current,
                        "REQUISITION_ALLOCATED",
                        id,
                        version,
                        version + 1,
                        Map.of(
                                "assetId",
                                Long.toString(input.asset().id()),
                                "demandId",
                                Long.toString(demandId)));
        if (predecessor != null) {
            long oldLock = number(predecessor, "business_lock_id");
            var lock = one("SELECT * FROM fams_asset_business_lock WHERE id=? FOR UPDATE", oldLock);
            if (lock == null
                    || lock.get("released_at") != null
                    || !"REQUISITION".equals(text(lock, "business_type"))
                    || number(lock, "business_id") != id
                    || number(lock, "business_item_id") != demandId) {
                throw RequisitionFailure.conflict("REQUISITION_LOCK_CONFLICT");
            }
            db.update(
                    "UPDATE fams_requisition_allocation SET"
                        + " allocation_status='CANCELLED',cancelled_at=?,cancellation_reason=?,version=version+1"
                        + " WHERE id=?",
                    now(),
                    input.replacementReason(),
                    input.predecessorAllocationId());
            db.update(
                    "UPDATE fams_asset_business_lock SET"
                        + " released_at=?,release_reason=?,lock_version=lock_version+1 WHERE id=?",
                    now(),
                    input.replacementReason(),
                    oldLock);
        }
        db.update(
                "INSERT INTO fams_asset_business_lock"
                    + " (asset_id,business_type,business_id,business_item_id,acquired_at) VALUES"
                    + " (?,'REQUISITION',?,?,?)",
                input.asset().id(),
                id,
                demandId,
                now());
        long lockId = lastId();
        db.update(
                "INSERT INTO fams_requisition_allocation"
                    + " (request_id,demand_id,ownership_id,asset_id,"
                    + "asset_version_snapshot,asset_snapshot_json,business_lock_id,allocation_status,predecessor_allocation_id,allocated_at,audit_event_id)"
                    + " VALUES (?,?,?,?,?,?,?,'WAITING_DELIVERY',?,?,?)",
                id,
                demandId,
                ownerId,
                input.asset().id(),
                input.asset().version(),
                encode(assetSnapshot(asset)),
                lockId,
                input.predecessorAllocationId(),
                now(),
                audit);
        if (predecessor == null) {
            db.update(
                    "UPDATE fams_requisition_demand SET"
                        + " pending_allocation_quantity=pending_allocation_quantity-1,pending_handover_quantity=pending_handover_quantity+1,version=version+1"
                        + " WHERE id=?",
                    demandId);
        }
        changeStatus(id, state(id));
        closeTodo(id, "WAREHOUSE_CLAIM");
        todo(id, "WAREHOUSE_HANDOVER", "WAREHOUSE_OPERATOR", current.id());
        return finish(current, "allocate", key, hash, id, 200);
    }

    Map<String, Object> unfulfillable(
            Actor actor,
            long id,
            long demandId,
            long version,
            String key,
            RequisitionInput.Unfulfillable input) {
        Current current = actors.revalidateLocked(actor);
        role(current, Role.WAREHOUSE_OPERATOR);
        requireVisible(current, id);
        String hash = digest("unfulfillable:" + id + ":" + demandId + ":" + version, input);
        var replay = replay(current, "unfulfillable", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        owner(current, header, true);
        var demand = demand(id, demandId);
        requireApprovedScope(header, demand);
        if (input.quantity() > number(demand, "pending_allocation_quantity")
                || !List.of(
                                "STOCK_SHORTAGE",
                                "SPECIFICATION_MISMATCH",
                                "TEMPORARILY_UNAVAILABLE",
                                "OTHER")
                        .contains(input.reason())
                || "OTHER".equals(input.reason()) && blank(input.description())) {
            throw RequisitionFailure.invalid("REQUISITION_UNFULFILLABLE_INVALID");
        }
        long audit =
                audit(
                        actor,
                        current,
                        "REQUISITION_UNFULFILLABLE",
                        id,
                        version,
                        version + 1,
                        Map.of());
        var summary =
                Map.of(
                        "categoryId",
                        Long.toString(number(demand, "category_id")),
                        "availableCount",
                        available(number(demand, "category_id")),
                        "queriedAt",
                        clock.instant().toString(),
                        "criteria",
                        "ACTIVE / IN_STOCK / NORMAL / no active business lock");
        db.update(
                "INSERT INTO fams_requisition_unfulfillable"
                    + " (request_id,demand_id,quantity,reason_code,reason_description,requirement_snapshot_json,inventory_query_summary_json,recorded_by_account_id,recorded_at,audit_event_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                id,
                demandId,
                input.quantity(),
                input.reason(),
                input.description(),
                encode(clean(demand)),
                encode(summary),
                current.id(),
                now(),
                audit);
        db.update(
                "UPDATE fams_requisition_demand SET"
                    + " pending_allocation_quantity=pending_allocation_quantity-?,unfulfillable_quantity=unfulfillable_quantity+?,version=version+1"
                    + " WHERE id=?",
                input.quantity(),
                input.quantity(),
                demandId);
        changeStatus(id, state(id));
        closeTodo(id, "WAREHOUSE_CLAIM");
        return finish(current, "unfulfillable", key, hash, id, 200);
    }

    Map<String, Object> dispute(
            Actor actor, long id, long version, String key, RequisitionInput.Reason input) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        String hash = digest("dispute:" + id + ":" + version, input);
        var replay = replay(current, "dispute", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        checkVersion(header, version);
        creator(current, header);
        if (demands(id).stream().noneMatch(d -> number(d, "unfulfillable_quantity") > 0)) {
            throw RequisitionFailure.conflict("REQUISITION_NO_UNFULFILLED_DEMAND");
        }
        todo(id, "UNFULFILLABLE_REVIEW", "ASSET_ADMIN", null);
        audit(
                actor,
                current,
                "REQUISITION_DISPUTED",
                id,
                version,
                version,
                Map.of("reason", input.reason()));
        return finish(current, "dispute", key, hash, id, 200);
    }

    Map<String, Object> followup(Actor actor, long id, long demandId, long version, String key) {
        Current current = actors.revalidateLocked(actor);
        requireVisible(current, id);
        String hash = digest("followup:" + id + ":" + demandId + ":" + version, Map.of());
        var replay = replay(current, "followup", key, hash);
        if (replay != null) {
            return replay;
        }
        var header = header(id);
        creator(current, header);
        var source = demand(id, demandId);
        if (number(source, "unfulfillable_quantity") < 1) {
            throw RequisitionFailure.conflict("REQUISITION_NO_UNFULFILLED_DEMAND");
        }
        var link =
                one(
                        "SELECT * FROM fams_requisition_followup WHERE source_demand_id=? FOR"
                                + " UPDATE",
                        demandId);
        if (link != null) {
            long latestId = number(link, "latest_request_id");
            var latest = header(latestId);
            boolean failed =
                    List.of("REJECTED", "CANCELLED").contains(text(latest, "request_status"))
                            && demands(latestId).stream()
                                    .allMatch(
                                            d ->
                                                    number(d, "completed_quantity") == 0
                                                            && number(
                                                                            d,
                                                                            "pending_handover_quantity")
                                                                    == 0);
            if (!failed) {
                requireVisible(current, latestId);
                return finish(current, "followup", key, hash, latestId, 200);
            }
        }
        checkVersion(header, version);
        var reasons =
                cleanRows(
                        db.queryForList(
                                "SELECT reason_code,reason_description,quantity,recorded_at FROM"
                                        + " fams_requisition_unfulfillable WHERE request_id=? AND"
                                        + " demand_id=? ORDER BY id",
                                id,
                                demandId));
        Map<String, Object> refreshed = new LinkedHashMap<>(source);
        for (var field :
                Map.of(
                                "category",
                                "category_id",
                                "organization",
                                "target_organization_id",
                                "employee",
                                source.get("target_employee_id") != null
                                        ? "target_employee_id"
                                        : "target_department_employee_id",
                                "location",
                                "target_location_id")
                        .entrySet()) {
            var row = references.current(field.getKey(), number(source, field.getValue()), true);
            if (row.isEmpty() || !"ACTIVE".equals(text(row, "status"))) {
                throw RequisitionFailure.conflict("REQUISITION_REFERENCE_CHANGED");
            }
            refreshed.put(field.getKey() + "_version_snapshot", number(row, "version"));
        }
        Draft copied =
                new Draft(
                        List.of(
                                inputFromRow(
                                        refreshed,
                                        (int) number(source, "unfulfillable_quantity"))),
                        text(header, "request_type"),
                        date(header, "expected_return_date"));
        long next = createDraft(current, copied, id, demandId, encode(reasons));
        db.update(
                "INSERT INTO fams_requisition_followup"
                    + " (source_request_id,source_demand_id,latest_request_id,updated_at) VALUES"
                    + " (?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " latest_request_id=?,version=version+1,updated_at=?",
                id,
                demandId,
                next,
                now(),
                next,
                now());
        audit(
                actor,
                current,
                "REQUISITION_FOLLOWUP_CREATED",
                next,
                null,
                0L,
                Map.of(
                        "sourceRequestId",
                        Long.toString(id),
                        "sourceDemandId",
                        Long.toString(demandId)));
        return finish(current, "followup", key, hash, next, 200);
    }

    private long createDraft(
            Current current, Draft input, Long sourceId, Long sourceDemand, String reasons) {
        var resolved = resolve(current, input);
        var parameters =
                one(
                        "SELECT v.id,v.snapshot_json FROM fams_business_parameter_state s JOIN"
                                + " fams_business_parameter_version v ON v.id=s.current_version_id"
                                + " WHERE s.singleton_id=1");
        if (parameters == null) {
            throw RequisitionFailure.conflict("REQUISITION_PARAMETERS_UNAVAILABLE");
        }
        borrowDate(input, text(parameters, "snapshot_json"));
        db.update(
                "INSERT INTO fams_requisition"
                    + " (request_no,creator_account_id,creator_username_snapshot,request_status,"
                    + "parameter_version_id,parameter_snapshot_json,request_type,expected_return_date,source_request_id,source_demand_id,source_reason_snapshot_json,created_at,updated_at)"
                    + " VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?)",
                "REQ-" + UUID.randomUUID(),
                current.id(),
                current.username(),
                number(parameters, "id"),
                text(parameters, "snapshot_json"),
                input.requestType(),
                input.expectedReturnDate(),
                sourceId,
                sourceDemand,
                reasons,
                now(),
                now());
        long id = lastId();
        insertDemands(id, resolved);
        return id;
    }

    private List<Map<String, Object>> resolve(Current current, Draft input) {
        if (input == null
                || input.demands() == null
                || input.demands().isEmpty()
                || input.demands().size() > 50) {
            throw RequisitionFailure.invalid("REQUISITION_DEMAND_INVALID");
        }
        if (!List.of("REQUISITION", "BORROW").contains(input.requestType())
                || "REQUISITION".equals(input.requestType()) && input.expectedReturnDate() != null
                || "BORROW".equals(input.requestType()) && input.expectedReturnDate() == null) {
            throw RequisitionFailure.invalid("LOAN_RETURN_DATE_INVALID");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Demand d : input.demands()) {
            if (d.quantity() < 1
                    || d.quantity() > 10000
                    || blank(d.specification())
                    || blank(d.purpose())
                    || d.expectedDate() == null
                    || !List.of("PERSONAL", "SHARED").contains(d.custodyType())
                    || "BORROW".equals(input.requestType()) && !"PERSONAL".equals(d.custodyType())) {
                throw RequisitionFailure.invalid("REQUISITION_DEMAND_INVALID");
            }
            var category = reference("category", d.category());
            var organization = reference("organization", d.organization());
            var employee = reference("employee", d.employee());
            var location = reference("location", d.location());
            if (number(employee, "organization_id") != d.organization().id()) {
                throw RequisitionFailure.conflict("REQUISITION_TARGET_ORGANIZATION_MISMATCH");
            }
            if (!current.globalApplicant()
                    && (current.has(Role.DEPARTMENT_ASSET_MANAGER)
                            ? d.organization().id() != current.organizationId()
                            : !"PERSONAL".equals(d.custodyType())
                                    || d.employee().id() != current.employeeId())) {
                throw RequisitionFailure.forbidden();
            }
            result.add(
                    Map.of(
                            "input",
                            d,
                            "category",
                            category,
                            "organization",
                            organization,
                            "employee",
                            employee,
                            "location",
                            location));
        }
        return result;
    }

    private Map<String, Object> reference(String kind, Reference reference) {
        if (reference == null) {
            throw RequisitionFailure.invalid("REQUISITION_REFERENCE_REQUIRED");
        }
        var row = references.current(kind, reference.id(), true);
        if (row.isEmpty()
                || !"ACTIVE".equals(text(row, "status"))
                || number(row, "version") != reference.version()) {
            throw RequisitionFailure.conflict("REQUISITION_REFERENCE_CHANGED");
        }
        return row;
    }

    private void insertDemands(long id, List<Map<String, Object>> resolved) {
        int line = 0;
        for (var row : resolved) {
            Demand d = (Demand) row.get("input");
            @SuppressWarnings("unchecked")
            var category = (Map<String, Object>) row.get("category");
            db.update(
                    "INSERT INTO fams_requisition_demand"
                        + " (request_id,line_no,category_id,category_version_snapshot,"
                        + "category_name_snapshot,specification,preferred_brand,preferred_model,purpose,expected_date,custody_type,"
                        + "target_organization_id,organization_version_snapshot,target_employee_id,target_department_employee_id,"
                        + "employee_version_snapshot,target_location_id,location_version_snapshot,target_snapshot_json,requested_quantity,pending_allocation_quantity)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,
                    ++line,
                    d.category().id(),
                    d.category().version(),
                    text(category, "name"),
                    d.specification(),
                    d.preferredBrand(),
                    d.preferredModel(),
                    d.purpose(),
                    d.expectedDate(),
                    d.custodyType(),
                    d.organization().id(),
                    d.organization().version(),
                    "PERSONAL".equals(d.custodyType()) ? d.employee().id() : null,
                    "SHARED".equals(d.custodyType()) ? d.employee().id() : null,
                    d.employee().version(),
                    d.location().id(),
                    d.location().version(),
                    encode(
                            Map.of(
                                    "organization",
                                    row.get("organization"),
                                    "employee",
                                    row.get("employee"),
                                    "location",
                                    row.get("location"))),
                    d.quantity(),
                    d.quantity());
        }
    }

    private Draft draftFromRows(Map<String, Object> header, List<Map<String, Object>> rows) {
        return new Draft(
                rows.stream()
                        .map(d -> inputFromRow(d, (int) number(d, "requested_quantity")))
                        .toList(),
                text(header, "request_type"),
                date(header, "expected_return_date"));
    }

    private java.time.LocalDate date(Map<String, Object> row, String field) {
        return row.get(field) == null ? null : java.time.LocalDate.parse(row.get(field).toString());
    }

    private void borrowDate(Draft input, String snapshot) {
        if ("BORROW".equals(input.requestType())) {
            var zone = java.time.ZoneId.of(json.readTree(snapshot).get("timezone").asText());
            if (input.expectedReturnDate() == null
                    || !input.expectedReturnDate().isAfter(clock.instant().atZone(zone).toLocalDate())) {
                throw RequisitionFailure.invalid("LOAN_RETURN_DATE_INVALID");
            }
        }
    }

    private Demand inputFromRow(Map<String, Object> d, int quantity) {
        long employee =
                d.get("target_employee_id") != null
                        ? number(d, "target_employee_id")
                        : number(d, "target_department_employee_id");
        return new Demand(
                new Reference(number(d, "category_id"), number(d, "category_version_snapshot")),
                text(d, "specification"),
                text(d, "preferred_brand"),
                text(d, "preferred_model"),
                text(d, "purpose"),
                java.time.LocalDate.parse(d.get("expected_date").toString()),
                text(d, "custody_type"),
                new Reference(
                        number(d, "target_organization_id"),
                        number(d, "organization_version_snapshot")),
                new Reference(employee, number(d, "employee_version_snapshot")),
                new Reference(
                        number(d, "target_location_id"), number(d, "location_version_snapshot")),
                quantity);
    }

    private void revalidateTargets(List<Map<String, Object>> rows) {
        for (var d : rows) {
            var input = inputFromRow(d, (int) number(d, "requested_quantity"));
            reference("category", input.category());
            reference("organization", input.organization());
            var employee = reference("employee", input.employee());
            reference("location", input.location());
            if (number(employee, "organization_id") != input.organization().id()) {
                throw RequisitionFailure.conflict("REQUISITION_TARGET_ORGANIZATION_MISMATCH");
            }
        }
    }

    private void checkSource(Map<String, Object> header, Draft input) {
        if (header.get("source_demand_id") == null) {
            return;
        }
        var source =
                demand(number(header, "source_request_id"), number(header, "source_demand_id"));
        long quantity = input.demands().stream().mapToLong(Demand::quantity).sum();
        if (input.demands().size() != 1 || quantity > number(source, "unfulfillable_quantity")) {
            throw RequisitionFailure.conflict("REQUISITION_SOURCE_QUANTITY_EXCEEDED");
        }
        var link =
                one(
                        "SELECT latest_request_id FROM fams_requisition_followup WHERE"
                                + " source_demand_id=? FOR UPDATE",
                        number(header, "source_demand_id"));
        if (link == null || number(link, "latest_request_id") != number(header, "id")) {
            throw RequisitionFailure.conflict("REQUISITION_FOLLOWUP_SUPERSEDED");
        }
    }

    private void requireApprovedScope(Map<String, Object> header, Map<String, Object> demand) {
        if (!approved(header)) {
            throw RequisitionFailure.conflict("REQUISITION_NOT_APPROVED");
        }
        var decision =
                one(
                        "SELECT id FROM fams_requisition_decision WHERE request_id=? AND"
                                + " request_version=? AND decision_result='APPROVED'",
                        number(header, "id"),
                        number(header, "request_version"));
        if (decision == null || number(demand, "request_id") != number(header, "id")) {
            throw RequisitionFailure.conflict("REQUISITION_APPROVAL_MISSING");
        }
    }

    private boolean approved(Map<String, Object> header) {
        return List.of(
                        "WAITING_WAREHOUSE_CLAIM",
                        "WAREHOUSE_PROCESSING",
                        "WAITING_HANDOVER",
                        "PARTIALLY_COMPLETED",
                        "COMPLETED",
                        "UNFULFILLABLE")
                .contains(text(header, "request_status"));
    }

    private long owner(Current current, Map<String, Object> header, boolean acquire) {
        if (!approved(header)) {
            throw RequisitionFailure.conflict("REQUISITION_NOT_APPROVED");
        }
        long id = number(header, "id");
        var owner =
                one(
                        "SELECT * FROM fams_requisition_ownership WHERE request_id=? AND"
                                + " archived_at IS NULL FOR UPDATE",
                        id);
        if (owner == null && acquire) {
            if (!List.of("WAITING_WAREHOUSE_CLAIM", "WAREHOUSE_PROCESSING", "WAITING_HANDOVER")
                    .contains(text(header, "request_status"))) {
                throw RequisitionFailure.conflict("REQUISITION_ALREADY_FINISHED");
            }
            db.update(
                    "INSERT INTO fams_requisition_ownership"
                            + " (request_id,processor_account_id,acquired_at) VALUES (?,?,?)",
                    id,
                    current.id(),
                    now());
            return lastId();
        }
        if (owner == null || number(owner, "processor_account_id") != current.id()) {
            throw RequisitionFailure.conflict("REQUISITION_OWNED_BY_ANOTHER");
        }
        return number(owner, "id");
    }

    private String scope(Current current, List<Object> args) {
        args.add(current.id());
        String result = "r.creator_account_id=?";
        if (current.has(Role.ASSET_ADMIN)) {
            return result + " OR r.submitted_at IS NOT NULL";
        }
        if (current.has(Role.WAREHOUSE_OPERATOR)) {
            args.add(current.id());
            return result
                    + " OR r.request_status IN"
                    + " ('WAITING_WAREHOUSE_CLAIM','WAREHOUSE_PROCESSING','WAITING_HANDOVER','PARTIALLY_COMPLETED','COMPLETED','UNFULFILLABLE')"
                    + " OR (r.request_status='PENDING_ADMIN_DECISION' AND EXISTS (SELECT 1 FROM"
                    + " fams_requisition_change_proposal p WHERE p.request_id=r.id AND"
                    + " p.proposal_status='PENDING')) OR (r.request_status='CANCELLED' AND EXISTS"
                    + " (SELECT 1 FROM fams_requisition_ownership o WHERE o.request_id=r.id AND"
                    + " o.processor_account_id=?))";
        }
        args.add(current.employeeId());
        args.add(current.employeeId());
        result +=
                " OR EXISTS (SELECT 1 FROM fams_requisition_demand d WHERE d.request_id=r.id "
                        + "AND (d.target_employee_id=? OR d.target_department_employee_id=?))";
        if (current.has(Role.DEPARTMENT_ASSET_MANAGER)) {
            args.add(current.organizationId());
            result +=
                    " OR EXISTS (SELECT 1 FROM fams_requisition_demand d WHERE d.request_id=r.id"
                            + " AND d.target_organization_id=?)";
        }
        return result;
    }

    private void requireVisible(Current current, long id) {
        List<Object> args = new ArrayList<>();
        String scope = scope(current, args);
        args.add(id);
        if (one(
                        "SELECT r.id FROM fams_requisition r WHERE (" + scope + ") AND r.id=?",
                        args.toArray())
                == null) {
            throw RequisitionFailure.unavailable();
        }
    }

    private void creator(Current current, Map<String, Object> header) {
        if (number(header, "creator_account_id") != current.id()) {
            throw RequisitionFailure.forbidden();
        }
    }

    private void role(Current current, Role role) {
        if (!current.has(role)) {
            throw RequisitionFailure.forbidden();
        }
    }

    private void checkVersion(Map<String, Object> header, long version) {
        if (number(header, "version") != version) {
            throw RequisitionFailure.conflict("REQUISITION_VERSION_CONFLICT");
        }
    }

    private Map<String, Object> header(long id) {
        var value = one("SELECT * FROM fams_requisition WHERE id=? FOR UPDATE", id);
        if (value == null) {
            throw RequisitionFailure.unavailable();
        }
        return value;
    }

    private Map<String, Object> demand(long id, long demandId) {
        var value =
                one(
                        "SELECT * FROM fams_requisition_demand WHERE request_id=? AND id=? FOR"
                                + " UPDATE",
                        id,
                        demandId);
        if (value == null) {
            throw RequisitionFailure.unavailable();
        }
        return value;
    }

    private List<Map<String, Object>> demands(long id) {
        return db.queryForList(
                "SELECT * FROM fams_requisition_demand WHERE request_id=? ORDER BY line_no", id);
    }

    private String state(long id) {
        long pending = 0, handover = 0, completed = 0, requested = 0, cancelled = 0;
        for (var d : demands(id)) {
            pending += number(d, "pending_allocation_quantity");
            handover += number(d, "pending_handover_quantity");
            completed += number(d, "completed_quantity");
            requested += number(d, "requested_quantity");
            cancelled += number(d, "cancelled_quantity");
        }
        if (handover > 0) {
            return "WAITING_HANDOVER";
        }
        if (pending > 0) {
            return "WAREHOUSE_PROCESSING";
        }
        if (completed == requested) {
            return "COMPLETED";
        }
        if (completed > 0) {
            return "PARTIALLY_COMPLETED";
        }
        return cancelled == requested ? "CANCELLED" : "UNFULFILLABLE";
    }

    private void changeStatus(long id, String status) {
        db.update(
                "UPDATE fams_requisition SET request_status=?,version=version+1,updated_at=? WHERE"
                        + " id=?",
                status,
                now(),
                id);
        if (List.of("COMPLETED", "PARTIALLY_COMPLETED", "CANCELLED", "UNFULFILLABLE")
                .contains(status)) {
            db.update("UPDATE fams_requisition SET completed_at=? WHERE id=?", now(), id);
            db.update(
                    "UPDATE fams_requisition_ownership SET"
                        + " archived_at=?,archive_reason='EXECUTION_FINISHED' WHERE request_id=?"
                        + " AND archived_at IS NULL",
                    now(),
                    id);
        }
    }

    private Map<String, Object> detail(long id) {
        var value =
                new LinkedHashMap<>(
                        clean(
                                Objects.requireNonNull(
                                        one("SELECT * FROM fams_requisition WHERE id=?", id))));
        value.put("demands", cleanRows(demands(id)));
        value.put(
                "handovers",
                cleanRows(
                        db.queryForList(
                                "SELECT h.* FROM fams_requisition_handover h JOIN"
                                    + " fams_requisition_allocation a ON a.id=h.allocation_id WHERE"
                                    + " a.request_id=? ORDER BY a.id",
                                id)));
        value.put(
                "handoverEvents",
                cleanRows(
                        db.queryForList(
                                "SELECT e.* FROM fams_requisition_handover_event e JOIN"
                                    + " fams_requisition_allocation a ON a.id=e.allocation_id WHERE"
                                    + " a.request_id=? ORDER BY e.id",
                                id)));

        value.put(
                "decisions",
                cleanRows(
                        db.queryForList(
                                "SELECT * FROM fams_requisition_decision WHERE request_id=? ORDER"
                                        + " BY id",
                                id)));
        value.put(
                "allocations",
                cleanRows(
                        db.queryForList(
                                "SELECT a.*,l.lock_version FROM fams_requisition_allocation a JOIN"
                                    + " fams_asset_business_lock l ON l.id=a.business_lock_id WHERE"
                                    + " a.request_id=? ORDER BY a.id",
                                id)));
        value.put(
                "unfulfillable",
                cleanRows(
                        db.queryForList(
                                "SELECT * FROM fams_requisition_unfulfillable WHERE request_id=?"
                                        + " ORDER BY id",
                                id)));
        value.put(
                "ownership",
                cleanRows(
                        db.queryForList(
                                "SELECT * FROM fams_requisition_ownership WHERE request_id=? ORDER"
                                        + " BY id",
                                id)));
        value.put(
                "changes",
                cleanRows(
                        db.queryForList(
                                "SELECT * FROM fams_requisition_change_proposal WHERE request_id=?"
                                        + " ORDER BY id",
                                id)));
        value.put(
                "followups",
                cleanRows(
                        db.queryForList(
                                "SELECT * FROM fams_requisition_followup WHERE source_request_id=?",
                                id)));
        return value;
    }

    private String inventoryPredicate() {
        return "a.category_id=? AND a.card_stage='ACTIVE' AND a.lifecycle_status='IN_STOCK' AND"
                + " a.exception_status='NORMAL' AND NOT EXISTS (SELECT 1 FROM"
                + " fams_asset_business_lock l WHERE l.asset_id=a.id AND l.released_at IS NULL)";
    }

    private long available(long category) {
        return Objects.requireNonNull(
                db.queryForObject(
                        "SELECT COUNT(*) FROM fams_asset a WHERE " + inventoryPredicate(),
                        Long.class,
                        category));
    }

    private Map<String, Object> assetSnapshot(Map<String, Object> row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String field :
                List.of(
                        "id",
                        "asset_code",
                        "name",
                        "category_id",
                        "brand_id",
                        "model",
                        "version")) {
            snapshot.put(field, row.get(field));
        }
        return clean(snapshot);
    }

    private Map<String, Object> replay(Current current, String operation, String key, String hash) {
        var row =
                one(
                        "SELECT * FROM fams_idempotency_record WHERE actor_account_id=? AND"
                                + " operation_id=? AND idempotency_key=? FOR UPDATE",
                        current.id(),
                        "requisition:" + operation,
                        key);
        if (row == null) {
            return null;
        }
        if (!Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT expires_at FROM fams_idempotency_record WHERE id=?",
                                Timestamp.class,
                                number(row, "id")))
                .toInstant()
                .isAfter(clock.instant())) {
            db.update("DELETE FROM fams_idempotency_record WHERE id=?", number(row, "id"));
            return null;
        }
        if (!hash.equals(text(row, "payload_sha256"))) {
            throw RequisitionFailure.conflict("REQUISITION_IDEMPOTENCY_CONFLICT");
        }
        requireVisible(current, number(row, "resource_id"));
        return json.readValue(
                text(row, "response_body_json"),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(
            Current current, String operation, String key, String hash, long id, int status) {
        var result = detail(id);
        db.update(
                "INSERT INTO fams_idempotency_record"
                    + " (actor_account_id,operation_id,idempotency_key,payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                    + " VALUES (?,?,?,?,'SUCCEEDED',?,?,'REQUISITION',?,?,?,?,?)",
                current.id(),
                "requisition:" + operation,
                key,
                hash,
                status,
                encode(result),
                id,
                ((Number) result.get("version")).longValue(),
                now(),
                now(),
                Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private void todo(long id, String type, String pool, Long assignee) {
        db.update(
                "INSERT INTO fams_todo"
                    + " (pool_type,assignee_account_id,business_type,business_id,todo_type,todo_status,opened_at)"
                    + " VALUES (?,?,'REQUISITION',?,?,'OPEN',?) ON DUPLICATE KEY UPDATE id=id",
                pool,
                assignee,
                id,
                type,
                now());
    }

    private void closeTodo(long id, String type) {
        db.update(
                "UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE"
                    + " business_type='REQUISITION' AND business_id=? AND todo_type=? AND closed_at"
                    + " IS NULL",
                now(),
                id,
                type);
    }

    private long audit(
            Actor actor,
            Current current,
            String action,
            long id,
            Long before,
            Long after,
            Map<String, Object> detail) {
        String role = current.roles().stream().map(Enum::name).sorted().findFirst().orElseThrow();
        db.update(
                "INSERT INTO fams_audit_event"
                    + " (request_id,actor_account_id,actor_role,action,resource_type,resource_id,before_version,after_version,occurred_at,detail_json)"
                    + " VALUES (?,?,?,?,'REQUISITION',?,?,?,?,?)",
                actor.requestId().toString(),
                current.id(),
                role,
                action,
                id,
                before,
                after,
                now(),
                encode(detail));
        return lastId();
    }

    private Map<String, Object> one(String sql, Object... args) {
        var values = db.queryForList(sql, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private long lastId() {
        return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private String text(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : row.get(key).toString();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String encode(Object value) {
        return json.writeValueAsString(value);
    }

    private String digest(String prefix, Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (prefix + "\n" + encode(value))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private List<Map<String, Object>> cleanRows(List<Map<String, Object>> values) {
        return values.stream().map(this::clean).toList();
    }

    private Map<String, Object> clean(Map<String, Object> value) {
        var result = new LinkedHashMap<String, Object>();
        value.forEach(
                (key, raw) -> {
                    String[] parts = key.split("_");
                    StringBuilder name = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length; i++) {
                        name.append(Character.toUpperCase(parts[i].charAt(0)))
                                .append(parts[i].substring(1));
                    }
                    String field = name.toString();
                    Object cleaned = raw;
                    if (raw instanceof Timestamp time) {
                        cleaned = time.toInstant().toString();
                    } else if (raw instanceof java.time.LocalDateTime time) {
                        cleaned = time.toInstant(java.time.ZoneOffset.UTC).toString();
                    } else if (raw instanceof java.sql.Date date) {
                        cleaned = date.toLocalDate().toString();
                    } else if (raw instanceof Number
                            && (field.equals("id") || field.endsWith("Id"))) {
                        cleaned = raw.toString();
                    } else if (field.endsWith("Json") && raw != null) {
                        cleaned = json.readValue(raw.toString(), Object.class);
                    }
                    result.put(field, cleaned);
                });
        return result;
    }
}
