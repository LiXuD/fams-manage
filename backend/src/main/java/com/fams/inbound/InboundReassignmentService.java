package com.fams.inbound;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fams.auth.InboundActorContext;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Transfers ownership only; approval, acceptance facts, deadlines and asset locks remain intact. */
@Profile("fams-product")
@Service
@Transactional
class InboundReassignmentService {
    private final JdbcTemplate db;
    private final RequisitionActorContext actors;
    private final InboundService inbound;
    private final ObjectMapper json;
    private final Clock clock;

    InboundReassignmentService(JdbcTemplate db, RequisitionActorContext actors,
            InboundService inbound, ObjectMapper json, Clock authClock) {
        this.db = db;
        this.actors = actors;
        this.inbound = inbound;
        this.json = json;
        this.clock = authClock;
    }

    Map<String, Object> inspect(Actor actor, long id) {
        admin(actor);
        var row = processing(id);
        var owner = owner(id);
        var operators = db.queryForList("SELECT a.id,e.name FROM fams_system_account a "
                + "JOIN fams_employee e ON e.id=a.employee_id JOIN fams_organization o "
                + "ON o.id=e.organization_id JOIN fams_account_role r ON r.account_id=a.id "
                + "WHERE a.status='ENABLED' AND a.initial_password_required=FALSE "
                + "AND e.status='ACTIVE' AND o.status='ACTIVE' AND r.role_code='WAREHOUSE_OPERATOR' "
                + "AND a.id<>? ORDER BY a.id", owner.get("processor_account_id"));
        return Map.of("id", Long.toString(id), "version", row.get("version"),
                "ownershipVersion", owner.get("ownership_version"),
                "processorAccountId", owner.get("processor_account_id").toString(),
                "items", checks(id), "operators", operators.stream().map(o -> Map.of(
                        "id", o.get("id").toString(), "name", o.get("name"))).toList());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> reassign(Actor actor, long id, long version, String key,
            InboundReassignmentController.Reassignment input) {
        var current = admin(actor);
        String digest = digest(id, version, input);
        var prior = db.queryForList("SELECT payload_sha256,response_body_json FROM fams_idempotency_record "
                + "WHERE actor_account_id=? AND operation_id='reassignInboundReceipt' "
                + "AND idempotency_key=? FOR UPDATE", actor.id(), key);
        if (!prior.isEmpty()) {
            if (!digest.equals(prior.getFirst().get("payload_sha256"))) {
                throw InboundFailure.conflict("INBOUND_IDEMPOTENCY_CONFLICT"); }
            return json.readValue(prior.getFirst().get("response_body_json").toString(), Map.class);
        }
        // Account locks precede receipt/ownership/item/asset locks, matching execution.
        var target = db.queryForList("SELECT id,employee_id,status,initial_password_required "
                + "FROM fams_system_account WHERE id=? FOR UPDATE", input.processorAccountId());
        if (target.isEmpty() || !"ENABLED".equals(target.getFirst().get("status"))
                || Boolean.TRUE.equals(target.getFirst().get("initial_password_required"))) {
            throw InboundFailure.conflict("INBOUND_PROCESSOR_INVALID"); }
        var eligible = db.queryForList("SELECT e.id FROM fams_employee e JOIN fams_organization o "
                + "ON o.id=e.organization_id JOIN fams_account_role r ON r.account_id=? "
                + "WHERE e.id=? AND e.status='ACTIVE' AND o.status='ACTIVE' "
                + "AND r.role_code='WAREHOUSE_OPERATOR' FOR UPDATE",
                input.processorAccountId(), target.getFirst().get("employee_id"));
        if (eligible.isEmpty()) { throw InboundFailure.conflict("INBOUND_PROCESSOR_INVALID"); }
        var row = processing(id);
        var owner = owner(id);
        if (number(row, "version") != version) { throw InboundFailure.conflict("INBOUND_VERSION_CONFLICT"); }
        if (number(owner, "ownership_version") != input.ownershipVersion()
                || number(owner, "processor_account_id") == input.processorAccountId()) {
            throw InboundFailure.conflict("INBOUND_OWNERSHIP_CHANGED"); }
        if (!Boolean.TRUE.equals(input.unsynchronizedResultsChecked())) {
            throw InboundFailure.invalid("INBOUND_REASSIGNMENT_CHECK_REQUIRED"); }
        var checks = checks(id);
        if (checks.size() != input.items().size()
                || input.items().stream().map(InboundReassignmentController.Item::id).distinct().count()
                    != checks.size()) { throw InboundFailure.conflict("INBOUND_REASSIGNMENT_SNAPSHOT_CHANGED"); }
        for (var item : checks) {
            var submitted = input.items().stream().filter(i -> Long.toString(i.id()).equals(item.get("id")))
                    .findFirst().orElseThrow(() -> InboundFailure.conflict("INBOUND_REASSIGNMENT_SNAPSHOT_CHANGED"));
            if (submitted.version() != number(item, "version") || !submitted.status().equals(item.get("status"))
                    || submitted.assetVersion() != number(item, "assetVersion")
                    || submitted.lockVersion() != number(item, "lockVersion")) {
                throw InboundFailure.conflict("INBOUND_REASSIGNMENT_SNAPSHOT_CHANGED"); }
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        db.update("UPDATE fams_inbound_processing_ownership SET processor_account_id=?,"
                + "processor_username_snapshot=?,ownership_version=ownership_version+1 WHERE id=?",
                input.processorAccountId(), targetUsername(input.processorAccountId()), owner.get("id"));
        db.update("UPDATE fams_inbound_receipt SET version=version+1,updated_at=?,updated_by=? WHERE id=?",
                now, actor.id(), id);
        db.update("UPDATE fams_todo SET assignee_account_id=? WHERE business_type='INBOUND' "
                + "AND business_id=? AND pool_type='WAREHOUSE_OPERATOR' AND closed_at IS NULL",
                input.processorAccountId(), id);
        var details = Map.of("previousProcessorAccountId", owner.get("processor_account_id").toString(),
                "processorAccountId", Long.toString(input.processorAccountId()),
                "ownershipVersionBefore", input.ownershipVersion(),
                "ownershipVersionAfter", input.ownershipVersion() + 1, "reason", input.reason(),
                "evidence", input.evidence(), "unsynchronizedResultsChecked", true, "items", checks);
        db.update("INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,"
                + "resource_type,resource_id,before_version,after_version,occurred_at,detail_json) "
                + "VALUES (?,?,'ASSET_ADMIN','INBOUND_CONTROLLED_REASSIGNMENT','INBOUND',?,?,?,?,?)",
                actor.requestId().toString(), actor.id(), id, version, version + 1, now,
                json.writeValueAsString(details));
        var result = inbound.get(new InboundActorContext.Actor(actor.id(), current.username(),
                "ASSET_ADMIN", actor.requestId().toString()), id);
        db.update("INSERT INTO fams_idempotency_record (actor_account_id,operation_id,idempotency_key,"
                + "payload_sha256,result_status,http_status,response_body_json,resource_type,resource_id,"
                + "resource_version,created_at,completed_at,expires_at) "
                + "VALUES (?,'reassignInboundReceipt',?,?,'SUCCEEDED',200,?,'INBOUND',?,?,?,?,?)",
                actor.id(), key, digest, json.writeValueAsString(result), id, version + 1,
                now, now, now.plus(24, ChronoUnit.HOURS));
        return result;
    }

    private RequisitionActorContext.Current admin(Actor actor) {
        var current = actors.revalidateLocked(actor);
        if (!current.has(Role.ASSET_ADMIN)) { throw new InboundFailure("INBOUND_FORBIDDEN", "Asset administrator required", 403); }
        return current;
    }

    private Map<String, Object> processing(long id) {
        var rows = db.queryForList("SELECT * FROM fams_inbound_receipt WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) { throw InboundFailure.missing(); }
        if (!"WAREHOUSE_PROCESSING".equals(rows.getFirst().get("receipt_status"))) {
            throw InboundFailure.conflict("INBOUND_NOT_PROCESSING"); }
        return rows.getFirst();
    }

    private Map<String, Object> owner(long id) {
        var rows = db.queryForList("SELECT * FROM fams_inbound_processing_ownership "
                + "WHERE receipt_id=? AND archived_at IS NULL FOR UPDATE", id);
        if (rows.size() != 1) { throw InboundFailure.conflict("INBOUND_OWNERSHIP_CHANGED"); }
        return rows.getFirst();
    }

    private List<Map<String, Object>> checks(long id) {
        var rows = db.queryForList("SELECT * FROM fams_inbound_receipt_item WHERE receipt_id=? ORDER BY id FOR UPDATE", id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            var asset = db.queryForMap("SELECT version FROM fams_asset WHERE id=? FOR UPDATE", row.get("asset_id"));
            var locks = db.queryForList("SELECT lock_version FROM fams_asset_business_lock WHERE "
                    + "business_type='INBOUND' AND business_id=? AND business_item_id=? "
                    + "AND asset_id=? AND released_at IS NULL FOR UPDATE", id, row.get("id"), row.get("asset_id"));
            boolean pending = "PENDING".equals(row.get("execution_status"));
            if ((pending && locks.size() != 1) || (!pending && !locks.isEmpty())) {
                throw InboundFailure.conflict("INBOUND_ASSET_LOCK_CONFLICT"); }
            result.add(Map.of("id", row.get("id").toString(), "version", row.get("version"),
                    "status", row.get("execution_status"), "assetVersion", asset.get("version"),
                    "lockVersion", pending ? locks.getFirst().get("lock_version") : -1L,
                    "assetCode", row.get("asset_code_snapshot"),
                    "warehouseId", row.get("target_warehouse_id").toString(),
                    "locationId", row.get("target_location_id").toString()));
        }
        return result;
    }

    private String targetUsername(long id) {
        return db.queryForObject("SELECT username FROM fams_system_account WHERE id=?", String.class, id);
    }

    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    private String digest(long id, long version, Object input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (id + ":" + version + ":" + json.writeValueAsString(input)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
