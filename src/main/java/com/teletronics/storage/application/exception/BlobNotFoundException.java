package com.teletronics.storage.application.exception;

/**
 * Nothing is stored under the key.
 */
public class BlobNotFoundException extends RuntimeException {

    public BlobNotFoundException(String key) {
        super("no stored bytes for " + key);
    }
}
