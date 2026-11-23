package com.teletronics.storage.presentation.http.request;

import java.util.List;

/**
 * The query parameters of the two listing endpoints, as the client sent them: page (from 0), size,
 * sort=field,direction and tag. Any of them may be absent (null). They are text on purpose:
 * ListParamsValidator checks them, so an invalid value gets this API's error format.
 */
public record ListParams(String page, String size, String sort, List<String> tag) {
}
