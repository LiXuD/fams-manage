package com.fams.auth;

/** Production delivery is supplied only with real SMS resources. Implementations must not log codes. */
public interface MiniSmsPort {
    /**
     * Delivers the one-time binding verification code for a mini-program binding challenge.
     * A delivery failure is reported as a runtime exception so the caller can mark the challenge
     * as not delivered.
     *
     * @param registeredPhone employee's registered phone number, the only allowed recipient
     * @param code one-time verification code in plaintext; must never be logged or persisted
     */
    void sendBindingCode(String registeredPhone, String code);
}
