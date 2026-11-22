package com.teletronics.storage.domain.file;

import java.util.Objects;
import java.util.Optional;

/**
 * A listing request: whose files (one owner's, or every PUBLIC file), an optional tag, the sort and the page.
 * {@code _id} always breaks ties (domain decision 11).
 */
public record FileQuery(
        Optional<OwnerId> owner,
        Optional<Tag> tag,
        SortField sortField,
        boolean ascending,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public FileQuery {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(tag);
        Objects.requireNonNull(sortField);
        if (page < 0) {
            throw new InvalidFileException("page", "page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidFileException("size", "size must be between 1 and 100");
        }
    }

    public static FileQuery ownedBy(OwnerId owner) {
        return new FileQuery(Optional.of(owner), Optional.empty(), SortField.UPLOAD_DATE, false, 0, DEFAULT_SIZE);
    }

    public static FileQuery publicFiles() {
        return new FileQuery(Optional.empty(), Optional.empty(), SortField.UPLOAD_DATE, false, 0, DEFAULT_SIZE);
    }

    public boolean isPublicListing() {
        return owner.isEmpty();
    }

    public FileQuery withTag(Tag value) {
        return new FileQuery(owner, Optional.of(value), sortField, ascending, page, size);
    }

    public FileQuery sortedBy(SortField field, boolean asc) {
        return new FileQuery(owner, tag, field, asc, page, size);
    }

    public FileQuery page(int number, int pageSize) {
        return new FileQuery(owner, tag, sortField, ascending, number, pageSize);
    }
}
