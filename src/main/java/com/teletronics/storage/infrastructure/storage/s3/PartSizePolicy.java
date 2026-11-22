package com.teletronics.storage.infrastructure.storage.s3;

import java.util.OptionalLong;

/**
 * How big each buffered part is (Storage decision 2). Known length: the larger of the minimum and
 * length ÷ maxParts, rounded up to whole MiB. Unknown length (chunked): a fixed larger part.
 */
public record PartSizePolicy(long minPartSize, long unknownLengthPartSize, int maxParts) {

    public static final long MIB = 1024L * 1024L;

    public PartSizePolicy {
        if (minPartSize < 1 || unknownLengthPartSize < 1 || maxParts < 1) {
            throw new IllegalArgumentException("part sizes and part count must be positive");
        }
    }

    /** 8 MiB minimum, 64 MiB for unknown length, S3's limit of 10,000 parts. */
    public static PartSizePolicy standard() {
        return new PartSizePolicy(8 * MIB, 64 * MIB, 10_000);
    }

    public long partSizeFor(OptionalLong declaredLength) {
        if (declaredLength.isEmpty()) {
            return unknownLengthPartSize;
        }
        long length = declaredLength.getAsLong();
        long spread = length / maxParts + (length % maxParts == 0 ? 0 : 1);
        long wholeMib = (spread + MIB - 1) / MIB * MIB;
        return Math.max(minPartSize, wholeMib);
    }
}
