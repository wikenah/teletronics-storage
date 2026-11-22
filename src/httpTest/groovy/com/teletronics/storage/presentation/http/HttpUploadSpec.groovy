package com.teletronics.storage.presentation.http

import com.teletronics.storage.application.FileService
import com.teletronics.storage.application.FileTooLargeException
import com.teletronics.storage.application.PendingUpload
import com.teletronics.storage.application.UploadCapacityReachedException
import com.teletronics.storage.application.UploadCommand
import com.teletronics.storage.domain.file.ContentType
import com.teletronics.storage.domain.file.DownloadToken
import com.teletronics.storage.domain.file.DuplicateFileException
import com.teletronics.storage.domain.file.FileId
import com.teletronics.storage.domain.file.Filename
import com.teletronics.storage.domain.file.OwnerId
import com.teletronics.storage.domain.file.Sha256
import com.teletronics.storage.domain.file.StorageKey
import com.teletronics.storage.domain.file.StoredFile
import com.teletronics.storage.domain.file.Tags
import com.teletronics.storage.domain.file.Visibility
import com.teletronics.storage.testing.RawHttpClient
import com.teletronics.storage.testing.StoredFiles
import org.spockframework.spring.SpringBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * The upload endpoint on a real Tomcat with the file service faked
 * (docs/acceptance-criteria/http-api.md sections A, B, C, D, E; test level HTTP).
 * The suite runs with a 256 MB heap (build.gradle, httpTest).
 */
@SpringBootTest(classes = HttpLayerTestApplication, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpUploadSpec extends Specification {

    static final long MIB = 1024L * 1024L

    @LocalServerPort
    int port

    @SpringBean
    FileService files = Mock()

    def conditions = new PollingConditions(timeout: 10)

    static final Map<String, String> VALID = [
            "X-User-Id"   : "alice",
            "Content-Type": "application/octet-stream",
    ]
    static final String TARGET = "/files?filename=big.bin&visibility=PRIVATE"

    // --- A. Rejections arrive before the body --------------------------------------------------------

    def "API-06 rejections arrive before the body is sent (#rejection)"() {
        given: "a request that the HTTP layer rejects, sent with Expect: 100-continue"
        def client = new RawHttpClient(port)

        when: "the request headers are sent and the client waits for the interim response"
        client.sendHead("POST", target, VALID + headers + ["Content-Length": "1048576", "Expect": "100-continue"])
        def response = client.readResponse()

        then: "the client receives the final #status without a 100 Continue"
        response.status == status
        response.problemType() == type

        and: "the client sent zero body bytes and the connection is closed"
        client.bodyBytesSent == 0
        response.header("Connection") == "close"

        and: "the file service is not called"
        0 * files._

        cleanup:
        client?.close()

        where:
        rejection             | target                                              | headers                                            || status | type
        "no user id"          | TARGET                                              | ["X-User-Id": ""]                                  || 400    | "/problems/invalid-request"
        "no filename"         | "/files?visibility=PRIVATE"                         | [:]                                                || 400    | "/problems/invalid-request"
        "bad visibility"      | "/files?filename=a&visibility=SECRET"               | [:]                                                || 400    | "/problems/invalid-request"
        "six tags"            | TARGET + "&tags=a&tags=b&tags=c&tags=d&tags=e&tags=f" | [:]                                              || 400    | "/problems/invalid-request"
        "a form body"         | TARGET                                              | ["Content-Type": "multipart/form-data; boundary=x"] || 415    | "about:blank"
        "a compressed body"   | TARGET                                              | ["Content-Encoding": "gzip"]                       || 415    | "/problems/unsupported-content-encoding"
    }

    def "API-13 filename taken, detected before the body (client sends Expect)"() {
        given: "the file service reports, before the body is read, that alice already has a file E named \"report.pdf\""
        def existing = StoredFiles.file("alice", "report.pdf")
        def pending = Mock(PendingUpload)
        files.startUpload(_) >> { throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, existing) }

        and: "the client sends Expect: 100-continue and waits"
        def client = new RawHttpClient(port)

        when: "alice uploads \"report.pdf\""
        client.sendHead("POST", "/files?filename=report.pdf&visibility=PRIVATE",
                VALID + ["Content-Length": "1048576", "Expect": "100-continue"])
        def response = client.readResponse()

        then: "the response is 409 with type /problems/filename-exists"
        response.status == 409
        response.problemType() == "/problems/filename-exists"

        and: "it carries existingFile with E's id, size and SHA-256"
        response.json().existingFile == [id: existing.id().value(), size: existing.size(), sha256: existing.sha256().hex()]

        and: "no 100 Continue was sent, the client sent zero body bytes, and the connection is closed"
        client.bodyBytesSent == 0
        response.header("Connection") == "close"

        and: "the file service never read the body"
        0 * pending.complete(_)

        cleanup:
        client?.close()
    }

    def "API-21 upload capacity reached: 503 before the body"() {
        given: "the file service reports that the upload's part buffer does not fit in the remaining budget"
        files.startUpload(_) >> { throw new UploadCapacityReachedException() }
        def client = new RawHttpClient(port)

        when: "a valid upload arrives with Expect: 100-continue"
        client.sendHead("POST", TARGET, VALID + ["Content-Length": "1048576", "Expect": "100-continue"])
        def response = client.readResponse()

        then: "the response is 503 with type /problems/upload-capacity-reached"
        response.status == 503
        response.problemType() == "/problems/upload-capacity-reached"

        and: "no 100 Continue was sent and the client sent zero body bytes"
        client.bodyBytesSent == 0

        cleanup:
        client?.close()
    }

    def "API-32 a file that can never fit is rejected up front"() {
        given: "the file service reports that the declared Content-Length needs a part larger than the budget"
        files.startUpload(_) >> { throw new FileTooLargeException("the file is too large to store with bounded memory") }
        def client = new RawHttpClient(port)

        when: "the upload's headers arrive with Expect: 100-continue"
        client.sendHead("POST", TARGET, VALID + ["Content-Length": String.valueOf(3L * 1024 * 1024 * 1024 * 1024), "Expect": "100-continue"])
        def response = client.readResponse()

        then: "the response is 413 with type /problems/file-too-large"
        response.status == 413
        response.problemType() == "/problems/file-too-large"

        and: "the client sent zero body bytes"
        client.bodyBytesSent == 0

        cleanup:
        client?.close()
    }

    // --- B. What reaches the file service -----------------------------------------------------------

    def "API-09 body without Content-Length (chunked)"() {
        given: "a valid upload request with Transfer-Encoding: chunked and no Content-Length"
        def upload = new RecordingUpload()
        files.startUpload(_) >> { UploadCommand c -> upload.command = c; upload }
        def client = new RawHttpClient(port)
        def chunk = new byte[64 * 1024]

        when: "5 MiB are sent"
        client.sendHead("POST", TARGET, VALID + ["Transfer-Encoding": "chunked"])
        80.times { client.sendChunk(chunk) }
        client.endChunks()
        def response = client.readResponse()

        then: "the file service receives an unknown declared length"
        upload.command.declaredLength().isEmpty()

        and: "the body stream yields exactly the 5 MiB sent and then ends normally"
        upload.outcome == "ended"
        upload.count == 5 * MIB
        response.status == 201

        cleanup:
        client?.close()
    }

    def "API-10 empty body (#framing)"() {
        given: "a valid upload request"
        def upload = new RecordingUpload()
        files.startUpload(_) >> upload
        def client = new RawHttpClient(port)

        when: "the body is empty, sent with #framing"
        client.sendHead("POST", TARGET, VALID + headers)
        if (chunked) {
            client.endChunks()
        }
        def response = client.readResponse()

        then: "the file service receives a body stream that ends immediately and normally"
        upload.outcome == "ended"
        upload.count == 0
        response.status == 201

        cleanup:
        client?.close()

        where:
        framing             | headers                            | chunked
        "Content-Length: 0" | ["Content-Length": "0"]            | false
        "chunked, no data"  | ["Transfer-Encoding": "chunked"]   | true
    }

    def "API-11 a body cut short is never a complete upload (#framing)"() {
        given: "an upload of a 50 MiB body sent with #framing"
        def upload = new RecordingUpload()
        files.startUpload(_) >> upload
        def client = new RawHttpClient(port)
        def piece = new byte[64 * 1024]

        when: "the client closes the connection after 25 MiB"
        client.sendHead("POST", TARGET, VALID + headers)
        400.times { chunked ? client.sendChunk(piece) : client.sendBody(piece) }
        client.flush()
        conditions.eventually { assert upload.count > 0 }
        client.close()

        then: "the body stream the file service reads fails with an error"
        conditions.eventually { assert upload.outcome?.startsWith("failed") }

        and: "it never ends normally after 25 MiB, so no success response is produced"
        upload.outcome != "ended"
        upload.count <= 25 * MIB

        where:
        framing                      | headers                              | chunked
        "Content-Length: 52428800"   | ["Content-Length": "52428800"]       | false
        "chunked"                    | ["Transfer-Encoding": "chunked"]     | true
    }

    // --- D. Streaming -------------------------------------------------------------------------------

    def "API-17 valid request with Expect: 100-continue"() {
        given: "a valid upload for a filename the file service reports as free; admitting it takes a moment"
        def upload = new RecordingUpload()
        long admittedAt = 0
        files.startUpload(_) >> { Thread.sleep(300); admittedAt = System.nanoTime(); upload }

        and: "the client sends Expect: 100-continue and waits"
        def client = new RawHttpClient(port)
        def body = "x".multiply(4096).bytes

        when: "the request headers are sent"
        client.sendHead("POST", TARGET, VALID + ["Content-Length": String.valueOf(body.length), "Expect": "100-continue"])
        def interim = client.readResponse()
        long continuedAt = System.nanoTime()

        then: "the server sends 100 Continue only once the file service starts reading the body"
        interim.status == 100
        admittedAt != 0 && admittedAt < continuedAt

        when: "the body is sent"
        client.sendBody(body)
        client.flush()
        def response = client.readResponse()

        then: "the response is 201 Created"
        response.status == 201
        upload.count == body.length

        cleanup:
        client?.close()
    }

    def "API-18 [BRIEF] 2 GiB streamed through the HTTP layer with a small heap"() {
        given: "the application runs with a 256 MB maximum heap"
        assert Runtime.runtime.maxMemory() <= 256 * MIB

        and: "the file service is faked to read the body to the end while counting and hashing it"
        def upload = new RecordingUpload()
        files.startUpload(_) >> upload

        and: "the client generates the 2 GiB body on the fly"
        long total = 2L * 1024 * MIB
        def piece = new byte[64 * 1024]
        new Random(42).nextBytes(piece)
        def clientSha = MessageDigest.getInstance("SHA-256")
        def tmp = Path.of(System.getProperty("java.io.tmpdir"))
        long tmpBefore = sizeOf(tmp)
        long tmpPeak = tmpBefore
        def client = new RawHttpClient(port, 120_000)

        when: "the body is streamed to the upload endpoint"
        client.sendHead("POST", TARGET, VALID + ["Content-Length": String.valueOf(total)])
        long pieces = total.intdiv(piece.length)
        for (long i = 0; i < pieces; i++) {
            piece[0] = (byte) i; piece[1] = (byte) (i >> 8)
            clientSha.update(piece)
            client.sendBody(piece)
            if (i % 8192 == 0) {
                tmpPeak = Math.max(tmpPeak, sizeOf(tmp))
            }
        }
        client.flush()
        def response = client.readResponse()

        then: "the response is 201 Created"
        response.status == 201

        and: "the file service saw 2147483648 bytes with the SHA-256 the client computed"
        upload.count == 2147483648L
        upload.sha256 == clientSha.digest().encodeHex().toString()
        response.json().size == 2147483648L

        and: "no OutOfMemoryError occurred and no temporary file grew with the upload"
        upload.outcome == "ended"
        tmpPeak - tmpBefore < 20 * MIB

        cleanup:
        client?.close()
    }

    // --- helpers -------------------------------------------------------------------------------------

    static long sizeOf(Path dir) {
        if (!Files.exists(dir)) {
            return 0
        }
        Files.walk(dir).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }.mapToLong { try { Files.size(it) } catch (IOException ignored) { 0L } }.sum()
        }
    }

    /** Reads the body to the end through a fixed buffer, counting and hashing, like the real service. */
    static class RecordingUpload implements PendingUpload {
        volatile UploadCommand command
        volatile long count
        volatile String sha256
        volatile String outcome

        @Override
        StoredFile complete(InputStream body) {
            def digest = MessageDigest.getInstance("SHA-256")
            def buffer = new byte[64 * 1024]
            try {
                int n
                while ((n = body.read(buffer)) != -1) {
                    digest.update(buffer, 0, n)
                    count += n
                }
            } catch (IOException e) {
                outcome = "failed: " + e
                throw new UncheckedIOException(e)
            }
            byte[] hash = digest.digest()
            sha256 = hash.encodeHex().toString()
            outcome = "ended"
            new StoredFile(FileId.newId(), OwnerId.of("alice"), Filename.of("big.bin"), Visibility.PRIVATE,
                    Tags.none(), count, Sha256.of(hash), ContentType.OCTET_STREAM, Instant.now(),
                    DownloadToken.generate(), StorageKey.random())
        }

        @Override
        void close() {
        }
    }
}
