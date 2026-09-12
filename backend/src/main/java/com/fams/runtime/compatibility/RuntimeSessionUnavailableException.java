package com.fams.runtime.compatibility;

final class RuntimeSessionUnavailableException extends RuntimeException {

    RuntimeSessionUnavailableException() {
        super("A valid JDBC-backed session proof was not supplied.");
    }
}
