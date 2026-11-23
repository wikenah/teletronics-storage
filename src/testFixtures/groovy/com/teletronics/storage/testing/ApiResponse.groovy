package com.teletronics.storage.testing

import groovy.json.JsonSlurper

import java.net.http.HttpHeaders
import java.nio.charset.StandardCharsets

/**
 * One HTTP response from the STORAGE API.
 */
class ApiResponse {

    final int status
    final HttpHeaders headers
    final byte[] body

    ApiResponse(int status, HttpHeaders headers, byte[] body) {
        this.status = status
        this.headers = headers
        this.body = body
    }

    Map json() {
        new JsonSlurper().parse(body) as Map
    }

    /** RFC 9457 §3.1.1: an absent "type" means about:blank. */
    String problemType() {
        json().type ?: "about:blank"
    }

    String header(String name) {
        headers.firstValue(name).orElse(null)
    }

    @Override
    String toString() {
        "HTTP $status ${new String(body, StandardCharsets.UTF_8).take(300)}"
    }
}
