package com.teletronics.storage.presentation.http.request;

import com.teletronics.storage.presentation.http.exception.FormBodyNotSupportedException;
import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import com.teletronics.storage.presentation.http.exception.UnsupportedContentEncodingException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Rejects the upload requests only HTTP can judge: form bodies and compressed bodies (API-05, API-24).
 * It reads headers only. It must run before anything reads a request parameter: for a form-urlencoded
 * POST, reading parameters would consume the body.
 */
@UtilityClass
public class UploadRequestValidator {

    public static void validate(HttpServletRequest request) {
        rejectFormBodies(request.getContentType());
        rejectCompressedBodies(request.getHeader("Content-Encoding"));
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
