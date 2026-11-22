package com.fams.requisition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Typed wire shapes of the business-result-notice HTTP exits (JAVA-14b2).
 * Every key, value shape and extraction expression mirrors the frozen pre-refactor
 * {@code Map.of} assemblies in BusinessResultNoticeService: identifiers are stringified with
 * {@code toString()} exactly where the legacy projection stringified them, timestamps travel as
 * their raw JDBC {@code toString()} text (the legacy exits never normalised to ISO instants),
 * stored content JSON stays a raw {@link JsonNode}, and the review-request {@code reason} keeps
 * the {@code path("reason").asText()} fallback. No conditional keys exist on these shapes:
 * {@code Map.of} rejected nulls, so every entry was always present with a non-null value.
 */
public final class NoticeViews {
    private NoticeViews() {
    }

    /** One inbox row of the recipient listing. */
    public record NoticeItem(
            String id,
            String businessType,
            String businessId,
            String itemId,
            JsonNode content,
            String createdAt,
            boolean read,
            boolean reviewRequested) {
    }

    /** Recipient listing envelope; unread counts notices with read_at IS NULL. */
    public record NoticePage(List<NoticeItem> items, int page, int pageSize, long total, long unread) {
        public NoticePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** One open proxy-receipt review request as seen by the asset admin. */
    public record ReviewRequestItem(
            String noticeId,
            String executionId,
            String businessId,
            String businessType,
            String recipient,
            String reason,
            String createdAt) {
    }

    public record ReviewRequestPage(
            List<ReviewRequestItem> items, long total, int page, int pageSize) {
        public ReviewRequestPage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** Mark-as-read receipt; the id is the stringified notice id. */
    public record ReadReceipt(String id, boolean read) {
    }

    /** Objection receipt; this exact shape is persisted in fams_idempotency_record. */
    public record ObjectionReceipt(String noticeId, String status, String businessType, String businessId) {
    }

    /** Frozen pre-JAVA-14b2 list() row projection over one JDBC row map. */
    static NoticeItem noticeItem(ObjectMapper json, Map<String, Object> row) {
        return new NoticeItem(
                row.get("id").toString(),
                (String) row.get("business_type"),
                row.get("business_id").toString(),
                row.get("item_id").toString(),
                json.readTree(row.get("content_json").toString()),
                row.get("created_at").toString(),
                row.get("read_at") != null,
                "1".equals(String.valueOf(row.get("review_requested")))
                        || Boolean.TRUE.equals(row.get("review_requested")));
    }

    /** Frozen pre-JAVA-14b2 reviewRequests() row projection over one JDBC row map. */
    static ReviewRequestItem reviewRequestItem(ObjectMapper json, Map<String, Object> row) {
        return new ReviewRequestItem(
                row.get("id").toString(),
                row.get("execution_id").toString(),
                row.get("business_id").toString(),
                (String) row.get("business_type"),
                (String) row.get("recipient"),
                json.readTree(row.get("detail_json").toString()).path("reason").asText(),
                row.get("occurred_at").toString());
    }
}
