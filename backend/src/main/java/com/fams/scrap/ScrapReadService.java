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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final Clock clock;

    public ScrapReadService(JdbcTemplate db, ObjectMapper json, RequisitionActorContext actors,
            AssetScrapPort assets, Clock clock) {
        this.db = db;
        this.json = json;
        this.actors = actors;
        this.assets = assets;
        this.clock = clock;
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
                "page", page, "pageSize", size, "canListOverdueDispositions", canListOverdue(current));
    }

    public Map<String, Object> overdueDispositions(Actor actor, int page, int size) {
        var current = actors.revalidateLocked(actor);
        if (!canListOverdue(current)) {
            throw new AssetHandoverPort.Failure("SCRAP_DISPOSITION_FOLLOW_UP_FORBIDDEN", 403);
        }
        String settings = db.queryForObject("SELECT v.snapshot_json FROM fams_business_parameter_state s"
                + " JOIN fams_business_parameter_version v ON v.id=s.current_version_id"
                + " WHERE s.singleton_id=1 AND v.status='ACTIVE'", String.class);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(json.readTree(settings).path("timezone").asText())));
        String where = " FROM fams_scrap_disposition d JOIN fams_scrap_request r ON r.id=d.request_id"
                + " WHERE d.disposition_status='PENDING_DISPOSITION' AND r.request_status='EXECUTED'"
                + " AND d.due_date<? AND (? OR d.processor_account_id=?)";
        boolean administrator = current.has(Role.ASSET_ADMIN);
        var items = db.queryForList("SELECT r.*" + where + " ORDER BY d.due_date,d.request_id LIMIT ? OFFSET ?",
                today, administrator, current.id(), size, ((long) page - 1) * size).stream()
                .map(row -> view(current, row)).toList();
        return Map.of("items", items, "total", Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + where,
                        Long.class, today, administrator, current.id())),
                "page", page, "pageSize", size, "businessDate", today, "canListOverdueDispositions", true);
    }

    private static boolean canListOverdue(Current current) {
        return current.has(Role.ASSET_ADMIN) || current.has(Role.WAREHOUSE_OPERATOR);
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
        boolean processing = "PROCESSING".equals(row.get("request_status"))
                && Objects.equals(row.get("processor_account_id"), current.id());
        boolean disposing = "employee".equals(kind) && "EXECUTED".equals(row.get("request_status"))
                && !db.queryForList("SELECT request_id FROM fams_scrap_disposition WHERE request_id=?"
                        + " AND processor_account_id=? AND disposition_status='PENDING_DISPOSITION'", id, current.id()).isEmpty();
        if (!current.has(Role.WAREHOUSE_OPERATOR) || !(processing || disposing)) {
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
        result.put("sourceType", row.get("source_type"));
        result.put("sourceBusinessId", row.get("source_business_id") == null ? null : row.get("source_business_id").toString());
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
        boolean lossSource = "LOSS".equals(row.get("source_type"));
        var execution = row.get("execution_snapshot_json") == null
                ? null : json.readTree(row.get("execution_snapshot_json").toString());
        result.put("execution", lossSource && execution != null
                ? Map.of("writtenOffAt", execution.path("writtenOffAt").asText()) : execution);
        var locks = db.queryForList("SELECT lock_version FROM fams_asset_business_lock"
                + " WHERE asset_id=? AND business_type='SCRAP' AND business_id=?"
                + " AND business_item_id=? AND released_at IS NULL", row.get("asset_id"), id, id);
        result.put("lockVersion", locks.isEmpty() ? null : locks.getFirst().get("lock_version"));
        boolean owner = actor.has(Role.WAREHOUSE_OPERATOR)
                && Objects.equals(actor.id(), row.get("processor_account_id"));
        var dispositions = db.queryForList("SELECT disposition_status,DATE_FORMAT(due_date,'%Y-%m-%d') due_date,version,custody_snapshot_json,"
                + "completion_snapshot_json,processor_account_id FROM fams_scrap_disposition WHERE request_id=?", id);
        Map<String, Object> disposition = dispositions.isEmpty() ? null : new LinkedHashMap<>(dispositions.getFirst());
        boolean canDispose = disposition != null && actor.has(Role.WAREHOUSE_OPERATOR)
                && Objects.equals(disposition.get("processor_account_id"), actor.id())
                && "EXECUTED".equals(row.get("request_status")) && ScrapReviewService.physicalSource(row.get("source_type"))
                && "PENDING_DISPOSITION".equals(disposition.get("disposition_status"));
        if (disposition != null) {
            disposition.remove("processor_account_id");
            if (lossSource) {
                disposition.remove("custody_snapshot_json");
            }
            Object completion = disposition.remove("completion_snapshot_json");
            if (completion != null && "DISPOSED".equals(disposition.get("disposition_status"))) {
                var snapshot = json.readTree(completion.toString());
                var facts = snapshot.path("facts");
                disposition.put("completion", Map.of("occurredAt", facts.path("occurredAt").asText(),
                        "method", facts.path("method").asText(), "recipient", facts.path("recipient").asText(),
                        "operatorName", snapshot.path("operatorName").asText()));
            }
        }
        result.put("actions", Map.of("decide", actor.has(Role.ASSET_ADMIN)
                        && "PENDING_ADMIN_DECISION".equals(row.get("request_status")),
                "claim", actor.has(Role.WAREHOUSE_OPERATOR)
                        && "WAITING_WAREHOUSE_CLAIM".equals(row.get("request_status")),
                "execute", owner && "PROCESSING".equals(row.get("request_status")) && ScrapReviewService.physicalSource(row.get("source_type")),
                "executeLoss", owner && "PROCESSING".equals(row.get("request_status")) && lossSource,
                "dispose", canDispose));
        result.put("disposition", disposition);
        return result;
    }
}
