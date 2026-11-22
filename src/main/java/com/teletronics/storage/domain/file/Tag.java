package com.teletronics.storage.domain.file;

import java.util.Locale;

/**
 * A tag, stored trimmed and lowercase ("TAG and tAg is the same tag"). Matching is exact (domain decision 7).
 */
public record Tag(String value) {

    public static final int MAX_LENGTH = 50;

    public Tag {
        if (value == null || value.isBlank()) {
            throw new InvalidFileException("tags", "a tag must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidFileException("tags", "a tag must be at most 50 characters");
        }
        if (value.indexOf(',') >= 0) {
            throw new InvalidFileException("tags", "a tag must not contain a comma");
        }
        if (value.chars().anyMatch(Filename::isControl)) {
            throw new InvalidFileException("tags", "a tag must not contain control characters");
        }
    }

    /** Trims and lowercases, then validates. */
    public static Tag of(String raw) {
        if (raw == null) {
            throw new InvalidFileException("tags", "a tag must not be blank");
        }
        return new Tag(raw.trim().toLowerCase(Locale.ROOT));
    }
}
