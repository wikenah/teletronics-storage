package com.teletronics.storage.application;

/**
 * The file can never be stored with bounded part buffers. Mapped to 413 /problems/file-too-large.
 */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
