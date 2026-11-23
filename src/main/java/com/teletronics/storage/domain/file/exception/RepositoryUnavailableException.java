package com.teletronics.storage.domain.file.exception;

/**
 * The metadata store can't be reached right now (connection refused, timeout). Mapped to 503.
 */
public class RepositoryUnavailableException extends RuntimeException {

    public RepositoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
