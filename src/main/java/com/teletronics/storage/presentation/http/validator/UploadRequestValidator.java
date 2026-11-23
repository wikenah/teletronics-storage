package com.teletronics.storage.presentation.http.validator;

import com.teletronics.storage.presentation.http.exception.FormBodyNotSupportedException;
import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import com.teletronics.storage.presentation.http.exception.UnsupportedContentEncodingException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Rejects the upload requests only HTTP can judge: form bodies, compressed bodies and a missing X-User-Id
 * header (API-01, API-05, API-24). It reads headers only, and must run before anything reads a request
 * parameter: for a form-urlencoded POST, reading parameters would consume the body.
 */
@UtilityClass
public class UploadRequestValidator {

    /** The order is part of the API: a form body is refused before anything else is looked at. */
    public static void validate(HttpServletRequest request) {
        rejectFormBody(request.getContentType());
        rejectCompressedBody(request.getHeader(HttpHeaders.CONTENT_ENCODING));
        UserIdValidator.validate(request);
    }

    private static void rejectFormBody(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return;
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            throw new InvalidRequestException(HttpHeaders.CONTENT_TYPE, "the Content-Type header is not a valid media type");
        }
        if (MediaType.MULTIPART_FORM_DATA.includes(type) || MediaType.APPLICATION_FORM_URLENCODED.includes(type)) {
            throw new FormBodyNotSupportedException(type.getType() + "/" + type.getSubtype());
        }
    }

    private static void rejectCompressedBody(String encoding) {
        if (encoding != null && !encoding.isBlank() && !encoding.trim().equalsIgnoreCase("identity")) {
            throw new UnsupportedContentEncodingException(encoding.trim());
        }
    }
}
