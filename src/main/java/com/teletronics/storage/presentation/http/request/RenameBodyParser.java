package com.teletronics.storage.presentation.http.request;

import com.teletronics.storage.presentation.http.exception.InvalidRequestException;

import java.util.Map;
import java.util.Set;

/**
 * A rename body is exactly {"filename": string}. Jackson 3 ignores unknown properties by default,
 * so the keys are checked here (API-26): only the name can change.
 */
public final class RenameBodyParser {

    private RenameBodyParser() {
    }

    public static String filename(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("filename"))) {
            throw new InvalidRequestException("body", "the body must be exactly {\"filename\": \"...\"}; only the name can change");
        }
        if (!(body.get("filename") instanceof String filename)) {
            throw new InvalidRequestException("filename", "filename must be a string");
        }
        return filename;
    }
}
