package com.teletronics.storage.infrastructure.persistence.mongo;

import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.valueobject.ContentType;
import com.teletronics.storage.domain.file.valueobject.DownloadToken;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.Filename;
import com.teletronics.storage.domain.file.valueobject.UserId;
import com.teletronics.storage.domain.file.valueobject.Sha256;
import com.teletronics.storage.domain.file.valueobject.StorageKey;
import com.teletronics.storage.domain.file.valueobject.Tags;
import com.teletronics.storage.domain.file.valueobject.Visibility;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One document per file in the "files" collection (Storage decision 6). The three unique indexes decide
 * duplicates at insert: name per owner, content per owner, and the download token.
 */
@Document("files")
@CompoundIndexes({
        @CompoundIndex(name = "owner_filename_key", def = "{'ownerId': 1, 'filenameKey': 1}", unique = true),
        @CompoundIndex(name = "owner_sha256", def = "{'ownerId': 1, 'sha256': 1}", unique = true)
})
record FileDocument(
        @Id String id,
        String ownerId,
        String filename,
        String filenameKey,
        String visibility,
        List<String> tags,
        long size,
        String sha256,
        String contentType,
        Instant uploadedAt,
        @Indexed(name = "download_token", unique = true) String downloadToken,
        String storageKey) {

    static final String OWNER_ID = "ownerId";
    static final String FILENAME = "filename";
    static final String FILENAME_KEY = "filenameKey";
    static final String VISIBILITY = "visibility";
    static final String TAGS = "tags";
    static final String SIZE = "size";
    static final String SHA256 = "sha256";
    static final String CONTENT_TYPE = "contentType";
    static final String UPLOADED_AT = "uploadedAt";
    static final String DOWNLOAD_TOKEN = "downloadToken";

    static FileDocument of(StoredFile file) {
        return new FileDocument(
                file.id().value(),
                file.owner().value(),
                file.filename().value(),
                file.filename().key(),
                file.visibility().name(),
                file.tags().values(),
                file.size(),
                file.sha256().hex(),
                file.contentType().value(),
                file.uploadedAt(),
                file.downloadToken().value(),
                file.storageKey().value());
    }

    StoredFile toDomain() {
        return new StoredFile(
                new FileId(id),
                UserId.of(ownerId),
                Filename.of(filename),
                Visibility.valueOf(visibility),
                Tags.of(tags == null ? List.of() : tags),
                size,
                new Sha256(sha256),
                new ContentType(contentType),
                uploadedAt,
                new DownloadToken(downloadToken),
                new StorageKey(storageKey));
    }
}
