package com.teletronics.storage.application.upload;

import com.teletronics.storage.domain.file.StoredFile;

import java.io.InputStream;

/**
 * An admitted upload: every check that needs no body has passed and its memory is reserved.
 * {@link #complete(InputStream)} streams the body; {@link #close()} always returns the reservation.
 */
public interface PendingUpload extends AutoCloseable {

    StoredFile complete(InputStream body);

    @Override
    void close();
}
