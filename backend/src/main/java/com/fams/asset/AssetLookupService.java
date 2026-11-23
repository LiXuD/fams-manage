package com.fams.asset;

import com.fams.auth.RequisitionActorContext;
import com.fams.auth.Role;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("fams-product")
class AssetLookupService {
    private static final int PAGE_SIZE = 30;
    private final RequisitionActorContext actors;
    private final AssetRepository assetRepository;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    AssetLookupService(RequisitionActorContext actors, AssetRepository assetRepository, JdbcTemplate db,
            ObjectMapper json) {
        this.actors = actors;
        this.assetRepository = assetRepository;
        this.db = db;
        this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Result lookup(RequisitionActorContext.Actor actor, String assetCode, int page) {
        var current = actors.revalidateLocked(actor);
        boolean global = current.globalApplicant();
        var matching = db.query("""
                SELECT a.id,a.asset_code,a.name,a.lifecycle_status,a.exception_status
                FROM fams_asset a
                WHERE a.asset_code=? AND a.card_stage='ACTIVE' AND a.lifecycle_status<>'WAITING_INBOUND'
                    AND (? OR a.responsible_employee_id=? OR a.department_responsible_employee_id=?
                        OR (a.using_organization_id=? AND (a.custody_type='SHARED' OR ?))
                        OR EXISTS (SELECT 1 FROM fams_loan l WHERE l.open_asset_id=a.id
                            AND l.borrower_employee_id=?))
                """, (row, index) -> new Card(row.getString("id"), row.getString("asset_code"), row.getString("name"),
                row.getString("lifecycle_status"), row.getString("exception_status")),
                assetCode, global, current.employeeId(), current.employeeId(), current.organizationId(),
                current.has(Role.DEPARTMENT_ASSET_MANAGER), current.employeeId());
        if (matching.isEmpty()) {
            throw AssetOperationException.notFound();
        }
        var asset = matching.getFirst();
        var id = Long.parseLong(asset.id());
        var rows = assetRepository.timeline(id, PAGE_SIZE + 1, (page - 1) * PAGE_SIZE).stream()
                .map(row -> new Entry(row.get("id").toString(), row.get("event_type").toString(),
                        eventTime(row.get("occurred_at")),
                        global ? row.get("business_type").toString() : null,
                        global ? row.get("business_id").toString() : null,
                        global ? json.readTree(row.get("summary_json").toString()).path("summary").asText() : null))
                .toList();
        boolean more = rows.size() > PAGE_SIZE;
        return new Result(asset, page, more, more ? List.copyOf(rows.subList(0, PAGE_SIZE)) : rows);
    }

    private static Instant eventTime(Object value) {
        return value instanceof java.time.LocalDateTime local
                ? local.toInstant(java.time.ZoneOffset.UTC) : ((java.sql.Timestamp) value).toInstant();
    }

    record Card(String id, String assetCode, String name, String lifecycle, String exception) {}
    record Entry(String id, String eventType, Instant occurredAt, String businessType, String businessId,
                 String summary) {}
    record Result(Card asset, int page, boolean more, List<Entry> timeline) {}
}
