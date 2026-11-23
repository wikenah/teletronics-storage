package com.teletronics.storage.infrastructure.persistence.mongo;

import com.teletronics.storage.domain.file.FileRepository;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.exception.RepositoryDuplicateException;
import com.teletronics.storage.domain.file.exception.RepositoryUnavailableException;
import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.query.PageResult;
import com.teletronics.storage.domain.file.query.SortField;
import com.teletronics.storage.domain.file.valueobject.DownloadToken;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.UserId;
import com.teletronics.storage.domain.file.valueobject.Sha256;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.function.Supplier;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * FileRepository on MongoDB. Each call holds a pooled connection only for its own round trip,
 * never while a body streams (DOM-18).
 */
@Repository
@RequiredArgsConstructor
public class MongoFileRepository implements FileRepository {

    private final MongoOperations mongo;

    @Override
    public Optional<StoredFile> findById(FileId id) {
        return call(() -> Optional.ofNullable(mongo.findById(id.value(), FileDocument.class)).map(FileDocument::toDomain));
    }

    @Override
    public Optional<StoredFile> findByOwnerAndFilenameKey(UserId owner, String filenameKey) {
        return findOne(where(FileDocument.OWNER_ID).is(owner.value()).and(FileDocument.FILENAME_KEY).is(filenameKey));
    }

    @Override
    public Optional<StoredFile> findByOwnerAndSha256(UserId owner, Sha256 sha256) {
        return findOne(where(FileDocument.OWNER_ID).is(owner.value()).and(FileDocument.SHA256).is(sha256.hex()));
    }

    @Override
    public Optional<StoredFile> findByDownloadToken(DownloadToken token) {
        return findOne(where(FileDocument.DOWNLOAD_TOKEN).is(token.value()));
    }

    @Override
    public void insert(StoredFile file) {
        call(() -> mongo.insert(FileDocument.of(file)));
    }

    @Override
    public void updateFilename(StoredFile file) {
        call(() -> mongo.updateFirst(
                Query.query(where("_id").is(file.id().value())),
                new Update().set(FileDocument.FILENAME, file.filename().value())
                        .set(FileDocument.FILENAME_KEY, file.filename().key()),
                FileDocument.class));
    }

    @Override
    public boolean delete(FileId id) {
        return call(() -> mongo.remove(Query.query(where("_id").is(id.value())), FileDocument.class).getDeletedCount() > 0);
    }

    @Override
    public PageResult<StoredFile> find(FileQuery query) {
        Criteria filter = filterOf(query);
        Query page = Query.query(filter)
                .with(sortOf(query))
                .skip((long) query.page() * query.size())
                .limit(query.size());
        return call(() -> {
            long total = mongo.count(Query.query(filter), FileDocument.class);
            var items = mongo.find(page, FileDocument.class).stream().map(FileDocument::toDomain).toList();
            return new PageResult<>(items, query.page(), query.size(), total);
        });
    }

    /** Only the filters the query carries: owner, visibility, tag. */
    private static Criteria filterOf(FileQuery query) {
        Criteria criteria = new Criteria();
        query.owner().ifPresent(owner -> criteria.and(FileDocument.OWNER_ID).is(owner.value()));
        query.visibility().ifPresent(visibility -> criteria.and(FileDocument.VISIBILITY).is(visibility.name()));
        query.tag().ifPresent(tag -> criteria.and(FileDocument.TAGS).is(tag.value()));
        return criteria;
    }

    /** The requested field, then the id, so files with equal values keep a stable order across pages. */
    private static Sort sortOf(FileQuery query) {
        Sort.Direction direction = query.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field(query.sortField())).and(Sort.by(direction, "_id"));
    }

    /**
     * Sort keys. Filenames sort by the lowercase key. Tags are an array: MongoDB sorts ascending by each
     * file's smallest tag and descending by its largest, and files without tags come first ascending.
     */
    private static String field(SortField field) {
        return switch (field) {
            case FILENAME -> FileDocument.FILENAME_KEY;
            case UPLOAD_DATE -> FileDocument.UPLOADED_AT;
            case TAG -> FileDocument.TAGS;
            case CONTENT_TYPE -> FileDocument.CONTENT_TYPE;
            case SIZE -> FileDocument.SIZE;
        };
    }

    private Optional<StoredFile> findOne(Criteria criteria) {
        return call(() -> Optional.ofNullable(mongo.findOne(Query.query(criteria), FileDocument.class))
                .map(FileDocument::toDomain));
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            throw new RepositoryDuplicateException("duplicate key", e);
        } catch (DataAccessResourceFailureException | TransientDataAccessException e) {
            throw new RepositoryUnavailableException("database unavailable", e);
        }
    }
}
