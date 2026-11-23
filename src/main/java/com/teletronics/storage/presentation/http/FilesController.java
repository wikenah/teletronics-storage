package com.teletronics.storage.presentation.http;

import com.teletronics.storage.application.download.Download;
import com.teletronics.storage.application.FileService;
import com.teletronics.storage.application.upload.PendingUpload;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.domain.file.exception.FileNotFoundException;
import com.teletronics.storage.domain.file.query.FileQuery;
import com.teletronics.storage.domain.file.valueobject.FileId;
import com.teletronics.storage.presentation.http.request.ListParamsParser;
import com.teletronics.storage.presentation.http.request.RenameBodyParser;
import com.teletronics.storage.presentation.http.request.RequestUser;
import com.teletronics.storage.presentation.http.request.UploadCommandMapper;
import com.teletronics.storage.presentation.http.request.UploadRequestValidator;
import com.teletronics.storage.presentation.http.response.FileResponse;
import com.teletronics.storage.presentation.http.response.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
public class FilesController {

    private static final String FILES = "/files";

    private final FileService files;

    public FilesController(FileService files) {
        this.files = files;
    }

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
        return FileResponse.of(files.get(RequestUser.owner(request), fileId(id)));
    }

    @PatchMapping(path = FILES + "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileResponse rename(@PathVariable String id, @RequestBody Map<String, Object> body,
                               HttpServletRequest request) {
        String newName = RenameBodyParser.filename(body);
        return FileResponse.of(files.rename(RequestUser.owner(request), fileId(id), newName));
    }

    @DeleteMapping(FILES + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        files.delete(RequestUser.owner(request), fileId(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(FILES)
    public PageResponse mine(HttpServletRequest request) {
        FileQuery query = ListParamsParser.parse(request, FileQuery.ownedBy(RequestUser.owner(request)));
        return PageResponse.of(files.list(query));
    }

    @GetMapping(FILES + "/public")
    public PageResponse publicFiles(HttpServletRequest request) {
        return PageResponse.of(files.list(ListParamsParser.parse(request, FileQuery.publicFiles())));
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

    /** An id that can't exist is the same as one that doesn't: 404, like any unknown file. */
    private static FileId fileId(String id) {
        return FileId.parse(id).orElseThrow(FileNotFoundException::new);
    }
}
