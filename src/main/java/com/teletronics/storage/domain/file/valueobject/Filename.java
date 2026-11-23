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
        if (value == null || value.isBlank()) {
            throw new InvalidFileException("filename", "filename must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidFileException("filename", "filename must be at most 255 characters");
        }
        if (value.indexOf('/') >= 0) {
            throw new InvalidFileException("filename", "filename must not contain '/'");
        }
        if (value.indexOf('\\') >= 0) {
            throw new InvalidFileException("filename", "filename must not contain '\\'");
        }
        if (value.equals(".") || value.equals("..")) {
            throw new InvalidFileException("filename", "filename must not be '.' or '..'");
        }
        if (value.chars().anyMatch(Filename::isControl)) {
            throw new InvalidFileException("filename", "filename must not contain control characters");
        }
    }

    public static Filename of(String value) {
        return new Filename(value);
    }

    /** The comparison key: lowercase with {@link Locale#ROOT}. */
    public String key() {
        return value.toLowerCase(Locale.ROOT);
    }

    static boolean isControl(int c) {
        return c < 32 || c == 127;
    }
}
