package com.teletronics.storage.domain.file.valueobject;

import com.teletronics.storage.domain.file.exception.InvalidFileException;

/**
 * The user a file belongs to, as given with each request (the brief has no user management).
 */
public record OwnerId(String value) {

    public OwnerId {
        if (value == null || value.isBlank()) {
            throw new InvalidFileException("owner", "user id must not be blank");
        }
    }

    public static OwnerId of(String value) {
        return new OwnerId(value);
    }
}
