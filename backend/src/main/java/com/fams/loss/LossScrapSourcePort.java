package com.fams.loss;

import com.fams.auth.RequisitionActorContext.Current;
import com.fams.auth.Role;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/** Loss owns access to and interpretation of confirmed loss evidence. */
@Component
@Profile("fams-product")
@Transactional(propagation = Propagation.MANDATORY)
public class LossScrapSourcePort {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public LossScrapSourcePort(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record BorrowedLoss(long assetId, long borrowerId, long organizationId,
            Instant confirmedAt, String confirmationJson) {}

    public BorrowedLoss requireBorrowedLoss(Current current, long id, long version) {
        var rows = db.queryForList("SELECT * FROM fams_loss_report WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new LossFailure(404, "LOSS_NOT_FOUND");
        }
        var row = rows.getFirst();
        if (!(current.has(Role.ASSET_ADMIN)
                || current.has(Role.WAREHOUSE_OPERATOR) && Objects.equals(current.id(), row.get("processor_account_id"))
                || current.has(Role.EMPLOYEE) && Objects.equals(current.employeeId(), row.get("responsible_employee_id"))
                || current.has(Role.DEPARTMENT_ASSET_MANAGER) && Objects.equals(current.organizationId(), row.get("using_organization_id")))) {
            throw new LossFailure(403, "SCRAP_LOSS_SOURCE_FORBIDDEN");
        }
        var source = json.readTree(row.get("source_snapshot_json").toString());
        if (((Number) row.get("version")).longValue() != version
                || !"CONFIRMED_LOST".equals(row.get("report_status")) || row.get("recovered_at") != null
                || !"BORROWED".equals(source.path("lifecycle").asText())
                || row.get("confirmation_snapshot_json") == null || row.get("confirmed_lost_at") == null
                || row.get("responsible_employee_id") == null || row.get("using_organization_id") == null) {
            throw new LossFailure(409, "SCRAP_LOSS_SOURCE_CHANGED");
        }
        Object confirmed = row.get("confirmed_lost_at");
        Instant at = confirmed instanceof java.sql.Timestamp timestamp ? timestamp.toInstant()
                : ((java.time.LocalDateTime) confirmed).toInstant(ZoneOffset.UTC);
        return new BorrowedLoss(((Number) row.get("asset_id")).longValue(),
                ((Number) row.get("responsible_employee_id")).longValue(),
                ((Number) row.get("using_organization_id")).longValue(), at, row.get("confirmation_snapshot_json").toString());
    }
}
