package com.teletronics.storage.presentation.http.response;

import com.teletronics.storage.domain.file.StoredFile;

import java.time.Instant;
import java.util.List;

/**
 * How a file is shown in responses and listings (DOM-50): never the owner's id, the storage key or the name key.
 */
public record FileResponse(
        String id,
        String filename,
        String visibility,
        List<String> tags,
        long size,
        String sha256,
        String contentType,
        Instant uploadedAt,
        String downloadLink) {

    /** A file's download link is this path plus its token, relative to the service as the brief asks. */
    public static final String DOWNLOAD_PATH = "/download/";

    public static FileResponse of(StoredFile file) {
        return new FileResponse(
                file.id().value(),
                file.filename().value(),
                file.visibility().name(),
                file.tags().values(),
                file.size(),
                file.sha256().hex(),
                file.contentType().value(),
                file.uploadedAt(),
                DOWNLOAD_PATH + file.downloadToken().value());
    }
}
