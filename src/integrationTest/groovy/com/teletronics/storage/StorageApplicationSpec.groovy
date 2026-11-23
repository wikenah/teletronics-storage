package com.teletronics.storage

import com.teletronics.storage.infrastructure.storage.s3.S3Properties
import com.teletronics.storage.testing.RawHttpClient
import com.teletronics.storage.testing.StorageApiClient
import com.teletronics.storage.testing.StorageContainersConfig
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


import static com.teletronics.storage.testing.TestBytes.random
import static com.teletronics.storage.testing.TestBytes.sha256
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

    StorageApiClient getApi() {
        new StorageApiClient("http://localhost:$port")
    }

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
        api.myFiles(alice).json().items*.filename == ["report.pdf"]

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
        api.myFiles(alice).json().items.size() == 1

        and: "the losing upload's stored bytes have been deleted"
        objectCount() == objectsBefore + 1
    }

    def "DOM-39 [BRIEF] deleting someone else's file"() {
        given: "alice has a PRIVATE file F"
        def file = upload(alice, "f.txt", "alice's bytes".bytes, "PRIVATE")

        when: "bob tries to delete F"
        def response = api.delete(bob, file.id)

        then: "it is rejected as not found (bob can't tell F exists)"
        response.status == 404
        response.problemType() == "/problems/file-not-found"

        and: "F still exists and downloads in full"
        api.download(file.downloadLink).body == "alice's bytes".bytes
        api.get(alice, file.id).status == 200
    }

    def "DOM-43 [BRIEF] the public list shows every user's PUBLIC files only"() {
        given: "alice has PUBLIC file A and PRIVATE file B, and bob has PUBLIC file C, all tagged with this run's tag"
        def tag = "run-" + UUID.randomUUID().toString().substring(0, 8)
        def a = upload(alice, "A.txt", "a".bytes, "PUBLIC", [tag])
        upload(alice, "B.txt", "b".bytes, "PRIVATE", [tag])
        def c = upload(bob, "C.txt", "c".bytes, "PUBLIC", [tag])

        when: "anyone lists public files (no user id)"
        def response = api.publicFiles(tag: tag)
        def page = response.json()

        then: "the list contains A and C and not B"
        response.status == 200
        page.items*.id as Set == [a.id, c.id] as Set
        page.totalItems == 2
    }

    def "STO-14 a 50 MiB file uploads and downloads byte for byte"() {
        given: "a stored file of 50 MiB, sent in 8 MiB parts"
        byte[] content = random(50 * 1024 * 1024, 4)
        def file = upload(alice, "big.bin", content, "PRIVATE")

        when: "it is downloaded by its link"
        def response = api.download(file.downloadLink)

        then: "the client receives bytes identical to the stored ones"
        response.status == 200
        sha256(response.body) == sha256(content)
        file.sha256 == sha256(content)
        file.size == content.length

        and: "with the download headers"
        response.header("Content-Disposition") == "attachment; filename=\"big.bin\"; filename*=UTF-8''big.bin"
        response.header("X-Content-Type-Options") == "nosniff"
    }

    def "DOM-37 a type is detected when the client doesn't send one"() {
        when: "alice uploads PNG bytes named \"photo.dat\" without a Content-Type"
        byte[] png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52] as byte[]
        def file = upload(alice, "photo.dat", png, "PRIVATE", [], null)

        then: "the file's content type is image/png, and the download sends it"
        file.contentType == "image/png"
        api.download(file.downloadLink).header("Content-Type") == "image/png"
    }

    def "STO-16 a record whose bytes are missing is an internal error"() {
        given: "a record exists but its object is missing from the store"
        def file = upload(alice, "f.txt", "bytes".bytes, "PRIVATE")
        String storageKey = mongo.findOne(Query.query(where("_id").is(file.id)), Map, "files").storageKey
        s3.deleteObject(DeleteObjectRequest.builder().bucket(s3Properties.bucket()).key(storageKey).build())

        when: "the file is downloaded by its link"
        def response = api.download(file.downloadLink)

        then: "the response is 500 with type about:blank, not 404"
        response.status == 500
        response.problemType() == "about:blank"
    }

    def "rename, get and delete through HTTP"() {
        given:
        def file = upload(alice, "draft.txt", "words".bytes, "PRIVATE")

        when: "alice renames it"
        def renamed = api.rename(alice, file.id, "final.txt")

        then:
        renamed.status == 200
        renamed.json().filename == "final.txt"
        api.get(alice, file.id).json().filename == "final.txt"

        when: "she deletes it"
        def deleted = api.delete(alice, file.id)

        then: "it is gone, and so is its link"
        deleted.status == 204
        api.get(alice, file.id).status == 404
        api.download(file.downloadLink).status == 404
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

    /** Uploads through the API and returns the created file; fails the spec if it isn't a 201. */
    Map upload(String user, String filename, byte[] content, String visibility, List<String> tags = [],
               String contentType = "application/octet-stream") {
        def response = api.upload(user, filename, content, [visibility: visibility, tags: tags, contentType: contentType])
        assert response.status == 201: response
        response.json()
    }

    long objectCount() {
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(s3Properties.bucket()).build())
                .contents().stream().count()
    }
}
