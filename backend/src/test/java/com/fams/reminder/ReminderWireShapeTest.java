package com.fams.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JAVA-11b wire equivalence: the typed message-centre views must serialize exactly like the
 * frozen pre-refactor projections. legacyItem is MessageService.item verbatim (with its
 * instant helper) and the page/read assemblies are the removed Map.of builders verbatim; they
 * are the reference oracles. Comparison round-trips through JSON text so integral node widths
 * normalize and object key order (never stable in Map.of responses) is ignored, while key
 * presence, explicit nulls and value shapes all count.
 */
class ReminderWireShapeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void todoItemMatchesLegacyProjectionWithTimestampMomentAndNullDeadline() {
        var row = todoRow();
        assertWireEquals(legacyItem(row, Set.of()), ReminderViews.messageItem(row, Set.of()));
        assertWireEquals(legacyItem(row, Set.of("TODO:101")), ReminderViews.messageItem(row, Set.of("TODO:101")));
    }

    @Test
    void reminderItemMatchesLegacyProjectionAcrossDriverMomentShapes() {
        var reminder = reminderRow();
        assertWireEquals(legacyItem(reminder, Set.of()), ReminderViews.messageItem(reminder, Set.of()));

        var timestampDeadline = new LinkedHashMap<>(reminder);
        timestampDeadline.put("moment", LocalDateTime.of(2026, 11, 20, 4, 5, 6));
        timestampDeadline.put("deadline_at", Timestamp.from(Instant.parse("2026-11-21T00:00:00Z")));
        assertWireEquals(legacyItem(timestampDeadline, Set.of("REMINDER:202")),
                ReminderViews.messageItem(timestampDeadline, Set.of("REMINDER:202")));

        var localDeadline = new LinkedHashMap<>(reminder);
        localDeadline.put("deadline_at", LocalDateTime.of(2026, 11, 21, 0, 0, 0));
        assertWireEquals(legacyItem(localDeadline, Set.of()), ReminderViews.messageItem(localDeadline, Set.of()));
    }

    @Test
    void absentParentAndPoolOwnerStayEmptyStringsLikeTheLegacyProjection() {
        var row = new LinkedHashMap<>(todoRow());
        row.put("parent_business_id", null);
        row.put("parent_business_type", null);
        row.put("assignee_account_id", null);
        assertWireEquals(legacyItem(row, Set.of()), ReminderViews.messageItem(row, Set.of()));

        row.put("parent_business_id", 7L);
        row.put("parent_business_type", "LOAN");
        row.put("assignee_account_id", 12L);
        assertWireEquals(legacyItem(row, Set.of()), ReminderViews.messageItem(row, Set.of()));
    }

    @Test
    void messagePageMatchesLegacyMapOfAssembly() {
        var todo = todoRow();
        var reminder = reminderRow();
        Set<String> read = Set.of("TODO:101");
        var legacy = Map.of(
                "items", List.of(legacyItem(todo, read), legacyItem(reminder, read)),
                "total", 2L,
                "unread", 1L,
                "page", 1,
                "pageSize", 20,
                "view", "INBOX");
        var typed = new ReminderViews.MessagePage(
                List.of(ReminderViews.messageItem(todo, read), ReminderViews.messageItem(reminder, read)),
                2L, 1L, 1, 20, "INBOX");
        assertWireEquals(legacy, typed);

        var exception = Map.of(
                "items", List.of(legacyItem(reminder, Set.of())),
                "total", 0L,
                "unread", 0L,
                "page", 3,
                "pageSize", 50,
                "view", "EXCEPTION");
        assertWireEquals(exception, new ReminderViews.MessagePage(
                List.of(ReminderViews.messageItem(reminder, Set.of())), 0L, 0L, 3, 50, "EXCEPTION"));
    }

    @Test
    void readReceiptMatchesLegacyMapOfAssembly() {
        long id = 9007199254740993L;
        assertWireEquals(Map.of("kind", "TODO", "id", Long.toString(id), "read", true),
                new ReminderViews.MessageReadReceipt("TODO", Long.toString(id), true));
    }

    @Test
    void typedViewsRoundTripThroughJsonText() {
        var page = new ReminderViews.MessagePage(
                List.of(ReminderViews.messageItem(todoRow(), Set.of("TODO:101")),
                        ReminderViews.messageItem(reminderRow(), Set.of())),
                2L, 1L, 1, 20, "INBOX");
        assertRoundTrip(page, ReminderViews.MessagePage.class);
        assertRoundTrip(new ReminderViews.MessageReadReceipt("REMINDER", "202", true),
                ReminderViews.MessageReadReceipt.class);
    }

    @Test
    void preRefactorStoredJsonDeserializesIntoTypedViews() {
        // The page/read shapes were the only Map responses of this domain; feed the frozen
        // legacy serialization back through the new records and require identical text output.
        var storedPage = JSON.writeValueAsString(Map.of(
                "items", List.of(legacyItem(todoRow(), Set.of("TODO:101")), legacyItem(reminderRow(), Set.of())),
                "total", 2L, "unread", 1L, "page", 1, "pageSize", 20, "view", "INBOX"));
        var page = JSON.readValue(storedPage, ReminderViews.MessagePage.class);
        assertEquals(JSON.readTree(storedPage), JSON.readTree(JSON.writeValueAsString(page)));

        var storedRead = JSON.writeValueAsString(Map.of("kind", "TODO", "id", "101", "read", true));
        var receipt = JSON.readValue(storedRead, ReminderViews.MessageReadReceipt.class);
        assertEquals(JSON.readTree(storedRead), JSON.readTree(JSON.writeValueAsString(receipt)));
    }

    @Test
    void dutyIdempotencyReplyShapeIsUnchanged() {
        // DutyService.replace stores DutyAssignment in fams_idempotency_record.response_body_json.
        // That record predates this batch and must keep round-tripping; the backup stays an
        // explicit null key exactly as the pre-batch stored JSON carried it.
        var stored = JSON.writeValueAsString(new DutyService.DutyAssignment("WAREHOUSE_OPERATOR", "3",
                new DutyService.DutyAccount("12", "Ada", true), null,
                java.time.OffsetDateTime.parse("2026-11-20T02:00:00Z")));
        var replayed = JSON.readValue(stored, DutyService.DutyAssignment.class);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(replayed)));
    }

    private static void assertRoundTrip(Object view, Class<?> type) {
        var stored = JSON.writeValueAsString(view);
        assertEquals(JSON.readTree(stored), JSON.readTree(JSON.writeValueAsString(JSON.readValue(stored, type))));
    }

    private static void assertWireEquals(Object legacy, Object typed) {
        // Round-trip through JSON text: integral node widths (int vs long) serialize to the
        // same bytes, and object key order was never stable in the Map.of-based responses.
        assertEquals(
                JSON.readTree(JSON.writeValueAsString(legacy)),
                JSON.readTree(JSON.writeValueAsString(typed)));
    }

    /** TODO branch row as the UNION's first SELECT names its columns. */
    private static Map<String, Object> todoRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("message_kind", "TODO");
        row.put("id", 101L);
        row.put("business_type", "INBOUND");
        row.put("business_id", 55L);
        row.put("stage", "ADMIN_DECISION");
        row.put("object_stage", "");
        row.put("pool_type", "ASSET_ADMIN");
        row.put("assignee_account_id", 12L);
        row.put("moment", Timestamp.from(Instant.parse("2026-11-20T02:00:00Z")));
        row.put("deadline_at", null);
        row.put("parent_business_id", null);
        row.put("parent_business_type", null);
        return row;
    }

    /** REMINDER branch row: same column names, pool-addressed, with a live deadline. */
    private static Map<String, Object> reminderRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("message_kind", "REMINDER");
        row.put("id", 202L);
        row.put("business_type", "TRANSFER_ITEM");
        row.put("business_id", 66L);
        row.put("stage", "OVERDUE");
        row.put("object_stage", "HANDOVER_RECEIPT");
        row.put("pool_type", "EMPLOYEE");
        row.put("assignee_account_id", null);
        row.put("moment", Timestamp.from(Instant.parse("2026-11-19T22:00:00Z")));
        row.put("deadline_at", Timestamp.from(Instant.parse("2026-11-20T22:00:00.123Z")));
        row.put("parent_business_id", 77L);
        row.put("parent_business_type", "REQUISITION");
        return row;
    }

    /** Frozen pre-JAVA-11b MessageService.item projection; reference oracle only. */
    private static Map<String, Object> legacyItem(Map<String, Object> row, Set<String> read) {
        var kind = String.valueOf(row.get("message_kind"));
        return Map.ofEntries(Map.entry("kind", kind), Map.entry("id", row.get("id").toString()),
                Map.entry("businessType", row.get("business_type")),
                Map.entry("businessId", row.get("business_id").toString()),
                Map.entry("parentBusinessId", Objects.toString(row.get("parent_business_id"), "")),
                Map.entry("parentBusinessType", Objects.toString(row.get("parent_business_type"), "")),
                Map.entry("stage", row.get("stage")), Map.entry("objectStage", row.get("object_stage")),
                Map.entry("poolType", row.get("pool_type")),
                Map.entry("assigneeAccountId", row.get("assignee_account_id") == null
                        ? "" : row.get("assignee_account_id").toString()),
                Map.entry("openedAt", legacyInstant(row.get("moment")).toString()),
                Map.entry("deadlineAt", row.get("deadline_at") == null ? ""
                        : legacyInstant(row.get("deadline_at")).toString()),
                Map.entry("read", read.contains(kind + ":" + row.get("id"))));
    }

    /** Frozen pre-JAVA-11b MessageService.instant helper; reference oracle only. */
    private static Instant legacyInstant(Object value) {
        return value instanceof LocalDateTime local
                ? local.toInstant(java.time.ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }
}
