package com.teletronics.storage.presentation.http.mapper;

import com.teletronics.storage.application.upload.UploadCommand;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Maps an upload request's headers and query parameters to an {@link UploadCommand}, without touching the
 * body. Expects a request that UploadRequestValidator has accepted. The filename, visibility and tags are checked by their own types when the command is built
 * (API-01 to API-04).
 */
@UtilityClass
public class UploadCommandMapper {

    public static UploadCommand toCommand(HttpServletRequest request) {
        String[] tags = request.getParameterValues("tags");
        long length = request.getContentLengthLong();
        return UploadCommand.of(
                request.getHeader(UserIdMapper.HEADER),
                request.getParameter("filename"),
                request.getParameter("visibility"),
                tags == null ? List.of() : List.of(tags),
                Optional.ofNullable(request.getContentType()).map(String::trim).filter(value -> !value.isEmpty()),
                length >= 0 ? OptionalLong.of(length) : OptionalLong.empty());
    }
}
