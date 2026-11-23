package com.teletronics.storage.infrastructure.storage.s3;

import com.teletronics.storage.application.port.BlobNotFoundException;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.application.port.FileTooLargeException;
import com.teletronics.storage.application.port.StorageUnavailableException;
import com.teletronics.storage.domain.file.StorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * BlobStore on any S3-compatible server (SeaweedFS in compose; RustFS or AWS by configuration).
 * One part is buffered in memory and parts are sent one at a time, so the SDK can retry a failed part from
 * the buffer. A body that fits in the first buffer is written with a single PutObject (Storage decision 2).
 */
public class S3BlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(S3BlobStore.class);
    private static final String BINARY = "application/octet-stream";
    private static final long MAX_BUFFER = Integer.MAX_VALUE - 8;

    private final S3Client s3;
    private final String bucket;
    private final PartSizePolicy parts;

    public S3BlobStore(S3Client s3, String bucket, PartSizePolicy parts) {
        this.s3 = s3;
        this.bucket = bucket;
        this.parts = parts;
    }

    @Override
    public long partSizeFor(OptionalLong declaredLength) {
        return parts.partSizeFor(declaredLength);
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
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key.value()).build());
        } catch (NoSuchKeyException e) {
            throw new BlobNotFoundException(key.value());
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void delete(StorageKey key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.value()).build());
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /** Creates the bucket if it doesn't exist yet (compose and tests start with an empty store). */
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("created bucket {}", bucket);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw translate(e);
            }
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("created bucket {}", bucket);
        }
    }

    private void putObject(StorageKey key, byte[] buffer, int length) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key.value()).contentLength((long) length).build(),
                    body(buffer, length));
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private void multipart(StorageKey key, InputStream body, byte[] buffer, int firstLength) {
        String uploadId;
        try {
            uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key.value()).contentType(BINARY).build()).uploadId();
        } catch (RuntimeException e) {
            throw translate(e);
        }
        try {
            List<CompletedPart> completed = new ArrayList<>();
            int length = firstLength;
            while (length > 0) {
                int partNumber = completed.size() + 1;
                if (partNumber > parts.maxParts()) {
                    throw new FileTooLargeException("the file needs more than " + parts.maxParts()
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

    /** Unreachable store or a 5xx from it: "storage unavailable" (503). Anything else stays as it is (500). */
    private static RuntimeException translate(RuntimeException e) {
        if (e instanceof SdkClientException) {
            return new StorageUnavailableException("object store unreachable", e);
        }
        if (e instanceof S3Exception s3 && s3.statusCode() >= 500) {
            return new StorageUnavailableException("object store failed with " + s3.statusCode(), e);
        }
        return e;
    }
}
