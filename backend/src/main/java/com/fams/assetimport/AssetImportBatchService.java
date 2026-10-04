package com.fams.assetimport;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns the immutable source and replaceable preflight snapshot; confirmation is a separate transition. */
@Service
@Profile("fams-product")
public class AssetImportBatchService {
    public record Ticket(UUID id, String status, long previewVersion, JsonNode mapping) { }
    public record Preview(UUID id, long version, int totalRows, int readyRows, int errorRows,
                          List<AssetImportWorkbook.CellError> errors, boolean moreErrors) {
        public Preview { errors = List.copyOf(errors); }
    }
    public record PreviewRow(int sheetRow, String status, JsonNode source,
                             JsonNode resolved, JsonNode errors) { }
    public record RowPage(int total, List<PreviewRow> items) {
        public RowPage { items = List.copyOf(items); }
    }
    public record ColumnList(List<String> columns) {
        public ColumnList { columns = List.copyOf(columns); }
    }

    private static final int ROW_BATCH = 500;
    private final JdbcTemplate db;
    private final AssetImportCatalogRepository catalog;
    private final ObjectMapper json;
    private final Clock clock;

    public AssetImportBatchService(JdbcTemplate db, AssetImportCatalogRepository catalog,
            ObjectMapper json, Clock clock) {
        this.db = db;
        this.catalog = catalog;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Ticket upload(long actorId, String uploadKey, byte[] xlsx) {
        if (actorId < 1 || uploadKey == null || uploadKey.length() < 16 || uploadKey.length() > 128
                || !uploadKey.matches("[\\x21-\\x7e]+")) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        AssetImportWorkbook.parse(xlsx);
        String digest = sha256(xlsx);
        if (db.queryForList("SELECT id FROM fams_system_account WHERE id=? FOR UPDATE", actorId).isEmpty()) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        var existing = db.queryForList("SELECT id,batch_status,preview_version,source_sha256,mapping_json "
                + "FROM fams_asset_import_batch WHERE actor_account_id=? AND upload_key=? FOR UPDATE",
                actorId, uploadKey);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!digest.equals(row.get("source_sha256"))) {
                throw new IllegalStateException("ASSET_IMPORT_UPLOAD_CONFLICT");
            }
            return new Ticket(UUID.fromString((String) row.get("id")), (String) row.get("batch_status"),
                    ((Number) row.get("preview_version")).longValue(), jsonOrNull((String) row.get("mapping_json")));
        }
        UUID id = UUID.randomUUID();
        var now = Timestamp.from(clock.instant());
        db.update("INSERT INTO fams_asset_import_batch (id,actor_account_id,upload_key,source_sha256,"
                + "source_bytes,source_xlsx,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)", id.toString(), actorId, uploadKey,
                digest, xlsx.length, xlsx, now, now);
        db.update("INSERT INTO fams_asset_import_event (batch_id,actor_account_id,action,detail_json,occurred_at) "
                + "VALUES (?,?,'ASSET_IMPORT_UPLOADED',?,?)", id.toString(), actorId,
                toJson(Map.of("sourceBytes", xlsx.length)), now);
        return new Ticket(id, "UPLOADED", 0, null);
    }

    @Transactional(readOnly = true)
    public Ticket get(long actorId, UUID batchId) {
        if (actorId < 1 || batchId == null) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        var rows = db.queryForList("SELECT batch_status,preview_version,mapping_json FROM fams_asset_import_batch "
                + "WHERE id=? AND actor_account_id=?", batchId.toString(), actorId);
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        var row = rows.getFirst();
        return new Ticket(batchId, (String) row.get("batch_status"),
                ((Number) row.get("preview_version")).longValue(), jsonOrNull((String) row.get("mapping_json")));
    }

    @Transactional(readOnly = true)
    public ColumnList columns(long actorId, UUID batchId) {
        if (actorId < 1 || batchId == null) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        var rows = db.queryForList("SELECT source_xlsx FROM fams_asset_import_batch "
                + "WHERE id=? AND actor_account_id=?", batchId.toString(), actorId);
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        return new ColumnList(AssetImportWorkbook.parse((byte[]) rows.getFirst().get("source_xlsx")).columns());
    }

    @Transactional
    public Preview preview(long actorId, UUID batchId, long expectedVersion,
            Map<String, String> selectedColumns) {
        if (actorId < 1 || batchId == null || expectedVersion < 0) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        var rows = db.queryForList("SELECT source_xlsx,batch_status,preview_version "
                + "FROM fams_asset_import_batch WHERE id=? AND actor_account_id=? FOR UPDATE",
                batchId.toString(), actorId);
        if (rows.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        var batch = rows.getFirst();
        if (!Set.of("UPLOADED", "PREVIEWED").contains(batch.get("batch_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_LOCKED");
        }
        var reviews = db.queryForList("SELECT review_status FROM fams_asset_import_review "
                + "WHERE batch_id=?", batchId.toString());
        if (!reviews.isEmpty() && !"REJECTED".equals(reviews.getFirst().get("review_status"))) {
            throw new IllegalStateException("ASSET_IMPORT_REVIEW_LOCKED");
        }
        if (((Number) batch.get("preview_version")).longValue() != expectedVersion) {
            throw new IllegalStateException("ASSET_IMPORT_VERSION_CONFLICT");
        }
        var parsed = AssetImportWorkbook.parse((byte[]) batch.get("source_xlsx"));
        Map<String, String> mapping = AssetImportPreflight.columns(parsed, selectedColumns);
        Set<String> assetCodes = identifiers(parsed, mapping.get("assetCode"));
        Set<String> serials = identifiers(parsed, mapping.get("serialNumber"));
        var result = AssetImportPreflight.check(parsed, selectedColumns, catalog.snapshot(assetCodes, serials));
        persistRows(batchId, parsed, result);
        long version = ((Number) batch.get("preview_version")).longValue() + 1;
        int total = parsed.rows().size();
        int ready = result.ready().size();
        int error = total - ready;
        db.update("UPDATE fams_asset_import_batch SET batch_status='PREVIEWED',mapping_json=?,"
                + "preview_version=?,total_rows=?,ready_rows=?,error_rows=?,updated_at=? WHERE id=?",
                toJson(mapping), version, total, ready, error, Timestamp.from(clock.instant()), batchId.toString());
        db.update("INSERT INTO fams_asset_import_event (batch_id,actor_account_id,action,detail_json,occurred_at) "
                + "VALUES (?,?,'ASSET_IMPORT_PREVIEWED',?,?)", batchId.toString(), actorId,
                toJson(Map.of("version", version, "totalRows", total, "readyRows", ready,
                        "errorRows", error)), Timestamp.from(clock.instant()));
        return new Preview(batchId, version, total, ready, error,
                result.errors().stream().limit(100).toList(), result.errors().size() > 100);
    }

    @Transactional(readOnly = true)
    public RowPage rows(long actorId, UUID batchId, String status, int page, int pageSize) {
        if (actorId < 1 || batchId == null || page < 1 || pageSize < 1 || pageSize > 200
                || page - 1 > Integer.MAX_VALUE / pageSize
                || (status != null && !Set.of("READY", "ERROR").contains(status))) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        Integer owned = db.queryForObject("SELECT COUNT(*) FROM fams_asset_import_batch "
                + "WHERE id=? AND actor_account_id=?", Integer.class, batchId.toString(), actorId);
        if (owned == null || owned == 0) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        String filter = status == null ? "" : " AND row_status=?";
        Object[] parameters = status == null ? new Object[] {batchId.toString()}
                : new Object[] {batchId.toString(), status};
        int total = java.util.Objects.requireNonNull(db.queryForObject(
                "SELECT COUNT(*) FROM fams_asset_import_row WHERE batch_id=?"
                        + filter, Integer.class, parameters));
        List<PreviewRow> items = db.query("SELECT sheet_row,row_status,source_json,resolved_json,errors_json "
                + "FROM fams_asset_import_row WHERE batch_id=?" + filter
                + " ORDER BY sheet_row LIMIT ? OFFSET ?", (result, index) -> new PreviewRow(
                        result.getInt("sheet_row"), result.getString("row_status"),
                        json.readTree(result.getString("source_json")),
                        jsonOrNull(result.getString("resolved_json")),
                        jsonOrNull(result.getString("errors_json"))),
                append(parameters, pageSize, (page - 1) * pageSize));
        return new RowPage(total, items);
    }

    @Transactional(readOnly = true)
    public byte[] preflightReport(long actorId, UUID batchId) {
        return report(actorId, batchId, false);
    }

    @Transactional(readOnly = true)
    public byte[] finalReport(long actorId, UUID batchId) {
        return report(actorId, batchId, true);
    }

    private byte[] report(long actorId, UUID batchId, boolean publishedOnly) {
        if (actorId < 1 || batchId == null) {
            throw new IllegalArgumentException("ASSET_IMPORT_REQUEST_INVALID");
        }
        var batches = db.queryForList("SELECT batch_status,source_xlsx FROM fams_asset_import_batch "
                + "WHERE id=? AND actor_account_id=?", batchId.toString(), actorId);
        if (batches.isEmpty()) { throw new IllegalStateException("ASSET_IMPORT_NOT_FOUND"); }
        String status = (String) batches.getFirst().get("batch_status");
        if (publishedOnly ? !Set.of("PUBLISHED", "ROLLED_BACK").contains(status)
                : !Set.of("PREVIEWED", "QUEUED", "PUBLISHING", "PUBLISHED", "FAILED", "ROLLED_BACK")
                        .contains(status)) {
            throw new IllegalStateException("ASSET_IMPORT_LOCKED");
        }
        List<String> headers = AssetImportWorkbook.parse((byte[]) batches.getFirst().get("source_xlsx")).columns();
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("工作表,Excel 行,预检状态");
        for (String header : headers) { csv.append(',').append(csvCell(header)); }
        csv.append(",成功资产编码,错误列,原值,原因\r\n");
        db.query("SELECT r.sheet_row,r.row_status,r.source_json,r.errors_json,p.asset_code "
                + "FROM fams_asset_import_row r LEFT JOIN fams_asset_import_publication p "
                + "ON p.batch_id=r.batch_id AND p.sheet_row=r.sheet_row "
                + "WHERE r.batch_id=? ORDER BY r.sheet_row", result -> {
                    JsonNode source = json.readTree(result.getString("source_json"));
                    JsonNode errors = jsonOrNull(result.getString("errors_json"));
                    csv.append("资产,").append(result.getInt("sheet_row")).append(',')
                            .append("READY".equals(result.getString("row_status")) ? "正确" : "错误");
                    for (String header : headers) {
                        csv.append(',').append(csvCell(source.path(header).asText("")));
                    }
                    csv.append(',').append(csvCell(result.getString("asset_code")));
                    if (errors == null || errors.isEmpty()) {
                        csv.append(",,,\r\n");
                    } else {
                        for (int index = 0; index < errors.size(); index++) {
                            JsonNode error = errors.get(index);
                            if (index > 0) {
                                csv.append("资产,").append(result.getInt("sheet_row")).append(",错误");
                                for (int column = 0; column <= headers.size(); column++) { csv.append(','); }
                            }
                            csv.append(',').append(csvCell(error.path("column").asText("")))
                                    .append(',').append(csvCell(error.path("original").asText("")))
                                    .append(',').append(csvCell(error.path("reason").asText("")))
                                    .append("\r\n");
                        }
                    }
                }, batchId.toString());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvCell(String value) {
        return "\"" + AssetImportWorkbook.safeExportCell(value).replace("\"", "\"\"") + "\"";
    }

    private Set<String> identifiers(AssetImportWorkbook.Parsed parsed, String column) {
        if (column == null) { return Set.of(); }
        Set<String> identifiers = new HashSet<>();
        for (var row : parsed.rows()) {
            String value = row.values().get(column).strip();
            if (!value.isEmpty()) { identifiers.add(value); }
        }
        return identifiers;
    }

    private void persistRows(UUID batchId, AssetImportWorkbook.Parsed parsed, AssetImportPreflight.Result result) {
        Map<Integer, AssetImportPreflight.ReadyRow> ready = new HashMap<>();
        for (var row : result.ready()) { ready.put(row.sheetRow(), row); }
        Map<Integer, List<AssetImportWorkbook.CellError>> errors = new HashMap<>();
        for (var error : result.errors()) {
            errors.computeIfAbsent(error.sheetRow(), ignored -> new ArrayList<>()).add(error);
        }
        db.update("DELETE FROM fams_asset_import_row WHERE batch_id=?", batchId.toString());
        for (int offset = 0; offset < parsed.rows().size(); offset += ROW_BATCH) {
            List<AssetImportWorkbook.DataRow> rows = parsed.rows().subList(offset,
                    Math.min(parsed.rows().size(), offset + ROW_BATCH));
            db.batchUpdate("INSERT INTO fams_asset_import_row (batch_id,sheet_row,source_json,resolved_json,"
                    + "errors_json,row_status) VALUES (?,?,?,?,?,?)", new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement statement, int index)
                                throws java.sql.SQLException {
                            var row = rows.get(index);
                            var resolved = ready.get(row.sheetRow());
                            statement.setString(1, batchId.toString());
                            statement.setInt(2, row.sheetRow());
                            statement.setString(3, toJson(row.values()));
                            statement.setString(4, resolved == null ? null : toJson(resolved));
                            statement.setString(5, resolved == null
                                    ? toJson(errors.getOrDefault(row.sheetRow(), List.of())) : null);
                            statement.setString(6, resolved == null ? "ERROR" : "READY");
                        }
                        @Override
                        public int getBatchSize() { return rows.size(); }
                    });
        }
    }

    private String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private JsonNode jsonOrNull(String value) {
        return value == null ? null : json.readTree(value);
    }

    private Object[] append(Object[] arguments, int size, int offset) {
        Object[] result = java.util.Arrays.copyOf(arguments, arguments.length + 2);
        result[arguments.length] = size;
        result[arguments.length + 1] = offset;
        return result;
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
