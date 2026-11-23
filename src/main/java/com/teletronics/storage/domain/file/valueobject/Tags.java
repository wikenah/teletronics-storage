package com.teletronics.storage.domain.file.valueobject;

import com.teletronics.storage.domain.file.exception.InvalidFileException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A file's tags: normalised, de-duplicated, in the order first given, at most five.
 */
public record Tags(List<Tag> tags) {

    public static final int MAX_TAGS = 5;

    public Tags {
        tags = List.copyOf(tags);
        if (tags.size() > MAX_TAGS) {
            throw new InvalidFileException("tags", "a file can have at most 5 tags");
        }
    }

    public static Tags of(Collection<String> raw) {
        Set<Tag> unique = new LinkedHashSet<>();
        if (raw != null) {
            raw.forEach(value -> unique.add(Tag.of(value)));
        }
        return new Tags(List.copyOf(unique));
    }

    public static Tags none() {
        return new Tags(List.of());
    }

    public List<String> values() {
        return tags.stream().map(Tag::value).toList();
    }
}
