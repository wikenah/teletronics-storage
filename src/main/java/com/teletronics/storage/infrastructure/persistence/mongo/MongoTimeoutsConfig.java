package com.teletronics.storage.infrastructure.persistence.mongo;

import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Short Mongo timeouts (Storage decision 3): a database that is down fails within seconds, not the
 * driver defaults of 30 s server selection and no limit on waiting for a pooled connection (STO-12).
 */
@Configuration(proxyBeanMethods = false)
public class MongoTimeoutsConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoTimeouts() {
        return settings -> settings
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(2, SECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout(2, SECONDS).readTimeout(10, SECONDS))
                .applyToConnectionPoolSettings(pool -> pool.maxWaitTime(2, SECONDS));
    }
}
