package com.teletronics.storage.presentation.http.problem;

/**
 * An upload sent as an HTML form (multipart/form-data or application/x-www-form-urlencoded) instead of the
 * raw file bytes. Mapped to 415 with type about:blank (API-05).
 */
public class FormBodyNotSupportedException extends RuntimeException {

    public FormBodyNotSupportedException(String mediaType) {
        super("Send the file as the raw request body, not as a form (" + mediaType + ")");
    }
}
