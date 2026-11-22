package com.teletronics.storage.domain.file;

/**
 * No such file, or not the caller's: both look the same to the caller (domain decision 10).
 */
public class FileNotFoundException extends RuntimeException {

    public FileNotFoundException() {
        super("file not found");
    }
}
