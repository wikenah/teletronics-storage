package com.teletronics.storage.presentation.http

import com.teletronics.storage.application.FileService
import com.teletronics.storage.application.exception.FileTooLargeException
import com.teletronics.storage.application.exception.StorageUnavailableException
import com.teletronics.storage.application.upload.PendingUpload
import com.teletronics.storage.application.upload.UploadCommand
import com.teletronics.storage.domain.file.exception.DuplicateFileException
import com.teletronics.storage.domain.file.exception.FileNotFoundException
import com.teletronics.storage.domain.file.exception.InvalidFileException
import com.teletronics.storage.domain.file.exception.RepositoryUnavailableException
import com.teletronics.storage.domain.file.query.FileQuery
import com.teletronics.storage.domain.file.query.PageResult
import com.teletronics.storage.domain.file.query.SortField
import com.teletronics.storage.domain.file.valueobject.FileId
import com.teletronics.storage.domain.file.valueobject.OwnerId
import com.teletronics.storage.domain.file.valueobject.Tag
import com.teletronics.storage.testing.StoredFiles
import groovy.json.JsonSlurper
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * The /files endpoints with the file service faked (docs/acceptance-criteria/http-api.md, sections A, B, C, F).
 */
@WebMvcTest(controllers = [FilesController])
class FilesControllerSpec extends Specification {

    @Autowired
    MockMvc mvc

    @SpringBean
    FileService files = Mock()

    // --- A. Validation ------------------------------------------------------------------------------

    def "API-01 missing or blank user id is rejected (#variant)"() {
        given: "a valid upload request"
        def request = upload(headers: ["X-User-Id": userId])

        when: "it is sent #variant the X-User-Id header"
        def response = perform(request)

        then: "the response is 400 with type /problems/invalid-request"
        response.status == 400
        problem(response).type == "/problems/invalid-request"
        problem(response).field == "X-User-Id"

        and: "the file service is not called"
        0 * files._

        where:
        variant                | userId
        "without"              | null
        "with a blank value in" | "   "
    }

    def "API-02 missing or blank filename is rejected (#variant)"() {
        given: "a valid upload request"
        def request = upload(params: [filename: filename])

        when: "the filename parameter is #variant"
        def response = perform(request)

        then: "the response is 400 with type /problems/invalid-request"
        response.status == 400
        problem(response).type == "/problems/invalid-request"

        and: "the file service is not called"
        0 * files._

        where:
        variant   | filename
        "missing" | null
        "blank"   | ["  "]
    }

    def "API-03 invalid visibility is rejected (#description)"() {
        given: "a valid upload request"
        def request = upload(params: [visibility: visibility])

        when: "visibility is #description"
        def response = perform(request)

        then: "the response is 400 with type /problems/invalid-request"
        response.status == 400
        problem(response).type == "/problems/invalid-request"
        problem(response).field == "visibility"

        and: "the file service is not called"
        0 * files._

        where:
        description | visibility
        "missing"   | null
        "SECRET"    | ["SECRET"]
        "empty"     | [""]
        "lowercase" | ["public"]
    }

    def "API-04 invalid tags are rejected (#description)"() {
        given: "a valid upload request"
        def request = upload(params: [tags: tags])

        when: "its tags are #description"
        def response = perform(request)

        then: "the response is 400 with type /problems/invalid-request"
        response.status == 400
        problem(response).type == "/problems/invalid-request"
        problem(response).field == "tags"

        and: "the file service is not called"
        0 * files._

        where:
        description                    | tags
        "six repeated values"          | ["a", "b", "c", "d", "e", "f"]
        "a value containing a comma"   | ["a,b"]
        "a mix of comma and repeated"  | ["a,b", "c"]
    }

    def "API-05 form content types are rejected (#type)"() {
        given: "a valid upload request"
        def request = upload(headers: ["Content-Type": type])

        when: "its Content-Type is #type"
        def response = perform(request)

        then: "the response is 415 with type about:blank, saying to send the raw bytes"
        response.status == 415
        problem(response).type == "about:blank"
        problem(response).detail.contains("raw request body")

        and: "the file service is not called"
        0 * files._

        where:
        type << ["multipart/form-data; boundary=x", "application/x-www-form-urlencoded"]
    }

    def "API-24 compressed request bodies are rejected (#coding)"() {
        given: "a valid upload request"
        def request = upload(headers: ["Content-Encoding": coding])

        when: "it carries Content-Encoding: #coding"
        def response = perform(request)

        then: "the response is 415 with type /problems/unsupported-content-encoding"
        response.status == 415
        problem(response).type == "/problems/unsupported-content-encoding"

        and: "the response carries Accept-Encoding: identity"
        response.getHeader("Accept-Encoding") == "identity"

        and: "the file service is not called"
        0 * files._

        where:
        coding << ["gzip", "deflate", "br"]
    }

    def "API-24 Content-Encoding identity is accepted"() {
        given:
        def pending = Mock(PendingUpload)

        when:
        def response = perform(upload(headers: ["Content-Encoding": "identity"]))

        then:
        1 * files.startUpload(_) >> pending
        1 * pending.complete(_) >> StoredFiles.file("alice", "report.pdf")
        response.status == 201
    }

    // --- B. What the HTTP layer hands to the file service -------------------------------------------

    def "API-07 the request reaches the file service intact"() {
        given: "alice sends report.pdf, PRIVATE, tags work and 2026, application/pdf and a 1 KiB body"
        byte[] body = (0..<1024).collect { (byte) it } as byte[]
        def pending = Mock(PendingUpload)
        UploadCommand command = null
        byte[] received = null

        when: "the request is processed"
        def response = perform(upload(params: [tags: ["work", "2026"]], body: body))

        then: "the file service receives the values exactly as sent"
        1 * files.startUpload(_) >> { UploadCommand c -> command = c; pending }
        command.owner().value() == "alice"
        command.filename().value() == "report.pdf"
        command.visibility().name() == "PRIVATE"
        command.tags().values() == ["work", "2026"]
        command.declaredContentType().map { it.value() } == Optional.of("application/pdf")
        command.declaredLength() == OptionalLong.of(1024)

        and: "the body stream it reads yields exactly the 1024 bytes sent and then ends normally"
        1 * pending.complete(_) >> { InputStream in -> received = in.readAllBytes(); StoredFiles.file("alice", "report.pdf") }
        received == body

        and: "the pending upload is always closed"
        1 * pending.close()
        response.status == 201
    }

    def "API-08 missing Content-Type is accepted"() {
        given: "a valid upload request without a Content-Type header"
        def pending = Mock(PendingUpload)
        UploadCommand command = null

        when: "it is sent"
        def response = perform(upload(headers: ["Content-Type": null]))

        then: "the file service receives no content type"
        1 * files.startUpload(_) >> { UploadCommand c -> command = c; pending }
        1 * pending.complete(_) >> StoredFiles.file("alice", "report.pdf")
        command.declaredContentType().isEmpty()

        and: "the request is not rejected by the HTTP layer"
        response.status == 201
    }

    // --- C. Responses -------------------------------------------------------------------------------

    def "API-12 successful upload"() {
        given: "the file service accepts the upload and returns file F"
        def file = StoredFiles.file("alice", "report.pdf")
        def pending = Mock(PendingUpload) { complete(_) >> file }
        files.startUpload(_) >> pending

        when: "the request completes"
        def response = perform(upload())
        def json = json(response)

        then: "the response is 201 Created"
        response.status == 201

        and: "Location is /files/{F.id}"
        response.getHeader("Location") == "/files/" + file.id().value()

        and: "the body is F's JSON representation"
        json.id == file.id().value()
        json.filename == "report.pdf"
        json.visibility == "PRIVATE"
        json.tags == ["work"]
        json.size == file.size()
        json.sha256 == file.sha256().hex()
        json.contentType == "application/pdf"
        json.uploadedAt == "2026-11-23T10:00:00Z"
        json.downloadLink == "/download/" + file.downloadToken().value()

        and: "it never shows the owner, the storage key or the name key (DOM-50)"
        json.keySet() == ["id", "filename", "visibility", "tags", "size", "sha256", "contentType", "uploadedAt", "downloadLink"] as Set
    }

    def "API-15 duplicate detected after the body was read (#kind)"() {
        given: "the file service reports, after reading the body, that alice already has a file E with the same #kind"
        def existing = StoredFiles.file("alice", "report.pdf")
        def pending = Mock(PendingUpload) { complete(_) >> { throw new DuplicateFileException(kind, existing) } }
        files.startUpload(_) >> pending

        when: "the upload completes"
        def response = perform(upload())

        then: "the response is 409 with type #type"
        response.status == 409
        problem(response).type == type

        and: "it carries existingFile with E's id, size and SHA-256"
        problem(response).existingFile == [id: existing.id().value(), size: existing.size(), sha256: existing.sha256().hex()]

        where:
        kind                                 || type
        DuplicateFileException.Kind.FILENAME || "/problems/filename-exists"
        DuplicateFileException.Kind.CONTENT  || "/problems/content-exists"
    }

    def "API-15 filename taken at the pre-check is a 409 too"() {
        given: "the file service reports before the body that the name is taken"
        def existing = StoredFiles.file("alice", "report.pdf")
        files.startUpload(_) >> { throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, existing) }

        when:
        def response = perform(upload())

        then:
        response.status == 409
        problem(response).type == "/problems/filename-exists"
        problem(response).existingFile.id == existing.id().value()
    }

    def "API-16 file service failures (#failure)"() {
        given: "the file service fails with #failure"
        def pending = Mock(PendingUpload) { complete(_) >> { throw error } }
        files.startUpload(_) >> pending

        when: "the upload is processed"
        def response = perform(upload())

        then: "the response is #status with type #type"
        response.status == status
        problem(response).type == type

        and: "the body contains no internal details"
        !response.contentAsString.contains("10.0.0.5")

        where:
        failure                | error                                                                 || status | type
        "storage unavailable"  | new StorageUnavailableException("s3 at 10.0.0.5:8333 refused", null) || 503    | "/problems/temporarily-unavailable"
        "database unavailable" | new RepositoryUnavailableException("mongo 10.0.0.5:27017 down", null) || 503    | "/problems/temporarily-unavailable"
        "any other error"      | new IllegalStateException("bug near 10.0.0.5")                      || 500    | "about:blank"
    }

    def "API-32 a file that can never fit is rejected up front"() {
        given: "the file service reports that the declared Content-Length needs a part larger than the budget"
        files.startUpload(_) >> { throw new FileTooLargeException("the file is too large to store with bounded memory") }

        when: "the upload's headers arrive"
        def response = perform(upload())

        then: "the response is 413 with type /problems/file-too-large"
        response.status == 413
        problem(response).type == "/problems/file-too-large"
    }

    // --- F. Other endpoints -------------------------------------------------------------------------

    def "API-25 get one file"() {
        given: "the file service returns file F for owner alice"
        def file = StoredFiles.file("alice", "report.pdf")
        files.get(OwnerId.of("alice"), file.id()) >> file

        when: "alice sends GET /files/{F.id}"
        def response = perform(get("/files/" + file.id().value()).header("X-User-Id", "alice"))

        then: "the response is 200 with F's JSON representation"
        response.status == 200
        json(response).id == file.id().value()
        json(response).filename == "report.pdf"
    }

    def "API-25 a file that doesn't exist or isn't the caller's is 404"() {
        given:
        files.get(_, _) >> { throw new FileNotFoundException() }

        when:
        def response = perform(get("/files/some-id").header("X-User-Id", "bob"))

        then:
        response.status == 404
        problem(response).type == "/problems/file-not-found"
    }

    def "API-25 a blank file id is 404, not an internal error (#method)"() {
        when: "alice sends #method for a file id of only spaces"
        def response = perform(request.header("X-User-Id", "alice").contentType("application/json")
                .content('{"filename": "new.txt"}'))

        then: "the response is 404 /problems/file-not-found"
        response.status == 404
        problem(response).type == "/problems/file-not-found"

        and: "the file service is not called"
        0 * files._

        where:
        method   | request
        "GET"    | get(URI.create("/files/%20"))
        "PATCH"  | patch(URI.create("/files/%20"))
        "DELETE" | delete(URI.create("/files/%20"))
    }

    def "an owner is required on every owner endpoint (#method #path)"() {
        when: "the request is sent without X-User-Id"
        def response = perform(request)

        then: "the response is 400 with type /problems/invalid-request and the file service is not called"
        response.status == 400
        problem(response).type == "/problems/invalid-request"
        0 * files._

        where:
        method   | path         | request
        "GET"    | "/files/x"   | get("/files/x")
        "PATCH"  | "/files/x"   | patch("/files/x").contentType("application/json").content('{"filename":"a.txt"}')
        "DELETE" | "/files/x"   | delete("/files/x")
        "GET"    | "/files"     | get("/files")
    }

    def "API-26 rename: #description"() {
        given: "alice owns file F"
        def file = StoredFiles.file("alice", "report.pdf")
        def request = patch("/files/" + file.id().value()).header("X-User-Id", "alice")
                .contentType(contentType).content(body)

        when: "she sends PATCH /files/{F.id} with #description"
        def response = perform(request)

        then: "the file service is asked to rename only when the body is exactly {\"filename\": string}"
        calls * files.rename(OwnerId.of("alice"), file.id(), newName) >> {
            if (failure) throw failure
            file.renamedTo(com.teletronics.storage.domain.file.valueobject.Filename.of(newName))
        }

        and: "the response is #status"
        response.status == status
        type == null || problem(response).type == type
        status != 200 || json(response).filename == newName

        where:
        description                     | contentType        | body                                         | newName     | failure                                                                         || calls | status | type
        "a new name"                    | "application/json" | '{"filename": "new.pdf"}'                    | "new.pdf"   | null                                                                            || 1     | 200    | null
        "a name the domain rejects"     | "application/json" | '{"filename": "a/b.pdf"}'                    | "a/b.pdf"   | new InvalidFileException("filename", "filename must not contain '/'")          || 1     | 400    | "/problems/invalid-request"
        "a name that is taken"          | "application/json" | '{"filename": "taken.pdf"}'                  | "taken.pdf" | new DuplicateFileException(DuplicateFileException.Kind.FILENAME, StoredFiles.file("alice", "taken.pdf")) || 1 | 409 | "/problems/filename-exists"
        "a file that isn't hers"        | "application/json" | '{"filename": "new.pdf"}'                    | "new.pdf"   | new FileNotFoundException()                                                     || 1     | 404    | "/problems/file-not-found"
        "only tags"                     | "application/json" | '{"tags": ["x"]}'                            | _           | null                                                                            || 0     | 400    | "/problems/invalid-request"
        "a name and a visibility"       | "application/json" | '{"filename": "x", "visibility": "PUBLIC"}'  | _           | null                                                                            || 0     | 400    | "/problems/invalid-request"
        "an empty object"               | "application/json" | '{}'                                         | _           | null                                                                            || 0     | 400    | "/problems/invalid-request"
        "a filename that isn't a string" | "application/json" | '{"filename": 42}'                          | _           | null                                                                            || 0     | 400    | "/problems/invalid-request"
        "malformed JSON"                | "application/json" | '{"filename": '                              | _           | null                                                                            || 0     | 400    | "/problems/invalid-request"
        "a body that isn't JSON"        | "text/plain"       | 'new.pdf'                                    | _           | null                                                                            || 0     | 415    | "about:blank"
    }

    def "API-26 the existing file comes with a rename conflict"() {
        given:
        def existing = StoredFiles.file("alice", "taken.pdf")
        files.rename(_, _, _) >> { throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, existing) }

        when:
        def response = perform(patch("/files/x").header("X-User-Id", "alice")
                .contentType("application/json").content('{"filename": "taken.pdf"}'))

        then:
        response.status == 409
        problem(response).existingFile.id == existing.id().value()
    }

    def "API-27 delete"() {
        given: "alice owns file F"
        def id = FileId.newId()

        when: "she sends DELETE /files/{F.id}"
        def response = perform(delete("/files/" + id.value()).header("X-User-Id", "alice"))

        then: "the file service deletes it as alice's"
        1 * files.delete(OwnerId.of("alice"), id)

        and: "the response is 204 with no body"
        response.status == 204
        response.contentAsByteArray.length == 0
    }

    def "API-27 deleting a file that doesn't exist or isn't the caller's is 404"() {
        given:
        files.delete(_, _) >> { throw new FileNotFoundException() }

        when:
        def response = perform(delete("/files/x").header("X-User-Id", "bob"))

        then:
        response.status == 404
        problem(response).type == "/problems/file-not-found"
    }

    def "API-28 list my files with no parameters"() {
        given: "alice has files"
        def file = StoredFiles.file("alice", "report.pdf")
        FileQuery query = null

        when: "she sends GET /files"
        def response = perform(get("/files").header("X-User-Id", "alice"))
        def json = json(response)

        then: "the file service is asked for alice's files: page 0, size 20, newest first"
        1 * files.list(_) >> { FileQuery q -> query = q; new PageResult([file], q.page(), q.size(), 1) }
        query == FileQuery.ownedBy(OwnerId.of("alice"))
        query.sortField() == SortField.UPLOAD_DATE && !query.ascending()

        and: "the response is 200 with items, page, size, totalItems and totalPages"
        response.status == 200
        json.keySet() == ["items", "page", "size", "totalItems", "totalPages"] as Set
        json.items*.id == [file.id().value()]
        json.page == 0
        json.size == 20
        json.totalItems == 1
        json.totalPages == 1
    }

    def "API-28 list my files with a tag, a sort and a page"() {
        given:
        FileQuery query = null

        when: "alice sends GET /files?tag=Work&sort=size,desc&page=1&size=5"
        def response = perform(get("/files").header("X-User-Id", "alice")
                .queryParam("tag", "Work").queryParam("sort", "size,desc")
                .queryParam("page", "1").queryParam("size", "5"))

        then: "the file service receives tag \"Work\", sort size descending, page 1, size 5"
        1 * files.list(_) >> { FileQuery q -> query = q; new PageResult([], q.page(), q.size(), 0) }
        query.owner() == Optional.of(OwnerId.of("alice"))
        query.tag() == Optional.of(Tag.of("Work"))
        query.sortField() == SortField.SIZE
        !query.ascending()
        query.page() == 1
        query.size() == 5
        response.status == 200
    }

    def "API-28 #path: invalid listing parameters are rejected (#params)"() {
        when:
        def request = get(path).header("X-User-Id", "alice")
        params.each { name, values -> request.queryParam(name, values as String[]) }
        def response = perform(request)

        then: "the response is 400 with type /problems/invalid-request"
        response.status == 400
        problem(response).type == "/problems/invalid-request"

        and: "the file service is not called"
        0 * files._

        where:
        [path, params] << [["/files", "/files/public"], [
                [sort: ["storageKey,asc"]],
                [sort: ["size,sideways"]],
                [size: ["101"]],
                [size: ["0"]],
                [page: ["-1"]],
                [page: ["first"]],
                [tag: ["a,b"]],
                [tag: ["a", "b"]],
        ]].combinations()
    }

    def "API-29 list public files (#variant)"() {
        given:
        FileQuery query = null
        def request = get("/files/public").queryParam("sort", "filename,asc")
        if (userId) request.header("X-User-Id", userId)

        when: "anyone sends GET /files/public #variant"
        def response = perform(request)

        then: "the response has the same shape"
        1 * files.list(_) >> { FileQuery q -> query = q; new PageResult([], q.page(), q.size(), 0) }
        response.status == 200
        json(response).keySet() == ["items", "page", "size", "totalItems", "totalPages"] as Set

        and: "the query is the public listing whoever asks"
        query.isPublicListing()
        query.sortField() == SortField.FILENAME && query.ascending()

        where:
        variant              | userId
        "without a user id"  | null
        "with a user id"     | "alice"
    }

    // --- helpers -------------------------------------------------------------------------------------

    /**
     * A valid upload by alice: report.pdf, PRIVATE, application/pdf, a small body.
     * A null value removes a parameter or header.
     */
    MockHttpServletRequestBuilder upload(Map options = [:]) {
        Map<String, List<String>> params = [filename: ["report.pdf"], visibility: ["PRIVATE"]] + (options.params ?: [:])
        Map<String, String> headers = ["X-User-Id": "alice", "Content-Type": "application/pdf"] + (options.headers ?: [:])
        def request = post("/files")
        params.each { name, values -> if (values != null) request.queryParam(name, values as String[]) }
        headers.each { name, value -> if (value != null) request.header(name, value) }
        request.content((byte[]) (options.body ?: "hello".bytes))
    }

    MockHttpServletResponse perform(MockHttpServletRequestBuilder request) {
        mvc.perform(request).andReturn().response
    }

    /** RFC 9457 §3.1.1: an absent "type" means about:blank (Spring 7 leaves it out). */
    static Map problem(MockHttpServletResponse response) {
        assert response.contentType?.startsWith("application/problem+json")
        def body = json(response) as Map
        [type: "about:blank"] + body
    }

    static Object json(MockHttpServletResponse response) {
        new JsonSlurper().parse(response.contentAsByteArray)
    }
}
