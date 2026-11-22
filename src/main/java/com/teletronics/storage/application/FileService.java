package com.teletronics.storage.application;

import com.teletronics.storage.application.port.BlobNotFoundException;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.application.port.BlobTooLargeException;
import com.teletronics.storage.application.port.ContentTypeDetector;
import com.teletronics.storage.domain.file.ContentType;
import com.teletronics.storage.domain.file.DownloadToken;
import com.teletronics.storage.domain.file.DuplicateFileException;
import com.teletronics.storage.domain.file.FileId;
import com.teletronics.storage.domain.file.FileNotFoundException;
import com.teletronics.storage.domain.file.FileQuery;
import com.teletronics.storage.domain.file.FileRepository;
import com.teletronics.storage.domain.file.Filename;
import com.teletronics.storage.domain.file.OwnerId;
import com.teletronics.storage.domain.file.PageResult;
import com.teletronics.storage.domain.file.RepositoryDuplicateException;
import com.teletronics.storage.domain.file.StorageKey;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.Tags;
import com.teletronics.storage.domain.file.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The file use cases. Bytes first, record after: the unique indexes decide duplicates at insert,
 * and bytes without a record are garbage (Domain decisions 1 and 5).
 */
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileRepository files;
    private final BlobStore blobs;
    private final ContentTypeDetector detector;
    private final UploadBudget budget;
    private final Clock clock;

    public FileService(FileRepository files, BlobStore blobs, ContentTypeDetector detector,
                       UploadBudget budget, Clock clock) {
        this.files = files;
        this.blobs = blobs;
        this.detector = detector;
        this.budget = budget;
        this.clock = clock;
    }

    /**
     * Every check that needs no body: validation, the memory budget, the filename pre-check.
     * The caller reads the body only through the returned {@link PendingUpload} and must close it.
     */
    public PendingUpload startUpload(UploadCommand command) {
        OwnerId owner = OwnerId.of(command.owner());
        Filename filename = Filename.of(command.filename());
        Visibility visibility = Visibility.parse(command.visibility());
        Tags tags = Tags.of(command.tags());
        Optional<ContentType> declaredType = command.declaredContentType()
                .filter(value -> !value.isBlank())
                .map(ContentType::new);

        long partSize = blobs.partSizeFor(command.declaredLength());
        if (!budget.canEverFit(partSize)) {
            throw new FileTooLargeException("the file is too large to store with bounded memory");
        }
        UploadBudget.Reservation reservation = budget.tryReserve(partSize)
                .orElseThrow(UploadCapacityReachedException::new);
        try {
            files.findByOwnerAndFilenameKey(owner, filename.key()).ifPresent(existing -> {
                throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, existing);
            });
        } catch (RuntimeException e) {
            reservation.close();
            throw e;
        }
        return new PendingUpload(owner, filename, visibility, tags, declaredType,
                command.declaredLength(), reservation);
    }

    public StoredFile get(OwnerId owner, FileId id) {
        return files.findById(id)
                .filter(file -> file.isOwnedBy(owner))
                .orElseThrow(FileNotFoundException::new);
    }

    public StoredFile rename(OwnerId owner, FileId id, String newName) {
        StoredFile file = get(owner, id);
        Filename filename = Filename.of(newName);
        if (filename.equals(file.filename())) {
            return file;
        }
        StoredFile renamed = file.renamedTo(filename);
        try {
            files.updateFilename(renamed);
        } catch (RepositoryDuplicateException e) {
            throw files.findByOwnerAndFilenameKey(owner, filename.key())
                    .filter(existing -> !existing.id().equals(id))
                    .map(existing -> new DuplicateFileException(DuplicateFileException.Kind.FILENAME, existing))
                    .orElseThrow(() -> e);
        }
        return renamed;
    }

    /** Record first, then bytes; failed byte deletion leaves garbage, never a dangling record. */
    public void delete(OwnerId owner, FileId id) {
        StoredFile file = get(owner, id);
        if (!files.delete(file.id())) {
            throw new FileNotFoundException();
        }
        deleteBytesOrLogOrphan(file.storageKey(), "file deleted");
    }

    public PageResult<StoredFile> list(FileQuery query) {
        return files.find(query);
    }

    public Download openDownload(String token) {
        StoredFile file = DownloadToken.parse(token)
                .flatMap(files::findByDownloadToken)
                .orElseThrow(FileNotFoundException::new);
        try {
            return new Download(file, blobs.open(file.storageKey()));
        } catch (BlobNotFoundException e) {
            log.error("record without bytes: file {} storage key {}", file.id().value(), file.storageKey().value());
            throw new RecordWithoutBytesException("record without bytes", e);
        }
    }

    private void deleteBytesOrLogOrphan(StorageKey key, String reason) {
        try {
            blobs.delete(key);
        } catch (RuntimeException e) {
            log.error("orphaned bytes ({}): storage key {} could not be deleted", reason, key.value(), e);
        }
    }

    /**
     * An admitted upload holding its memory reservation. {@link #complete(InputStream)} streams the body;
     * {@link #close()} always returns the reservation.
     */
    public final class PendingUpload implements AutoCloseable {

        private final OwnerId owner;
        private final Filename filename;
        private final Visibility visibility;
        private final Tags tags;
        private final Optional<ContentType> declaredType;
        private final OptionalLong declaredLength;
        private final UploadBudget.Reservation reservation;

        private PendingUpload(OwnerId owner, Filename filename, Visibility visibility, Tags tags,
                              Optional<ContentType> declaredType, OptionalLong declaredLength,
                              UploadBudget.Reservation reservation) {
            this.owner = owner;
            this.filename = filename;
            this.visibility = visibility;
            this.tags = tags;
            this.declaredType = declaredType;
            this.declaredLength = declaredLength;
            this.reservation = reservation;
        }

        public StoredFile complete(InputStream body) {
            StorageKey key = StorageKey.random();
            DigestingInputStream digesting = new DigestingInputStream(body, detector.prefixLength());
            try {
                blobs.store(key, digesting, declaredLength);
            } catch (BlobTooLargeException e) {
                throw new FileTooLargeException(e.getMessage());
            }

            long size = digesting.count();
            if (declaredLength.isPresent() && declaredLength.getAsLong() != size) {
                deleteBytesOrLogOrphan(key, "body shorter than Content-Length");
                throw new IncompleteUploadException("received " + size + " of " + declaredLength.getAsLong() + " bytes");
            }

            ContentType contentType = declaredType
                    .filter(type -> !type.isGeneric())
                    .orElseGet(() -> detector.detect(digesting.prefix(), filename));

            StoredFile file = new StoredFile(FileId.newId(), owner, filename, visibility, tags, size,
                    digesting.sha256(), contentType, clock.instant(), DownloadToken.generate(), key);
            try {
                files.insert(file);
                return file;
            } catch (RepositoryDuplicateException e) {
                deleteBytesOrLogOrphan(key, "lost a duplicate race");
                throw duplicateOf(file, e);
            } catch (RuntimeException e) {
                deleteBytesOrLogOrphan(key, "record insert failed");
                throw e;
            }
        }

        /** Name wins over content, the same order as the pre-check (Domain decision 5). */
        private RuntimeException duplicateOf(StoredFile file, RepositoryDuplicateException cause) {
            Optional<StoredFile> byName = files.findByOwnerAndFilenameKey(owner, file.filename().key());
            if (byName.isPresent()) {
                return new DuplicateFileException(DuplicateFileException.Kind.FILENAME, byName.get());
            }
            return files.findByOwnerAndSha256(owner, file.sha256())
                    .<RuntimeException>map(existing -> new DuplicateFileException(DuplicateFileException.Kind.CONTENT, existing))
                    .orElse(cause);
        }

        @Override
        public void close() {
            reservation.close();
        }
    }
}
