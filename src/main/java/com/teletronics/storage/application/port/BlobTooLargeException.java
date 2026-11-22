package com.teletronics.storage.application.port;

/**
 * A body of unknown length outgrew what the store can take with the chosen part size.
 */
public class BlobTooLargeException extends RuntimeException {

    public BlobTooLargeException(String message) {
        super(message);
    }
}
