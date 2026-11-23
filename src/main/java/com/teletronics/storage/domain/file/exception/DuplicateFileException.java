package com.teletronics.storage.domain.file.exception;

import com.teletronics.storage.domain.file.StoredFile;

/**
 * The owner already has a file with this name or this content. Carries the existing file so the
 * caller can recognise it (size, SHA-256).
 */
public class DuplicateFileException extends RuntimeException {

    public enum Kind { FILENAME, CONTENT }

    private final Kind kind;
    private final StoredFile existing;

    public static DuplicateFileException filename(StoredFile existing) {
        return new DuplicateFileException(Kind.FILENAME, existing);
    }

    public static DuplicateFileException content(StoredFile existing) {
        return new DuplicateFileException(Kind.CONTENT, existing);
    }

    public DuplicateFileException(Kind kind, StoredFile existing) {
        super(kind == Kind.FILENAME ? "filename already exists" : "content already exists");
        this.kind = kind;
        this.existing = existing;
    }

    public Kind kind() {
        return kind;
    }

    public StoredFile existing() {
        return existing;
    }
}
