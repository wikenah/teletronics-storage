package com.teletronics.storage.presentation.http.problem;

/**
 * A request value the HTTP layer rejects before calling the file service. Mapped to 400.
 */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
