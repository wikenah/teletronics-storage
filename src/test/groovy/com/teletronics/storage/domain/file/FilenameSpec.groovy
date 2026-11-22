package com.teletronics.storage.domain.file

import spock.lang.Specification

/**
 * Domain criteria for filenames (docs/acceptance-criteria/domain.md, decisions 2 and 3).
 */
class FilenameSpec extends Specification {

    def "DOM-01 filenames that differ only in case are the same (#name)"() {
        given: "alice has a file named \"Report.pdf\""
        def existing = Filename.of("Report.pdf")

        when: "alice uploads a file named #name"
        def candidate = Filename.of(name)

        then: "it is treated as the same name"
        candidate.key() == existing.key()

        where:
        name << ["report.pdf", "REPORT.PDF", "rEpOrT.pDf"]
    }

    def "DOM-02 the name is kept as typed"() {
        when: "alice uploads a file named \"Report.PDF\""
        def filename = Filename.of("Report.PDF")

        then: "the file is shown as \"Report.PDF\""
        filename.value() == "Report.PDF"
    }

    def "DOM-03 comparison does not depend on the server's locale"() {
        given: "the JVM default locale is Turkish (tr)"
        def original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))

        when: "\"TITLE.txt\" and \"title.txt\" are compared"
        def same = Filename.of("TITLE.txt").key() == Filename.of("title.txt").key()

        then: "they are the same name"
        same

        cleanup:
        Locale.setDefault(original)
    }

    def "DOM-04 invalid filenames are rejected: #description"() {
        when: "alice uploads a file named #description"
        Filename.of(name)

        then: "it is rejected as an invalid request, with the broken rule named"
        def e = thrown(InvalidFileException)
        e.field() == "filename"
        e.getMessage().contains(rule)

        where:
        description            | name                   || rule
        "an empty name"        | ""                     || "blank"
        "only spaces"          | "   "                  || "blank"
        "256 characters"       | "a" * 256              || "255"
        "a forward slash"      | "a/b.txt"              || "/"
        "a backslash"          | "a\\b.txt"             || "\\"
        "a parent path"        | "../x"                 || "/"
        "a single dot"         | "."                    || "'.'"
        "a double dot"         | ".."                   || "'..'"
        "a line break"         | "a\nb.txt"             || "control"
        "a NUL character"      | "a\u0000b.txt"         || "control"
        "a DEL character"      | "a\u007Fb.txt"         || "control"
        "a missing name"       | null                   || "blank"
    }

    def "DOM-05 unusual but valid filenames are accepted: #name"() {
        when: "alice uploads a file named #name"
        def filename = Filename.of(name)

        then: "the upload is accepted and the name is kept exactly as typed"
        filename.value() == name

        where:
        name << [".hidden", "notes: v2.txt", "what?.md", "  leading space.txt",
                 "trailing space.txt ", "résumé.pdf", "b" * 255]
    }
}
