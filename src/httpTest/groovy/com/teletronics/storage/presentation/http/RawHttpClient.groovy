package com.teletronics.storage.presentation.http

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets

/**
 * A minimal HTTP/1.1 client on a plain socket, so a spec controls exactly when body bytes are sent
 * and can see interim 100 Continue responses (which java.net.http hides).
 */
class RawHttpClient implements Closeable {

    private final Socket socket
    private final InputStream input
    private final OutputStream output
    long bodyBytesSent = 0

    RawHttpClient(int port, int readTimeoutMillis = 15_000) {
        socket = new Socket("localhost", port)
        socket.soTimeout = readTimeoutMillis
        input = new BufferedInputStream(socket.inputStream)
        output = new BufferedOutputStream(socket.outputStream, 64 * 1024)
    }

    void sendHead(String method, String target, Map<String, String> headers) {
        def head = new StringBuilder("$method $target HTTP/1.1\r\nHost: localhost\r\n")
        headers.each { name, value -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    void sendBody(byte[] bytes, int offset = 0, int length = bytes.length) {
        output.write(bytes, offset, length)
        bodyBytesSent += length
    }

    void sendChunk(byte[] bytes, int offset = 0, int length = bytes.length) {
        output.write("${Integer.toHexString(length)}\r\n".getBytes(StandardCharsets.ISO_8859_1))
        output.write(bytes, offset, length)
        output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1))
        bodyBytesSent += length
    }

    void endChunks() {
        output.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))
        flush()
    }

    void flush() {
        output.flush()
    }

    /** The next response: an interim 1xx (head only) or a final response with its body. */
    Response readResponse() {
        String statusLine = readLine()
        if (statusLine == null) {
            throw new EOFException("connection closed before a response")
        }
        int status = statusLine.split(" ")[1] as int
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
        String line
        while ((line = readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':')
            headers.computeIfAbsent(line.substring(0, colon).trim()) { [] } << line.substring(colon + 1).trim()
        }
        byte[] body = new byte[0]
        if (status >= 200 && status != 204 && status != 304) {
            if (headers["Transfer-Encoding"]?.any { it.equalsIgnoreCase("chunked") }) {
                body = readChunked()
            } else if (headers["Content-Length"]) {
                body = input.readNBytes(headers["Content-Length"][0] as int)
            } else {
                body = input.readAllBytes()
            }
        }
        new Response(status, headers, body)
    }

    /** Whether the server has closed the connection (reads EOF within the timeout). */
    boolean serverClosed(int timeoutMillis = 5_000) {
        int previous = socket.soTimeout
        socket.soTimeout = timeoutMillis
        try {
            return input.read() == -1
        } catch (SocketException ignored) {
            return true
        } catch (SocketTimeoutException ignored) {
            return false
        } finally {
            socket.soTimeout = previous
        }
    }

    @Override
    void close() {
        socket.close()
    }

    private byte[] readChunked() {
        def out = new ByteArrayOutputStream()
        while (true) {
            int size = Integer.parseInt(readLine().split(";")[0].trim(), 16)
            if (size == 0) {
                while (readLine()) { }
                return out.toByteArray()
            }
            out.write(input.readNBytes(size))
            readLine()
        }
    }

    private String readLine() {
        def line = new ByteArrayOutputStream()
        int b
        while ((b = input.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray()
                int length = bytes.length > 0 && bytes[-1] == (byte) '\r' ? bytes.length - 1 : bytes.length
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1)
            }
            line.write(b)
        }
        line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    static class Response {
        final int status
        final Map<String, List<String>> headers
        final byte[] body

        Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status
            this.headers = headers
            this.body = body
        }

        String header(String name) {
            headers[name]?.join(", ")
        }

        Map json() {
            new JsonSlurper().parse(body) as Map
        }

        /** RFC 9457 §3.1.1: an absent "type" means about:blank. */
        String problemType() {
            json().type ?: "about:blank"
        }
    }
}
