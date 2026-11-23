package com.teletronics.storage.presentation.http;

import com.teletronics.storage.application.Download;
import com.teletronics.storage.application.FileService;
import com.teletronics.storage.domain.file.StoredFile;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Download by link: anyone holding the token gets the bytes, PUBLIC or PRIVATE (domain decision 8).
 * Bytes are copied straight to the response through a fixed-size buffer. A Range header is ignored:
 * the whole file is sent with 200 (API-30). The filename is sent once, exactly, in the RFC 8187 form
 * {@code filename*=UTF-8''...}; there is no second, ASCII-only copy of the name.
 */
@RestController
public class DownloadController {

    /** The download route. A file's link is this path plus its token, relative to the service as the brief asks. */
    public static final String PATH = "/download/";

    private final FileService files;

    public DownloadController(FileService files) {
        this.files = files;
    }

    @GetMapping(PATH + "{token}")
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
}
