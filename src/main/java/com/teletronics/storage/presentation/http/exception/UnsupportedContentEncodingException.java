package com.teletronics.storage.presentation.http.exception;

/**
 * A compressed request body (Content-Encoding other than identity). Mapped to 415 with Accept-Encoding: identity.
 */
public class UnsupportedContentEncodingException extends RuntimeException {

    public UnsupportedContentEncodingException(String encoding) {
        super("Content-Encoding '" + encoding + "' is not supported; send the file uncompressed");
    }
}
