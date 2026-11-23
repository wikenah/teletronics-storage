package com.teletronics.storage.application

import com.teletronics.storage.application.upload.UploadBudget
import com.teletronics.storage.application.upload.UploadCommand
import com.teletronics.storage.application.port.StorageUnavailableException
import com.teletronics.storage.domain.file.DuplicateFileException
import com.teletronics.storage.domain.file.FileNotFoundException
import com.teletronics.storage.domain.file.FileQuery
import com.teletronics.storage.domain.file.InvalidFileException
import com.teletronics.storage.domain.file.OwnerId
import com.teletronics.storage.domain.file.SortField
import com.teletronics.storage.domain.file.Tag
import com.teletronics.storage.testing.FixedContentTypeDetector
import com.teletronics.storage.testing.InMemoryBlobStore
import com.teletronics.storage.testing.InMemoryFileRepository
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Rename, delete, download, listing and type detection at service level
 * (docs/acceptance-criteria/domain.md decisions 6, 8, 9, 10, 11).
 */
class FileServiceFilesSpec extends Specification {

    def repository = new InMemoryFileRepository()
    def blobs = new InMemoryBlobStore()
    def detector = new FixedContentTypeDetector("image/png")
    def now = Instant.parse("2026-11-23T10:00:00Z")
    def clock = Mock(Clock) { instant() >> { now = now.plusSeconds(1); now } }
    def service = new FileService(repository, blobs, detector, new UploadBudget(256), clock)

    def alice = OwnerId.of("alice")
    def bob = OwnerId.of("bob")

    // --- rename (decision 6) ------------------------------------------------------------------------

    def "DOM-21 rename changes only the name"() {
        given: "alice has \"report.pdf\""
        def file = upload("alice", "report.pdf", "bytes")

        when: "she renames it to \"new_report.pdf\""
        def renamed = service.rename(alice, file.id(), "new_report.pdf")

        then: "the file is listed as \"new_report.pdf\""
        service.get(alice, file.id()).filename().value() == "new_report.pdf"

        and: "its bytes, size, SHA-256, content type, upload date and storage key are unchanged"
        renamed.size() == file.size()
        renamed.sha256() == file.sha256()
        renamed.contentType() == file.contentType()
        renamed.uploadedAt() == file.uploadedAt()
        renamed.storageKey() == file.storageKey()

        and: "downloading it returns the same bytes"
        service.openDownload(file.downloadToken().value()).body().bytes == "bytes".bytes
    }

    def "DOM-22 rename is rejected when the new name is not allowed (#name)"() {
        given: "alice has \"report.pdf\" and \"notes.txt\""
        def file = upload("alice", "report.pdf", "one")
        upload("alice", "notes.txt", "two")

        when: "she renames \"report.pdf\" to #name"
        service.rename(alice, file.id(), name)

        then: "it is rejected"
        thrown(failure)

        and: "\"report.pdf\" keeps its name"
        service.get(alice, file.id()).filename().value() == "report.pdf"

        where:
        name        || failure
        "notes.txt" || DuplicateFileException
        "NOTES.TXT" || DuplicateFileException
        "a/b.pdf"   || InvalidFileException
        ""          || InvalidFileException
    }

    def "DOM-23 only the owner can rename"() {
        given: "alice has \"report.pdf\""
        def file = upload("alice", "report.pdf", "one")

        when: "bob tries to rename it"
        service.rename(bob, file.id(), "mine.pdf")

        then: "it is rejected as not found and the name is unchanged"
        thrown(FileNotFoundException)
        service.get(alice, file.id()).filename().value() == "report.pdf"
    }

    def "DOM-24 a case-only rename is allowed"() {
        given: "alice has \"report.pdf\""
        def file = upload("alice", "report.pdf", "one")

        when: "she renames it to \"Report.pdf\""
        service.rename(alice, file.id(), "Report.pdf")

        then: "the file is listed as \"Report.pdf\""
        service.get(alice, file.id()).filename().value() == "Report.pdf"
    }

    def "DOM-25 renaming to the same name succeeds without changes"() {
        given: "alice has \"report.pdf\""
        def file = upload("alice", "report.pdf", "one")

        when: "she renames it to \"report.pdf\""
        def result = service.rename(alice, file.id(), "report.pdf")

        then: "the rename succeeds and nothing changes"
        result == file
    }

    // --- download link (decision 8) -----------------------------------------------------------------

    def "DOM-32 anyone with the link can download, #visibility"() {
        given: "alice has a #visibility file"
        def file = upload("alice", "f.txt", "secret bytes", visibility)

        when: "someone without any user id downloads it by its link"
        def download = service.openDownload(file.downloadToken().value())

        then: "they receive the file's bytes"
        download.body().bytes == "secret bytes".bytes

        where:
        visibility << ["PUBLIC", "PRIVATE"]
    }

    def "DOM-33 an unknown token finds nothing (#description)"() {
        given: "alice has a file"
        def file = upload("alice", "f.txt", "bytes")

        when: "someone downloads /download/#description"
        service.openDownload(token(file))

        then: "the file is not found"
        thrown(FileNotFoundException)

        where:
        description                              | token
        "a well-formed token that was never issued" | { f -> "AAAAAAAAAAAAAAAAAAAAAA" }
        "a malformed string"                     | { f -> "not-a-token" }
        "another file's id"                      | { f -> f.id().value() }
    }

    def "DOM-34 the link survives a rename"() {
        given: "alice has \"report.pdf\" with download link L"
        def file = upload("alice", "report.pdf", "bytes")

        when: "she renames it to \"final.pdf\""
        service.rename(alice, file.id(), "final.pdf")

        then: "L still downloads the file"
        service.openDownload(file.downloadToken().value()).file().filename().value() == "final.pdf"
    }

    def "STO-16 a record whose bytes are missing is an internal error"() {
        given: "a record exists but its object is missing from the store"
        def file = upload("alice", "f.txt", "bytes")
        blobs.delete(file.storageKey())

        when: "the file is downloaded by its link"
        service.openDownload(file.downloadToken().value())

        then: "it is reported as an internal inconsistency, not as not found"
        thrown(RecordWithoutBytesException)
    }

    // --- delete (decision 10) -----------------------------------------------------------------------

    def "DOM-39 [BRIEF] deleting someone else's file (#visibility)"() {
        given: "alice has a #visibility file F"
        def file = upload("alice", "f.txt", "alice's bytes", visibility)

        when: "bob tries to delete F"
        service.delete(bob, file.id())

        then: "it is rejected as not found"
        thrown(FileNotFoundException)

        and: "F still exists and downloads in full"
        service.openDownload(file.downloadToken().value()).body().bytes == "alice's bytes".bytes

        where:
        visibility << ["PRIVATE", "PUBLIC"]
    }

    def "DOM-40 deleting your own file"() {
        given: "alice has \"report.pdf\" with content C and download link L"
        def file = upload("alice", "report.pdf", "content C")

        when: "she deletes it"
        service.delete(alice, file.id())

        then: "the record and its stored bytes are gone"
        repository.count() == 0
        blobs.keys().isEmpty()

        and: "L finds nothing"
        when:
        service.openDownload(file.downloadToken().value())
        then:
        thrown(FileNotFoundException)

        and: "alice can upload a file named \"report.pdf\" with content C again"
        upload("alice", "report.pdf", "content C") != null
    }

    def "DOM-41 a failed byte deletion still deletes the file"() {
        given: "alice has a file and deleting stored bytes will fail"
        def file = upload("alice", "f.txt", "bytes")
        blobs.failDeletesWith(new StorageUnavailableException("store down", null))

        when: "she deletes it"
        service.delete(alice, file.id())

        then: "the file is gone for every user (the orphaned storage key is logged)"
        repository.count() == 0
        blobs.keys().size() == 1
    }

    def "DOM-42 deleting twice"() {
        given: "alice has deleted file F"
        def file = upload("alice", "f.txt", "bytes")
        service.delete(alice, file.id())

        when: "she deletes F again"
        service.delete(alice, file.id())

        then: "it is rejected as not found"
        thrown(FileNotFoundException)
    }

    // --- listing (decision 11) ----------------------------------------------------------------------

    def "DOM-43 [BRIEF] the public list shows every user's PUBLIC files only"() {
        given: "alice has PUBLIC file A and PRIVATE file B, and bob has PUBLIC file C"
        def a = upload("alice", "A", "a", "PUBLIC")
        upload("alice", "B", "b", "PRIVATE")
        def c = upload("bob", "C", "c", "PUBLIC")

        when: "anyone lists public files"
        def page = service.list(FileQuery.publicFiles())

        then: "the list contains A and C and not B"
        page.items()*.id() as Set == [a.id(), c.id()] as Set
    }

    def "DOM-44 my files shows all of the user's files and nobody else's"() {
        given: "alice has PUBLIC file A and PRIVATE file B, and bob has PUBLIC file C"
        def a = upload("alice", "A", "a", "PUBLIC")
        def b = upload("alice", "B", "b", "PRIVATE")
        upload("bob", "C", "c", "PUBLIC")

        when: "alice lists her files"
        def page = service.list(FileQuery.ownedBy(alice))

        then: "the list contains A and B and not C"
        page.items()*.id() as Set == [a.id(), b.id()] as Set
    }

    def "DOM-45 the optional tag filter works on both lists (#list)"() {
        given: "alice has PUBLIC files tagged \"work\" and \"home\""
        def work = upload("alice", "w", "w", "PUBLIC", ["work"])
        upload("alice", "h", "h", "PUBLIC", ["home"])

        when: "#list is filtered by \"WORK\""
        def page = service.list(query.withTag(Tag.of("WORK")))

        then: "only the file tagged \"work\" is returned"
        page.items()*.id() == [work.id()]

        where:
        list              | query
        "the public list" | FileQuery.publicFiles()
        "alice's files"   | FileQuery.ownedBy(OwnerId.of("alice"))
    }

    def "DOM-47 the default order is newest first"() {
        given: "alice uploaded three files one after another"
        def first = upload("alice", "1", "1")
        def second = upload("alice", "2", "2")
        def third = upload("alice", "3", "3")

        when: "she lists her files without choosing a sort"
        def page = service.list(FileQuery.ownedBy(alice))

        then: "they are ordered by upload date, newest first"
        page.items()*.id() == [third.id(), second.id(), first.id()]
    }

    def "DOM-46 filename sort ignores case"() {
        given:
        upload("alice", "zebra.pdf", "z")
        upload("alice", "Banana.pdf", "b")
        upload("alice", "apple.pdf", "a")

        expect:
        service.list(FileQuery.ownedBy(alice).sortedBy(SortField.FILENAME, true)).items()*.filename()*.value() ==
                ["apple.pdf", "Banana.pdf", "zebra.pdf"]
    }

    // --- type detection (decision 9) ----------------------------------------------------------------

    def "DOM-36 a declared type is kept"() {
        when: "alice uploads a file with Content-Type text/csv"
        def file = upload("alice", "data.csv", "a,b", "PRIVATE", [], "text/csv")

        then: "the file's content type is text/csv and nothing was detected"
        file.contentType().value() == "text/csv"
        detector.calls() == 0
    }

    def "DOM-37 a missing or generic type is detected from the content (#declared)"() {
        when: "alice uploads a file with #declared"
        def file = upload("alice", "photo.dat", "png bytes", "PRIVATE", [], declared)

        then: "the detected type is used"
        file.contentType().value() == "image/png"
        detector.calls() == 1

        where:
        declared << [null, "application/octet-stream"]
    }

    // --- helpers -------------------------------------------------------------------------------------

    def upload(String owner, String name, String content, String visibility = "PRIVATE",
               List<String> tags = [], String type = null) {
        def bytes = content.bytes
        def pending = service.startUpload(UploadCommand.of(owner, name, visibility, tags,
                Optional.ofNullable(type), OptionalLong.of(bytes.length)))
        try {
            return pending.complete(new ByteArrayInputStream(bytes))
        } finally {
            pending.close()
        }
    }
}
