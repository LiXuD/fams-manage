package com.fams.report;

import com.fams.auth.AssetActorContext;
import com.fams.assetimport.AssetImportWorkbook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("fams-product")
class AssetCustodyReportService {
    private static final int EXPORT_LIMIT = 10_000;
    private static final String EFFECTIVE = "a.card_stage = 'ACTIVE' AND a.lifecycle_status NOT IN ('WAITING_INBOUND','SCRAPPED')";
    private static final String QUERY = """
            SELECT a.id, a.asset_code, a.name, a.lifecycle_status, a.exception_status, a.custody_type,
                a.last_inventory_business_date, u.name AS organization_name, w.name AS warehouse_name,
                l.name AS location_name, e.name AS responsible_name
            FROM fams_asset a
            LEFT JOIN fams_organization u ON u.id = a.using_organization_id
            LEFT JOIN fams_warehouse w ON w.id = a.storage_warehouse_id
            LEFT JOIN fams_location l ON l.id = a.current_location_id
            LEFT JOIN fams_employee e ON e.id = COALESCE(a.responsible_employee_id, a.department_responsible_employee_id)
            WHERE
            """ + EFFECTIVE + """
                AND (? IS NULL OR a.using_organization_id = ? OR (? = 0 AND a.using_organization_id IS NULL))
                AND (? IS NULL OR a.category_id = ? OR (? = 0 AND a.category_id IS NULL))
                AND (? IS NULL OR a.responsible_employee_id = ? OR a.department_responsible_employee_id = ?)
                AND (? IS NULL OR a.storage_warehouse_id = ?)
                AND (? IS NULL OR a.current_location_id = ?)
                AND (? IS NULL OR a.custody_type = ?)
                AND (? IS NULL OR a.last_inventory_business_date IS NULL OR a.last_inventory_business_date < ?)
                AND (? IS NULL OR a.id < ?)
            ORDER BY a.id DESC LIMIT ?
            """;
    private final JdbcTemplate db;
    private final Clock clock;

    AssetCustodyReportService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Distribution distribution() {
        return new Distribution(groups("using_organization_id", "fams_organization"),
                groups("category_id", "fams_asset_category"));
    }

    private List<Group> groups(String field, String catalog) {
        return db.query("SELECT COALESCE(g.id, 0) AS group_id,g.name,COUNT(*) AS total"
                        + " FROM fams_asset a LEFT JOIN " + catalog + " g ON g.id=a." + field
                        + " WHERE " + EFFECTIVE + " GROUP BY g.id,g.name ORDER BY total DESC,group_id",
                (row, index) -> new Group(row.getString("group_id"), row.getString("name"), row.getLong("total")));
    }

    public Page list(Filter filter, Long before, int pageSize) {
        var rows = query(filter, before, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new Page(rows, null);
        }
        return new Page(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).assetId());
    }

    @Transactional
    public String export(AssetActorContext.Actor actor, Filter filter) {
        var rows = query(filter, null, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new ExportLimit();
        }
        db.update("""
                INSERT INTO fams_audit_event (request_id,actor_account_id,actor_role,action,resource_type,
                    resource_id,occurred_at,detail_json)
                VALUES (?,?,?,'OPERATIONS_REPORT_EXPORTED','ASSET_CUSTODY_REPORT',0,?,
                    JSON_OBJECT('rows',?,'organizationId',?,'responsibleId',?,'warehouseId',?,
                        'locationId',?,'custody',?,'inventoryBefore',?,'categoryId',?))
                """, actor.requestId(), actor.id(), actor.role(), Timestamp.from(clock.instant()), rows.size(),
                filter.organizationId(), filter.responsibleId(), filter.warehouseId(), filter.locationId(),
                filter.custody(), filter.inventoryBefore(), filter.categoryId());
        return "\uFEFFassetId,assetCode,name,lifecycle,exception,custody,organization,responsible,warehouse,location,lastInventory\r\n"
                + rows.stream().map(AssetCustodyReportService::csvRow).collect(Collectors.joining("\r\n"));
    }

    private List<Row> query(Filter filter, Long before, int limit) {
        return db.query(QUERY, (row, index) -> new Row(row.getString("id"), row.getString("asset_code"),
                row.getString("name"), row.getString("lifecycle_status"), row.getString("exception_status"),
                row.getString("custody_type"), row.getString("organization_name"), row.getString("responsible_name"),
                row.getString("warehouse_name"), row.getString("location_name"),
                row.getObject("last_inventory_business_date", LocalDate.class)),
                filter.organizationId(), filter.organizationId(), filter.organizationId(),
                filter.categoryId(), filter.categoryId(), filter.categoryId(),
                filter.responsibleId(), filter.responsibleId(),
                filter.responsibleId(), filter.warehouseId(), filter.warehouseId(), filter.locationId(),
                filter.locationId(), filter.custody(), filter.custody(), filter.inventoryBefore(),
                filter.inventoryBefore(), before, before, limit);
    }

    private static String csvRow(Row row) {
        return String.join(",", csv(row.assetId()), csv(row.assetCode()), csv(row.name()), csv(row.lifecycle()),
                csv(row.exception()), csv(row.custody()), csv(row.organization()), csv(row.responsible()),
                csv(row.warehouse()), csv(row.location()), csv(row.lastInventory() == null
                        ? null : row.lastInventory().toString()));
    }

    private static String csv(String value) {
        return "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    record Filter(Long organizationId, Long responsibleId, Long warehouseId, Long locationId,
                  String custody, LocalDate inventoryBefore, Long categoryId) {}
    record Distribution(List<Group> organizations, List<Group> categories) {}
    record Group(String id, String name, long count) {}
    record Page(List<Row> items, String nextBefore) {}
    record Row(String assetId, String assetCode, String name, String lifecycle, String exception, String custody,
               String organization, String responsible, String warehouse, String location, LocalDate lastInventory) {}

    static final class ExportLimit extends RuntimeException {}
}
