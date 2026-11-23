package com.teletronics.storage.application.upload;

/**
 * The body ended before the declared Content-Length: never treated as a complete file (API-11).
 */
public class IncompleteUploadException extends RuntimeException {

    public IncompleteUploadException(String message) {
        super(message);
    }
}
