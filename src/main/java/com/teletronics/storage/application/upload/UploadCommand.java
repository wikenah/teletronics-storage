package com.teletronics.storage.application.upload;

import com.teletronics.storage.domain.file.valueobject.ContentType;
import com.teletronics.storage.domain.file.valueobject.Filename;
import com.teletronics.storage.domain.file.valueobject.UserId;
import com.teletronics.storage.domain.file.valueobject.Tags;
import com.teletronics.storage.domain.file.valueobject.Visibility;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What is known about an upload before its body is read. The values are already valid: each one is
 * checked once, by its own type, when the command is built.
 */
public record UploadCommand(
        UserId userId,
        Filename filename,
        Visibility visibility,
        Tags tags,
        Optional<ContentType> declaredContentType,
        OptionalLong declaredLength) {

    /**
     * Builds a command from the values as the client sent them.
     *
     * @throws com.teletronics.storage.domain.file.exception.InvalidFileException for the first invalid value, in
     *         the order user id, filename, visibility, tags
     */
    public static UploadCommand of(String userId, String filename, String visibility, List<String> tags,
                                   Optional<String> declaredContentType, OptionalLong declaredLength) {
        return new UploadCommand(
                UserId.of(userId),
                Filename.of(filename),
                Visibility.parse(visibility),
                Tags.of(tags),
                declaredContentType.filter(value -> !value.isBlank()).map(ContentType::new),
                declaredLength);
    }
}
