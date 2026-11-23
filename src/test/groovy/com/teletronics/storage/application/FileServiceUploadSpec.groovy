package com.teletronics.storage.application

import com.teletronics.storage.application.port.FileTooLargeException
import com.teletronics.storage.application.port.StorageUnavailableException
import com.teletronics.storage.domain.file.DuplicateFileException
import com.teletronics.storage.domain.file.FileQuery
import com.teletronics.storage.domain.file.InvalidFileException
import com.teletronics.storage.domain.file.OwnerId
import com.teletronics.storage.domain.file.RepositoryUnavailableException
import com.teletronics.storage.domain.file.StorageKey
import com.teletronics.storage.testing.FixedContentTypeDetector
import com.teletronics.storage.testing.InMemoryBlobStore
import com.teletronics.storage.testing.InMemoryFileRepository
import spock.lang.Specification

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

import static com.teletronics.storage.testing.InMemoryBlobStore.MIB

/**
 * Upload use case at service level (docs/acceptance-criteria/domain.md decisions 1, 4, 5;
 * storage.md decision 2 for the memory budget).
 */
class FileServiceUploadSpec extends Specification {

    def repository = new InMemoryFileRepository()
    def blobs = new InMemoryBlobStore()
    def detector = new FixedContentTypeDetector("text/plain")
    def budget = new UploadBudget(256)
    def clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC)
    def service = new FileService(repository, blobs, detector, budget, clock)

    def "DOM-14 a successful upload stores the bytes, then the record"() {
        given: "alice has no file named \"report.pdf\" and no file with content C"
        def content = "content C".bytes

        when: "she uploads content C as \"report.pdf\""
        def file = upload("alice", "report.pdf", content, "application/pdf")

        then: "a record exists with the size and SHA-256 computed by the server"
        repository.findById(file.id()).isPresent()
        file.size() == content.length
        file.sha256().hex() == sha256(content)

        and: "the record's storage key is random, not derived from the filename or content"
        file.storageKey().value().startsWith(StorageKey.PREFIX)
        !file.storageKey().value().contains("report")

        and: "the stored bytes are identical to the uploaded bytes"
        blobs.bytes(file.storageKey()) == content

        and: "the upload's memory reservation was returned"
        budget.availableMib() == budget.totalMib()
    }

    def "DOM-13 a name taken by a completed file is caught before the body"() {
        given: "alice has a file named \"Report.pdf\""
        def existing = upload("alice", "Report.pdf", "first".bytes)

        when: "an upload of \"REPORT.PDF\" asks whether the name is free"
        service.startUpload(command("alice", "REPORT.PDF"))

        then: "the answer is \"taken\", with the existing file's id, size and SHA-256"
        def e = thrown(DuplicateFileException)
        e.kind() == DuplicateFileException.Kind.FILENAME
        e.existing().id() == existing.id()
        e.existing().size() == existing.size()
        e.existing().sha256() == existing.sha256()

        and: "no bytes are stored beyond the existing file's, and no memory stays reserved"
        blobs.keys().size() == 1
        budget.availableMib() == budget.totalMib()
    }

    def "DOM-11 parallel uploads with the same filename: exactly one wins"() {
        given: "alice has no file named \"report.pdf\" and two uploads of it have both passed the pre-check"
        def first = service.startUpload(command("alice", "report.pdf"))
        def second = service.startUpload(command("alice", "report.pdf"))

        when: "both complete with different content"
        def winner = first.complete(stream("version one"))
        second.complete(stream("version two"))

        then: "the second is rejected as filename-exists"
        def e = thrown(DuplicateFileException)
        e.kind() == DuplicateFileException.Kind.FILENAME
        e.existing().id() == winner.id()

        and: "exactly one record named \"report.pdf\" exists for alice"
        repository.count() == 1

        and: "the losing upload's stored bytes have been deleted"
        blobs.keys() == [winner.storageKey().value()] as Set

        cleanup:
        first.close(); second.close()
    }

    def "DOM-12 parallel uploads with the same content: exactly one wins"() {
        given: "alice has no file with content C and two uploads under different names have started"
        def first = service.startUpload(command("alice", "a.txt"))
        def second = service.startUpload(command("alice", "b.txt"))

        when: "both complete with content C"
        def winner = first.complete(stream("content C"))
        second.complete(stream("content C"))

        then: "the second is rejected as content-exists"
        def e = thrown(DuplicateFileException)
        e.kind() == DuplicateFileException.Kind.CONTENT
        e.existing().id() == winner.id()

        and: "exactly one record with content C exists, and the loser's bytes are gone"
        repository.count() == 1
        blobs.keys() == [winner.storageKey().value()] as Set

        cleanup:
        first.close(); second.close()
    }

    def "DOM-16 when name and content both clash, the name is reported"() {
        given: "alice has \"a.txt\" with content A and \"b.txt\" with content B, and a pending upload of \"a.txt\""
        upload("alice", "b.txt", "content B".bytes)
        def pending = service.startUpload(command("alice", "a.txt"))
        upload("alice", "a.txt", "content A".bytes)

        when: "the pending upload completes with content B"
        pending.complete(stream("content B"))

        then: "it is rejected as filename-exists"
        def e = thrown(DuplicateFileException)
        e.kind() == DuplicateFileException.Kind.FILENAME

        cleanup:
        pending.close()
    }

    def "DOM-06 identical bytes are the same content, whatever else differs (#difference)"() {
        given: "alice has a file with content C"
        upload("alice", "original.txt", "content C".bytes, "text/plain", "PUBLIC", ["one"])

        when: "she uploads content C with #difference"
        upload("alice", name, "content C".bytes, type, visibility, tags)

        then: "it is rejected as content-exists"
        def e = thrown(DuplicateFileException)
        e.kind() == DuplicateFileException.Kind.CONTENT

        where:
        difference                   | name        | type         | visibility | tags
        "another filename"           | "copy.txt"  | "text/plain" | "PUBLIC"   | ["one"]
        "another visibility"         | "copy2.txt" | "text/plain" | "PRIVATE"  | ["one"]
        "other tags"                 | "copy3.txt" | "text/plain" | "PUBLIC"   | ["two"]
        "another declared type"      | "copy4.txt" | "text/csv"   | "PUBLIC"   | ["one"]
    }

    def "DOM-06 a user's second empty file is a content duplicate"() {
        given: "alice already has an empty file"
        upload("alice", "empty-1", new byte[0])

        when: "she uploads another empty file under a different filename"
        upload("alice", "empty-2", new byte[0])

        then: "it is rejected as content-exists"
        thrown(DuplicateFileException)
    }

    def "DOM-07 one differing byte is different content"() {
        given: "alice has a file with content C"
        upload("alice", "c.bin", [1, 2, 3] as byte[])

        when: "she uploads content C with one byte changed, under another filename"
        def file = upload("alice", "d.bin", [1, 2, 4] as byte[])

        then: "the upload is accepted"
        file != null
    }

    def "DOM-08 content becomes free again after delete; rename keeps it taken (#action)"() {
        given: "alice has a file with content C"
        def file = upload("alice", "c.txt", "content C".bytes)

        when: "she #action and then uploads content C under a new filename"
        change(service, file)
        def outcome = attempt { upload("alice", "new.txt", "content C".bytes) }

        then: "the upload is #result"
        outcome == result

        where:
        action                 | change                                                                 || result
        "deletes the file"     | { s, f -> s.delete(OwnerId.of("alice"), f.id()) }                      || "accepted"
        "renames the file"     | { s, f -> s.rename(OwnerId.of("alice"), f.id(), "renamed.txt") }       || "rejected"
    }

    def "DOM-19 the same filename for different users never conflicts"() {
        given: "alice has a file named \"report.pdf\""
        upload("alice", "report.pdf", "alice's".bytes)

        when: "bob uploads a file named \"report.pdf\""
        def bobs = upload("bob", "report.pdf", "bob's".bytes)

        then: "the upload is accepted"
        bobs.owner() == OwnerId.of("bob")
    }

    def "DOM-20 a retried upload never creates a second file"() {
        given: "alice's upload of \"report.pdf\" with content C completed"
        def original = upload("alice", "report.pdf", "content C".bytes)

        when: "the same upload is sent again"
        upload("alice", "report.pdf", "content C".bytes)

        then: "it is rejected as filename-exists with the existing file's id, size and SHA-256"
        def e = thrown(DuplicateFileException)
        e.existing().id() == original.id()
        e.existing().sha256() == original.sha256()

        and: "alice still has exactly one file"
        repository.count() == 1
    }

    def "DOM-10 a record exists only for a completely stored file"() {
        given: "alice's upload of \"big.bin\" has started but not finished"
        def pending = service.startUpload(command("alice", "big.bin"))

        when: "alice lists her files"
        def page = service.list(FileQuery.ownedBy(OwnerId.of("alice")))

        then: "\"big.bin\" is not listed"
        page.items().isEmpty()

        cleanup:
        pending.close()
    }

    def "DOM-15 a failed body leaves nothing behind"() {
        given: "the object store fails while the body is written"
        blobs.failStoresWith(new StorageUnavailableException("store down", null))

        when: "alice uploads a file"
        upload("alice", "report.pdf", "content".bytes)

        then: "the failure is reported and no record or bytes exist"
        thrown(StorageUnavailableException)
        repository.count() == 0
        blobs.keys().isEmpty()

        and: "the memory reservation was returned"
        budget.availableMib() == budget.totalMib()
    }

    def "API-11 a body shorter than Content-Length is never a complete upload"() {
        when: "a body of 5 bytes is sent with a declared length of 10"
        def pending = service.startUpload(command("alice", "short.bin", Optional.empty(), OptionalLong.of(10)))
        pending.complete(stream("12345"))

        then: "the upload fails and nothing is kept"
        thrown(IncompleteUploadException)
        repository.count() == 0
        blobs.keys().isEmpty()

        cleanup:
        pending?.close()
    }

    def "DOM-17 a database failure at insert deletes the stored bytes"() {
        given: "saving the record fails with an error other than a duplicate"
        repository.failNextInsertWith(new RepositoryUnavailableException("mongo down", null))

        when: "alice uploads a file"
        upload("alice", "report.pdf", "content".bytes)

        then: "the error is reported and the stored bytes were deleted"
        thrown(RepositoryUnavailableException)
        blobs.keys().isEmpty()
    }

    def "DOM-17 if deleting the bytes also fails, the upload still reports the database error"() {
        given: "saving the record fails and deleting bytes fails too"
        repository.failNextInsertWith(new RepositoryUnavailableException("mongo down", null))
        blobs.failDeletesWith(new StorageUnavailableException("store down", null))

        when: "alice uploads a file"
        upload("alice", "report.pdf", "content".bytes)

        then: "the database error is what the caller sees (the orphaned key is logged)"
        thrown(RepositoryUnavailableException)
        blobs.keys().size() == 1
    }

    def "STO-09 part buffers never exceed the memory budget (budget #budgetMib MiB)"() {
        given: "the upload buffer budget is #budgetMib MiB"
        def smallBudget = new UploadBudget(budgetMib)
        def service = new FileService(repository, blobs, detector, smallBudget, clock)
        blobs.declaredLengthIsPartSizeInMib()

        when: "uploads with part sizes #parts MiB ask to start at the same time"
        def started = []
        def refused = 0
        parts.eachWithIndex { int partMib, int i ->
            try {
                started << service.startUpload(command("u$i", "f$i", Optional.empty(), OptionalLong.of(partMib)))
            } catch (UploadCapacityReachedException ignored) {
                refused++
            }
        }

        then: "those whose part fits start, the rest are refused as capacity reached"
        started.size() == expectStarted
        refused == parts.size() - expectStarted

        and: "the total reserved never exceeds the budget"
        smallBudget.totalMib() - smallBudget.availableMib() <= budgetMib

        when: "every started upload ends"
        started*.close()

        then: "every reservation is returned"
        smallBudget.availableMib() == budgetMib

        where:
        budgetMib | parts                 || expectStarted
        256       | [8, 8, 96, 96, 64]    || 4
        16        | [8, 8, 8]             || 2
    }

    def "STO-10 a part that could never fit is refused as file too large"() {
        given: "the upload buffer budget is 256 MiB and the declared length needs 300 MiB parts"
        blobs.fixedPartSize(300 * MIB)

        when: "the upload starts"
        service.startUpload(command("alice", "huge.bin", Optional.empty(), OptionalLong.of(3_000_000_000_000L)))

        then: "it is refused as file too large before any byte is read"
        thrown(FileTooLargeException)
        budget.availableMib() == budget.totalMib()
    }

    def "invalid values are rejected before anything is reserved: #field"() {
        when:
        service.startUpload(new UploadCommand(owner, filename, visibility, tags, Optional.empty(), OptionalLong.empty()))

        then:
        def e = thrown(InvalidFileException)
        e.field() == field
        budget.availableMib() == budget.totalMib()

        where:
        field        | owner   | filename | visibility | tags
        "owner"      | " "     | "a.txt"  | "PUBLIC"   | []
        "filename"   | "alice" | "a/b"    | "PUBLIC"   | []
        "visibility" | "alice" | "a.txt"  | "public"   | []
        "tags"       | "alice" | "a.txt"  | "PUBLIC"   | ["a,b"]
    }

    // --- helpers -------------------------------------------------------------------------------------

    def upload(String owner, String name, byte[] content, String type = null, String visibility = "PRIVATE",
               List<String> tags = []) {
        def pending = service.startUpload(new UploadCommand(owner, name, visibility, tags,
                Optional.ofNullable(type), OptionalLong.of(content.length)))
        try {
            return pending.complete(new ByteArrayInputStream(content))
        } finally {
            pending.close()
        }
    }

    def command(String owner, String name, Optional<String> type = Optional.empty(),
                OptionalLong length = OptionalLong.empty()) {
        new UploadCommand(owner, name, "PRIVATE", [], type, length)
    }

    static InputStream stream(String text) {
        new ByteArrayInputStream(text.bytes)
    }

    static String attempt(Closure action) {
        try {
            action()
            return "accepted"
        } catch (DuplicateFileException ignored) {
            return "rejected"
        }
    }

    static String sha256(byte[] bytes) {
        MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString()
    }
}
