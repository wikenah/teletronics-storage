package com.teletronics.storage.domain.file.valueobject;

import java.util.UUID;

/**
 * Where a file's bytes live in the object store. Random and fixed when the write starts, so a rename
 * never moves bytes (domain decision 5).
 */
public record StorageKey(String value) {

    public static final String PREFIX = "blobs/";

    public StorageKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage key must not be blank");
        }
    }

    public static StorageKey random() {
        return new StorageKey(PREFIX + UUID.randomUUID());
    }
}
