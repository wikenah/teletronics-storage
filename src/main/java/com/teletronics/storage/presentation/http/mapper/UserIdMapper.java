package com.teletronics.storage.presentation.http.mapper;

import com.teletronics.storage.domain.file.valueobject.UserId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

/**
 * The caller's id comes in the X-User-Id header (the brief has no user management; HTTP decision 8).
 * Expects a request that UserIdValidator has accepted.
 */
@UtilityClass
public class UserIdMapper {

    public static final String HEADER = "X-User-Id";

    public static UserId toUserId(HttpServletRequest request) {
        return UserId.of(request.getHeader(HEADER));
    }
}
