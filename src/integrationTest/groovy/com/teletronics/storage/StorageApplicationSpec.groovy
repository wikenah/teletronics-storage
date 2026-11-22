package com.teletronics.storage

import com.teletronics.storage.infrastructure.storage.s3.S3Properties
import com.teletronics.storage.testing.RawHttpClient
import com.teletronics.storage.testing.StorageContainersConfig
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

import static org.springframework.data.mongodb.core.query.Criteria.where

/**
 * The whole application (Tomcat, controllers, file service, Mongo, SeaweedFS) on a random port.
 * The brief's required tests run here end to end, through HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StorageContainersConfig)
class StorageApplicationSpec extends Specification {

    @LocalServerPort
    int port

    @Autowired
    S3Client s3

    @Autowired
    S3Properties s3Properties

    @Autowired
    MongoTemplate mongo

    def http = HttpClient.newHttpClient()

    // A user of its own per feature, so features never see each other's files.
    def alice = "alice-" + UUID.randomUUID()
    def bob = "bob-" + UUID.randomUUID()

    def "DOM-11 [BRIEF] parallel uploads with the same filename"() {
        given: "alice has no file named \"report.pdf\""
        long objectsBefore = objectCount()

        when: "two uploads of \"report.pdf\" with different content both pass the pre-check, then send their bodies"
        def results = raceTwoUploads(
                [filename: "report.pdf", body: random(1024 * 1024, 1)],
                [filename: "report.pdf", body: random(1024 * 1024, 2)])

        then: "exactly one succeeds and the other is rejected as filename-exists"
        results*.status.sort() == [201, 409]
        def loser = results.find { it.status == 409 }
        loser.problemType() == "/problems/filename-exists"
        loser.json().existingFile.id == results.find { it.status == 201 }.json().id

        and: "exactly one record named \"report.pdf\" exists for alice"
        list(alice).items*.filename == ["report.pdf"]

        and: "the losing upload's stored bytes have been deleted"
        objectCount() == objectsBefore + 1
    }

    def "DOM-12 [BRIEF] parallel uploads with the same content"() {
        given: "alice has no file with content C"
        long objectsBefore = objectCount()
        byte[] content = random(1024 * 1024, 3)

        when: "two uploads of content C under different filenames both pass the pre-check, then send their bodies"
        def results = raceTwoUploads([filename: "a.bin", body: content], [filename: "b.bin", body: content])

        then: "exactly one succeeds and the other is rejected as content-exists"
        results*.status.sort() == [201, 409]
        results.find { it.status == 409 }.problemType() == "/problems/content-exists"

        and: "exactly one record with content C exists for alice"
        list(alice).items.size() == 1

        and: "the losing upload's stored bytes have been deleted"
        objectCount() == objectsBefore + 1
    }

    def "DOM-39 [BRIEF] deleting someone else's file"() {
        given: "alice has a PRIVATE file F"
        def file = upload(alice, "f.txt", "alice's bytes".bytes, "PRIVATE")

        when: "bob tries to delete F"
        def response = send(HttpRequest.newBuilder(url("/files/${file.id}")).header("X-User-Id", bob).DELETE())

        then: "it is rejected as not found (bob can't tell F exists)"
        response.statusCode() == 404
        problem(response).type == "/problems/file-not-found"

        and: "F still exists and downloads in full"
        download(file.downloadLink).body() == "alice's bytes".bytes
        get("/files/${file.id}", alice).statusCode() == 200
    }

    def "DOM-43 [BRIEF] the public list shows every user's PUBLIC files only"() {
        given: "alice has PUBLIC file A and PRIVATE file B, and bob has PUBLIC file C, all tagged with this run's tag"
        def tag = "run-" + UUID.randomUUID().toString().substring(0, 8)
        def a = upload(alice, "A.txt", "a".bytes, "PUBLIC", [tag])
        upload(alice, "B.txt", "b".bytes, "PRIVATE", [tag])
        def c = upload(bob, "C.txt", "c".bytes, "PUBLIC", [tag])

        when: "anyone lists public files (no user id)"
        def response = send(HttpRequest.newBuilder(url("/files/public?tag=$tag")).GET())
        def page = new JsonSlurper().parse(response.body()) as Map

        then: "the list contains A and C and not B"
        response.statusCode() == 200
        page.items*.id as Set == [a.id, c.id] as Set
        page.totalItems == 2
    }

    def "STO-14 a 50 MiB file uploads and downloads byte for byte"() {
        given: "a stored file of 50 MiB, sent in 8 MiB parts"
        byte[] content = random(50 * 1024 * 1024, 4)
        def file = upload(alice, "big.bin", content, "PRIVATE")

        when: "it is downloaded by its link"
        def response = download(file.downloadLink)

        then: "the client receives bytes identical to the stored ones"
        response.statusCode() == 200
        sha256(response.body()) == sha256(content)
        file.sha256 == sha256(content)
        file.size == content.length

        and: "with the download headers"
        response.headers().firstValue("Content-Disposition").get() == "attachment; filename=\"big.bin\"; filename*=UTF-8''big.bin"
        response.headers().firstValue("X-Content-Type-Options").get() == "nosniff"
    }

    def "DOM-37 a type is detected when the client doesn't send one"() {
        when: "alice uploads PNG bytes named \"photo.dat\" without a Content-Type"
        byte[] png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52] as byte[]
        def file = upload(alice, "photo.dat", png, "PRIVATE", [], null)

        then: "the file's content type is image/png, and the download sends it"
        file.contentType == "image/png"
        download(file.downloadLink).headers().firstValue("Content-Type").get() == "image/png"
    }

    def "STO-16 a record whose bytes are missing is an internal error"() {
        given: "a record exists but its object is missing from the store"
        def file = upload(alice, "f.txt", "bytes".bytes, "PRIVATE")
        String storageKey = mongo.findOne(Query.query(where("_id").is(file.id)), Map, "files").storageKey
        s3.deleteObject(DeleteObjectRequest.builder().bucket(s3Properties.bucket()).key(storageKey).build())

        when: "the file is downloaded by its link"
        def response = download(file.downloadLink)

        then: "the response is 500 with type about:blank, not 404"
        response.statusCode() == 500
        problem(response).type == "about:blank"
    }

    def "rename, get and delete through HTTP"() {
        given:
        def file = upload(alice, "draft.txt", "words".bytes, "PRIVATE")

        when: "alice renames it"
        def renamed = send(HttpRequest.newBuilder(url("/files/${file.id}"))
                .header("X-User-Id", alice).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString('{"filename": "final.txt"}')))

        then:
        renamed.statusCode() == 200
        json(renamed).filename == "final.txt"
        json(get("/files/${file.id}", alice)).filename == "final.txt"

        when: "she deletes it"
        def deleted = send(HttpRequest.newBuilder(url("/files/${file.id}")).header("X-User-Id", alice).DELETE())

        then: "it is gone, and so is its link"
        deleted.statusCode() == 204
        get("/files/${file.id}", alice).statusCode() == 404
        download(file.downloadLink).statusCode() == 404
    }

    // --- helpers -------------------------------------------------------------------------------------

    /**
     * Both uploads send their headers with Expect: 100-continue. Each gets its 100 only after passing the
     * name pre-check, and neither body has been sent yet, so both are past the pre-check before either
     * can insert: the unique index decides.
     */
    List<RawHttpClient.Response> raceTwoUploads(Map first, Map second) {
        def clients = [first, second].collect { upload ->
            def client = new RawHttpClient(port)
            client.sendHead("POST", "/files?filename=${upload.filename}&visibility=PRIVATE", [
                    "X-User-Id"     : alice,
                    "Content-Type"  : "application/octet-stream",
                    "Content-Length": String.valueOf(upload.body.length),
                    "Expect"        : "100-continue"])
            assert client.readResponse().status == 100
            client
        }
        try {
            [clients, [first, second]].transpose().each { client, upload ->
                client.sendBody(upload.body as byte[])
                client.flush()
            }
            clients*.readResponse()
        } finally {
            clients*.close()
        }
    }

    Map upload(String user, String filename, byte[] content, String visibility, List<String> tags = [],
               String contentType = "application/octet-stream") {
        def query = "filename=${URLEncoder.encode(filename, "UTF-8")}&visibility=$visibility" + tags.collect { "&tags=$it" }.join()
        def request = HttpRequest.newBuilder(url("/files?$query")).header("X-User-Id", user)
        if (contentType) {
            request.header("Content-Type", contentType)
        }
        def response = send(request.POST(HttpRequest.BodyPublishers.ofByteArray(content)))
        assert response.statusCode() == 201: new String(response.body())
        json(response)
    }

    Map list(String user) {
        json(get("/files", user))
    }

    HttpResponse<byte[]> get(String path, String user) {
        send(HttpRequest.newBuilder(url(path)).header("X-User-Id", user).GET())
    }

    HttpResponse<byte[]> download(String link) {
        send(HttpRequest.newBuilder(url(link)).GET())
    }

    HttpResponse<byte[]> send(HttpRequest.Builder request) {
        http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    URI url(String path) {
        URI.create("http://localhost:$port$path")
    }

    long objectCount() {
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(s3Properties.bucket()).build())
                .contents().stream().count()
    }

    static Map json(HttpResponse<byte[]> response) {
        new JsonSlurper().parse(response.body()) as Map
    }

    /** RFC 9457 §3.1.1: an absent "type" means about:blank. */
    static Map problem(HttpResponse<byte[]> response) {
        [type: "about:blank"] + json(response)
    }

    static byte[] random(int size, long seed) {
        def bytes = new byte[size]
        new Random(seed).nextBytes(bytes)
        bytes
    }

    static String sha256(byte[] bytes) {
        MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString()
    }
}
