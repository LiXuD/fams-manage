package com.fams.scrap;

import com.fams.asset.AssetHandoverPort;
import com.fams.asset.AssetScrapPort;
import com.fams.auth.RequisitionActorContext;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@Profile("fams-product")
@Transactional
public class ScrapReadService {
    private static final String SCOPE = " FROM fams_scrap_request r WHERE (? OR r.creator_account_id=?"
            + " OR (? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.responsible_employee_id')) AS UNSIGNED)=?)"
            + " OR (? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.source_snapshot_json,'$.using_organization_id')) AS UNSIGNED)=?)"
            + " OR (? AND r.decision_snapshot_json IS NOT NULL"
            + " AND JSON_UNQUOTE(JSON_EXTRACT(r.decision_snapshot_json,'$.decision.result'))='APPROVE'))";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequisitionActorContext actors;
    private final AssetScrapPort assets;

    public ScrapReadService(JdbcTemplate db, ObjectMapper json, RequisitionActorContext actors,
            AssetScrapPort assets) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
    }

    public Map<String, Object> eligible(Actor actor, int page, int size) {
        return assets.eligible(actors.revalidateLocked(actor), page, size);
    }

    public Map<String, Object> list(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        Object[] scope = scope(current);
        var args = new ArrayList<Object>(Arrays.asList(scope));
        args.add(size);
        args.add(((long) page - 1) * size);
        return Map.of("items", db.queryForList("SELECT r.*" + SCOPE
                        + " ORDER BY r.id DESC LIMIT ? OFFSET ?", args.toArray()).stream()
                        .map(row -> view(current, row)).toList(),
                "total", Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + SCOPE, Long.class, scope)),
                "page", page, "pageSize", size);
    }

    public Map<String, Object> get(Actor actor, long id) {
        var current = actors.revalidateLocked(actor);
        var row = visible(current, id);
        var result = view(current, row);
        result.put("history", db.queryForList("SELECT id,action,actor_username,occurred_at"
                + " FROM fams_scrap_event WHERE request_id=? ORDER BY id DESC LIMIT 100", id));
        return result;
    }

    public Map<String, Object> catalog(Actor actor, long id, String kind, int page, int size) {
        var current = actors.revalidateLocked(actor);
        var row = visible(current, id);
        if (!"PROCESSING".equals(row.get("request_status")) || !current.has(Role.WAREHOUSE_OPERATOR)
                || !Objects.equals(row.get("processor_account_id"), current.id())) {
            throw new AssetHandoverPort.Failure("SCRAP_PROCESSOR_REQUIRED", 403);
        }
        String from = switch (kind) {
            case "location" -> " FROM fams_location a LEFT JOIN fams_warehouse p ON p.id=a.warehouse_id"
                    + " WHERE a.status='ACTIVE' AND (a.warehouse_id IS NULL OR p.status='ACTIVE')";
            case "employee" -> " FROM fams_employee a JOIN fams_organization p ON p.id=a.organization_id"
                    + " WHERE a.status='ACTIVE' AND p.status='ACTIVE'";
            default -> throw new AssetHandoverPort.Failure("SCRAP_CATALOG_INVALID", 400);
        };
        var rows = db.queryForList("SELECT a.id,a.name,a.version" + from + " ORDER BY a.id LIMIT ? OFFSET ?",
                size, ((long) page - 1) * size).stream().map(item -> Map.of(
                        "id", item.get("id").toString(), "name", item.get("name"), "version", item.get("version"))).toList();
        return Map.of("items", rows, "total", Objects.requireNonNull(db.queryForObject(
                "SELECT COUNT(*)" + from, Long.class)), "page", page, "pageSize", size);
    }

    private Map<String, Object> visible(Current current, long id) {
        var args = new ArrayList<Object>(Arrays.asList(scope(current)));
        args.add(id);
        var rows = db.queryForList("SELECT r.*" + SCOPE + " AND r.id=?", args.toArray());
        if (rows.isEmpty()) {
            throw new AssetHandoverPort.Failure("SCRAP_NOT_FOUND", 404);
        }
        return rows.getFirst();
    }

    private Object[] scope(Current actor) {
        return new Object[] {actor.has(Role.ASSET_ADMIN) || actor.has(Role.AUDIT_READ_ONLY), actor.id(),
                actor.has(Role.EMPLOYEE), actor.employeeId(),
                actor.has(Role.DEPARTMENT_ASSET_MANAGER), actor.organizationId(),
                actor.has(Role.WAREHOUSE_OPERATOR)};
    }

    private Map<String, Object> view(Current actor, Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        long id = ((Number) row.get("id")).longValue();
        result.put("id", Long.toString(id));
        result.put("assetId", row.get("asset_id").toString());
        result.put("version", row.get("version"));
        result.put("assetVersion", row.get("asset_version"));
        result.put("ownershipVersion", row.get("ownership_version"));
        result.put("processorAccountId", row.get("processor_account_id") == null
                ? null : row.get("processor_account_id").toString());
        result.put("status", row.get("request_status"));
        result.put("reason", row.get("reason"));
        result.put("observedCondition", row.get("observed_condition"));
        result.put("proposedDisposition", row.get("proposed_disposition"));
        var source = json.readTree(row.get("source_snapshot_json").toString());
        result.put("source", Map.of("assetCode", source.path("asset_code").asText(),
                "name", source.path("name").asText(), "serialNumber", source.path("serial_number").asText("")));
        var decision = row.get("decision_snapshot_json") == null
                ? null : json.readTree(row.get("decision_snapshot_json").toString());
        if (decision instanceof tools.jackson.databind.node.ObjectNode object) {
            object.remove("attachmentEvidence");
        }
        result.put("decision", decision);
        result.put("execution", row.get("execution_snapshot_json") == null
                ? null : json.readTree(row.get("execution_snapshot_json").toString()));
        var locks = db.queryForList("SELECT lock_version FROM fams_asset_business_lock"
                + " WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND released_at IS NULL", row.get("asset_id"), id, id);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        boolean owner = actor.has(Role.WAREHOUSE_OPERATOR)
                && Objects.equals(actor.id(), row.get("processor_account_id"));
        result.put("actions", Map.of("decide", actor.has(Role.ASSET_ADMIN)
                        && "PENDING_ADMIN_DECISION".equals(row.get("request_status")),
                "claim", actor.has(Role.WAREHOUSE_OPERATOR)
                        && "WAITING_WAREHOUSE_CLAIM".equals(row.get("request_status")),
                "execute", owner && "PROCESSING".equals(row.get("request_status"))));
        var disposition = db.queryForList("SELECT disposition_status,due_date,version,custody_snapshot_json,"
                + "completion_snapshot_json FROM fams_scrap_disposition WHERE request_id=?", id);
        result.put("disposition", disposition.isEmpty() ? null : disposition.getFirst());
        return result;
    }
}
