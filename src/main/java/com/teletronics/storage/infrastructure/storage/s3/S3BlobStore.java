package com.teletronics.storage.infrastructure.storage.s3;

import com.teletronics.storage.application.exception.BlobNotFoundException;
import com.teletronics.storage.application.exception.FileTooLargeException;
import com.teletronics.storage.application.exception.StorageUnavailableException;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.domain.file.valueobject.StorageKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * BlobStore on any S3-compatible server (SeaweedFS in compose; RustFS or AWS by configuration).
 * One part is buffered in memory and parts are sent one at a time, so the SDK can retry a failed part from
 * the buffer. A body that fits in the first buffer is written with a single PutObject (Storage decision 2).
 */
@Slf4j
@RequiredArgsConstructor
public class S3BlobStore implements BlobStore {
    private static final String BINARY = "application/octet-stream";
    private static final long MAX_BUFFER = Integer.MAX_VALUE - 8;

    private final S3Client s3;
    private final String bucket;
    private final S3PartSizePolicy partSizePolicy;

    @Override
    public long partSizeFor(OptionalLong declaredLength) {
        return partSizePolicy.partSizeFor(declaredLength);
    }

    @Override
    public void store(StorageKey key, InputStream body, OptionalLong declaredLength) {
        long partSize = partSizeFor(declaredLength);
        if (partSize > MAX_BUFFER) {
            throw new FileTooLargeException("a part of " + partSize + " bytes can't be buffered");
        }
        // A declared length below one part needs only that much buffer; the body can't be longer.
        int capacity = (int) (declaredLength.isPresent() ? Math.min(partSize, declaredLength.getAsLong()) : partSize);
        byte[] buffer = new byte[capacity];
        int filled = fill(body, buffer);
        if (filled < partSize) {
            putObject(key, buffer, filled);
        } else {
            multipart(key, body, buffer, filled);
        }
    }

    @Override
    public InputStream open(StorageKey key) {
        try {
            return call(() -> s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key.value()).build()));
        } catch (NoSuchKeyException e) {
            throw new BlobNotFoundException(key.value());
        }
    }

    @Override
    public void delete(StorageKey key) {
        call(() -> s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.value()).build()));
    }

    /** Creates the bucket if it doesn't exist yet (compose and tests start with an empty store). */
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            // Some servers answer a missing bucket with a bare 404 instead of NoSuchBucket.
            if (!(e instanceof NoSuchBucketException) && e.statusCode() != 404) {
                throw translate(e);
            }
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("created bucket {}", bucket);
        }
    }

    private void putObject(StorageKey key, byte[] buffer, int length) {
        call(() -> s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key.value()).contentLength((long) length).build(),
                body(buffer, length)));
    }

    private void multipart(StorageKey key, InputStream body, byte[] buffer, int firstLength) {
        String uploadId = call(() -> s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key.value()).contentType(BINARY).build())).uploadId();
        try {
            List<CompletedPart> completed = new ArrayList<>();
            int length = firstLength;
            while (length > 0) {
                int partNumber = completed.size() + 1;
                if (partNumber > partSizePolicy.maxParts()) {
                    throw new FileTooLargeException("the file needs more than " + partSizePolicy.maxParts()
                            + " parts of " + buffer.length + " bytes");
                }
                String eTag = s3.uploadPart(UploadPartRequest.builder()
                                .bucket(bucket).key(key.value()).uploadId(uploadId)
                                .partNumber(partNumber).contentLength((long) length).build(),
                        body(buffer, length)).eTag();
                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
                length = fill(body, buffer);
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key.value()).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        } catch (RuntimeException e) {
            abort(key, uploadId);
            throw translate(e);
        }
    }

    private void abort(StorageKey key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key.value()).uploadId(uploadId).build());
        } catch (RuntimeException e) {
            log.error("orphaned multipart upload: key {} upload id {} could not be aborted", key.value(), uploadId, e);
        }
    }

    /**
     * Each attempt (including the SDK's retries) opens a fresh stream over the same buffer: no copy.
     * RequestBody.fromBytes and the ByteBuffer variants copy the data.
     */
    private static RequestBody body(byte[] buffer, int length) {
        return RequestBody.fromContentProvider(() -> new ByteArrayInputStream(buffer, 0, length), length, BINARY);
    }

    /** Reads until the buffer is full or the body ends; returns how many bytes were read. */
    private static int fill(InputStream body, byte[] buffer) {
        try {
            return body.readNBytes(buffer, 0, buffer.length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs one S3 request and translates its failure, so no SDK exception type leaks out of this class. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /**
     * The store's fault (unreachable, or it answered 5xx) becomes "storage unavailable" (503: retry later).
     * Anything else is ours, a bad request or a missing key, and stays as it is (500 unless the caller handles it).
     */
    private static RuntimeException translate(RuntimeException e) {
        if (neverReachedTheStore(e)) {
            return new StorageUnavailableException("object store unreachable", e);
        }
        if (e instanceof S3Exception failure && isServerError(failure)) {
            return new StorageUnavailableException("object store failed with " + failure.statusCode(), e);
        }
        return e;
    }

    private static boolean neverReachedTheStore(RuntimeException e) {
        return e instanceof SdkClientException;
    }

    private static boolean isServerError(S3Exception failure) {
        return failure.statusCode() >= 500;
    }
}
