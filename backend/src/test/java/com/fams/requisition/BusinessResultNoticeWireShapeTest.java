package com.fams.requisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAVA-14b2 wire equivalence: every business-result-notice HTTP exit must serialize exactly like
 * the frozen pre-refactor {@code Map.of} assembly (legacyWire below keeps those assemblies
 * verbatim as the reference oracle). Comparison is on JSON trees round-tripped through text, so
 * key presence and value shapes count while the legacy Map.of iteration order never did. The
 * mapper mirrors the production HTTP mapper: com.fams.contract.probe.JsonConfiguration restores
 * ALWAYS value inclusion on top of the Jackson 3 NON_NULL default, which keeps a JsonNull
 * content node an explicit {@code "content": null} key on both sides.
 */
class BusinessResultNoticeWireShapeTest {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    @Test
    void noticeItemMatchesLegacyProjectionAcrossDriverShapes() {
        var read = legacyWire(noticeRow(Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")), true, 1L));
        assertWireEquals(read, NoticeViews.noticeItem(JSON,
                noticeRow(Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")), true, 1L)));

        var unread = legacyWire(noticeRow(LocalDateTime.of(2026, 11, 20, 2, 0, 0), false, 0L));
        assertWireEquals(unread, NoticeViews.noticeItem(JSON,
                noticeRow(LocalDateTime.of(2026, 11, 20, 2, 0, 0), false, 0L)));

        // EXISTS surfaces as Boolean on some drivers; the legacy predicate accepted both.
        var booleanFlag = noticeRow(Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")), false, null);
        booleanFlag.put("review_requested", Boolean.TRUE);
        assertWireEquals(legacyWire(booleanFlag), NoticeViews.noticeItem(JSON, booleanFlag));

        // A JSON 'null' content snapshot stays an explicit null key under the ALWAYS mapper.
        var nullContent = noticeRow(Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")), false, 0L);
        nullContent.put("content_json", "null");
        var typed = NoticeViews.noticeItem(JSON, nullContent);
        assertWireEquals(legacyWire(nullContent), typed);
        assertTrue(JSON.readTree(JSON.writeValueAsString(typed)).has("content"));
    }

    @Test
    void noticePageMatchesLegacyAssemblyWithAndWithoutRows() {
        var row = noticeRow(Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")), true, 1L);
        var items = List.of(NoticeViews.noticeItem(JSON, row));
        var legacyItems = List.of(legacyWire(row));
        assertWireEquals(legacyListPage(legacyItems, 2, 20, 41L, 3L),
                new NoticeViews.NoticePage(items, 2, 20, 41L, 3L));
        assertWireEquals(legacyListPage(List.of(), 1, 20, 0L, 0L),
                new NoticeViews.NoticePage(List.of(), 1, 20, 0L, 0L));
    }

    @Test
    void reviewRequestItemMatchesLegacyProjection() {
        var row = reviewRow(Timestamp.from(Instant.parse("2026-11-21T08:30:00Z")),
                "{\"reason\":\"实物不符\",\"executionId\":\"77\"}");
        assertWireEquals(legacyWire(row), NoticeViews.reviewRequestItem(JSON, row));

        var local = reviewRow(LocalDateTime.of(2026, 11, 21, 8, 30, 0), "{\"note\":\"x\"}");
        assertWireEquals(legacyWire(local), NoticeViews.reviewRequestItem(JSON, local));
        assertEquals("", JSON.readTree(JSON.writeValueAsString(NoticeViews.reviewRequestItem(JSON, local)))
                .path("reason").asText(), "absent reason keeps the asText empty-string fallback");
    }

    @Test
    void reviewRequestPageMatchesLegacyAssemblyWithAndWithoutRows() {
        var row = reviewRow(Timestamp.from(Instant.parse("2026-11-21T08:30:00Z")), "{\"reason\":\"r\"}");
        var items = List.of(NoticeViews.reviewRequestItem(JSON, row));
        var legacyItems = List.of(legacyWire(row));
        assertWireEquals(legacyReviewPage(legacyItems, 7L, 1, 20),
                new NoticeViews.ReviewRequestPage(items, 7L, 1, 20));
        assertWireEquals(legacyReviewPage(List.of(), 0L, 3, 50),
                new NoticeViews.ReviewRequestPage(List.of(), 0L, 3, 50));
    }

    @Test
    void readReceiptMatchesLegacyAssembly() {
        long id = 4242L;
        assertWireEquals(Map.of("id", Long.toString(id), "read", true), new NoticeViews.ReadReceipt("4242", true));
    }

    @Test
    void objectionReceiptMatchesLegacyAssembly() {
        long id = 4242L;
        var notice = new LinkedHashMap<String, Object>();
        notice.put("business_type", "REQUISITION");
        notice.put("business_id", 88L);
        var typed = new NoticeViews.ObjectionReceipt(Long.toString(id), "ADMIN_REVIEW_REQUESTED",
                (String) notice.get("business_type"), notice.get("business_id").toString());
        assertWireEquals(legacyObjectionWire(id, notice), typed);
    }

    @Test
    void objectionIdempotencyReplayRoundTripsPreRefactorStoredJson() {
        // Stored response_body_json written before this batch (legacy Map.of assembly serialized
        // by the same ALWAYS mapper) must deserialize into the record and re-serialize identically.
        long id = 4242L;
        var notice = new LinkedHashMap<String, Object>();
        notice.put("business_type", "TRANSFER");
        notice.put("business_id", 99L);
        var stored = JSON.writeValueAsString(legacyObjectionWire(id, notice));
        var replayed = JSON.readValue(stored, NoticeViews.ObjectionReceipt.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
        // Fresh writes of the typed result keep the exact same stored shape for future replays.
        var fresh = new NoticeViews.ObjectionReceipt(Long.toString(id), "ADMIN_REVIEW_REQUESTED", "TRANSFER", "99");
        assertEquals(JSON.readTree(JSON.writeValueAsString(fresh)),
                JSON.readTree(JSON.writeValueAsString(JSON.readValue(
                        JSON.writeValueAsString(fresh), NoticeViews.ObjectionReceipt.class))));
    }

    private static Map<String, Object> noticeRow(Object createdAt, boolean read, Long reviewRequested) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 4242L);
        row.put("business_type", "REQUISITION");
        row.put("business_id", 88L);
        row.put("item_id", 7L);
        row.put("content_json", "{\"assetCode\":\"FA-1\",\"result\":\"APPROVED\"}");
        row.put("created_at", createdAt);
        row.put("read_at", read ? Timestamp.from(Instant.parse("2026-11-20T03:00:00Z")) : null);
        row.put("review_requested", reviewRequested);
        return row;
    }

    private static Map<String, Object> reviewRow(Object occurredAt, String detailJson) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 4242L);
        row.put("execution_id", 77L);
        row.put("business_id", 88L);
        row.put("business_type", "REPAIR");
        row.put("recipient", "张三");
        row.put("detail_json", detailJson);
        row.put("occurred_at", occurredAt);
        return row;
    }

    /** Frozen pre-JAVA-14b2 list() item assembly. */
    private static Map<String, Object> legacyWire(Map<String, Object> row) {
        if (row.containsKey("execution_id")) {
            return Map.of(
                    "noticeId", row.get("id").toString(),
                    "executionId", row.get("execution_id").toString(),
                    "businessId", row.get("business_id").toString(),
                    "businessType", row.get("business_type"),
                    "recipient", row.get("recipient"),
                    "reason", JSON.readTree(row.get("detail_json").toString()).path("reason").asText(),
                    "createdAt", row.get("occurred_at").toString());
        }
        return Map.of(
                "id", row.get("id").toString(), "businessType", row.get("business_type"),
                "businessId", row.get("business_id").toString(), "itemId", row.get("item_id").toString(),
                "content", JSON.readTree(row.get("content_json").toString()),
                "createdAt", row.get("created_at").toString(),
                "read", row.get("read_at") != null, "reviewRequested",
                "1".equals(String.valueOf(row.get("review_requested")))
                        || Boolean.TRUE.equals(row.get("review_requested")));
    }

    /** Frozen pre-JAVA-14b2 list() envelope assembly. */
    private static Map<String, Object> legacyListPage(Object items, int page, int pageSize, long total, long unread) {
        return Map.of("items", items, "page", page, "pageSize", pageSize, "total", total, "unread", unread);
    }

    /** Frozen pre-JAVA-14b2 reviewRequests() envelope assembly. */
    private static Map<String, Object> legacyReviewPage(Object items, long total, int page, int pageSize) {
        return Map.of("items", items, "total", total, "page", page, "pageSize", pageSize);
    }

    /** Frozen pre-JAVA-14b2 objection() result assembly, also the persisted idempotency shape. */
    private static Map<String, Object> legacyObjectionWire(long id, Map<String, Object> notice) {
        return Map.of("noticeId", Long.toString(id), "status", "ADMIN_REVIEW_REQUESTED",
                "businessType", notice.get("business_type"), "businessId", notice.get("business_id").toString());
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and Map.of key order was never stable across JVM runs.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }
}
