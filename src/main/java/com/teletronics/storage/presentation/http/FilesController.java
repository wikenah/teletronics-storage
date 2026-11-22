package com.teletronics.storage.presentation.http;

import com.teletronics.storage.application.FileService;
import com.teletronics.storage.application.PendingUpload;
import com.teletronics.storage.domain.file.FileId;
import com.teletronics.storage.domain.file.FileQuery;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.presentation.http.request.ListParamsParser;
import com.teletronics.storage.presentation.http.request.RenameBodyParser;
import com.teletronics.storage.presentation.http.request.RequestUser;
import com.teletronics.storage.presentation.http.request.UploadRequestParser;
import com.teletronics.storage.presentation.http.response.FileResponse;
import com.teletronics.storage.presentation.http.response.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * The file endpoints (docs/acceptance-criteria/http-api.md). The upload handler takes the raw
 * HttpServletRequest, never @RequestBody: nothing may read the body before every check has passed,
 * so that a rejection reaches the client before it sends any body byte.
 */
@RestController
@RequestMapping("/files")
public class FilesController {

    private final FileService files;

    public FilesController(FileService files) {
        this.files = files;
    }

    @PostMapping
    public ResponseEntity<FileResponse> upload(HttpServletRequest request)
            throws HttpMediaTypeNotSupportedException, IOException {
        try (PendingUpload pending = files.startUpload(UploadRequestParser.parse(request))) {
            StoredFile file = pending.complete(request.getInputStream());
            return ResponseEntity.created(URI.create("/files/" + file.id().value())).body(FileResponse.of(file));
        }
    }

    @GetMapping("/{id}")
    public FileResponse get(@PathVariable String id, HttpServletRequest request) {
        return FileResponse.of(files.get(RequestUser.owner(request), new FileId(id)));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileResponse rename(@PathVariable String id, @RequestBody Map<String, Object> body,
                               HttpServletRequest request) {
        String newName = RenameBodyParser.filename(body);
        return FileResponse.of(files.rename(RequestUser.owner(request), new FileId(id), newName));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        files.delete(RequestUser.owner(request), new FileId(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public PageResponse mine(HttpServletRequest request) {
        FileQuery query = ListParamsParser.parse(request, FileQuery.ownedBy(RequestUser.owner(request)));
        return PageResponse.of(files.list(query));
    }

    @GetMapping("/public")
    public PageResponse publicFiles(HttpServletRequest request) {
        return PageResponse.of(files.list(ListParamsParser.parse(request, FileQuery.publicFiles())));
    }
}
