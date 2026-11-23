package com.teletronics.storage.domain.file.query;

import com.teletronics.storage.domain.file.exception.InvalidFileException;
import com.teletronics.storage.domain.file.valueobject.OwnerId;
import com.teletronics.storage.domain.file.valueobject.Tag;
import com.teletronics.storage.domain.file.valueobject.Visibility;

import java.util.Objects;
import java.util.Optional;

/**
 * A listing request: the filters (an owner, a visibility, a tag; each optional), the sort and the page.
 * The two listings the service offers are {@link #ownedBy} (all of one owner's files) and
 * {@link #publicFiles} (every owner's PUBLIC files). {@code _id} always breaks ties (domain decision 11).
 */
public record FileQuery(
        Optional<OwnerId> owner,
        Optional<Visibility> visibility,
        Optional<Tag> tag,
        SortField sortField,
        boolean ascending,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public FileQuery {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(visibility);
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
        return new FileQuery(Optional.of(owner), Optional.empty(), Optional.empty(), SortField.UPLOAD_DATE, false, 0, DEFAULT_SIZE);
    }

    public static FileQuery publicFiles() {
        return new FileQuery(Optional.empty(), Optional.of(Visibility.PUBLIC), Optional.empty(), SortField.UPLOAD_DATE, false, 0, DEFAULT_SIZE);
    }

    public FileQuery withTag(Tag value) {
        return new FileQuery(owner, visibility, Optional.of(value), sortField, ascending, page, size);
    }

    public FileQuery sortedBy(SortField field, boolean asc) {
        return new FileQuery(owner, visibility, tag, field, asc, page, size);
    }

    public FileQuery page(int number, int pageSize) {
        return new FileQuery(owner, visibility, tag, sortField, ascending, number, pageSize);
    }
}
