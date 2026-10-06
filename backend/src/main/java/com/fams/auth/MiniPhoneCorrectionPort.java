package com.fams.auth;

import java.time.Instant;
import java.util.Optional;

public interface MiniPhoneCorrectionPort {
    Optional<Change> correct(long employeeId, long version, String phone, long actorId, Instant now);
    record Change(long version, String previousCiphertext, String updatedCiphertext) { }
}
