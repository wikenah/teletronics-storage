package com.teletronics.storage.presentation.http

import com.teletronics.storage.application.Download
import com.teletronics.storage.application.FileService
import com.teletronics.storage.application.RecordWithoutBytesException
import com.teletronics.storage.domain.file.FileNotFoundException
import com.teletronics.storage.testing.StoredFiles
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * GET /download/{token} with the file service faked (docs/acceptance-criteria/http-api.md, API-30).
 */
@WebMvcTest(controllers = [DownloadController])
class DownloadControllerSpec extends Specification {

    @Autowired
    MockMvc mvc

    @SpringBean
    FileService files = Mock()

    def "API-30 download (#variant)"() {
        given: "the file service resolves token T to file F (name \"résumé final.pdf\", type application/pdf, size N)"
        byte[] content = "%PDF-1.7 the file's bytes".bytes
        def file = StoredFiles.file("alice", "résumé final.pdf", "application/pdf", content)
        def token = file.downloadToken().value()
        files.openDownload(token) >> new Download(file, new ByteArrayInputStream(content))

        when: "anyone sends GET /download/T #variant"
        def request = get("/download/" + token)
        headers.each { name, value -> request.header(name, value) }
        def response = mvc.perform(request).andReturn().response

        then: "the response is 200 with the file's bytes"
        response.status == 200
        response.contentAsByteArray == content

        and: "Content-Type is application/pdf and Content-Length is N"
        response.contentType == "application/pdf"
        response.getHeader("Content-Length") == String.valueOf(content.length)

        and: "Content-Disposition is attachment with an ASCII fallback and the exact name in filename*"
        response.getHeader("Content-Disposition") ==
                "attachment; filename=\"r_sum_ final.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20final.pdf"

        and: "X-Content-Type-Options is nosniff"
        response.getHeader("X-Content-Type-Options") == "nosniff"

        where:
        variant                           | headers
        "without a user id"               | [:]
        "with a Range header (ignored)"   | ["Range": "bytes=0-3"]
    }

    def "API-30 an unknown token is 404"() {
        given: "the file service doesn't know the token"
        files.openDownload(_) >> { throw new FileNotFoundException() }

        when:
        def response = mvc.perform(get("/download/AAAAAAAAAAAAAAAAAAAAAA")).andReturn().response

        then: "the response is 404 with type /problems/file-not-found"
        response.status == 404
        FilesControllerSpec.problem(response).type == "/problems/file-not-found"
    }

    def "STO-16 a record whose bytes are missing is a 500, not a 404"() {
        given:
        files.openDownload(_) >> { throw new RecordWithoutBytesException("record without bytes", null) }

        when:
        def response = mvc.perform(get("/download/AAAAAAAAAAAAAAAAAAAAAA")).andReturn().response

        then:
        response.status == 500
        FilesControllerSpec.problem(response).type == "about:blank"
    }
}
