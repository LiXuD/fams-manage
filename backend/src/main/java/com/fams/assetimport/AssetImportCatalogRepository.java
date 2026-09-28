package com.fams.assetimport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads one preflight snapshot; publication must repeat all mutable checks under its write transaction. */
@Repository
@Profile("fams-product")
public class AssetImportCatalogRepository {
    private static final int IDENTITY_BATCH = 500;
    private final JdbcTemplate db;

    public AssetImportCatalogRepository(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public AssetImportPreflight.Catalog snapshot(Set<String> assetCodes, Set<String> serialNumbers) {
        return new AssetImportPreflight.Catalog(
                references("SELECT id,code,name,status,NULL AS scope_id FROM fams_asset_category"),
                references("SELECT id,code,name,status,NULL AS scope_id FROM fams_organization"),
                references("SELECT id,code,name,status,NULL AS scope_id FROM fams_warehouse"),
                references("SELECT id,code,name,status,warehouse_id AS scope_id FROM fams_location"),
                references("SELECT id,employee_no AS code,name,status,organization_id AS scope_id "
                        + "FROM fams_employee"),
                existing("asset_code", assetCodes), existing("serial_number_normalized", serialNumbers));
    }

    private List<AssetImportPreflight.Reference> references(String sql) {
        return db.query(sql, (result, row) -> new AssetImportPreflight.Reference(
                result.getLong("id"), result.getString("code"), result.getString("name"),
                result.getString("status"), result.getObject("scope_id", Long.class)));
    }

    private Set<String> existing(String column, Set<String> inputs) {
        if (inputs.isEmpty()) { return Set.of(); }
        List<String> values = new ArrayList<>(inputs);
        Set<String> found = new HashSet<>();
        for (int offset = 0; offset < values.size(); offset += IDENTITY_BATCH) {
            List<String> batch = values.subList(offset, Math.min(values.size(), offset + IDENTITY_BATCH));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT " + column + " FROM fams_asset WHERE " + column + " IN (" + placeholders + ")";
            found.addAll(db.queryForList(sql, String.class, batch.toArray()));
        }
        return Set.copyOf(found);
    }
}
