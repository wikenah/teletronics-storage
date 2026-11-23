package com.teletronics.storage.system

import groovy.json.JsonSlurper
import org.testcontainers.DockerClientFactory
import spock.lang.Shared
import spock.lang.Specification

import java.security.MessageDigest

/**
 * The brief's 2 GB upload against the shipped image under the brief's limits
 * (docs/acceptance-criteria/system.md, SYS-01).
 */
class LargeUploadSpec extends Specification {

    static final long MIB = 1024L * 1024L
    static final long TWO_GIB = 2048L * MIB

    @Shared
    ApplicationStack stack = ApplicationStack.shared()

    def "SYS-01 [BRIEF] 2 GiB upload against the running container"() {
        given: "the built application image runs with a 1 GiB memory limit (swap equal to memory)"
        def hostConfig = inspect().hostConfig
        assert hostConfig.memory == ApplicationStack.ONE_GIB
        assert hostConfig.memorySwap == ApplicationStack.ONE_GIB

        and: "Mongo and SeaweedFS run alongside it, and the writable layer's size is noted"
        long writableBefore = inspect().sizeRw ?: 0L

        when: "a client streams a generated 2 GiB body to the upload endpoint"
        def clientSha = MessageDigest.getInstance("SHA-256")
        def connection = (HttpURLConnection) new URL(stack.baseUrl() + "/files?filename=two-gib.bin&visibility=PRIVATE").openConnection()
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(TWO_GIB)
        connection.setRequestProperty("X-User-Id", "alice")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.readTimeout = 600_000
        def piece = new byte[1024 * 1024]
        new Random(2026).nextBytes(piece)
        connection.outputStream.withCloseable { out ->
            for (long i = 0; i < TWO_GIB.intdiv(piece.length); i++) {
                piece[0] = (byte) i
                piece[1] = (byte) (i >> 8)
                clientSha.update(piece)
                out.write(piece)
            }
        }
        int status = connection.responseCode
        def body = new JsonSlurper().parse(status < 400 ? connection.inputStream : connection.errorStream) as Map

        then: "the response is 201 Created"
        status == 201

        and: "the reported size is 2147483648 and the SHA-256 matches the client's"
        body.size == 2147483648L
        body.sha256 == clientSha.digest().encodeHex().toString()

        and: "the application container was not OOM-killed and is still running"
        def state = inspect().state
        !state.OOMKilled
        state.running

        and: "the kernel never had to kill anything in the container's memory group"
        cgroup("memory.events").find { it.startsWith("oom_kill ") } in [null, "oom_kill 0"]
        println "SYS-01 cgroup memory.peak: ${cgroup("memory.peak").join()} bytes"

        and: "the application container's writable layer grew by far less than 200 MB"
        long growth = (inspect().sizeRw ?: 0L) - writableBefore
        println "SYS-01 writable layer growth: ${growth} bytes"
        assert growth < 20 * MIB: "writable layer grew by $growth bytes; changed files: ${changedFiles()}"
    }

    // --- helpers -------------------------------------------------------------------------------------

    def inspect() {
        DockerClientFactory.instance().client().inspectContainerCmd(stack.app.containerId).withSize(true).exec()
    }

    /** A cgroup v2 file of the application container, or nothing where it isn't available. */
    List<String> cgroup(String file) {
        def result = stack.app.execInContainer("cat", "/sys/fs/cgroup/" + file)
        result.exitCode == 0 ? result.stdout.readLines() : []
    }

    List<String> changedFiles() {
        DockerClientFactory.instance().client().containerDiffCmd(stack.app.containerId).exec().collect { "${it.kind} ${it.path}" }
    }
}
