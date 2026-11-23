package com.teletronics.storage.application;

import com.teletronics.storage.domain.file.ContentType;
import com.teletronics.storage.domain.file.Filename;
import com.teletronics.storage.domain.file.OwnerId;
import com.teletronics.storage.domain.file.Tags;
import com.teletronics.storage.domain.file.Visibility;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What is known about an upload before its body is read. The values are already valid: each one is
 * checked once, by its own type, when the command is built.
 */
public record UploadCommand(
        OwnerId owner,
        Filename filename,
        Visibility visibility,
        Tags tags,
        Optional<ContentType> declaredContentType,
        OptionalLong declaredLength) {

    /**
     * Builds a command from the values as the client sent them.
     *
     * @throws com.teletronics.storage.domain.file.InvalidFileException for the first invalid value, in
     *         the order owner, filename, visibility, tags
     */
    public static UploadCommand of(String owner, String filename, String visibility, List<String> tags,
                                   Optional<String> declaredContentType, OptionalLong declaredLength) {
        return new UploadCommand(
                OwnerId.of(owner),
                Filename.of(filename),
                Visibility.parse(visibility),
                Tags.of(tags),
                declaredContentType.filter(value -> !value.isBlank()).map(ContentType::new),
                declaredLength);
    }
}
