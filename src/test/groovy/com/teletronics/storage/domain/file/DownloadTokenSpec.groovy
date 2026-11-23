package com.teletronics.storage.domain.file

import spock.lang.Specification

/**
 * Domain criteria for download links (docs/acceptance-criteria/domain.md, decision 8).
 */
class DownloadTokenSpec extends Specification {

    def "DOM-31 every file gets its own random download link"() {
        when: "two files get download tokens"
        def first = DownloadToken.generate()
        def second = DownloadToken.generate()

        then: "the two tokens differ"
        first != second

        and: "each token is 128 random bits written as 22 URL-safe base64 characters"
        first.value() ==~ /[A-Za-z0-9_-]{22}/
        Base64.urlDecoder.decode(first.value()).length == 16
    }

    def "a token that is not a well-formed one is refused"() {
        expect:
        !DownloadToken.parse(input).isPresent()

        where:
        input << ["", "short", "not a token at all!!!!!", "x" * 23, null]
    }
}
