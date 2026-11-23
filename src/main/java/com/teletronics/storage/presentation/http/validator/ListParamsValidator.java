package com.teletronics.storage.presentation.http.validator;

import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import com.teletronics.storage.presentation.http.mapper.FileQueryMapper;
import com.teletronics.storage.presentation.http.request.ListParams;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Locale;

/**
 * Checks the syntax of the listing parameters (page, size, sort=field,direction, one optional tag).
 * Sort fields come from an allow-list, so a client can never sort by an internal field (API-28).
 * Ranges (page, size) and tag rules are the domain's and are checked when the query is built.
 */
@UtilityClass
public class ListParamsValidator {

    public static void validate(ListParams params) {
        requireWholeNumber("page", params.page());
        requireWholeNumber("size", params.size());
        validateSort(params.sort());
        validateTag(params.tag());
    }

    private static void requireWholeNumber(String name, String value) {
        if (value == null) {
            return;
        }
        try {
            Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException(name, name + " must be a whole number");
        }
    }

    private static void validateSort(String sort) {
        if (sort == null) {
            return;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !FileQueryMapper.SORT_FIELDS.containsKey(parts[0].trim())) {
            throw new InvalidRequestException("sort", "sort by one of filename, uploadDate, tag, contentType, size");
        }
        if (parts.length == 2) {
            String direction = parts[1].trim().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw new InvalidRequestException("sort", "sort direction must be asc or desc");
            }
        }
    }

    /** Spring splits a comma-separated value, so tag=a,b counts as two tags, like tag=a&tag=b. */
    private static void validateTag(List<String> tags) {
        if (tags != null && tags.size() != 1) {
            throw new InvalidRequestException("tag", "filter by one tag at a time");
        }
    }
}
