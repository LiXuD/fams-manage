package com.fams.reminder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed wire shapes of the reminder domain, mirroring contracts/fams-core-v1.yaml.
 * The message-centre rows follow the frozen pre-JAVA-11b MessageService.item projection
 * (identifiers stringified with an empty string for absent values, instants as ISO text);
 * the duty, anomaly, follow-up and subscription responses were already typed records before
 * this batch and stay where they are. No conditional keys exist on these three shapes:
 * every entry of the previous Map.of/Map.ofEntries assembly is always present.
 */
public final class ReminderViews {
    private ReminderViews() {
    }

    /** One inbox or exception row; TODO and REMINDER branches share this exact key set by SQL UNION. */
    public record MessageItem(
            String kind,
            String id,
            String businessType,
            String businessId,
            String parentBusinessId,
            String parentBusinessType,
            String stage,
            String objectStage,
            String poolType,
            String assigneeAccountId,
            String openedAt,
            String deadlineAt,
            boolean read) {
    }

    public record MessagePage(List<MessageItem> items, long total, long unread, int page, int pageSize, String view) {
        public MessagePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Personal read receipt acknowledgement; the id stays the stringified message id. */
    public record MessageReadReceipt(String kind, String id, boolean read) {
    }

    /** Frozen pre-JAVA-11b MessageService.item projection over one JDBC row map. */
    static MessageItem messageItem(Map<String, Object> row, Set<String> read) {
        var kind = String.valueOf(row.get("message_kind"));
        return new MessageItem(kind,
                row.get("id").toString(),
                (String) row.get("business_type"),
                row.get("business_id").toString(),
                Objects.toString(row.get("parent_business_id"), ""),
                Objects.toString(row.get("parent_business_type"), ""),
                (String) row.get("stage"),
                (String) row.get("object_stage"),
                (String) row.get("pool_type"),
                row.get("assignee_account_id") == null ? "" : row.get("assignee_account_id").toString(),
                instant(row.get("moment")).toString(),
                row.get("deadline_at") == null ? "" : instant(row.get("deadline_at")).toString(),
                read.contains(kind + ":" + row.get("id")));
    }

    // MySQL DATETIME columns come back as UTC wall-clock LocalDateTime or Timestamp
    // depending on the driver mapping; both were accepted by the frozen projection.
    private static Instant instant(Object value) {
        return value instanceof LocalDateTime local
                ? local.toInstant(ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }
}
