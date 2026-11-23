package com.teletronics.storage.testing;

import com.teletronics.storage.domain.file.FileRepository;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.exception.RepositoryDuplicateException;
import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.query.PageResult;
import com.teletronics.storage.domain.file.valueobject.DownloadToken;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.OwnerId;
import com.teletronics.storage.domain.file.valueobject.Sha256;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A FileRepository in memory that enforces the same unique rules as the Mongo indexes:
 * owner + filename key, owner + SHA-256, download token. Sorting mirrors MongoDB, including arrays:
 * ascending uses each file's smallest tag, descending its largest, and no tags sorts first ascending.
 */
public class InMemoryFileRepository implements FileRepository {

    private final Map<FileId, StoredFile> files = new LinkedHashMap<>();
    private RuntimeException nextInsertFailure;

    public synchronized void failNextInsertWith(RuntimeException failure) {
        this.nextInsertFailure = failure;
    }

    public synchronized int count() {
        return files.size();
    }

    @Override
    public synchronized Optional<StoredFile> findById(FileId id) {
        return Optional.ofNullable(files.get(id));
    }

    @Override
    public synchronized Optional<StoredFile> findByOwnerAndFilenameKey(OwnerId owner, String filenameKey) {
        return files.values().stream()
                .filter(f -> f.owner().equals(owner) && f.filename().key().equals(filenameKey))
                .findFirst();
    }

    @Override
    public synchronized Optional<StoredFile> findByOwnerAndSha256(OwnerId owner, Sha256 sha256) {
        return files.values().stream()
                .filter(f -> f.owner().equals(owner) && f.sha256().equals(sha256))
                .findFirst();
    }

    @Override
    public synchronized Optional<StoredFile> findByDownloadToken(DownloadToken token) {
        return files.values().stream().filter(f -> f.downloadToken().equals(token)).findFirst();
    }

    @Override
    public synchronized void insert(StoredFile file) {
        if (nextInsertFailure != null) {
            RuntimeException failure = nextInsertFailure;
            nextInsertFailure = null;
            throw failure;
        }
        for (StoredFile other : files.values()) {
            if (clashes(file, other)) {
                throw new RepositoryDuplicateException("duplicate key");
            }
        }
        files.put(file.id(), file);
    }

    @Override
    public synchronized void updateFilename(StoredFile file) {
        for (StoredFile other : files.values()) {
            if (!other.id().equals(file.id()) && other.owner().equals(file.owner())
                    && other.filename().key().equals(file.filename().key())) {
                throw new RepositoryDuplicateException("duplicate key");
            }
        }
        files.put(file.id(), file);
    }

    @Override
    public synchronized boolean delete(FileId id) {
        return files.remove(id) != null;
    }

    @Override
    public synchronized PageResult<StoredFile> find(FileQuery query) {
        List<StoredFile> matching = new ArrayList<>(files.values().stream()
                .filter(f -> query.owner().map(o -> f.owner().equals(o)).orElse(true))
                .filter(f -> query.visibility().map(v -> f.visibility() == v).orElse(true))
                .filter(f -> query.tag().map(t -> f.tags().values().contains(t.value())).orElse(true))
                .toList());
        Comparator<StoredFile> order = comparator(query);
        Comparator<StoredFile> byId = Comparator.comparing(f -> f.id().value());
        matching.sort(query.ascending() ? order.thenComparing(byId) : order.reversed().thenComparing(byId.reversed()));
        int from = Math.min(query.page() * query.size(), matching.size());
        int to = Math.min(from + query.size(), matching.size());
        return new PageResult<>(matching.subList(from, to), query.page(), query.size(), matching.size());
    }

    private static Comparator<StoredFile> comparator(FileQuery query) {
        return switch (query.sortField()) {
            case FILENAME -> Comparator.comparing(f -> f.filename().key());
            case UPLOAD_DATE -> Comparator.comparing(StoredFile::uploadedAt);
            case SIZE -> Comparator.comparingLong(StoredFile::size);
            case CONTENT_TYPE -> Comparator.comparing(f -> f.contentType().value());
            case TAG -> query.ascending()
                    ? Comparator.comparing(f -> f.tags().values().stream().min(String::compareTo).orElse(""))
                    : Comparator.comparing(f -> f.tags().values().stream().max(String::compareTo).orElse(""));
        };
    }

    private static boolean clashes(StoredFile a, StoredFile b) {
        boolean sameOwner = a.owner().equals(b.owner());
        return (sameOwner && a.filename().key().equals(b.filename().key()))
                || (sameOwner && a.sha256().equals(b.sha256()))
                || a.downloadToken().equals(b.downloadToken());
    }
}
