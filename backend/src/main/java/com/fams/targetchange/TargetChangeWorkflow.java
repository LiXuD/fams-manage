package com.fams.targetchange;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A linked single-admin decision changes only the unfinished allocation's approved target. */
@Service
@Profile("fams-product")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TargetChangeWorkflow {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final RequisitionActorContext actors;
    private final Map<String, TargetChangePort> domains;

    public TargetChangeWorkflow(JdbcTemplate db, ObjectMapper json, Clock clock,
            RequisitionActorContext actors, java.util.List<TargetChangePort> domains) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actors = actors;
        this.domains = domains.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                TargetChangePort::businessType, value -> value));
    }

    public Map<String, Object> context(Actor actor, String type, long business, long item) {
        return domain(type).context(actor, business, item);
    }

    public Map<String, Object> list(Actor actor, String type, int page, int size) {
        return list(actor, type, page, size, null);
    }

    public Map<String, Object> list(Actor actor, String type, int page, int size, Long businessId) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN) && !current.has(Role.WAREHOUSE_OPERATOR)) { throw TargetChangeFailure.forbidden(); }
        domain(type);
        String where = " WHERE business_type=?" + (current.has(Role.ASSET_ADMIN) ? "" : " AND creator_account_id=?");
        var args = new java.util.ArrayList<Object>();
        args.add(type);
        if (!current.has(Role.ASSET_ADMIN)) { args.add(current.id()); }
        if (businessId != null) {
            where += " AND business_id=?";
            args.add(businessId);
        }
        Long total = db.queryForObject("SELECT COUNT(*) FROM fams_item_target_change" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var items = db.queryForList("SELECT * FROM fams_item_target_change" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", items.stream().map(this::clean).toList(), "total", Objects.requireNonNull(total), "page", page, "pageSize", size,
                "canApprove", current.has(Role.ASSET_ADMIN), "canExecute", current.has(Role.WAREHOUSE_OPERATOR));
    }

    public Map<String, Object> propose(Actor actor, String type, long version, String key, TargetChangeInput.Proposal input) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.WAREHOUSE_OPERATOR)) { throw TargetChangeFailure.forbidden(); }
        var business = domain(type);
        business.requireProcessor(current.id(), input.businessId());
        // Pure target decoding precedes fingerprinting; current references are checked only after replay.
        var canonical = input.withTarget(business.canonicalTarget(input.target()));
        String operation = "REQUISITION".equals(type) ? "propose" : "propose:" + type;
        var replay = replay(current, operation, key, version, canonical);
        if (replay != null) { return replay; }
        var snapshot = business.context(actor, canonical.businessId(), canonical.itemId());
        if (((Number) snapshot.get("version")).longValue() != version || !same(snapshot, canonical.snapshot())) {
            throw TargetChangeFailure.conflict("TARGET_CHANGE_SCOPE_CHANGED");
        }
        business.validateTarget(canonical.businessId(), canonical.itemId(), canonical.target());
        if (same(snapshot.get("target"), canonical.target())) { throw TargetChangeFailure.invalid("TARGET_CHANGE_UNCHANGED"); }
        if (!snapshot.get("assetCode").equals(canonical.observedAssetCode())) { throw TargetChangeFailure.conflict("HANDOVER_ASSET_CODE_MISMATCH"); }
        if (!db.queryForList("SELECT id FROM fams_item_target_change WHERE business_type=?"
                + " AND business_id=? AND item_id=? AND change_status='PENDING' FOR UPDATE", type, canonical.businessId(), canonical.itemId()).isEmpty()) {
            throw TargetChangeFailure.conflict("TARGET_CHANGE_ALREADY_PENDING");
        }
        db.update("INSERT INTO fams_item_target_change (business_type,business_id,item_id,source_snapshot_json,"
                + "proposed_target_json,reason,observed_asset_code,physical_verification,creator_account_id,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)", type, canonical.businessId(), canonical.itemId(), json.writeValueAsString(snapshot),
                json.writeValueAsString(canonical.target()), canonical.reason(), canonical.observedAssetCode(), canonical.physicalVerification(), current.id(), now());
        long id = Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        db.update("INSERT INTO fams_todo (business_type,business_id,todo_type,pool_type,todo_status,opened_at)"
                + " VALUES ('ITEM_TARGET_CHANGE',?,'TARGET_CHANGE_APPROVAL','ASSET_ADMIN','OPEN',?)", id, now());
        audit(actor, current, id, "TARGET_CHANGE_PROPOSED", 0, 0);
        return finish(current, operation, key, version, canonical, clean(row(id)));
    }

    public Map<String, Object> decide(Actor actor, String type, long id, long version, String key, TargetChangeInput.Decision input) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) { throw TargetChangeFailure.forbidden(); }
        var initial = row(id);
        if (!type.equals(initial.get("business_type"))) { throw TargetChangeFailure.unavailable(); }
        var replay = replay(current, "decide:" + id, key, version, input);
        if (replay != null) { return replay; }
        long request = n(initial, "business_id"), item = n(initial, "item_id");
        var business = domain(type);
        business.lockHeader(request);
        var proposal = rowLocked(id);
        if (n(proposal, "version") != version || !"PENDING".equals(proposal.get("change_status"))) {
            throw TargetChangeFailure.conflict("TARGET_CHANGE_VERSION_CONFLICT");
        }
        if ("APPROVED".equals(input.result())) {
            var snapshot = business.context(actor, request, item);
            if (!same(snapshot, json.readTree(proposal.get("source_snapshot_json").toString()))) {
                throw TargetChangeFailure.conflict("TARGET_CHANGE_SCOPE_CHANGED");
            }
            business.apply(actor, request, item, json.readTree(proposal.get("proposed_target_json").toString()),
                    proposal.get("observed_asset_code").toString(), id);
        }
        db.update("UPDATE fams_item_target_change SET change_status=?,approver_account_id=?,decision_reason=?,"
                + "decided_at=?,version=version+1 WHERE id=?", input.result(), current.id(), input.reason(), now(), id);
        db.update("UPDATE fams_todo SET todo_status='CLOSED',closed_at=? WHERE business_type='ITEM_TARGET_CHANGE'"
                + " AND business_id=? AND closed_at IS NULL", now(), id);
        audit(actor, current, id, "TARGET_CHANGE_" + input.result(), version, version + 1);
        return finish(current, "decide:" + id, key, version, input, clean(row(id)));
    }

    private TargetChangePort domain(String type) {
        var result = domains.get(type);
        if (result == null) { throw TargetChangeFailure.invalid("TARGET_CHANGE_BUSINESS_INVALID"); }
        return result;
    }

    private Map<String, Object> replay(Current current, String operation, String key, long version, Object input) {
        var rows = db.queryForList("SELECT * FROM fams_idempotency_record WHERE actor_account_id=? AND operation_id=?"
                + " AND idempotency_key=? FOR UPDATE", current.id(), "targetChange:" + operation, key);
        if (rows.isEmpty()) { return null; }
        var row = rows.getFirst();
        if (!Objects.requireNonNull(db.queryForObject("SELECT expires_at FROM fams_idempotency_record WHERE id=?", Timestamp.class, n(row, "id"))).toInstant().isAfter(clock.instant())) {
            db.update("DELETE FROM fams_idempotency_record WHERE id=?", n(row, "id"));
            return null;
        }
        if (!fingerprint(version, input).equals(row.get("payload_sha256"))) { throw TargetChangeFailure.conflict("TARGET_CHANGE_IDEMPOTENCY_CONFLICT"); }
        return json.readValue(row.get("response_body_json").toString(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> finish(Current current, String operation, String key, long version, Object input, Map<String, Object> result) {
        db.update("INSERT INTO fams_idempotency_record (actor_account_id,operation_id,idempotency_key,payload_sha256,"
                + "result_status,http_status,response_body_json,resource_type,resource_id,resource_version,created_at,completed_at,expires_at)"
                + " VALUES (?,?,?,?,'SUCCEEDED',200,?,'ITEM_TARGET_CHANGE',?,?,?,?,?)", current.id(), "targetChange:" + operation,
                key, fingerprint(version, input), json.writeValueAsString(result), Long.parseLong(result.get("id").toString()),
                ((Number) result.get("version")).longValue(), now(), now(), Timestamp.from(clock.instant().plusSeconds(86400)));
        return result;
    }

    private String fingerprint(long version, Object input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(Map.of("version", version, "input", input)).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private boolean same(Object left, Object right) { return json.readTree(json.writeValueAsString(left)).equals(json.readTree(json.writeValueAsString(right))); }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private long n(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Map<String, Object> row(long id) { return select(id, false); }
    private Map<String, Object> rowLocked(long id) { return select(id, true); }
    private Map<String, Object> select(long id, boolean lock) {
        var rows = db.queryForList("SELECT * FROM fams_item_target_change WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) { throw TargetChangeFailure.unavailable(); }
        return rows.getFirst();
    }
    private Map<String, Object> clean(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : row.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value != null && key.endsWith("_json")) { value = json.readTree(value.toString()); }
            else if (value instanceof Timestamp timestamp) { value = timestamp.toInstant().toString(); }
            else if (value instanceof java.time.LocalDateTime time) { value = time.toInstant(java.time.ZoneOffset.UTC).toString(); }
            else if (value != null && (key.equals("id") || key.endsWith("_id"))) { value = value.toString(); }
            result.put(key, value);
        }
        var display = domain(row.get("business_type").toString()).targetDisplay(
                json.readTree(row.get("proposed_target_json").toString()));
        if (!display.isEmpty()) {
            result.put("targetDisplay", display);
        }
        return result;
    }
    private void audit(Actor actor, Current current, long id, String action, long before, long after) {
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,resource_id,"
                + "before_version,after_version,occurred_at,detail_json) VALUES (?,?,?,?,'ITEM_TARGET_CHANGE',?,?,?,?,?)",
                actor.requestId().toString(), current.id(), "TARGET_CHANGE_PROPOSED".equals(action) ? "WAREHOUSE_OPERATOR" : "ASSET_ADMIN",
                action, id, before, after, now(), "{}");
    }
}
