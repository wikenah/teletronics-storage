package com.teletronics.storage.application.port;

import com.teletronics.storage.domain.file.ContentType;
import com.teletronics.storage.domain.file.Filename;

/**
 * Detects a file's media type from its first bytes and name (Domain decision 9).
 */
public interface ContentTypeDetector {

    /** How many leading bytes the detector wants to see. */
    int prefixLength();

    ContentType detect(byte[] prefix, Filename filename);
}
