package com.fams.asset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The asset domain owns formal custody writes; the caller owns approval and business locks. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class AssetHandoverPort {
    public static final class Failure extends RuntimeException {
        private final int status;

        public Failure(String code, int status) {
            super(code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final JdbcTemplate db;
    private final AssetMapper mapper;
    private final AssetSecretCodec codec;
    private final ObjectMapper json;

    AssetHandoverPort(
            JdbcTemplate db, AssetMapper mapper, AssetSecretCodec codec, ObjectMapper json) {
        this.db = db;
        this.mapper = mapper;
        this.codec = codec;
        this.json = json;
    }

    public Map<String, Object> stock(long id, long version) {
        var rows = db.queryForList("SELECT * FROM fams_asset WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new Failure("ASSET_NOT_FOUND", 404);
        }
        var row = rows.getFirst();
        if (((Number) row.get("version")).longValue() != version
                || !"ACTIVE".equals(row.get("card_stage"))
                || !"IN_STOCK".equals(row.get("lifecycle_status"))
                || !"NORMAL".equals(row.get("exception_status"))
                || !"STORAGE".equals(row.get("custody_type"))) {
            throw new Failure("ASSET_HANDOVER_CHANGED", 409);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key :
                new String[] {
                    "id",
                    "version",
                    "asset_code",
                    "name",
                    "model",
                    "custody_type",
                    "storage_warehouse_id",
                    "current_location_id"
                }) {
            result.put(key, row.get(key));
        }
        return result;
    }

    public String observedCode(long assetId, String observed) {
        var row = db.queryForList("SELECT asset_code FROM fams_asset WHERE id=?", assetId);
        if (row.isEmpty()) {
            throw new Failure("ASSET_NOT_FOUND", 404);
        }
        String code = row.getFirst().get("asset_code").toString();
        if (code.equals(observed)) {
            return code;
        }
        // Scan results may carry the current opaque tag; possession never grants receipt rights.
        Long tagged = mapper.assetIdByTokenHash(codec.hash(observed));
        if (tagged == null || tagged != assetId) {
            throw new Failure("ASSET_PHYSICAL_MISMATCH", 409);
        }
        return code;
    }

    public void receive(
            long id,
            long expectedVersion,
            String custody,
            long organization,
            long employee,
            long location,
            long actor,
            long request,
            Instant time) {
        assign(id, expectedVersion, custody, organization, employee, location, actor, request, time,
                "IN_USE", "REQUISITION_RECEIVED");
    }

    public void borrow(
            long id, long expectedVersion, long organization, long employee, long location,
            long actor, long request, Instant time) {
        assign(id, expectedVersion, "PERSONAL", organization, employee, location, actor, request,
                time, "BORROWED", "BORROW_RECEIVED");
    }

    private void assign(
            long id, long expectedVersion, String custody, long organization, long employee,
            long location, long actor, long request, Instant time, String lifecycle, String event) {
        stock(id, expectedVersion);
        if (!"PERSONAL".equals(custody) && !"SHARED".equals(custody)) {
            throw new IllegalArgumentException("ASSET_CUSTODY_INVALID");
        }
        if (db.update(
                        "UPDATE fams_asset SET lifecycle_status=?,custody_type=?,"
                            + "storage_warehouse_id=NULL,current_location_id=?,using_organization_id=?,"
                            + "responsible_employee_id=?,department_responsible_employee_id=?,version=version+1,updated_by=?,updated_at=?"
                            + " WHERE id=? AND version=?",
                        lifecycle,
                        custody,
                        location,
                        organization,
                        "PERSONAL".equals(custody) ? employee : null,
                        "SHARED".equals(custody) ? employee : null,
                        actor,
                        Timestamp.from(time),
                        id,
                        expectedVersion)
                != 1) {
            throw new Failure("ASSET_HANDOVER_CHANGED", 409);
        }
        mapper.event(
                id,
                event,
                time,
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                Long.toString(request),
                                "custodyType",
                                custody,
                                "receiverEmployeeId",
                                Long.toString(employee))),
                actor);
    }
}
