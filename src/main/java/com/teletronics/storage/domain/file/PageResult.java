package com.teletronics.storage.domain.file;

import java.util.List;

/**
 * One page of a listing plus the total count.
 */
public record PageResult<T>(List<T> items, int page, int size, long totalItems) {

    public PageResult {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return totalItems == 0 ? 0 : (totalItems + size - 1) / size;
    }
}
