package com.fams.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Master-data port used by the system-admin phone correction flow to update an employee's
 * registered phone in place, with optimistic version control.
 */
public interface MiniPhoneCorrectionPort {
    /**
     * Applies the phone correction when the employee record is still at the expected version.
     *
     * @param employeeId employee whose registered phone is corrected
     * @param version expected employee record version for the optimistic check
     * @param phone new registered phone number in plaintext; the port stores it encrypted
     * @param actorId account id of the correcting system admin, recorded for audit
     * @param now timestamp used for the correction audit trail
     * @return the applied change with the new version and both ciphertexts, or empty on a version
     *         mismatch
     */
    Optional<Change> correct(long employeeId, long version, String phone, long actorId, Instant now);
    /**
     * Result of an applied correction: the new employee version plus the previous and updated
     * phone ciphertexts as stored, used for the audit detail without exposing plaintext.
     */
    record Change(long version, String previousCiphertext, String updatedCiphertext) { }
}
