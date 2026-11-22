package com.teletronics.storage.infrastructure.config;

import com.teletronics.storage.application.FileService;
import com.teletronics.storage.application.UploadBudget;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.application.port.ContentTypeDetector;
import com.teletronics.storage.domain.file.FileRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The composition root: the application layer has no Spring annotations, so its objects are built here.
 */
@Configuration(proxyBeanMethods = false)
public class FileServiceConfig {

    @Bean
    UploadBudget uploadBudget(UploadProperties properties) {
        return new UploadBudget(properties.bufferBudgetMib());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FileService fileService(FileRepository files, BlobStore blobs, ContentTypeDetector detector,
                            UploadBudget budget, Clock clock) {
        return new FileService(files, blobs, detector, budget, clock);
    }
}
