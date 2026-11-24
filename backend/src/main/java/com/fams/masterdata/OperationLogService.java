package com.fams.masterdata;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fams.auth.OperationLogAccess;
import com.fams.product.contract.model.OperationLogEntry;
import com.fams.product.contract.model.OperationLogSource;
import com.fams.product.contract.model.PageOperationLog;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Profile("fams-product")
@Service
class OperationLogService {

    private static final String UNION = """
            SELECT 'BUSINESS' AS source, id AS event_id, action AS event_type,
                   actor_account_id, actor_role, request_id, resource_type, resource_id,
                   occurred_at, detail_json
              FROM fams_audit_event
            UNION ALL
            SELECT 'SECURITY' AS source, id AS event_id, event_type,
                   account_id AS actor_account_id, NULL AS actor_role, request_id,
                   NULL AS resource_type, NULL AS resource_id, occurred_at, detail_json
              FROM fams_security_event
            """;

    private final JdbcTemplate db;
    private final OperationLogAccess access;
    private final OperationLogMasker masker;

    OperationLogService(JdbcTemplate db, OperationLogAccess access, ObjectMapper objectMapper) {
        this.db = db;
        this.access = access;
        this.masker = new OperationLogMasker(objectMapper);
    }

    @Transactional
    public PageOperationLog list(int page, int pageSize, String source, String actorAccountId,
            String occurredFrom, String occurredTo) {
        access.require();
        List<Object> arguments = new ArrayList<>();
        StringBuilder filter = new StringBuilder(" WHERE 1=1");
        if (present(source)) {
            filter.append(" AND source = ?");
            arguments.add(parseSource(source));
        }
        if (present(actorAccountId)) {
            filter.append(" AND actor_account_id = ?");
            arguments.add(parseId(actorAccountId));
        }
        if (present(occurredFrom)) {
            filter.append(" AND occurred_at >= ?");
            arguments.add(java.sql.Timestamp.from(parseInstant(occurredFrom)));
        }
        if (present(occurredTo)) {
            filter.append(" AND occurred_at <= ?");
            arguments.add(java.sql.Timestamp.from(parseInstant(occurredTo)));
        }
        String where = filter.toString();
        Long total = db.queryForObject("SELECT COUNT(*) FROM (" + UNION + ") merged" + where,
                Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(pageSize);
        pageArguments.add((page - 1) * pageSize);
        List<OperationLogEntry> items = db.query(
                "SELECT * FROM (" + UNION + ") merged" + where
                        + " ORDER BY occurred_at DESC, source ASC, event_id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    OperationLogEntry entry = new OperationLogEntry(
                            OperationLogSource.valueOf(rs.getString("source")),
                            Long.toString(rs.getLong("event_id")),
                            rs.getString("event_type"),
                            java.util.UUID.fromString(rs.getString("request_id")),
                            OffsetDateTime.ofInstant(rs.getTimestamp("occurred_at").toInstant(),
                                    ZoneOffset.UTC),
                            masker.mask(rs.getString("detail_json")));
                    long actorId = rs.getLong("actor_account_id");
                    if (!rs.wasNull()) {
                        entry.setActorAccountId(Long.toString(actorId));
                    }
                    String actorRole = rs.getString("actor_role");
                    if (actorRole != null) {
                        entry.setActorRole(actorRole);
                    }
                    String resourceType = rs.getString("resource_type");
                    if (resourceType != null) {
                        entry.setResourceType(resourceType);
                    }
                    long resourceId = rs.getLong("resource_id");
                    if (!rs.wasNull()) {
                        entry.setResourceId(Long.toString(resourceId));
                    }
                    return entry;
                },
                pageArguments.toArray());
        return new PageOperationLog(page, pageSize, total == null ? Long.valueOf(0) : total, items);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private String parseSource(String value) {
        try {
            return OperationLogSource.valueOf(value.strip()).name();
        } catch (IllegalArgumentException exception) {
            throw MasterDataOperationException.invalid("The log source filter is invalid.");
        }
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            throw MasterDataOperationException.invalid("The actor filter is invalid.");
        }
    }

    private Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException exception) {
            throw MasterDataOperationException.invalid("The time filter is invalid.");
        }
    }
}
