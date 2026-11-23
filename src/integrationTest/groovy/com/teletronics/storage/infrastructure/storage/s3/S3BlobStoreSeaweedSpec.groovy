package com.teletronics.storage.infrastructure.storage.s3

import com.teletronics.storage.application.exception.BlobNotFoundException
import com.teletronics.storage.domain.file.valueobject.StorageKey
import com.teletronics.storage.testing.SeaweedFsContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import spock.lang.Shared
import spock.lang.Specification

import java.security.MessageDigest

import static com.teletronics.storage.infrastructure.storage.s3.PartSizePolicy.MIB
import static com.teletronics.storage.testing.TestBytes.random

/**
 * The S3 adapter on a real SeaweedFS (docs/acceptance-criteria/storage.md decisions 1, 2, 4).
 * Also the build-time spike: multipart, abort, a 0-byte object.
 */
class S3BlobStoreSeaweedSpec extends Specification {

    @Shared
    SeaweedFsContainer seaweed = new SeaweedFsContainer()

    @Shared
    S3Client s3

    @Shared
    S3BlobStore store

    def setupSpec() {
        seaweed.start()
        s3 = S3Client.builder()
                .endpointOverride(URI.create(seaweed.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(SeaweedFsContainer.ACCESS_KEY, SeaweedFsContainer.SECRET_KEY)))
                .forcePathStyle(true)
                .httpClientBuilder(Apache5HttpClient.builder())
                .build()
        store = new S3BlobStore(s3, "files", PartSizePolicy.standard())
        store.ensureBucket()
    }

    def cleanupSpec() {
        s3?.close()
        seaweed.stop()
    }

    def "STO-01 the S3 adapter stores, reads and deletes bytes on SeaweedFS (#description)"() {
        given: "a new random key"
        def key = StorageKey.random()
        byte[] content = random(size)

        when: "the adapter writes a stream of #description under it"
        store.store(key, new ByteArrayInputStream(content), declared ? OptionalLong.of(size) : OptionalLong.empty())

        then: "reading the key back returns identical bytes (same SHA-256)"
        sha256(store.open(key)) == sha256(new ByteArrayInputStream(content))

        when: "the key is deleted"
        store.delete(key)
        store.open(key)

        then: "reading it reports not found"
        thrown(BlobNotFoundException)

        where:
        description                          | size          | declared
        "1 KiB"                              | 1024          | true
        "20 MiB in three 8 MiB parts"        | 20 * MIB      | true
        "0 bytes (STO-03)"                   | 0             | true
        "0 bytes of unknown length (STO-03)" | 0             | false
        "exactly one part"                   | 8 * MIB       | true
    }

    def "STO-05 a multipart write of unknown length reads back identical"() {
        given: "an adapter with small parts, so a 21 MiB chunked body takes five parts"
        def small = new S3BlobStore(s3, "files", new PartSizePolicy(5 * MIB, 5 * MIB, 10_000))
        def key = StorageKey.random()
        byte[] content = random(21 * MIB)

        when:
        small.store(key, new ByteArrayInputStream(content), OptionalLong.empty())

        then:
        sha256(store.open(key)) == sha256(new ByteArrayInputStream(content))

        cleanup:
        store.delete(key)
    }

    def "STO-08 a write that fails after some parts is aborted and leaves nothing behind"() {
        given: "a body that breaks after 20 MiB, so two 8 MiB parts have been sent"
        def key = StorageKey.random()
        def body = new BreakingInputStream(random(20 * MIB))

        when: "the adapter writes it"
        store.store(key, body, OptionalLong.of(40 * MIB))

        then: "the write fails"
        thrown(UncheckedIOException)

        and: "no object exists under the key and no multipart upload is left open"
        when:
        store.open(key)
        then:
        thrown(BlobNotFoundException)
        s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket("files").prefix(key.value()).build()).uploads().isEmpty()
    }

    def "deleting a key that is already gone succeeds"() {
        when:
        store.delete(StorageKey.random())

        then:
        noExceptionThrown()
    }

    // --- helpers -------------------------------------------------------------------------------------

    static String sha256(InputStream in) {
        def digest = MessageDigest.getInstance("SHA-256")
        in.withCloseable { stream ->
            def buffer = new byte[64 * 1024]
            int n
            while ((n = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().encodeHex().toString()
    }

    /** Delivers its content, then fails as a dropped client connection would. */
    static class BreakingInputStream extends ByteArrayInputStream {
        BreakingInputStream(byte[] content) {
            super(content)
        }

        @Override
        synchronized int read(byte[] b, int off, int len) {
            int n = super.read(b, off, len)
            if (n == -1) {
                throw new IOException("connection reset by client")
            }
            n
        }
    }
}
