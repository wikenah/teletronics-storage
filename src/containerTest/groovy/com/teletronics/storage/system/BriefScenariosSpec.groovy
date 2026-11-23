package com.teletronics.storage.system

import com.teletronics.storage.testing.ApiResponse
import com.teletronics.storage.testing.RawHttpClient
import com.teletronics.storage.testing.StorageApiClient
import static com.teletronics.storage.testing.TestBytes.sha256
import static com.teletronics.storage.testing.TestBytes.unique
import spock.lang.Shared
import spock.lang.Specification


/**
 * Every scenario in the brief, through the REST API only, against the shipped Docker image with MongoDB and
 * SeaweedFS beside it. Grouped and worded as in the brief ("TestProject Backend V2"). Each feature uses
 * users of its own, so features never see each other's files.
 *
 * The 2 GB upload (checklist 1.3) runs on the same stack in LargeUploadSpec, with the container limits.
 */
class BriefScenariosSpec extends Specification {

    static final byte[] PNG = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52] as byte[]
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".bytes
    static final byte[] TEXT = "Shopping list\nmilk\neggs\nbread\n".bytes

    @Shared
    ApplicationStack stack = ApplicationStack.shared()

    @Shared
    StorageApiClient api = new StorageApiClient(stack.baseUrl())

    def alice = user("alice")
    def bob = user("bob")

    // --- "User should be able to upload a file with a filename with: a visibility setting (PUBLIC/PRIVATE),
    //      set of tags (up to 5 per file), without limit on the file size" ---------------------------------

    def "Upload a file with a filename, a #visibility visibility and 5 tags"() {
        given: "a file of a few KB"
        byte[] content = unique(3 * 1024)

        when: "alice uploads it with a filename, a visibility and 5 tags"
        def response = api.upload(alice, "Quarterly Report.pdf", content,
                [visibility: visibility, tags: ["Work", "2026", "Q4", "draft", "Finance"]])
        def file = response.json()

        then: "it is created, with its location relative to the service"
        response.status == 201
        response.header("Location") == "/files/" + file.id

        and: "the file has the filename, visibility and tags given (tags lowercased), and the server's size and SHA-256"
        file.filename == "Quarterly Report.pdf"
        file.visibility == visibility
        file.tags == ["work", "2026", "q4", "draft", "finance"]
        file.size == content.length
        file.sha256 == sha256(content)

        and: "it can be read back"
        api.get(alice, file.id).json().filename == "Quarterly Report.pdf"

        where:
        visibility << ["PUBLIC", "PRIVATE"]
    }

    def "An upload is refused with #problem"() {
        when:
        def response = api.upload(alice, "a.txt", unique(100), options)

        then: "the response is 400 invalid-request, naming the field"
        response.status == 400
        response.problemType() == "/problems/invalid-request"
        response.json().field == field

        and: "nothing was stored"
        api.myFiles(alice).json().totalItems == 0

        where:
        problem                     | options                                         || field
        "more than 5 tags"          | [tags: ["a", "b", "c", "d", "e", "f"]]          || "tags"
        "no visibility"             | [visibility: null]                              || "visibility"
        "a visibility not in PUBLIC/PRIVATE" | [visibility: "SECRET"]                 || "visibility"
    }

    def "Files of any size upload and download byte for byte (#description)"() {
        given:
        byte[] content = unique(size)

        when: "alice uploads the file"
        def file = uploaded(alice, "file.bin", content)

        then: "downloading it returns exactly the same bytes"
        def download = api.download(file.downloadLink)
        download.status == 200
        sha256(download.body) == sha256(content)
        file.size == size

        where:
        description                                | size
        "a few KB"                                 | 3 * 1024
        "20 MiB, stored in three 8 MiB parts"      | 20 * 1024 * 1024
        // 2 GiB: LargeUploadSpec. Hundreds of GB: part sizes grow with the declared length (STO-05).
    }

    // --- "User should be able to change the filename of files uploaded"
    //     Checklist 2: "Changing a name of file should not need reupload of a file." ---------------------------

    def "Change the filename without uploading the file again"() {
        given: "alice has uploaded \"draft.txt\""
        byte[] content = unique(4096)
        def original = uploaded(alice, "draft.txt", content, [tags: ["work"]])

        when: "she sends only the new name"
        def response = api.rename(alice, original.id, "final.txt")
        def renamed = response.json()

        then: "the file has the new name"
        response.status == 200
        renamed.filename == "final.txt"
        api.myFiles(alice).json().items*.filename == ["final.txt"]

        and: "it is the same file: same id, bytes, size, SHA-256, tags, upload date and download link"
        renamed.id == original.id
        renamed.size == original.size
        renamed.sha256 == original.sha256
        renamed.tags == original.tags
        renamed.uploadedAt == original.uploadedAt
        renamed.downloadLink == original.downloadLink

        and: "the link downloads the same bytes under the new name"
        def download = api.download(original.downloadLink)
        download.body == content
        download.header("Content-Disposition").endsWith("filename*=UTF-8''final.txt")
    }

    def "A rename is refused onto a name the user already has, and for someone else's file"() {
        given: "alice has \"a.txt\" and \"b.txt\""
        def a = uploaded(alice, "a.txt")
        uploaded(alice, "b.txt")

        expect: "renaming a.txt to B.TXT is refused as filename-exists"
        api.rename(alice, a.id, "B.TXT").with { status == 409 && problemType() == "/problems/filename-exists" }

        and: "bob can't rename alice's file"
        api.rename(bob, a.id, "mine.txt").with { status == 404 && problemType() == "/problems/file-not-found" }

        and: "a.txt keeps its name"
        api.get(alice, a.id).json().filename == "a.txt"
    }

    // --- "User should be able to list files stored in the application that: all PUBLIC;
    //      all files that belong to USER"   Checklist 1.5: "List all public files." ---------------------------

    def "[Checklist 1.5] List all public files"() {
        given: "alice has PUBLIC file A and PRIVATE file B, bob has PUBLIC file C, all with this feature's tag"
        def tag = runTag()
        def a = uploaded(alice, "A.txt", unique(100), [visibility: "PUBLIC", tags: [tag]])
        def b = uploaded(alice, "B.txt", unique(100), [visibility: "PRIVATE", tags: [tag]])
        def c = uploaded(bob, "C.txt", unique(100), [visibility: "PUBLIC", tags: [tag]])

        when: "anyone lists public files, without a user id"
        def page = api.publicFiles([tag: tag]).json()

        then: "A and C are listed, B is not"
        page.items*.id as Set == [a.id, c.id] as Set
        page.totalItems == 2

        and: "the unfiltered public list never contains a PRIVATE file"
        def all = api.publicFiles([size: 100]).json()
        all.items*.visibility.every { it == "PUBLIC" }
        !(b.id in all.items*.id)
    }

    def "List all files that belong to the user"() {
        given: "alice has PUBLIC file A and PRIVATE file B, bob has PUBLIC file C"
        def a = uploaded(alice, "A.txt", unique(100), [visibility: "PUBLIC"])
        def b = uploaded(alice, "B.txt", unique(100), [visibility: "PRIVATE"])
        def c = uploaded(bob, "C.txt", unique(100), [visibility: "PUBLIC"])

        expect: "alice's list has A and B, PUBLIC and PRIVATE, and not C"
        api.myFiles(alice).json().items*.id as Set == [a.id, b.id] as Set

        and: "bob's list has only C"
        api.myFiles(bob).json().items*.id == [c.id]

        and: "the user id comes with the request (no session): without it, the list is refused"
        api.myFiles(null).with { status == 400 && json().field == "X-User-Id" }
    }

    // --- "All file lists should have filter by TAG - tag should be selected from the list (no tag guessing);
    //      TAG and tAg - is the same tag" ------------------------------------------------------------------

    def "Filter #list by tag: case doesn't matter, and there is no tag guessing"() {
        given: "files tagged with a tag of this feature in one case, and another file with another tag"
        def tag = runTag()
        def tagged = uploaded(alice, "tagged.txt", unique(100), [visibility: "PUBLIC", tags: [tag.toUpperCase()]])
        uploaded(alice, "other.txt", unique(100), [visibility: "PUBLIC", tags: [runTag()]])

        expect: "the tag in any case finds the tagged file only"
        ids(list, [tag: tag]) == [tagged.id]
        ids(list, [tag: tag.toUpperCase()]) == [tagged.id]
        ids(list, [tag: mixedCase(tag)]) == [tagged.id]

        and: "no tag guessing: part of the tag, or a tag nobody used, finds nothing"
        ids(list, [tag: tag.dropRight(1)]) == []
        ids(list, [tag: tag + "x"]) == []

        where:
        list << ["my files", "public files"]
    }

    def "[Checklist 7] A tag that doesn't exist yet is created by using it"() {
        given: "a tag nobody has used"
        def tag = runTag()
        assert api.publicFiles([tag: tag]).json().totalItems == 0

        when: "alice uploads a file with it"
        def file = uploaded(alice, "new-tag.txt", unique(100), [visibility: "PUBLIC", tags: [tag]])

        then: "the file carries the tag, and the tag can be filtered by"
        file.tags == [tag]
        api.publicFiles([tag: tag]).json().items*.id == [file.id]
    }

    // --- "All file lists should be sort-able by the FILENAME, UPLOAD DATE, TAG, CONTENT TYPE and FILE SIZE" ---

    def "Sort my files by #field #direction"() {
        given: "alice uploads files, in this order, that differ in #field"
        files.each { Map f ->
            uploaded(alice, f.name as String, unique((f.size ?: 256) as int),
                    [tags: f.tags ?: [], contentType: f.type ?: "application/octet-stream"])
        }

        when: "she lists her files sorted by #field #direction"
        def page = api.myFiles(alice, [sort: "$field,$direction"]).json()

        then:
        page.items*.filename == order

        where:
        field         | direction | files                                                                                                  || order
        "filename"    | "asc"     | [[name: "zebra.txt"], [name: "Banana.txt"], [name: "apple.txt"]]                                       || ["apple.txt", "Banana.txt", "zebra.txt"]
        "filename"    | "desc"    | [[name: "zebra.txt"], [name: "Banana.txt"], [name: "apple.txt"]]                                       || ["zebra.txt", "Banana.txt", "apple.txt"]
        "uploadDate"  | "asc"     | [[name: "first"], [name: "second"], [name: "third"]]                                                   || ["first", "second", "third"]
        "uploadDate"  | "desc"    | [[name: "first"], [name: "second"], [name: "third"]]                                                   || ["third", "second", "first"]
        "tag"         | "asc"     | [[name: "mz", tags: ["m", "z"]], [name: "none"], [name: "by", tags: ["b", "y"]], [name: "k", tags: ["k"]]] || ["none", "by", "k", "mz"]
        "tag"         | "desc"    | [[name: "mz", tags: ["m", "z"]], [name: "none"], [name: "by", tags: ["b", "y"]], [name: "k", tags: ["k"]]] || ["mz", "by", "k", "none"]
        "contentType" | "asc"     | [[name: "t", type: "text/plain"], [name: "p", type: "application/pdf"], [name: "i", type: "image/png"]] || ["p", "i", "t"]
        "contentType" | "desc"    | [[name: "t", type: "text/plain"], [name: "p", type: "application/pdf"], [name: "i", type: "image/png"]] || ["t", "i", "p"]
        "size"        | "asc"     | [[name: "big", size: 3000], [name: "small", size: 100], [name: "medium", size: 1000]]                  || ["small", "medium", "big"]
        "size"        | "desc"    | [[name: "big", size: 3000], [name: "small", size: 100], [name: "medium", size: 1000]]                  || ["big", "medium", "small"]
    }

    def "Sort public files too, and the default order is newest first"() {
        given: "PUBLIC files of two users, uploaded in this order, with this feature's tag"
        def tag = runTag()
        uploaded(alice, "b-medium", unique(500), [visibility: "PUBLIC", tags: [tag]])
        uploaded(bob, "a-big", unique(900), [visibility: "PUBLIC", tags: [tag]])
        uploaded(alice, "c-small", unique(10), [visibility: "PUBLIC", tags: [tag]])

        expect:
        names(api.publicFiles([tag: tag, sort: "size,desc"])) == ["a-big", "b-medium", "c-small"]
        names(api.publicFiles([tag: tag, sort: "filename,asc"])) == ["a-big", "b-medium", "c-small"]
        names(api.publicFiles([tag: tag])) == ["c-small", "a-big", "b-medium"]

        and: "only the five sort fields are accepted"
        api.publicFiles([sort: "ownerId,asc"]).status == 400
    }

    // --- "All file lists should be divided into result pages containing defined number of results" ----------

    def "Lists are divided into pages with a defined number of results"() {
        given: "alice has 5 files"
        def names = (1..5).collect { "file-${it}.txt" }
        names.each { uploaded(alice, it) }

        when: "she reads her files 2 per page, sorted by filename"
        def pages = (0..3).collect { api.myFiles(alice, [sort: "filename,asc", page: it, size: 2]).json() }

        then: "the pages hold 2, 2, 1 and 0 files, and each carries the totals"
        pages.collect { it.items.size() } == [2, 2, 1, 0]
        pages*.page == [0, 1, 2, 3]
        pages.every { it.size == 2 && it.totalItems == 5 && it.totalPages == 3 }

        and: "every file appears exactly once, in order"
        pages.collectMany { it.items*.filename } == names

        and: "the default page size is 20, and at most 100 can be asked for"
        api.myFiles(alice).json().size == 20
        api.myFiles(alice, [size: 101]).status == 400

        and: "the public list is paged the same way"
        def tag = runTag()
        3.times { uploaded(bob, "public-$it", unique(100), [visibility: "PUBLIC", tags: [tag]]) }
        api.publicFiles([tag: tag, size: 2]).json().with { items.size() == 2 && totalItems == 3 && totalPages == 2 }
        api.publicFiles([tag: tag, size: 2, page: 1]).json().items.size() == 1
    }

    // --- "User should be only able to delete files that they uploaded"
    //     Checklist 1.4: "Try to delete file that does not belong to user." --------------------------------

    def "Delete your own file"() {
        given: "alice has a file"
        byte[] content = unique(1000)
        def file = uploaded(alice, "old.txt", content)

        when: "she deletes it"
        def response = api.delete(alice, file.id)

        then: "it is deleted: 204, gone from her list, and its link finds nothing"
        response.status == 204
        api.get(alice, file.id).status == 404
        api.myFiles(alice).json().totalItems == 0
        api.download(file.downloadLink).status == 404

        and: "she can upload the same file again"
        api.upload(alice, "old.txt", content).status == 201
    }

    def "[Checklist 1.4] Deleting a #visibility file that belongs to another user is refused"() {
        given: "alice has a #visibility file"
        byte[] content = unique(1000)
        def file = uploaded(alice, "alice.txt", content, [visibility: visibility])

        when: "bob tries to delete it"
        def response = api.delete(bob, file.id)

        then: "it is refused as not found, so bob can't even tell whether it exists"
        response.status == 404
        response.problemType() == "/problems/file-not-found"

        and: "the file is untouched: still in alice's list and downloadable in full"
        api.myFiles(alice).json().items*.id == [file.id]
        api.download(file.downloadLink).body == content

        where:
        visibility << ["PRIVATE", "PUBLIC"]
    }

    // --- "After successful upload, the application should identify the type of the file (if not provided by user)"
    //     Checklist 4: "File type analysis can be done after uploading." -----------------------------------

    def "The file type is identified when the user doesn't provide it (#description)"() {
        when: "alice uploads #description"
        def file = uploaded(alice, name, content, [contentType: declared])

        then: "the file's type is #expected, and the download is sent with it"
        file.contentType == expected
        api.download(file.downloadLink).header("Content-Type") == expected

        where:
        description                                  | name       | content    | declared                   || expected
        "PNG bytes without a Content-Type"           | "photo"    | PNG        | null                       || "image/png"
        "PDF bytes with the generic octet-stream"    | "doc.bin"  | PDF        | "application/octet-stream" || "application/pdf"
        "plain text without a Content-Type"          | "notes"    | TEXT       | null                       || "text/plain"
        "unrecognisable bytes without a Content-Type" | "blob"    | unique(512) | null                      || "application/octet-stream"
        "a file whose type the user provides"        | "data.csv" | "a,b\n1,2\n".bytes | "text/csv"         || "text/csv"
    }

    // --- "After successful upload, user should be provided with a unique download link for the file - No link guessing"
    //     Checklist 5: "Download link should be relative to the service." -----------------------------------

    def "Every upload gets its own download link, relative to the service"() {
        when: "alice uploads two files"
        def first = uploaded(alice, "one.txt")
        def second = uploaded(alice, "two.txt")

        then: "each gets a link of the form /download/{token}, relative to the service"
        [first, second].every { it.downloadLink ==~ "/download/[A-Za-z0-9_-]{22}" }

        and: "the links differ, and say nothing about the file or its owner"
        first.downloadLink != second.downloadLink
        [first, second].every { f -> !f.downloadLink.contains(f.id) && !f.downloadLink.contains("one") && !f.downloadLink.contains(alice) }
    }

    def "No link guessing: #guess finds nothing"() {
        given: "alice has a file with a download link"
        def file = uploaded(alice, "secret.txt", unique(100), [visibility: "PRIVATE"])

        when: "someone requests a download with #guess"
        def response = api.download("/download/" + make(file))

        then: "nothing is found"
        response.status == 404
        response.problemType() == "/problems/file-not-found"

        where:
        guess                                  | make
        "one character of the token changed"   | { Map f -> flipFirst(token(f)) }
        "the token cut short"                  | { Map f -> token(f).dropRight(1) }
        "the file's id"                        | { Map f -> f.id }
        "the filename"                         | { Map f -> f.filename }
        "a made-up token of the right form"    | { Map f -> "AAAAAAAAAAAAAAAAAAAAAA" }
    }

    // --- "All files can be downloaded by the link (both PRIVATE and PUBLIC) - No link guessing"
    //     Checklist 6: "Application should allow to download a file." -----------------------------------------

    def "[Checklist 6] A #visibility file can be downloaded by its link, by anyone holding it"() {
        given: "alice has a #visibility file"
        byte[] content = unique(10_000)
        def file = uploaded(alice, "shared report.pdf", content, [visibility: visibility, contentType: "application/pdf"])

        when: "someone downloads it by the link, with no user id"
        def response = api.download(file.downloadLink)

        then: "they get the file's bytes, as an attachment with its name and type"
        response.status == 200
        response.body == content
        response.header("Content-Length") == String.valueOf(content.length)
        response.header("Content-Type") == "application/pdf"
        response.header("Content-Disposition") == "attachment; filename*=UTF-8''shared%20report.pdf"
        response.header("X-Content-Type-Options") == "nosniff"

        where:
        visibility << ["PRIVATE", "PUBLIC"]
    }

    // --- "The application should prevent users from uploading the same file multiple times, based on content or
    //      filename. No global uniqueness of file. File is the same if: content is the same; filename already exists"

    def "The same file is refused when the filename already exists (#variant)"() {
        given: "alice has \"Report.pdf\""
        def existing = uploaded(alice, "Report.pdf")

        when: "she uploads different content named #name"
        def response = api.upload(alice, name, unique(200))

        then: "it is refused as filename-exists, pointing at her existing file"
        response.status == 409
        response.problemType() == "/problems/filename-exists"
        response.json().existingFile == [id: existing.id, size: existing.size, sha256: existing.sha256]

        where:
        variant                  | name
        "the same name"          | "Report.pdf"
        "the name in other case" | "REPORT.PDF"
    }

    def "The same file is refused when the content is the same, under any filename"() {
        given: "alice has a file with content C"
        byte[] content = unique(2000)
        def existing = uploaded(alice, "original.bin", content)

        when: "she uploads content C as \"copy.bin\""
        def response = api.upload(alice, "copy.bin", content, [visibility: "PUBLIC", tags: ["other"]])

        then: "it is refused as content-exists, pointing at her existing file"
        response.status == 409
        response.problemType() == "/problems/content-exists"
        response.json().existingFile.id == existing.id
    }

    def "No global uniqueness: another user can upload the same filename and the same content"() {
        given: "alice has \"report.pdf\" with content C"
        byte[] content = unique(2000)
        def alices = uploaded(alice, "report.pdf", content)

        when: "bob uploads content C as \"report.pdf\""
        def response = api.upload(bob, "report.pdf", content)

        then: "it is accepted as bob's own file"
        response.status == 201
        response.json().id != alices.id
        response.json().sha256 == alices.sha256
        api.myFiles(bob).json().items*.id == [response.json().id]
    }

    // --- Checklist 1.1 and 1.2: "Simulate parallel UPLOAD of a file with the same FILENAME / CONTENTS." -------

    def "[Checklist 1.1] Parallel uploads of a file with the same filename: exactly one is stored"() {
        when: "two uploads named \"report.pdf\" with different content are in flight at the same time"
        def responses = parallelUploads(alice, [[filename: "report.pdf", body: unique(1024 * 1024)],
                                                [filename: "report.pdf", body: unique(1024 * 1024)]])

        then: "one is created and the other refused as filename-exists"
        responses*.status.sort() == [201, 409]
        responses.find { it.status == 409 }.problemType() == "/problems/filename-exists"

        and: "alice has exactly one \"report.pdf\""
        api.myFiles(alice).json().items*.filename == ["report.pdf"]
    }

    def "[Checklist 1.2] Parallel uploads of a file with the same contents: exactly one is stored"() {
        given:
        byte[] content = unique(1024 * 1024)

        when: "two uploads of the same content under different filenames are in flight at the same time"
        def responses = parallelUploads(alice, [[filename: "a.bin", body: content], [filename: "b.bin", body: content]])

        then: "one is created and the other refused as content-exists"
        responses*.status.sort() == [201, 409]
        responses.find { it.status == 409 }.problemType() == "/problems/content-exists"

        and: "alice has exactly one file with that content"
        api.myFiles(alice).json().items*.sha256 == [sha256(content)]
    }

    // --- Checklist 3: "Application should not have any session nor user management endpoints. You can assume
    //     that user id will be provided with the request to your API." ----------------------------------------

    def "[Checklist 3] No sessions and no user management: the user id comes with every request"() {
        when: "a user works with the API"
        def upload = api.upload(alice, "a.txt", unique(100), [visibility: "PUBLIC"])
        def responses = [upload, api.myFiles(alice), api.publicFiles(), api.download(upload.json().downloadLink)]

        then: "no response creates a session or sets a cookie"
        responses.every { it.status in [200, 201] && it.header("Set-Cookie") == null }

        and: "requests without the user id are refused where an owner is needed"
        api.upload(null, "b.txt", unique(100)).with { status == 400 && json().field == "X-User-Id" }
        api.delete(null, upload.json().id).status == 400

        and: "there are no user or session endpoints"
        ["/users", "/login", "/logout", "/session", "/register"].every { api.get(it).status == 404 }
    }

    // --- helpers -------------------------------------------------------------------------------------

    Map uploaded(String user, String filename, byte[] content = unique(256), Map options = [:]) {
        def response = api.upload(user, filename, content, options)
        assert response.status == 201: "upload of $filename: $response"
        response.json()
    }

    List<String> ids(String list, Map params) {
        def response = list == "my files" ? api.myFiles(alice, params) : api.publicFiles(params)
        response.json().items*.id
    }

    static List<String> names(ApiResponse response) {
        response.json().items*.filename
    }

    /**
     * Both uploads send their headers with Expect: 100-continue and get their 100 only after passing the
     * filename check; neither body has been sent yet, so both are past the check before either can be
     * stored. The database's unique indexes decide which one wins.
     */
    List<RawHttpClient.Response> parallelUploads(String user, List<Map> uploads) {
        def clients = uploads.collect { Map upload ->
            def client = new RawHttpClient(stack.host(), stack.port())
            client.sendHead("POST", "/files?filename=${URLEncoder.encode(upload.filename as String, "UTF-8")}&visibility=PRIVATE", [
                    "X-User-Id"     : user,
                    "Content-Type"  : "application/octet-stream",
                    "Content-Length": String.valueOf((upload.body as byte[]).length),
                    "Expect"        : "100-continue"])
            assert client.readResponse().status == 100
            client
        }
        try {
            [clients, uploads].transpose().each { RawHttpClient client, Map upload ->
                client.sendBody(upload.body as byte[])
                client.flush()
            }
            clients*.readResponse()
        } finally {
            clients*.close()
        }
    }

    static String user(String name) {
        "$name-${UUID.randomUUID().toString().take(8)}"
    }

    /** A tag no other feature uses, so the shared public list can be filtered down to this feature's files. */
    static String runTag() {
        "tag-" + UUID.randomUUID().toString().take(8)
    }

    static String mixedCase(String text) {
        text.toList().withIndex().collect { String c, int i -> i % 2 ? c.toUpperCase() : c }.join()
    }

    static String token(Map file) {
        (file.downloadLink as String).substring("/download/".length())
    }

    static String flipFirst(String token) {
        (token[0] == "A" ? "B" : "A") + token.substring(1)
    }
}
