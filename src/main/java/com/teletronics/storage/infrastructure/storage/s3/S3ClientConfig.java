package com.teletronics.storage.infrastructure.storage.s3;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * The S3 client: path-style addressing (SeaweedFS and most self-hosted servers don't do virtual-host
 * buckets) and an HTTP pool as large as Tomcat's thread pool. Timeouts and retries are the SDK defaults.
 */
@Configuration(proxyBeanMethods = false)
public class S3ClientConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3Properties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .forcePathStyle(true)
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(properties.maxConnections()))
                .build();
    }

    @Bean
    S3BlobStore s3BlobStore(S3Client s3, S3Properties properties) {
        S3BlobStore store = new S3BlobStore(s3, properties.bucket(), PartSizePolicy.standard());
        if (properties.ensureBucket()) {
            store.ensureBucket();
        }
        return store;
    }
}
