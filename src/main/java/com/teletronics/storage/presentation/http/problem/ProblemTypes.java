package com.teletronics.storage.presentation.http.problem;

import java.net.URI;

/**
 * Our RFC 9457 problem types: stable, full-path relative URIs (docs/acceptance-criteria/http-api.md, decision 6).
 * Spring's own errors and 500s use about:blank.
 */
public final class ProblemTypes {

    public static final URI INVALID_REQUEST = URI.create("/problems/invalid-request");
    public static final URI FILE_NOT_FOUND = URI.create("/problems/file-not-found");
    public static final URI FILENAME_EXISTS = URI.create("/problems/filename-exists");
    public static final URI CONTENT_EXISTS = URI.create("/problems/content-exists");
    public static final URI UNSUPPORTED_CONTENT_ENCODING = URI.create("/problems/unsupported-content-encoding");
    public static final URI TEMPORARILY_UNAVAILABLE = URI.create("/problems/temporarily-unavailable");
    public static final URI UPLOAD_CAPACITY_REACHED = URI.create("/problems/upload-capacity-reached");
    public static final URI FILE_TOO_LARGE = URI.create("/problems/file-too-large");

    private ProblemTypes() {
    }
}
