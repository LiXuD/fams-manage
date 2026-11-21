package com.fams.business;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The request domain owns outstanding dates; extension decisions use its transactional boundary. */
public interface BorrowRequestDatePort {
    /**
     * Eligible borrow-request choice, mirroring contracts BorrowRequestChoice. Rows follow the
     * frozen loan clean projection: identifiers stringified, DATE columns become toString date
     * strings (drivers surface java.sql.Date or LocalDate; both toString to yyyy-MM-dd).
     */
    record BorrowRequestChoice(String id, long version, String expectedReturnDate, String requestStatus) {}

    /** Mirrors contracts BorrowRequestChoicePage; explicit null keys keep default inclusion. */
    record BorrowRequestChoicePage(List<BorrowRequestChoice> items, long total, int page, int pageSize) {
        public BorrowRequestChoicePage {
            items = items == null ? null : List.copyOf(items);
        }
    }

    BorrowRequestChoicePage eligible(long account, boolean administrator, boolean warehouse, int page, int size);
    Map<String, Object> outstanding(long id);
    Map<String, Object> snapshot(long id);
    void extend(long id, long version, LocalDate due, Instant time);
}
