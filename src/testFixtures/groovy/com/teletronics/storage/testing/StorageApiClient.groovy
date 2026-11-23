package com.teletronics.storage.testing

import groovy.json.JsonOutput

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * The STORAGE REST API as a client for black-box specs: every method is one HTTP request, exactly as a
 * user of the API would send it (README, "API").
 */
class StorageApiClient {

    final URI base
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    StorageApiClient(String baseUrl) {
        base = URI.create(baseUrl)
    }

    /**
     * POST /files with the raw bytes as the body.
     * Options: visibility (default PRIVATE), tags, contentType (default application/octet-stream).
     * A null visibility or contentType leaves it out of the request.
     */
    ApiResponse upload(String user, String filename, byte[] content, Map options = [:]) {
        Map<String, List<String>> params = [filename: [filename]]
        String visibility = options.containsKey("visibility") ? options.visibility : "PRIVATE"
        if (visibility != null) {
            params.visibility = [visibility]
        }
        if (options.tags) {
            params.tags = options.tags as List<String>
        }
        def request = HttpRequest.newBuilder(uri("/files", params))
        if (user != null) {
            request.header("X-User-Id", user)
        }
        String contentType = options.containsKey("contentType") ? options.contentType : "application/octet-stream"
        if (contentType != null) {
            request.header("Content-Type", contentType)
        }
        send(request.POST(HttpRequest.BodyPublishers.ofByteArray(content)))
    }

    ApiResponse get(String user, String id) {
        send(request("/files/$id", user).GET())
    }

    ApiResponse rename(String user, String id, String filename) {
        send(request("/files/$id", user).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JsonOutput.toJson([filename: filename]))))
    }

    ApiResponse delete(String user, String id) {
        send(request("/files/$id", user).DELETE())
    }

    /** GET /files: the user's own files. Params: tag, sort ("size,desc"), page, size. */
    ApiResponse myFiles(String user, Map params = [:]) {
        def request = HttpRequest.newBuilder(uri("/files", listParams(params)))
        if (user != null) {
            request.header("X-User-Id", user)
        }
        send(request.GET())
    }

    /** GET /files/public: every user's PUBLIC files. No user id needed. */
    ApiResponse publicFiles(Map params = [:]) {
        send(HttpRequest.newBuilder(uri("/files/public", listParams(params))).GET())
    }

    /** GET on a download link as the upload returned it (relative to the service). */
    ApiResponse download(String link) {
        send(HttpRequest.newBuilder(base.resolve(link)).GET())
    }

    ApiResponse get(String path) {
        send(HttpRequest.newBuilder(base.resolve(path)).GET())
    }

    private HttpRequest.Builder request(String path, String user) {
        def request = HttpRequest.newBuilder(base.resolve(path))
        if (user != null) {
            request.header("X-User-Id", user)
        }
        request
    }

    private ApiResponse send(HttpRequest.Builder request) {
        def response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
        new ApiResponse(response.statusCode(), response.headers(), response.body())
    }

    private static Map<String, List<String>> listParams(Map params) {
        params.collectEntries { name, value -> [(name as String): (value instanceof List ? value : [value]).collect { it as String }] }
    }

    private URI uri(String path, Map<String, List<String>> params) {
        def query = params.collectMany { name, values ->
            values.collect { "${name}=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }
        }.join("&")
        base.resolve(query ? "$path?$query" : path)
    }
}
