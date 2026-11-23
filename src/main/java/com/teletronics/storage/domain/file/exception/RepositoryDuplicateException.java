package com.teletronics.storage.domain.file.exception;

import com.teletronics.storage.domain.file.FileRepository;

/**
 * Raised by a {@link FileRepository} when a write breaks one of the unique rules
 * (owner + filename key, owner + SHA-256, download token). The repository doesn't say which one;
 * callers look the existing file up (domain decision 5).
 */
public class RepositoryDuplicateException extends RuntimeException {

    public RepositoryDuplicateException(String message, Throwable cause) {
        super(message, cause);
    }

    public RepositoryDuplicateException(String message) {
        super(message);
    }
}
