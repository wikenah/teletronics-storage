package com.teletronics.storage.infrastructure.detection

import com.teletronics.storage.application.FileService
import com.teletronics.storage.application.UploadBudget
import com.teletronics.storage.application.UploadCommand
import com.teletronics.storage.testing.InMemoryBlobStore
import com.teletronics.storage.testing.InMemoryFileRepository
import spock.lang.Specification

import java.time.Clock

/**
 * Type detection at service level with the real Tika detector (docs/acceptance-criteria/domain.md decision 9).
 */
class TikaDetectionSpec extends Specification {

    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".bytes
    static final byte[] PNG = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                               0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52] as byte[]
    static final byte[] TEXT = "Shopping list\nmilk\neggs\nbread\n".bytes

    def service = new FileService(new InMemoryFileRepository(), new InMemoryBlobStore(),
            new TikaContentTypeDetector(), new UploadBudget(256), Clock.systemUTC())

    def "DOM-36 a declared type is kept (#type)"() {
        when: "alice uploads a file with Content-Type #type"
        def file = upload("data", PNG, type)

        then: "the file's content type is #type"
        file.contentType().value() == type

        where:
        type << ["application/pdf", "text/csv", "image/png"]
    }

    def "DOM-37 a missing or generic type is detected from the content (#name, #declared)"() {
        when: "alice uploads #description named #name with #declared"
        def file = upload(name, content, declared)

        then: "the file's content type is #detected"
        file.contentType().value() == detected

        where:
        description  | content | name        | declared                   || detected
        "PDF bytes"  | PDF     | "report.pdf" | null                      || "application/pdf"
        "PNG bytes"  | PNG     | "photo.dat"  | "application/octet-stream" || "image/png"
        "plain text" | TEXT    | "notes"      | null                      || "text/plain"
    }

    def "DOM-38 unrecognisable content falls back to the generic type"() {
        given: "random bytes"
        byte[] content = new byte[4096]
        new Random(1).nextBytes(content)

        when: "alice uploads them named \"blob\" with no Content-Type"
        def file = upload("blob", content, null)

        then: "the file's content type is application/octet-stream"
        file.contentType().value() == "application/octet-stream"
    }

    def upload(String name, byte[] content, String type) {
        def pending = service.startUpload(UploadCommand.of("alice", name, "PRIVATE", [],
                Optional.ofNullable(type), OptionalLong.of(content.length)))
        try {
            pending.complete(new ByteArrayInputStream(content))
        } finally {
            pending.close()
        }
    }
}
