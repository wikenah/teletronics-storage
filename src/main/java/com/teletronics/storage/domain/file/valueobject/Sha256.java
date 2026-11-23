package com.teletronics.storage.domain.file.valueobject;

import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SHA-256 of a file's bytes, lowercase hex. Same hash for the same owner = same content (domain decision 4).
 */
public record Sha256(String hex) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public Sha256 {
        if (hex == null || !FORMAT.matcher(hex).matches()) {
            throw new IllegalArgumentException("not a lowercase hex SHA-256: " + hex);
        }
    }

    public static Sha256 of(byte[] digest) {
        return new Sha256(HexFormat.of().formatHex(digest));
    }
}
