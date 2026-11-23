package com.teletronics.storage.application.upload;

import com.teletronics.storage.application.exception.IncompleteUploadException;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.application.port.ContentTypeDetector;
import com.teletronics.storage.domain.file.FileRepository;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.exception.DuplicateFileException.Kind;
import com.teletronics.storage.domain.file.exception.RepositoryDuplicateException;
import com.teletronics.storage.domain.file.valueobject.ContentType;
import com.teletronics.storage.domain.file.valueobject.StorageKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An upload that passed every check that needs no body, holding its memory reservation.
 * Bytes first, record after: the unique indexes decide duplicates at insert, and bytes without a record
 * are deleted (Domain decisions 1 and 5).
 */
@Slf4j
@RequiredArgsConstructor
public final class AdmittedUpload implements PendingUpload {

    private final FileRepository files;
    private final BlobStore blobs;
    private final ContentTypeDetector detector;
    private final Clock clock;
    private final UploadCommand command;
    private final UploadBudget.Reservation reservation;

    @Override
    public StoredFile complete(InputStream body) {
        StorageKey key = StorageKey.random();
        OptionalLong declaredLength = command.declaredLength();
        DigestingInputStream digesting = new DigestingInputStream(body, detector.prefixLength());
        blobs.store(key, digesting, declaredLength);
        long size = digesting.count();
        if (declaredLength.isPresent() && declaredLength.getAsLong() != size) {
            deleteBytes(key, "body shorter than Content-Length");
            throw new IncompleteUploadException("received " + size + " of " + declaredLength.getAsLong() + " bytes");
        }
        StoredFile file = newFile(key, digesting);
        try {
            files.insert(file);
            return file;
        } catch (RepositoryDuplicateException e) {
            deleteBytes(key, "lost a duplicate race");
            throw duplicateOf(file, e);
        } catch (RuntimeException e) {
            deleteBytes(key, "record insert failed");
            throw e;
        }
    }

    /** The record for the stored bytes: what the client declared, plus what was measured while streaming. */
    private StoredFile newFile(StorageKey key, DigestingInputStream digesting) {
        ContentType contentType = command.declaredContentType()
                .filter(type -> !type.isGeneric())
                .orElseGet(() -> detector.detect(digesting.prefix(), command.filename()));
        return StoredFile.create(command.userId(), command.filename(), command.visibility(),
                command.tags(), digesting.count(), digesting.sha256(), contentType, uploadedAt(), key);
    }

    @Override
    public void close() {
        reservation.close();
    }

    /** Name wins over content, the same order as the pre-check (Domain decision 5). */
    private RuntimeException duplicateOf(StoredFile file, RepositoryDuplicateException cause) {
        Optional<StoredFile> byName = files.findByOwnerAndFilenameKey(file.owner(), file.filename().key());
        if (byName.isPresent()) {
            return byName.get().alreadyExists(Kind.FILENAME);
        }
        return files.findByOwnerAndSha256(file.owner(), file.sha256())
                .<RuntimeException>map(existing -> existing.alreadyExists(Kind.CONTENT))
                .orElse(cause);
    }

    /** Millisecond precision, the precision MongoDB stores dates with, so every store returns the same value. */
    private Instant uploadedAt() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void deleteBytes(StorageKey key, String reason) {
        try {
            blobs.delete(key);
        } catch (RuntimeException e) {
            log.error("orphaned bytes ({}): storage key {} could not be deleted", reason, key.value(), e);
        }
    }
}
