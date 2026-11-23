package com.teletronics.storage.domain.file

import com.teletronics.storage.domain.file.exception.InvalidFileException
import com.teletronics.storage.domain.file.valueobject.Tag
import com.teletronics.storage.domain.file.valueobject.Tags
import spock.lang.Specification

/**
 * Domain criteria for tags (docs/acceptance-criteria/domain.md, decision 7).
 */
class TagsSpec extends Specification {

    def "DOM-27 tags are lowercased, trimmed and deduplicated"() {
        when: "alice uploads a file with tags \" Work \", \"work\" and \"Machine Learning\""
        def tags = Tags.of([" Work ", "work", "Machine Learning"])

        then: "the file's tags are \"work\" and \"machine learning\""
        tags.values() == ["work", "machine learning"]
    }

    def "DOM-28 invalid tags are rejected: #description"() {
        when: "one of the file's tags is #description"
        Tags.of(["ok", tag])

        then: "it is rejected as an invalid request, with the broken rule named"
        def e = thrown(InvalidFileException)
        e.field() == "tags"
        e.getMessage().contains(rule)

        where:
        description             | tag         || rule
        "empty"                 | ""          || "blank"
        "only spaces"           | "   "       || "blank"
        "51 characters"         | "t" * 51    || "50"
        "a line break"          | "a\nb"      || "control"
        "a comma"               | "a,b"       || "comma"
    }

    def "a file may have at most 5 distinct tags"() {
        when: "a file has six different tags"
        Tags.of(["a", "b", "c", "d", "e", "f"])

        then: "it is rejected"
        thrown(InvalidFileException)
    }

    def "five tags that collapse to fewer after normalising are fine"() {
        expect:
        Tags.of(["A", "a", "B", "b", "C"]).values() == ["a", "b", "c"]
    }

    def "DOM-30 the tag filter value is normalised the same way (#filter)"() {
        expect: "filtering by #filter matches a file tagged \"work\" exactly when expected"
        (Tag.of(filter).value() == "work") == matches

        where:
        filter  || matches
        "work"  || true
        "WORK"  || true
        "work " || true
        "wor"   || false
    }
}
