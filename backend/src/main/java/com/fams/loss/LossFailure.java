package com.fams.loss;

/**
 * Runtime failure carrying an HTTP status and an error code for loss operations.
 * Thrown by loss services to signal rejected requests; {@link LossErrorHandler}
 * translates it into a structured error response using the code as the message.
 */
public final class LossFailure extends RuntimeException {
    private final int status;

    /**
     * Creates a failure with the HTTP status to return and the error code used as
     * the exception message.
     *
     * @param status HTTP status code the error handler maps this failure to
     * @param code stable loss error code, stored as the exception message
     */
    public LossFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    /**
     * Returns the HTTP status code associated with this failure.
     *
     * @return HTTP status code to surface in the error response
     */
    public int status() {
        return status;
    }
}
