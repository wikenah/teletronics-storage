package com.teletronics.storage.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.time.Duration

/**
 * SeaweedFS 4.54 with its S3 gateway, set up as in docker-compose.yml.
 */
class SeaweedFsContainer extends GenericContainer<SeaweedFsContainer> {

    static final String ACCESS_KEY = "teletronics"
    static final String SECRET_KEY = "teletronics-secret"
    static final int S3_PORT = 8333

    SeaweedFsContainer() {
        super(DockerImageName.parse("chrislusf/seaweedfs:4.54"))
        withCommand("server", "-s3")
        withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
        withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
        withExposedPorts(S3_PORT)
        waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)))
    }

    String endpoint() {
        "http://${host}:${getMappedPort(S3_PORT)}"
    }
}
