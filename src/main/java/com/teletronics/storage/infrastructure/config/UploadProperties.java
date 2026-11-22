package com.teletronics.storage.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code uploads.buffer-budget-mib}: MiB of upload part buffers that may be held at once (Storage decision 2).
 */
@ConfigurationProperties("uploads")
public record UploadProperties(int bufferBudgetMib) {

    public UploadProperties {
        if (bufferBudgetMib < 1) {
            throw new IllegalArgumentException("uploads.buffer-budget-mib must be at least 1");
        }
    }
}
