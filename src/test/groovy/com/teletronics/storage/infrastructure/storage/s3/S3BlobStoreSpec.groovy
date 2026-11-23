package com.teletronics.storage.infrastructure.storage.s3

import com.teletronics.storage.application.exception.FileTooLargeException
import com.teletronics.storage.application.exception.StorageUnavailableException
import com.teletronics.storage.domain.file.valueobject.StorageKey
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import spock.lang.Specification

import java.security.MessageDigest

import static com.teletronics.storage.infrastructure.storage.s3.S3PartSizePolicy.MIB
import static com.teletronics.storage.testing.TestBytes.random

/**
 * The S3 write loop with a fake S3 client that records every call
 * (docs/acceptance-criteria/storage.md decisions 2 and 4).
 */
class S3BlobStoreSpec extends Specification {

    def s3 = Mock(S3Client)
    def store = new S3BlobStore(s3, "files", S3PartSizePolicy.standard())
    def key = StorageKey.random()

    def "STO-04 a body that fits in the first buffer is written with one PutObject (#length)"() {
        given: "the part size for the upload is 8 MiB"
        byte[] content = random(1024)
        byte[] stored = null

        when: "the body ends before 8 MiB have arrived"
        store.store(key, new ByteArrayInputStream(content), length)

        then: "exactly one PutObject is sent"
        1 * s3.putObject({ PutObjectRequest r -> r.bucket() == "files" && r.key() == key.value() && r.contentLength() == 1024L }, _ as RequestBody) >>
                { PutObjectRequest r, RequestBody body -> stored = read(body); PutObjectResponse.builder().build() }

        and: "no multipart call is made"
        0 * s3.createMultipartUpload(_)
        0 * s3.uploadPart(*_)

        and: "the stored bytes equal the body"
        stored == content

        where:
        length << [OptionalLong.of(1024), OptionalLong.empty()]
    }

    def "STO-03 an empty body is written as one empty object, never as multipart (#length)"() {
        given:
        byte[] stored = null

        when: "the adapter is given a body stream that ends immediately"
        store.store(key, new ByteArrayInputStream(new byte[0]), length)

        then: "exactly one PutObject with an empty body is sent and no multipart call is made"
        1 * s3.putObject({ PutObjectRequest r -> r.contentLength() == 0L }, _ as RequestBody) >>
                { PutObjectRequest r, RequestBody body -> stored = read(body); PutObjectResponse.builder().build() }
        0 * s3.createMultipartUpload(_)
        stored.length == 0

        where:
        length << [OptionalLong.of(0), OptionalLong.empty()]
    }

    def "STO-05 part size follows the declared length (#description)"() {
        expect: "every part except the last is #expected and at most 10,000 parts are used"
        S3PartSizePolicy.standard().partSizeFor(length) == expected
        length.isEmpty() || Math.ceil(length.asLong / (double) expected) <= 10_000

        where:
        description | length                                  || expected
        "2 GiB"     | OptionalLong.of(2L * 1024 * MIB)        || 8 * MIB
        "100 GB"    | OptionalLong.of(100_000_000_000L)       || 10 * MIB
        "1 TB"      | OptionalLong.of(1_000_000_000_000L)     || 96 * MIB
        "1 KiB"     | OptionalLong.of(1024)                   || 8 * MIB
        "unknown"   | OptionalLong.empty()                    || 64 * MIB
    }

    def "STO-06 parts are sent one at a time from a single buffer"() {
        given: "a 50 MiB body with part size 8 MiB"
        byte[] content = random(50 * MIB)
        def partNumbers = []
        def partSizes = []
        def assembled = MessageDigest.getInstance("SHA-256")
        def inFlight = 0
        def maxInFlight = 0

        when: "the adapter writes it"
        store.store(key, new ByteArrayInputStream(content), OptionalLong.of(content.length))

        then: "a multipart upload is started"
        1 * s3.createMultipartUpload({ CreateMultipartUploadRequest r -> r.key() == key.value() }) >>
                CreateMultipartUploadResponse.builder().uploadId("u1").build()

        and: "7 UploadPart calls are made in order, never two at once"
        7 * s3.uploadPart({ UploadPartRequest r -> r.uploadId() == "u1" }, _ as RequestBody) >> { UploadPartRequest r, RequestBody body ->
            inFlight++
            maxInFlight = Math.max(maxInFlight, inFlight)
            partNumbers << r.partNumber()
            partSizes << r.contentLength()
            assembled.update(read(body))
            inFlight--
            UploadPartResponse.builder().eTag("etag-" + r.partNumber()).build()
        }
        partNumbers == (1..7).toList()
        partSizes == [8 * MIB] * 6 + [2 * MIB]
        maxInFlight == 1

        and: "the upload is completed with the parts in order, and the parts add up to the body"
        1 * s3.completeMultipartUpload({ CompleteMultipartUploadRequest r ->
            r.multipartUpload().parts()*.partNumber() == (1..7).toList() &&
                    r.multipartUpload().parts()*.eTag() == (1..7).collect { "etag-$it" }
        }) >> CompleteMultipartUploadResponse.builder().build()
        assembled.digest() == MessageDigest.getInstance("SHA-256").digest(content)
        0 * s3.putObject(*_)
        0 * s3.abortMultipartUpload(_)
    }

    def "STO-07 every part can be sent again from the same buffer"() {
        given: "a 20 MiB body in 8 MiB parts"
        byte[] content = random(20 * MIB)
        s3.createMultipartUpload(_) >> CreateMultipartUploadResponse.builder().uploadId("u1").build()
        s3.completeMultipartUpload(_) >> CompleteMultipartUploadResponse.builder().build()
        def resent = []

        when: "the adapter writes it"
        store.store(key, new ByteArrayInputStream(content), OptionalLong.of(content.length))

        then: "a second attempt at any part reads exactly the bytes of the first (what an SDK retry does)"
        3 * s3.uploadPart(_, _) >> { UploadPartRequest r, RequestBody body ->
            resent << (read(body) == read(body))
            UploadPartResponse.builder().eTag("e").build()
        }
        resent == [true, true, true]
    }

    def "STO-08 when a part fails for good, the multipart upload is aborted (#failure)"() {
        given: "the store fails part 3 on every attempt"
        byte[] content = random(30 * MIB)
        s3.createMultipartUpload(_) >> CreateMultipartUploadResponse.builder().uploadId("u1").build()
        s3.uploadPart({ UploadPartRequest r -> r.partNumber() < 3 }, _) >> UploadPartResponse.builder().eTag("e").build()
        s3.uploadPart({ UploadPartRequest r -> r.partNumber() == 3 }, _) >> { throw error }

        when: "the adapter writes the body"
        store.store(key, new ByteArrayInputStream(content), OptionalLong.of(content.length))

        then: "the failure is reported as #reported"
        thrown(reported)

        and: "AbortMultipartUpload is called and the upload is never completed"
        1 * s3.abortMultipartUpload({ AbortMultipartUploadRequest r -> r.uploadId() == "u1" && r.key() == key.value() })
        0 * s3.completeMultipartUpload(_)

        where:
        failure                 | error                                                                            || reported
        "store unreachable"     | SdkClientException.create("connection refused")                                  || StorageUnavailableException
        "store failing (5xx)"   | S3Exception.builder().statusCode(503).message("slow down").build()             || StorageUnavailableException
        "store refusing (4xx)"  | S3Exception.builder().statusCode(403).message("access denied").build()         || S3Exception
    }

    def "STO-08 a client that disconnects mid-body aborts the multipart upload"() {
        given: "a body that fails after two full parts"
        def body = new SequenceInputStream(new ByteArrayInputStream(random(16 * MIB)), new FailingInputStream())
        s3.createMultipartUpload(_) >> CreateMultipartUploadResponse.builder().uploadId("u1").build()
        s3.uploadPart(_, _) >> UploadPartResponse.builder().eTag("e").build()

        when:
        store.store(key, body, OptionalLong.of(40 * MIB))

        then:
        thrown(UncheckedIOException)
        1 * s3.abortMultipartUpload(_)
        0 * s3.completeMultipartUpload(_)
    }

    def "STO-10 a chunked upload that needs more parts than allowed is aborted and refused as too large"() {
        given: "parts of 4 bytes and at most 3 parts"
        def tiny = new S3BlobStore(s3, "files", new S3PartSizePolicy(4, 4, 3))
        s3.createMultipartUpload(_) >> CreateMultipartUploadResponse.builder().uploadId("u1").build()

        when: "a 13-byte body of unknown length is written"
        tiny.store(key, new ByteArrayInputStream(random(13)), OptionalLong.empty())

        then: "three parts are sent, then the upload is aborted and refused as too large"
        3 * s3.uploadPart(_, _) >> UploadPartResponse.builder().eTag("e").build()
        1 * s3.abortMultipartUpload(_)
        0 * s3.completeMultipartUpload(_)
        thrown(FileTooLargeException)
    }

    def "an unreachable store on a single PutObject is reported as storage unavailable"() {
        given:
        s3.putObject(_, _) >> { throw SdkClientException.create("connection refused") }

        when:
        store.store(key, new ByteArrayInputStream("small".bytes), OptionalLong.of(5))

        then:
        thrown(StorageUnavailableException)
    }

    // --- helpers -------------------------------------------------------------------------------------

    static byte[] read(RequestBody body) {
        body.contentStreamProvider().newStream().withCloseable { it.readAllBytes() }
    }

    static class FailingInputStream extends InputStream {
        @Override
        int read() {
            throw new IOException("connection reset by client")
        }
    }
}
