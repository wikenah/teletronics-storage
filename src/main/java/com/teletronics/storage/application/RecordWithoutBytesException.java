package com.teletronics.storage.application;

/**
 * A file record exists but its bytes are missing from the store: our inconsistency, mapped to 500 (STO-16).
 */
public class RecordWithoutBytesException extends RuntimeException {

    public RecordWithoutBytesException(String message, Throwable cause) {
        super(message, cause);
    }
}
