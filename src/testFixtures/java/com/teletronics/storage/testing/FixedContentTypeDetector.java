package com.teletronics.storage.testing;

import com.teletronics.storage.application.port.ContentTypeDetector;
import com.teletronics.storage.domain.file.ContentType;
import com.teletronics.storage.domain.file.Filename;

/**
 * Always "detects" the same type; records what it was asked.
 */
public class FixedContentTypeDetector implements ContentTypeDetector {

    private final ContentType type;
    private volatile int calls;

    public FixedContentTypeDetector(String type) {
        this.type = new ContentType(type);
    }

    public int calls() {
        return calls;
    }

    @Override
    public int prefixLength() {
        return 64 * 1024;
    }

    @Override
    public ContentType detect(byte[] prefix, Filename filename) {
        calls++;
        return type;
    }
}
