package com.fams.runtime.compatibility;

final class RuntimeCompatibilityRejectedException extends RuntimeException {

    private final String probeKey;

    RuntimeCompatibilityRejectedException(String probeKey) {
        super("The deliberate transaction failure was rejected and rolled back.");
        this.probeKey = probeKey;
    }

    String getProbeKey() {
        return probeKey;
    }
}
