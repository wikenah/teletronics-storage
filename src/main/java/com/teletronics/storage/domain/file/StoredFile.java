package com.teletronics.storage.domain.file;

import com.teletronics.storage.domain.file.exception.DuplicateFileException;
import com.teletronics.storage.domain.file.valueobject.ContentType;
import com.teletronics.storage.domain.file.valueobject.DownloadToken;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.Filename;
import com.teletronics.storage.domain.file.valueobject.UserId;
import com.teletronics.storage.domain.file.valueobject.Sha256;
import com.teletronics.storage.domain.file.valueobject.StorageKey;
import com.teletronics.storage.domain.file.valueobject.Tags;
import com.teletronics.storage.domain.file.valueobject.Visibility;

import java.time.Instant;
import java.util.Objects;

/**
 * The file aggregate: the metadata of one completely stored file. A record exists only once its bytes
 * are stored (domain decision 1).
 */
public record StoredFile(
        FileId id,
        UserId owner,
        Filename filename,
        Visibility visibility,
        Tags tags,
        long size,
        Sha256 sha256,
        ContentType contentType,
        Instant uploadedAt,
        DownloadToken downloadToken,
        StorageKey storageKey) {

    public StoredFile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(filename);
        Objects.requireNonNull(visibility);
        Objects.requireNonNull(tags);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(uploadedAt);
        Objects.requireNonNull(downloadToken);
        Objects.requireNonNull(storageKey);
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }

    /** A newly stored file: the domain gives it its identity and its download token. */
    public static StoredFile create(UserId owner, Filename filename, Visibility visibility, Tags tags, long size,
                                    Sha256 sha256, ContentType contentType, Instant uploadedAt, StorageKey storageKey) {
        return new StoredFile(FileId.newId(), owner, filename, visibility, tags, size, sha256, contentType,
                uploadedAt, DownloadToken.generate(), storageKey);
    }

    /** The refusal of an upload or rename that would duplicate this file, by name or by content. */
    public DuplicateFileException alreadyExists(DuplicateFileException.Kind kind) {
        return new DuplicateFileException(kind,
                new DuplicateFileException.Existing(id.value(), size, sha256.hex()));
    }

    public boolean isOwnedBy(UserId candidate) {
        return owner.equals(candidate);
    }

    /** Only the name changes; bytes, hash, type, upload date and link stay (domain decision 6). */
    public StoredFile renamedTo(Filename newName) {
        return new StoredFile(id, owner, newName, visibility, tags, size, sha256, contentType,
                uploadedAt, downloadToken, storageKey);
    }
}
