package com.teletronics.storage.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real MongoDB 8.0 (the version docker-compose runs) for integration specs. Testcontainers starts it
 * as a single-node replica set; Spring Boot points the client at it through @ServiceConnection.
 */
@TestConfiguration(proxyBeanMethods = false)
class MongoContainerConfig {

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        new MongoDBContainer(DockerImageName.parse("mongo:8.0"))
    }
}
