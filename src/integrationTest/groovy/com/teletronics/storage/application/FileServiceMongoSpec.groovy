package com.teletronics.storage.application

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.teletronics.storage.application.port.BlobStore
import com.teletronics.storage.application.upload.UploadBudget
import com.teletronics.storage.application.upload.UploadCommand
import com.teletronics.storage.domain.file.FileRepository
import com.teletronics.storage.domain.file.StoredFile
import com.teletronics.storage.domain.file.exception.DuplicateFileException
import com.teletronics.storage.domain.file.exception.FileNotFoundException
import com.teletronics.storage.domain.file.query.FileQuery
import com.teletronics.storage.domain.file.valueobject.OwnerId
import com.teletronics.storage.infrastructure.persistence.mongo.MongoFileRepository
import com.teletronics.storage.infrastructure.persistence.mongo.MongoTimeoutsConfig
import com.teletronics.storage.testing.BarrierBlobStore
import com.teletronics.storage.testing.FixedContentTypeDetector
import com.teletronics.storage.testing.InMemoryBlobStore
import com.teletronics.storage.testing.MongoContainerConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.mongodb.MongoDBContainer
import spock.lang.Specification

import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future

import static java.util.concurrent.TimeUnit.SECONDS
import static org.springframework.data.mongodb.core.query.Criteria.where

/**
 * The file service on a real MongoDB, bytes kept in memory (domain.md decisions 1, 5, 10, 11).
 * The brief's parallel-upload tests: the unique indexes decide which upload wins.
 */
@DataMongoTest
@Import([MongoContainerConfig, MongoFileRepository, MongoTimeoutsConfig])
class FileServiceMongoSpec extends Specification {

    @Autowired
    MongoFileRepository repository

    @Autowired
    MongoTemplate mongo

    @Autowired
    MongoDBContainer container

    def executor = Executors.newFixedThreadPool(2)

    def setup() {
        mongo.remove(new Query(), "files")
    }

    def cleanup() {
        executor.shutdownNow()
    }

    def "DOM-11 [BRIEF] parallel uploads with the same filename"() {
        given: "alice has no file named \"report.pdf\""
        def blobs = new BarrierBlobStore(2)
        def service = service(repository, blobs)

        when: "two uploads of \"report.pdf\" with different content run at the same time"
        def outcomes = inParallel(
                { upload(service, "alice", "report.pdf", "version one") },
                { upload(service, "alice", "report.pdf", "version two") })

        then: "exactly one succeeds and the other is rejected as filename-exists"
        def winners = outcomes.findAll { it instanceof StoredFile }
        def losers = outcomes.findAll { it instanceof DuplicateFileException }
        winners.size() == 1
        losers.size() == 1
        losers[0].kind() == DuplicateFileException.Kind.FILENAME
        losers[0].existing().id() == winners[0].id()

        and: "exactly one record named \"report.pdf\" exists for alice"
        mongo.count(Query.query(where("ownerId").is("alice").and("filenameKey").is("report.pdf")), "files") == 1

        and: "the losing upload's stored bytes have been deleted"
        blobs.keys() == [winners[0].storageKey().value()] as Set
    }

    def "DOM-12 [BRIEF] parallel uploads with the same content"() {
        given: "alice has no file with content C"
        def blobs = new BarrierBlobStore(2)
        def service = service(repository, blobs)

        when: "two uploads of content C under different filenames run at the same time"
        def outcomes = inParallel(
                { upload(service, "alice", "a.txt", "content C") },
                { upload(service, "alice", "b.txt", "content C") })

        then: "exactly one succeeds and the other is rejected as content-exists"
        def winners = outcomes.findAll { it instanceof StoredFile }
        def losers = outcomes.findAll { it instanceof DuplicateFileException }
        winners.size() == 1
        losers.size() == 1
        losers[0].kind() == DuplicateFileException.Kind.CONTENT
        losers[0].existing().id() == winners[0].id()

        and: "exactly one record with content C exists for alice"
        mongo.count(Query.query(where("ownerId").is("alice").and("sha256").is(winners[0].sha256().hex())), "files") == 1

        and: "the losing upload's stored bytes have been deleted"
        blobs.keys() == [winners[0].storageKey().value()] as Set
    }

    def "parallel uploads of the same file by different users both succeed"() {
        given:
        def blobs = new BarrierBlobStore(2)
        def service = service(repository, blobs)

        when:
        def outcomes = inParallel(
                { upload(service, "alice", "report.pdf", "same bytes") },
                { upload(service, "bob", "report.pdf", "same bytes") })

        then:
        outcomes.every { it instanceof StoredFile }
        blobs.keys().size() == 2
    }

    def "DOM-39 [BRIEF] deleting someone else's file"() {
        given: "alice has a file F"
        def blobs = new InMemoryBlobStore()
        def service = service(repository, blobs)
        StoredFile file = upload(service, "alice", "f.txt", "alice's bytes")

        when: "bob tries to delete F"
        service.delete(OwnerId.of("bob"), file.id())

        then: "it is rejected as not found"
        thrown(FileNotFoundException)

        and: "F still exists and downloads in full"
        repository.findById(file.id()).isPresent()
        service.openDownload(file.downloadToken().value()).body().bytes == "alice's bytes".bytes
    }

    def "DOM-43 [BRIEF] the public list shows every user's PUBLIC files only"() {
        given: "alice has PUBLIC file A and PRIVATE file B, and bob has PUBLIC file C"
        def service = service(repository, new InMemoryBlobStore())
        StoredFile a = upload(service, "alice", "A", "a", "PUBLIC")
        upload(service, "alice", "B", "b", "PRIVATE")
        StoredFile c = upload(service, "bob", "C", "c", "PUBLIC")

        when: "anyone lists public files"
        def page = service.list(FileQuery.publicFiles())

        then: "the list contains A and C and not B"
        page.items()*.id() as Set == [a.id(), c.id()] as Set
    }

    def "DOM-18 no database connection is held while the body streams"() {
        given: "the Mongo connection pool size is 1"
        def client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(container.getReplicaSetUrl("dom18")))
                .applyToConnectionPoolSettings { it.maxSize(1).maxWaitTime(2, SECONDS) }
                .applyToClusterSettings { it.serverSelectionTimeout(2, SECONDS) }
                .build())
        def smallPool = new MongoFileRepository(new MongoTemplate(client, "dom18"))
        def service = service(smallPool, new InMemoryBlobStore())

        and: "an upload is paused halfway through its body"
        def halfway = new CountDownLatch(1)
        def resume = new CountDownLatch(1)
        byte[] content = new byte[1024 * 1024]
        new Random(7).nextBytes(content)
        def body = new PausingInputStream(content, content.length.intdiv(2), halfway, resume)
        Future<StoredFile> running = executor.submit({
            def pending = service.startUpload(command("alice", "big.bin", "PRIVATE", content.length))
            try {
                pending.complete(body)
            } finally {
                pending.close()
            }
        } as java.util.concurrent.Callable<StoredFile>)
        assert halfway.await(10, SECONDS)

        when: "another request lists alice's files"
        def page = service.list(FileQuery.ownedBy(OwnerId.of("alice")))

        then: "the listing is served"
        page.totalItems() == 0

        when: "the upload resumes"
        resume.countDown()

        then: "it completes"
        running.get(10, SECONDS).size() == content.length

        cleanup:
        resume.countDown()
        client?.close()
    }

    // --- helpers -------------------------------------------------------------------------------------

    static FileService service(FileRepository repository, BlobStore blobs) {
        new FileService(repository, blobs, new FixedContentTypeDetector("text/plain"), new UploadBudget(256), Clock.systemUTC())
    }

    static UploadCommand command(String owner, String name, String visibility, long length) {
        UploadCommand.of(owner, name, visibility, [], Optional.empty(), OptionalLong.of(length))
    }

    static StoredFile upload(FileService service, String owner, String name, String content, String visibility = "PRIVATE") {
        byte[] bytes = content.bytes
        def pending = service.startUpload(command(owner, name, visibility, bytes.length))
        try {
            return pending.complete(new ByteArrayInputStream(bytes))
        } finally {
            pending.close()
        }
    }

    /** Runs both actions on their own threads; each result is the stored file or the exception thrown. */
    List<Object> inParallel(Closure first, Closure second) {
        [first, second].collect { action ->
            executor.submit({
                try {
                    action()
                } catch (Exception e) {
                    e
                }
            } as java.util.concurrent.Callable<Object>)
        }*.get(30, SECONDS)
    }

    /** Delivers the first bytes, then signals and waits before delivering the rest. */
    static class PausingInputStream extends FilterInputStream {
        private final int pauseAt
        private final CountDownLatch halfway
        private final CountDownLatch resume
        private int delivered

        PausingInputStream(byte[] content, int pauseAt, CountDownLatch halfway, CountDownLatch resume) {
            super(new ByteArrayInputStream(content))
            this.pauseAt = pauseAt
            this.halfway = halfway
            this.resume = resume
        }

        @Override
        int read(byte[] buffer, int offset, int length) {
            if (delivered >= pauseAt && halfway.count > 0) {
                halfway.countDown()
                resume.await(30, SECONDS)
            }
            int allowed = delivered < pauseAt ? Math.min(length, pauseAt - delivered) : length
            int n = super.read(buffer, offset, allowed)
            if (n > 0) {
                delivered += n
            }
            n
        }
    }
}
