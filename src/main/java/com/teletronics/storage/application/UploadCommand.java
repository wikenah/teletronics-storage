package com.teletronics.storage.application;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the HTTP layer hands over before reading the body: raw values, validated by the domain.
 */
public record UploadCommand(
        String owner,
        String filename,
        String visibility,
        List<String> tags,
        Optional<String> declaredContentType,
        OptionalLong declaredLength) {

    public UploadCommand {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
