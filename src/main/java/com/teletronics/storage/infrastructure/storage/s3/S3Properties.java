package com.teletronics.storage.infrastructure.storage.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code storage.s3.*}: the only place the store is named. Any S3-compatible server works by changing these.
 *
 * @param maxConnections the HTTP pool size, equal to Tomcat's maxThreads (Storage decision 3)
 * @param ensureBucket   create the bucket at startup if it's missing
 */
@ConfigurationProperties("storage.s3")
public record S3Properties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        int maxConnections,
        boolean ensureBucket) {
}
