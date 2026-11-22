package com.teletronics.storage.presentation.http.request;

import com.teletronics.storage.application.UploadCommand;
import com.teletronics.storage.domain.file.Visibility;
import com.teletronics.storage.presentation.http.problem.FormBodyNotSupportedException;
import com.teletronics.storage.presentation.http.problem.InvalidRequestException;
import com.teletronics.storage.presentation.http.problem.UnsupportedContentEncodingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads an upload request's headers and query parameters without touching the body, and rejects what the
 * HTTP layer owns (API-01 to API-05, API-24) before the file service is called. The content type is checked
 * first: for a form-urlencoded POST, reading parameters would consume the body.
 */
public final class UploadRequestParser {

    public static final int MAX_TAGS = 5;

    private UploadRequestParser() {
    }

    public static UploadCommand parse(HttpServletRequest request) {
        String contentType = request.getContentType();
        rejectFormBodies(contentType);
        rejectCompressedBodies(request.getHeader("Content-Encoding"));
        String owner = RequestUser.required(request);

        String filename = request.getParameter("filename");
        if (filename == null || filename.isBlank()) {
            throw new InvalidRequestException("filename", "the filename parameter is required");
        }
        String visibility = request.getParameter("visibility");
        Visibility.parse(visibility);

        String[] tags = request.getParameterValues("tags");
        if (tags != null && tags.length > MAX_TAGS) {
            throw new InvalidRequestException("tags", "at most 5 tags are allowed");
        }
        if (tags != null && Arrays.stream(tags).anyMatch(tag -> tag.indexOf(',') >= 0)) {
            throw new InvalidRequestException("tags", "send each tag as its own 'tags' parameter; commas are not allowed");
        }

        long length = request.getContentLengthLong();
        return new UploadCommand(
                owner,
                filename,
                visibility,
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
