package com.teletronics.storage.presentation.http;

import com.teletronics.storage.application.FileService;
import com.teletronics.storage.application.download.Download;
import com.teletronics.storage.application.upload.PendingUpload;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.exception.FileNotFoundException;
import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.domain.file.valueobject.OwnerId;
import com.teletronics.storage.presentation.http.mapper.FileQueryMapper;
import com.teletronics.storage.presentation.http.mapper.OwnerIdMapper;
import com.teletronics.storage.presentation.http.mapper.UploadCommandMapper;
import com.teletronics.storage.presentation.http.response.FileResponse;
import com.teletronics.storage.presentation.http.response.PageResponse;
import com.teletronics.storage.presentation.http.validator.ListParamsValidator;
import com.teletronics.storage.presentation.http.validator.RenameBodyValidator;
import com.teletronics.storage.presentation.http.validator.UploadRequestValidator;
import com.teletronics.storage.presentation.http.validator.UserIdValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The file endpoints (docs/acceptance-criteria/http-api.md). The upload handler takes the raw
 * HttpServletRequest, never @RequestBody: nothing may read the body before every check has passed,
 * so that a rejection reaches the client before it sends any body byte.
 */
@RestController
@RequiredArgsConstructor
public class FilesController {

    private static final String FILES = "/files";

    private final FileService files;

    @PostMapping(FILES)
    public ResponseEntity<FileResponse> upload(HttpServletRequest request) throws IOException {
        UploadRequestValidator.validate(request);
        try (PendingUpload pending = files.startUpload(UploadCommandMapper.toCommand(request))) {
            StoredFile file = pending.complete(request.getInputStream());
            return ResponseEntity.created(URI.create(FILES + "/" + file.id().value())).body(FileResponse.of(file));
        }
    }

    @GetMapping(FILES + "/{id}")
    public FileResponse get(@PathVariable String id, HttpServletRequest request) {
        return FileResponse.of(files.get(owner(request), fileId(id)));
    }

    @PatchMapping(path = FILES + "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileResponse rename(@PathVariable String id, @RequestBody Map<String, Object> body,
                               HttpServletRequest request) {
        RenameBodyValidator.validate(body);
        String newName = (String) body.get(RenameBodyValidator.FILENAME);
        return FileResponse.of(files.rename(owner(request), fileId(id), newName));
    }

    @DeleteMapping(FILES + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        files.delete(owner(request), fileId(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(FILES)
    public PageResponse mine(HttpServletRequest request) {
        OwnerId owner = owner(request);
        ListParamsValidator.validate(request);
        return PageResponse.of(files.list(FileQueryMapper.toQuery(request, FileQuery.ownedBy(owner))));
    }

    @GetMapping(FILES + "/public")
    public PageResponse publicFiles(HttpServletRequest request) {
        ListParamsValidator.validate(request);
        return PageResponse.of(files.list(FileQueryMapper.toQuery(request, FileQuery.publicFiles())));
    }

    @GetMapping(FileResponse.DOWNLOAD_PATH + "{token}")
    public void download(@PathVariable String token, HttpServletResponse response) throws IOException {
        Download download = files.openDownload(token);
        try (InputStream body = download.body()) {
            StoredFile file = download.file();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(file.contentType().value());
            response.setContentLengthLong(file.size());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + UriUtils.encode(file.filename().value(), StandardCharsets.UTF_8));
            response.setHeader("X-Content-Type-Options", "nosniff");
            body.transferTo(response.getOutputStream());
        }
    }

    private static OwnerId owner(HttpServletRequest request) {
        UserIdValidator.validate(request);
        return OwnerIdMapper.toOwnerId(request);
    }

    /** An id that can't exist is the same as one that doesn't: 404, like any unknown file. */
    private static FileId fileId(String id) {
        return FileId.parse(id).orElseThrow(FileNotFoundException::new);
    }
}
