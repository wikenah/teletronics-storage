package com.teletronics.storage.presentation.http.validator;

import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import com.teletronics.storage.presentation.http.mapper.UserIdMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

/**
 * Requires the X-User-Id header (API-01). Missing or blank is a 400, not a 401: there is no authentication
 * scheme to challenge with.
 */
@UtilityClass
public class UserIdValidator {

    public static void validate(HttpServletRequest request) {
        String value = request.getHeader(UserIdMapper.HEADER);
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(UserIdMapper.HEADER, "the X-User-Id header is required");
        }
    }
}
