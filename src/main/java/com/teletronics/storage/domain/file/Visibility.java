package com.teletronics.storage.domain.file;

/**
 * PUBLIC files appear in the public listing. Both kinds are downloadable by their link.
 */
public enum Visibility {
    PUBLIC,
    PRIVATE;

    /** Exact values only: "public" is rejected (HTTP decision 2). */
    public static Visibility parse(String value) {
        if ("PUBLIC".equals(value)) {
            return PUBLIC;
        }
        if ("PRIVATE".equals(value)) {
            return PRIVATE;
        }
        throw new InvalidFileException("visibility", "visibility must be PUBLIC or PRIVATE");
    }
}
