package com.teletronics.storage.presentation.http;

import com.teletronics.storage.application.Download;
import com.teletronics.storage.application.FileService;
import com.teletronics.storage.domain.file.StoredFile;
import com.teletronics.storage.presentation.http.response.ContentDispositionFormatter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Download by link: anyone holding the token gets the bytes, PUBLIC or PRIVATE (domain decision 8).
 * Bytes are copied straight to the response through a fixed-size buffer. A Range header is ignored:
 * the whole file is sent with 200 (API-30).
 */
@RestController
public class DownloadController {

    private final FileService files;

    public DownloadController(FileService files) {
        this.files = files;
    }

    @GetMapping("/download/{token}")
    public void download(@PathVariable String token, HttpServletResponse response) throws IOException {
        Download download = files.openDownload(token);
        try (InputStream body = download.body()) {
            StoredFile file = download.file();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(file.contentType().value());
            response.setContentLengthLong(file.size());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDispositionFormatter.attachment(file.filename().value()));
            response.setHeader("X-Content-Type-Options", "nosniff");
            body.transferTo(response.getOutputStream());
        }
    }
}
