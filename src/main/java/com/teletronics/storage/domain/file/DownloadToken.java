package com.teletronics.storage.domain.file;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The secret part of a file's download link: 128 random bits, URL-safe base64 (22 characters).
 * Anyone holding the link can download the file (a capability URL, domain decision 8).
 */
public record DownloadToken(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

    public DownloadToken {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("malformed download token");
        }
    }

    public static DownloadToken generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return new DownloadToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** Parses a token from a URL; anything malformed is simply "no such token". */
    public static Optional<DownloadToken> parse(String value) {
        return value != null && FORMAT.matcher(value).matches()
                ? Optional.of(new DownloadToken(value))
                : Optional.empty();
    }
}
