package com.teletronics.storage.testing;

import com.teletronics.storage.domain.file.ContentType;
import com.teletronics.storage.domain.file.DownloadToken;
import com.teletronics.storage.domain.file.FileId;
import com.teletronics.storage.domain.file.Filename;
import com.teletronics.storage.domain.file.OwnerId;
import com.teletronics.storage.domain.file.Sha256;
import com.teletronics.storage.domain.file.StorageKey;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.Tags;
import com.teletronics.storage.domain.file.Visibility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

/**
 * Sample StoredFile values for specs that fake the file service.
 */
public final class StoredFiles {

    public static final Instant UPLOADED_AT = Instant.parse("2026-11-23T10:00:00Z");

    private StoredFiles() {
    }

    public static StoredFile file(String owner, String filename) {
        return file(owner, filename, "application/pdf", filename.getBytes(StandardCharsets.UTF_8));
    }

    /** A file whose size and SHA-256 match the given content. */
    public static StoredFile file(String owner, String filename, String contentType, byte[] content) {
        return new StoredFile(FileId.newId(), OwnerId.of(owner), Filename.of(filename), Visibility.PRIVATE,
                Tags.of(List.of("work")), content.length, sha256(content), new ContentType(contentType),
                UPLOADED_AT, DownloadToken.generate(), StorageKey.random());
    }

    public static Sha256 sha256(byte[] content) {
        try {
            return Sha256.of(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
