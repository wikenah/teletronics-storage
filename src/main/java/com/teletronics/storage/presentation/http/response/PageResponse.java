package com.teletronics.storage.presentation.http.response;

import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.query.PageResult;

import java.util.List;

/**
 * Our own page shape, not Spring Data's Page, whose JSON structure isn't guaranteed to stay stable.
 */
public record PageResponse(List<FileResponse> items, int page, int size, long totalItems, long totalPages) {

    public static PageResponse of(PageResult<StoredFile> result) {
        return new PageResponse(result.items().stream().map(FileResponse::of).toList(),
                result.page(), result.size(), result.totalItems(), result.totalPages());
    }
}
