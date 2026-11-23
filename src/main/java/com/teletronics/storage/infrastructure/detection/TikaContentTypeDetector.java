package com.teletronics.storage.infrastructure.detection;

import com.teletronics.storage.application.port.ContentTypeDetector;
import com.teletronics.storage.domain.file.valueobject.ContentType;
import com.teletronics.storage.domain.file.valueobject.Filename;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Apache Tika (tika-core: magic bytes, then the filename's extension) on the first 64 KiB of the body
 * (Domain decision 9). Unknown content comes back as application/octet-stream.
 */
@Component
public class TikaContentTypeDetector implements ContentTypeDetector {

    private static final int PREFIX_LENGTH = 64 * 1024;

    private final Tika tika = new Tika();

    @Override
    public int prefixLength() {
        return PREFIX_LENGTH;
    }

    @Override
    public ContentType detect(byte[] prefix, Filename filename) {
        return new ContentType(tika.detect(prefix, filename.value()));
    }
}
