package com.teletronics.storage.application.upload

import spock.lang.Specification

import static com.teletronics.storage.testing.TestBytes.random
import static com.teletronics.storage.testing.TestBytes.sha256

class DigestingInputStreamSpec extends Specification {

    def "skipped bytes still count towards the size and the SHA-256"() {
        given: "a 20,000-byte body"
        byte[] content = random(20_000)
        def stream = new DigestingInputStream(new ByteArrayInputStream(content), 16)

        when: "a reader skips the first 12,000 bytes and reads the rest"
        long skipped = stream.skip(12_000)
        stream.readAllBytes()

        then: "the skip behaves like any InputStream's"
        skipped == 12_000

        and: "the count, hash and prefix cover the whole body"
        stream.count() == 20_000
        stream.sha256().hex() == sha256(content)
        stream.prefix() == Arrays.copyOf(content, 16)
    }

    def "skipping past the end stops at the end"() {
        given:
        def stream = new DigestingInputStream(new ByteArrayInputStream(random(100)), 16)

        expect:
        stream.skip(500) == 100
        stream.skip(-1) == 0
        stream.count() == 100
    }
}
