package com.teletronics.storage.application.port;

import com.teletronics.storage.application.exception.BlobNotFoundException;
import com.teletronics.storage.application.exception.FileTooLargeException;
import com.teletronics.storage.application.exception.StorageUnavailableException;
import com.teletronics.storage.domain.file.valueobject.StorageKey;

import java.io.InputStream;
import java.util.OptionalLong;

/**
 * Where file bytes live (an outbound port). Adapters: the S3-compatible store (SeaweedFS), and documented
 * alternatives (RustFS by configuration, GridFS as another adapter).
 */
public interface BlobStore {

    /**
     * The part buffer size, in bytes, that writing a body of this declared length will hold in memory.
     * The upload budget reserves this much before the body is read (Storage decision 2).
     */
    long partSizeFor(OptionalLong declaredLength);

    /**
     * Writes the whole stream under the key. On any failure nothing is left behind (the write is aborted).
     *
     * @throws StorageUnavailableException when the store can't be reached
     * @throws FileTooLargeException       when a body of unknown length outgrows the part limit
     */
    void store(StorageKey key, InputStream body, OptionalLong declaredLength);

    /** @throws BlobNotFoundException when nothing is stored under the key */
    InputStream open(StorageKey key);

    /** Deleting a key that is already gone counts as success. */
    void delete(StorageKey key);
}
