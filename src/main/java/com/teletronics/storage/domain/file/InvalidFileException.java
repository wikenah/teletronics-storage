package com.teletronics.storage.domain.file;

/**
 * A value supplied for a file breaks a domain rule. Carries the field and the rule that was broken.
 */
public class InvalidFileException extends RuntimeException {

    private final String field;

    public InvalidFileException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
