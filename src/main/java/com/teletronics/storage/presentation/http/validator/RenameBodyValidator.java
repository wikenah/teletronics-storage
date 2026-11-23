package com.teletronics.storage.presentation.http.validator;

import com.teletronics.storage.presentation.http.exception.InvalidRequestException;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.Set;

/**
 * A rename body is exactly {"filename": string}. Jackson 3 ignores unknown properties by default,
 * so the keys are checked here (API-26): only the name can change.
 */
@UtilityClass
public class RenameBodyValidator {

    public static final String FILENAME = "filename";

    public static void validate(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of(FILENAME))) {
            throw new InvalidRequestException("body", "the body must be exactly {\"filename\": \"...\"}; only the name can change");
        }
        if (!(body.get(FILENAME) instanceof String)) {
            throw new InvalidRequestException(FILENAME, "filename must be a string");
        }
    }
}
