package com.teletronics.storage.domain.file.exception;

/**
 * The owner already has a file with this name or this content. Carries what identifies the existing file
 * (id, size, SHA-256) so the caller can recognise it.
 */
public class DuplicateFileException extends RuntimeException {

    public enum Kind { FILENAME, CONTENT }

    /** The file that is already there, as plain values: this package depends on nothing else. */
    public record Existing(String id, long size, String sha256) {
    }

    private final Kind kind;
    private final Existing existing;

    public DuplicateFileException(Kind kind, Existing existing) {
        super(kind == Kind.FILENAME ? "filename already exists" : "content already exists");
        this.kind = kind;
        this.existing = existing;
    }

    public Kind kind() {
        return kind;
    }

    public Existing existing() {
        return existing;
    }
}
