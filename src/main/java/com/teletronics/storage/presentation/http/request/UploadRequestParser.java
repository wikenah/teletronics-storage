package com.teletronics.storage.presentation.http.request;

import com.teletronics.storage.application.upload.UploadCommand;
import com.teletronics.storage.presentation.http.exception.FormBodyNotSupportedException;
import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import com.teletronics.storage.presentation.http.exception.UnsupportedContentEncodingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads an upload request's headers and query parameters without touching the body. What only HTTP knows is
 * rejected here (form bodies, compressed bodies, a missing X-User-Id header); the filename, visibility and
 * tags are checked by their own types when the command is built (API-01 to API-05, API-24). The content type
 * is checked first: for a form-urlencoded POST, reading parameters would consume the body.
 */
public final class UploadRequestParser {

    private UploadRequestParser() {
    }

    public static UploadCommand parse(HttpServletRequest request) {
        String contentType = request.getContentType();
        rejectFormBodies(contentType);
        rejectCompressedBodies(request.getHeader("Content-Encoding"));
        String owner = RequestUser.required(request);
        String[] tags = request.getParameterValues("tags");
        long length = request.getContentLengthLong();
        return UploadCommand.of(
                owner,
                request.getParameter("filename"),
                request.getParameter("visibility"),
                tags == null ? List.of() : List.of(tags),
                Optional.ofNullable(contentType).map(String::trim).filter(value -> !value.isEmpty()),
                length >= 0 ? OptionalLong.of(length) : OptionalLong.empty());
    }

    private static void rejectFormBodies(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return;
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            throw new InvalidRequestException("Content-Type", "the Content-Type header is not a valid media type");
        }
        if (MediaType.MULTIPART_FORM_DATA.includes(type) || MediaType.APPLICATION_FORM_URLENCODED.includes(type)) {
            throw new FormBodyNotSupportedException(type.getType() + "/" + type.getSubtype());
        }
    }

    private static void rejectCompressedBodies(String encoding) {
        if (encoding != null && !encoding.isBlank() && !encoding.trim().equalsIgnoreCase("identity")) {
            throw new UnsupportedContentEncodingException(encoding.trim());
        }
    }
}
