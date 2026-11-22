package com.teletronics.storage.application;

import com.teletronics.storage.domain.file.StoredFile;

import java.io.InputStream;

/**
 * A file to send back: its metadata (for the headers) and its bytes. The caller closes the stream.
 */
public record Download(StoredFile file, InputStream body) {
}
