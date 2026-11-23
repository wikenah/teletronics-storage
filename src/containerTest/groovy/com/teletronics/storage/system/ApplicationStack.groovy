package com.teletronics.storage.system

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.time.Duration

/**
 * The shipped image with MongoDB and SeaweedFS on one Docker network, set up as docker-compose.yml does:
 * the application container gets 1 GiB of memory and no extra swap.
 */
class ApplicationStack {

    static final long ONE_GIB = 1L << 30

    final Network network = Network.newNetwork()

    final GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8.0"))
            .withNetwork(network)
            .withNetworkAliases("mongo")
            .withCommand("--wiredTigerCacheSizeGB", "0.25")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))

    final GenericContainer<?> seaweedFs = new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.54"))
            .withNetwork(network)
            .withNetworkAliases("seaweedfs")
            .withCommand("server", "-s3")
            .withEnv("AWS_ACCESS_KEY_ID", "teletronics")
            .withEnv("AWS_SECRET_ACCESS_KEY", "teletronics-secret")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/healthz").forPort(8333).withStartupTimeout(Duration.ofSeconds(120)))

    final GenericContainer<?> app = new GenericContainer<>(DockerImageName.parse(System.getProperty("app.image")))
            .withNetwork(network)
            .withEnv("SPRING_MONGODB_URI", "mongodb://mongo:27017/teletronics")
            .withEnv("STORAGE_S3_ENDPOINT", "http://seaweedfs:8333")
            .withEnv("STORAGE_S3_ACCESS_KEY", "teletronics")
            .withEnv("STORAGE_S3_SECRET_KEY", "teletronics-secret")
            .withExposedPorts(8080)
            .withCreateContainerCmdModifier { cmd -> cmd.hostConfig.withMemory(ONE_GIB).withMemorySwap(ONE_GIB) }
            .waitingFor(Wait.forHttp("/files/public").forPort(8080).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)))

    private static ApplicationStack shared

    /**
     * One stack for every container spec in the test run, started on first use.
     * Testcontainers removes the containers when the test JVM exits.
     */
    static synchronized ApplicationStack shared() {
        if (shared == null) {
            shared = new ApplicationStack()
            shared.start()
        }
        shared
    }

    void start() {
        mongo.start()
        seaweedFs.start()
        app.start()
    }

    void stop() {
        [app, seaweedFs, mongo].each { it.stop() }
        network.close()
    }

    String host() {
        app.host
    }

    int port() {
        app.getMappedPort(8080)
    }

    String baseUrl() {
        "http://${host()}:${port()}"
    }
}
