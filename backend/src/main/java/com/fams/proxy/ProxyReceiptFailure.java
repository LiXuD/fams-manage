package com.fams.proxy;

/** Public owner-port failure without coupling the shared workflow to private domain exceptions. */
public final class ProxyReceiptFailure extends RuntimeException {
    private final int status;

    public ProxyReceiptFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
