package com.teletronics.storage.domain.file;

import com.teletronics.storage.domain.file.exception.RepositoryDuplicateException;
import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.query.PageResult;
import com.teletronics.storage.domain.file.valueobject.DownloadToken;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.UserId;
import com.teletronics.storage.domain.file.valueobject.Sha256;

import java.util.Optional;

/**
 * The collection of stored files (a DDD repository; its implementation lives in infrastructure).
 * Enforces uniqueness per owner of the filename key and of the SHA-256, and of the download token.
 */
public interface FileRepository {

    Optional<StoredFile> findById(FileId id);

    Optional<StoredFile> findByOwnerAndFilenameKey(UserId owner, String filenameKey);

    Optional<StoredFile> findByOwnerAndSha256(UserId owner, Sha256 sha256);

    Optional<StoredFile> findByDownloadToken(DownloadToken token);

    /** @throws RepositoryDuplicateException when a unique rule is broken */
    void insert(StoredFile file);

    /** @throws RepositoryDuplicateException when the new name is taken */
    void updateFilename(StoredFile file);

    /** @return whether a file was deleted */
    boolean delete(FileId id);

    PageResult<StoredFile> find(FileQuery query);
}
