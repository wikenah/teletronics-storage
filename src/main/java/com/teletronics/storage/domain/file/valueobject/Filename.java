package com.teletronics.storage.domain.file.valueobject;

import com.teletronics.storage.domain.file.exception.InvalidFileException;

import java.util.Locale;

/**
 * A file's name, kept exactly as typed. Two names are the same when their {@link #key()} matches:
 * case is ignored, using {@link Locale#ROOT} so the server's locale never matters (domain decision 2).
 */
public record Filename(String value) {

    public static final int MAX_LENGTH = 255;

    public Filename {
        require(value != null && !value.isBlank(), "must not be blank");
        require(value.length() <= MAX_LENGTH, "must be at most " + MAX_LENGTH + " characters");
        require(value.indexOf('/') < 0, "must not contain '/'");
        require(value.indexOf('\\') < 0, "must not contain '\\'");
        require(!value.equals(".") && !value.equals(".."), "must not be '.' or '..'");
        require(value.chars().noneMatch(Filename::isControl), "must not contain control characters");
    }

    public static Filename of(String value) {
        return new Filename(value);
    }

    /** The comparison key: lowercase with {@link Locale#ROOT}. */
    public String key() {
        return value.toLowerCase(Locale.ROOT);
    }

    private static void require(boolean valid, String rule) {
        if (!valid) {
            throw new InvalidFileException("filename", "filename " + rule);
        }
    }

    static boolean isControl(int c) {
        return c < 32 || c == 127;
    }
}
