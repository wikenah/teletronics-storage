package com.teletronics.storage.presentation.http.request;

import com.teletronics.storage.domain.file.valueobject.OwnerId;
import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's id comes in the X-User-Id header (the brief has no user management; HTTP decision 8).
 * Missing or blank is a 400, not a 401: there is no authentication scheme to challenge with.
 */
public final class RequestUser {

    public static final String HEADER = "X-User-Id";

    private RequestUser() {
    }

    public static String required(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(HEADER, "the X-User-Id header is required");
        }
        return value;
    }

    public static OwnerId owner(HttpServletRequest request) {
        return OwnerId.of(required(request));
    }
}
