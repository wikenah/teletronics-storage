package com.teletronics.storage.domain.file;

/**
 * A media type such as "application/pdf". Either declared by the client or detected (domain decision 9).
 */
public record ContentType(String value) {

    public static final ContentType OCTET_STREAM = new ContentType("application/octet-stream");

    public ContentType {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content type must not be blank");
        }
    }

    /** "application/octet-stream" means "unknown", the same as no type at all. */
    public boolean isGeneric() {
        return this.equals(OCTET_STREAM);
    }
}
