package com.teletronics.storage.infrastructure.persistence.mongo

import com.teletronics.storage.domain.file.StoredFile
import com.teletronics.storage.domain.file.exception.RepositoryDuplicateException
import com.teletronics.storage.domain.file.query.FileQuery
import com.teletronics.storage.domain.file.query.SortField
import com.teletronics.storage.domain.file.valueobject.ContentType
import com.teletronics.storage.domain.file.valueobject.DownloadToken
import com.teletronics.storage.domain.file.valueobject.FileId
import com.teletronics.storage.domain.file.valueobject.Filename
import com.teletronics.storage.domain.file.valueobject.UserId
import com.teletronics.storage.domain.file.valueobject.StorageKey
import com.teletronics.storage.domain.file.valueobject.Tag
import com.teletronics.storage.domain.file.valueobject.Tags
import com.teletronics.storage.domain.file.valueobject.Visibility
import com.teletronics.storage.testing.MongoContainerConfig
import com.teletronics.storage.testing.StoredFiles
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import spock.lang.Specification

import java.time.Instant

/**
 * The Mongo adapter against a real MongoDB (docs/acceptance-criteria/storage.md decision 6,
 * domain.md decision 11 for the listing order).
 */
@DataMongoTest
@Import([MongoContainerConfig, MongoFileRepository, MongoTimeoutsConfig])
class MongoFileRepositorySpec extends Specification {

    @Autowired
    MongoFileRepository repository

    @Autowired
    MongoTemplate mongo

    def setup() {
        mongo.remove(new Query(), FileDocument)
    }

    def "STO-13 the three unique indexes exist"() {
        given: "the application has started against an empty database"
        def indexes = mongo.indexOps(FileDocument).indexInfo

        expect: "the files collection has unique indexes on (ownerId, filenameKey), (ownerId, sha256) and (downloadToken)"
        def unique = indexes.findAll { it.unique }.collect { it.indexFields*.key }
        unique as Set == [["ownerId", "filenameKey"], ["ownerId", "sha256"], ["downloadToken"]] as Set
    }

    def "STO-13 inserting a second record with the same #key fails as a duplicate"() {
        given:
        def first = file(owner: "alice", name: "report.pdf", content: "one")
        repository.insert(first)

        when:
        repository.insert(second(first))

        then:
        thrown(RepositoryDuplicateException)
        mongo.count(new Query(), FileDocument) == 1

        where:
        key                     | second
        "ownerId + filenameKey" | { StoredFile f -> file(owner: "alice", name: "REPORT.PDF", content: "two") }
        "ownerId + sha256"      | { StoredFile f -> file(owner: "alice", name: "other.pdf", content: "one") }
        "downloadToken"         | { StoredFile f -> withToken(file(owner: "bob", name: "x", content: "three"), f.downloadToken()) }
    }

    def "the same name and content for another owner is not a duplicate"() {
        given:
        repository.insert(file(owner: "alice", name: "report.pdf", content: "one"))

        when:
        repository.insert(file(owner: "bob", name: "report.pdf", content: "one"))

        then:
        mongo.count(new Query(), FileDocument) == 2
    }

    def "a stored file reads back unchanged, by id and by download token"() {
        given:
        def stored = file(owner: "alice", name: "Résumé.pdf", tags: ["work", "2026"], visibility: "PUBLIC")
        repository.insert(stored)

        expect:
        repository.findById(stored.id()) == Optional.of(stored)
        repository.findByDownloadToken(stored.downloadToken()) == Optional.of(stored)
        repository.findByOwnerAndFilenameKey(UserId.of("alice"), "résumé.pdf") == Optional.of(stored)
        repository.findByOwnerAndSha256(UserId.of("alice"), stored.sha256()) == Optional.of(stored)
        repository.findByOwnerAndFilenameKey(UserId.of("bob"), "résumé.pdf").isEmpty()
    }

    def "a rename onto a taken name is a duplicate; a case-only rename is not"() {
        given:
        def report = file(owner: "alice", name: "report.pdf", content: "one")
        repository.insert(report)
        repository.insert(file(owner: "alice", name: "notes.txt", content: "two"))

        when:
        repository.updateFilename(report.renamedTo(Filename.of("Report.PDF")))

        then:
        repository.findById(report.id()).get().filename().value() == "Report.PDF"

        when:
        repository.updateFilename(report.renamedTo(Filename.of("NOTES.txt")))

        then:
        thrown(RepositoryDuplicateException)
    }

    def "delete reports whether a record was removed"() {
        given:
        def stored = file(owner: "alice", name: "a")
        repository.insert(stored)

        expect:
        repository.delete(stored.id())
        !repository.delete(stored.id())
    }

    def "DOM-43 [BRIEF] the public list shows every user's PUBLIC files only"() {
        given: "alice has PUBLIC file A and PRIVATE file B, and bob has PUBLIC file C"
        def a = file(owner: "alice", name: "A", visibility: "PUBLIC")
        def b = file(owner: "alice", name: "B", visibility: "PRIVATE")
        def c = file(owner: "bob", name: "C", visibility: "PUBLIC")
        [a, b, c].each { repository.insert(it) }

        when: "anyone lists public files"
        def page = repository.find(FileQuery.publicFiles())

        then: "the list contains A and C and not B"
        page.items()*.id() as Set == [a.id(), c.id()] as Set
        page.totalItems() == 2
    }

    def "DOM-44 and DOM-45 my files, with and without a tag filter"() {
        given:
        def work = file(owner: "alice", name: "w", tags: ["work", "home"])
        def home = file(owner: "alice", name: "h", tags: ["home"])
        def bobs = file(owner: "bob", name: "b", tags: ["work"], visibility: "PUBLIC")
        [work, home, bobs].each { repository.insert(it) }

        expect:
        repository.find(FileQuery.ownedBy(UserId.of("alice"))).items()*.id() as Set == [work.id(), home.id()] as Set
        repository.find(FileQuery.ownedBy(UserId.of("alice")).withTag(Tag.of("WORK"))).items()*.id() == [work.id()]
        repository.find(FileQuery.publicFiles().withTag(Tag.of("work"))).items()*.id() == [bobs.id()]
        repository.find(FileQuery.publicFiles().withTag(Tag.of("wor"))).items().isEmpty()
    }

    def "DOM-46 each sort field, both directions: #field #direction"() {
        given: "alice has files that differ in #field"
        files.each { repository.insert(file([owner: "alice"] + it)) }

        when: "she lists her files sorted by #field #direction"
        def page = repository.find(FileQuery.ownedBy(UserId.of("alice")).sortedBy(field, direction == "asc"))

        then: "they come back in the expected order"
        page.items()*.filename()*.value() == order

        where:
        field                  | direction | files                                                                                                                                                       || order
        SortField.FILENAME     | "asc"     | [[name: "zebra.pdf"], [name: "Banana.pdf"], [name: "apple.pdf"]]                                                                                            || ["apple.pdf", "Banana.pdf", "zebra.pdf"]
        SortField.FILENAME     | "desc"    | [[name: "zebra.pdf"], [name: "Banana.pdf"], [name: "apple.pdf"]]                                                                                            || ["zebra.pdf", "Banana.pdf", "apple.pdf"]
        SortField.UPLOAD_DATE  | "desc"    | [[name: "old", uploadedAt: at(1)], [name: "new", uploadedAt: at(3)], [name: "mid", uploadedAt: at(2)]]                                                    || ["new", "mid", "old"]
        SortField.UPLOAD_DATE  | "asc"     | [[name: "old", uploadedAt: at(1)], [name: "new", uploadedAt: at(3)], [name: "mid", uploadedAt: at(2)]]                                                    || ["old", "mid", "new"]
        SortField.SIZE         | "asc"     | [[name: "big", size: 300], [name: "small", size: 1], [name: "medium", size: 20]]                                                                            || ["small", "medium", "big"]
        SortField.SIZE         | "desc"    | [[name: "big", size: 300], [name: "small", size: 1], [name: "medium", size: 20]]                                                                            || ["big", "medium", "small"]
        SortField.CONTENT_TYPE | "asc"     | [[name: "t", type: "text/plain"], [name: "p", type: "application/pdf"], [name: "i", type: "image/png"]]                                                      || ["p", "i", "t"]
        SortField.TAG          | "asc"     | [[name: "mz", tags: ["m", "z"]], [name: "none", tags: []], [name: "by", tags: ["b", "y"]], [name: "k", tags: ["k"]]]                                        || ["none", "by", "k", "mz"]
        SortField.TAG          | "desc"    | [[name: "mz", tags: ["m", "z"]], [name: "none", tags: []], [name: "by", tags: ["b", "y"]], [name: "k", tags: ["k"]]]                                        || ["mz", "by", "k", "none"]
    }

    def "DOM-48 paging never repeats or skips a file"() {
        given: "alice has 45 files, many with the same size"
        def all = (1..45).collect { file(owner: "alice", name: "f$it", size: it % 3) }
        all.each { repository.insert(it) }

        when: "she walks every page sorted by size with page size 20"
        def pages = (0..2).collect { repository.find(FileQuery.ownedBy(UserId.of("alice")).sortedBy(SortField.SIZE, true).page(it, 20)) }

        then: "she sees 3 pages of 20, 20 and 5 files"
        pages*.items()*.size() == [20, 20, 5]
        pages.every { it.totalItems() == 45 && it.totalPages() == 3 }

        and: "every file appears exactly once, in size order"
        def seen = pages*.items().flatten()
        seen*.id() as Set == all*.id() as Set
        seen.size() == 45
        seen*.size() == seen*.size().sort(false)
    }

    def "a page past the end is empty but still carries the total"() {
        given:
        3.times { repository.insert(file(owner: "alice", name: "f$it")) }

        when:
        def page = repository.find(FileQuery.ownedBy(UserId.of("alice")).page(5, 20))

        then:
        page.items().isEmpty()
        page.totalItems() == 3
    }

    // --- helpers -------------------------------------------------------------------------------------

    static Instant at(int minute) {
        Instant.parse("2026-11-23T10:00:00Z").plusSeconds(60L * minute)
    }

    static StoredFile file(Map args) {
        String name = args.name ?: UUID.randomUUID().toString()
        byte[] content = ((args.content ?: name + UUID.randomUUID()) as String).bytes
        new StoredFile(FileId.newId(), UserId.of(args.owner as String), Filename.of(name),
                Visibility.valueOf((args.visibility ?: "PRIVATE") as String), Tags.of((args.tags ?: []) as List<String>),
                (args.size != null ? args.size : content.length) as long, StoredFiles.sha256(content),
                new ContentType((args.type ?: "application/octet-stream") as String),
                (args.uploadedAt ?: StoredFiles.UPLOADED_AT) as Instant, DownloadToken.generate(), StorageKey.random())
    }

    static StoredFile withToken(StoredFile f, DownloadToken token) {
        new StoredFile(f.id(), f.owner(), f.filename(), f.visibility(), f.tags(), f.size(), f.sha256(), f.contentType(),
                f.uploadedAt(), token, f.storageKey())
    }
}
