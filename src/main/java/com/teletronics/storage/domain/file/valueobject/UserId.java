package com.teletronics.storage.domain.file.valueobject;

import com.teletronics.storage.domain.file.exception.InvalidFileException;

/**
 * A user, as given with each request (the brief has no user management). A file belongs to one.
 */
public record UserId(String value) {

    public UserId {
        if (value == null || value.isBlank()) {
            throw new InvalidFileException("userId", "user id must not be blank");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }
}
