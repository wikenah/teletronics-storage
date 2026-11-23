package com.teletronics.storage.presentation.http.validator;

import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import com.teletronics.storage.presentation.http.mapper.OwnerIdMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

/**
 * Requires the X-User-Id header. Missing or blank is a 400, not a 401: there is no authentication scheme to challenge with.
 */
@UtilityClass
public class UserIdValidator {

    public static void validate(HttpServletRequest request) {
        String value = request.getHeader(OwnerIdMapper.HEADER);
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(OwnerIdMapper.HEADER, "the X-User-Id header is required");
        }
    }
}
