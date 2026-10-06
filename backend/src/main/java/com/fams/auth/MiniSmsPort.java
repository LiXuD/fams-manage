package com.fams.auth;

/** Production delivery is supplied only with real SMS resources. Implementations must not log codes. */
public interface MiniSmsPort {
    void sendBindingCode(String registeredPhone, String code);
}
