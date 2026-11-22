package com.teletronics.storage.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar

/**
 * MongoDB and SeaweedFS for specs that run the whole application.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(MongoContainerConfig)
class StorageContainersConfig {

    @Bean
    SeaweedFsContainer seaweedFs() {
        new SeaweedFsContainer()
    }

    @Bean
    DynamicPropertyRegistrar seaweedFsProperties(SeaweedFsContainer seaweed) {
        { registry ->
            registry.add("storage.s3.endpoint") { seaweed.endpoint() }
            registry.add("storage.s3.access-key") { SeaweedFsContainer.ACCESS_KEY }
            registry.add("storage.s3.secret-key") { SeaweedFsContainer.SECRET_KEY }
        } as DynamicPropertyRegistrar
    }
}
