package com.teletronics.storage.application.port;

/**
 * The file can never be stored with bounded part buffers: its parts would outgrow the upload budget, or a body
 * of unknown length outgrew what the store can take. Mapped to 413 /problems/file-too-large.
 */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
