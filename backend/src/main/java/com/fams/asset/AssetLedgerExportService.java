package com.fams.asset;

import com.fams.auth.AssetLedgerExportAccess;
import com.fams.auth.RequisitionActorContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Profile("fams-product")
class AssetLedgerExportService {
    private static final int MAX_ROWS = 10_000;
    // The ledger export projects public asset and custody facts. Row metadata uses the same scope as the ledger.
    private static final List<String> COLUMNS = List.of("id", "asset_code", "name", "category_id", "model",
            "lifecycle_status", "exception_status", "card_stage", "custody_type", "owning_organization_id",
            "using_organization_id", "responsible_employee_id", "department_responsible_employee_id",
            "storage_warehouse_id", "current_location_id", "inbound_business_date", "last_inventory_business_date");

    private final AssetLedgerExportAccess access;
    private final AssetService assets;
    private final AssetMapper mapper;
    private final Clock clock;
    private final ObjectMapper json;

    AssetLedgerExportService(AssetLedgerExportAccess access, AssetService assets, AssetMapper mapper,
            Clock clock, ObjectMapper json) {
        this.access = access;
        this.assets = assets;
        this.mapper = mapper;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String export(RequisitionActorContext.Actor actor, Map<String, String> query) {
        access.require(actor);
        var filters = assets.filters(query);
        if (mapper.count(actor.id(), filters) > MAX_ROWS) {
            throw new AssetOperationException("ASSET_EXPORT_LIMIT", "Narrow the asset filters to 10000 rows.", 422);
        }
        var rows = mapper.list(actor.id(), filters, MAX_ROWS, 0);
        var csv = new StringBuilder("\uFEFF").append(String.join(",", COLUMNS)).append("\r\n");
        for (var row : rows) {
            csv.append(COLUMNS.stream().map(column -> cell(row.get(column)))
                    .collect(Collectors.joining(","))).append("\r\n");
        }
        mapper.audit(actor.id(), "ASSET_LEDGER_EXPORTED", actor.requestId().toString(), clock.instant(),
                json.writeValueAsString(Map.of("filterFields", filters.keySet(), "rows", rows.size())));
        return csv.toString();
    }

    private static String cell(Object value) {
        if (value == null) { return ""; }
        String raw = value.toString();
        String leading = raw.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) {
            raw = "'" + raw;
        }
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
