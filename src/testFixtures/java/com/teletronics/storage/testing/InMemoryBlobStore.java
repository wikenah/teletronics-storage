package com.teletronics.storage.testing;

import com.teletronics.storage.application.exception.BlobNotFoundException;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.domain.file.valueobject.StorageKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;

/**
 * A BlobStore in memory, for specs. Part sizes and failures can be set per test.
 */
public class InMemoryBlobStore implements BlobStore {

    public static final long MIB = 1024L * 1024L;

    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private volatile LongUnaryOperator partSizes = length -> 8 * MIB;
    private volatile long unknownLengthPartSize = 64 * MIB;
    private volatile RuntimeException storeFailure;
    private volatile RuntimeException deleteFailure;

    public void partSizes(LongUnaryOperator forKnownLength, long forUnknownLength) {
        this.partSizes = forKnownLength;
        this.unknownLengthPartSize = forUnknownLength;
    }

    /** Every upload with a declared length uses this part size. */
    public void fixedPartSize(long bytes) {
        partSizes(length -> bytes, 64 * MIB);
    }

    /** For budget specs: the declared length, read as MiB, is the part size. */
    public void declaredLengthIsPartSizeInMib() {
        partSizes(length -> length * MIB, 64 * MIB);
    }

    public void failStoresWith(RuntimeException failure) {
        this.storeFailure = failure;
    }

    public void failDeletesWith(RuntimeException failure) {
        this.deleteFailure = failure;
    }

    public Set<String> keys() {
        return Set.copyOf(blobs.keySet());
    }

    public byte[] bytes(StorageKey key) {
        return blobs.get(key.value());
    }

    @Override
    public long partSizeFor(OptionalLong declaredLength) {
        return declaredLength.isPresent() ? partSizes.applyAsLong(declaredLength.getAsLong()) : unknownLengthPartSize;
    }

    @Override
    public void store(StorageKey key, InputStream body, OptionalLong declaredLength) {
        if (storeFailure != null) {
            throw storeFailure;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            body.transferTo(out);
            blobs.put(key.value(), out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(StorageKey key) {
        byte[] bytes = blobs.get(key.value());
        if (bytes == null) {
            throw new BlobNotFoundException(key.value());
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(StorageKey key) {
        if (deleteFailure != null) {
            throw deleteFailure;
        }
        blobs.remove(key.value());
    }
}
