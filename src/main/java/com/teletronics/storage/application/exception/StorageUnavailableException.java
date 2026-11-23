package com.teletronics.storage.application.exception;

/**
 * The object store can't be reached right now (connection refused, timeout). Mapped to 503.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
