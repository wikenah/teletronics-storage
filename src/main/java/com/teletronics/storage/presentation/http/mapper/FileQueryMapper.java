package com.teletronics.storage.presentation.http.mapper;

import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.query.SortField;
import com.teletronics.storage.domain.file.valueobject.Tag;
import com.teletronics.storage.presentation.http.request.ListParams;
import lombok.experimental.UtilityClass;

import java.util.Map;

/**
 * Maps listing parameters in Spring Data's naming (page from 0, size, sort=field,direction, tag) onto a
 * {@link FileQuery}. Expects parameters that ListParamsValidator has accepted.
 */
@UtilityClass
public class FileQueryMapper {

    /** The sort fields a client may name, and the domain field each one means. */
    public static final Map<String, SortField> SORT_FIELDS = Map.of(
            "filename", SortField.FILENAME,
            "uploadDate", SortField.UPLOAD_DATE,
            "tag", SortField.TAG,
            "contentType", SortField.CONTENT_TYPE,
            "size", SortField.SIZE);

    public static FileQuery toQuery(ListParams params, FileQuery base) {
        FileQuery query = base.page(integer(params.page(), 0), integer(params.size(), FileQuery.DEFAULT_SIZE));
        if (params.sort() != null) {
            String[] parts = params.sort().split(",", -1);
            boolean ascending = parts.length < 2 || parts[1].trim().equalsIgnoreCase("asc");
            query = query.sortedBy(SORT_FIELDS.get(parts[0].trim()), ascending);
        }
        return params.tag() == null ? query : query.withTag(Tag.of(params.tag().getFirst()));
    }

    private static int integer(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value.trim());
    }
}
