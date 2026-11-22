package com.teletronics.storage.presentation.http.request;

import com.teletronics.storage.domain.file.FileQuery;
import com.teletronics.storage.domain.file.SortField;
import com.teletronics.storage.domain.file.Tag;
import com.teletronics.storage.presentation.http.problem.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/**
 * Listing parameters in Spring Data's naming (page from 0, size, sort=field,direction) plus one optional tag.
 * Sort fields come from an allow-list, so a client can never sort by an internal field (API-28).
 */
public final class ListParamsParser {

    private static final Map<String, SortField> SORT_FIELDS = Map.of(
            "filename", SortField.FILENAME,
            "uploadDate", SortField.UPLOAD_DATE,
            "tag", SortField.TAG,
            "contentType", SortField.CONTENT_TYPE,
            "size", SortField.SIZE);

    private ListParamsParser() {
    }

    public static FileQuery parse(HttpServletRequest request, FileQuery base) {
        FileQuery query = base.page(integer(request, "page", 0), integer(request, "size", FileQuery.DEFAULT_SIZE));

        String sort = request.getParameter("sort");
        if (sort != null) {
            String[] parts = sort.split(",", -1);
            SortField field = SORT_FIELDS.get(parts[0].trim());
            if (field == null || parts.length > 2) {
                throw new InvalidRequestException("sort", "sort by one of filename, uploadDate, tag, contentType, size");
            }
            boolean ascending = true;
            if (parts.length == 2) {
                String direction = parts[1].trim().toLowerCase();
                if (!direction.equals("asc") && !direction.equals("desc")) {
                    throw new InvalidRequestException("sort", "sort direction must be asc or desc");
                }
                ascending = direction.equals("asc");
            }
            query = query.sortedBy(field, ascending);
        }

        String[] tags = request.getParameterValues("tag");
        if (tags != null) {
            if (tags.length > 1) {
                throw new InvalidRequestException("tag", "filter by one tag at a time");
            }
            if (tags[0].indexOf(',') >= 0) {
                throw new InvalidRequestException("tag", "a tag must not contain a comma");
            }
            query = query.withTag(Tag.of(tags[0]));
        }
        return query;
    }

    private static int integer(HttpServletRequest request, String name, int fallback) {
        String value = request.getParameter(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException(name, name + " must be a whole number");
        }
    }
}
