package com.teletronics.storage.application;

import com.teletronics.storage.application.download.Download;
import com.teletronics.storage.application.exception.BlobNotFoundException;
import com.teletronics.storage.application.exception.FileTooLargeException;
import com.teletronics.storage.application.exception.RecordWithoutBytesException;
import com.teletronics.storage.application.exception.UploadCapacityReachedException;
import com.teletronics.storage.application.port.BlobStore;
import com.teletronics.storage.application.port.ContentTypeDetector;
import com.teletronics.storage.application.upload.AdmittedUpload;
import com.teletronics.storage.application.upload.PendingUpload;
import com.teletronics.storage.application.upload.UploadBudget;
import com.teletronics.storage.application.upload.UploadCommand;
import com.teletronics.storage.domain.file.FileRepository;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.exception.DuplicateFileException.Kind;
import com.teletronics.storage.domain.file.exception.FileNotFoundException;
import com.teletronics.storage.domain.file.exception.RepositoryDuplicateException;
import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.query.PageResult;
import com.teletronics.storage.domain.file.valueobject.DownloadToken;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.Filename;
import com.teletronics.storage.domain.file.valueobject.OwnerId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * The file use cases. The upload itself, once admitted, is carried out by {@link AdmittedUpload}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileService {

    private final FileRepository files;
    private final BlobStore blobs;
    private final ContentTypeDetector detector;
    private final UploadBudget budget;
    private final Clock clock;

    /**
     * The checks that need no body: the memory budget and the filename pre-check. The command's values
     * are already valid.
     * The caller reads the body only through the returned {@link PendingUpload} and must close it.
     */
    public PendingUpload startUpload(UploadCommand command) {
        long partSize = blobs.partSizeFor(command.declaredLength());
        if (!budget.canEverFit(partSize)) {
            throw new FileTooLargeException("the file is too large to store with bounded memory");
        }
        UploadBudget.Reservation reservation = budget.tryReserve(partSize)
                .orElseThrow(UploadCapacityReachedException::new);
        try {
            files.findByOwnerAndFilenameKey(command.owner(), command.filename().key()).ifPresent(existing -> {
                throw existing.alreadyExists(Kind.FILENAME);
            });
        } catch (RuntimeException e) {
            reservation.close();
            throw e;
        }
        return new AdmittedUpload(files, blobs, detector, clock, command, reservation);
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
                    .map(existing -> existing.alreadyExists(Kind.FILENAME))
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
        try {
            blobs.delete(file.storageKey());
        } catch (RuntimeException e) {
            log.error("orphaned bytes (file deleted): storage key {} could not be deleted", file.storageKey().value(), e);
        }
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
}
