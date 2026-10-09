package com.fams.business;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** The request domain owns outstanding dates; extension decisions use its transactional boundary. */
public interface BorrowRequestDatePort {
    Map<String, Object> eligible(long account, boolean administrator, boolean warehouse, int page, int size);
    Map<String, Object> outstanding(long id);
    Map<String, Object> snapshot(long id);
    void extend(long id, long version, LocalDate due, Instant time);
}
