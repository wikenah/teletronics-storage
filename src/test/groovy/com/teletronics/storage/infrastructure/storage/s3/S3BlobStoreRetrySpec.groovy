package com.teletronics.storage.infrastructure.storage.s3

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.teletronics.storage.application.port.StorageUnavailableException
import com.teletronics.storage.domain.file.StorageKey
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import static com.teletronics.storage.infrastructure.storage.s3.PartSizePolicy.MIB

/**
 * The real AWS SDK client against a small local S3 stub that can fail chosen parts, so the SDK's own
 * retries run against our buffered parts (docs/acceptance-criteria/storage.md STO-07, STO-08).
 */
class S3BlobStoreRetrySpec extends Specification {

    def stub = new S3Stub()
    S3Client s3
    S3BlobStore store

    def setup() {
        stub.start()
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:${stub.port}"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
                .forcePathStyle(true)
                .httpClientBuilder(Apache5HttpClient.builder())
                .build()
        store = new S3BlobStore(s3, "files", PartSizePolicy.standard())
    }

    def cleanup() {
        s3?.close()
        stub.stop()
    }

    def "STO-07 a failed part is retried from the buffer without a copy"() {
        given: "the store fails the first attempt of part 3 with a retryable error"
        stub.failures[3] = 1
        def key = StorageKey.random()
        byte[] content = random(30 * MIB)

        when: "the adapter writes the body"
        store.store(key, new ByteArrayInputStream(content), OptionalLong.of(content.length))

        then: "part 3 is sent again and the upload completes"
        stub.attempts.count { it == 3 } == 2
        stub.completed.contains(key.value())

        and: "the stored bytes equal the body"
        stub.objects[key.value()] == content
    }

    def "STO-08 when retries run out, the multipart upload is aborted"() {
        given: "the store fails part 3 on every attempt"
        stub.failures[3] = Integer.MAX_VALUE
        def key = StorageKey.random()
        byte[] content = random(30 * MIB)

        when: "the adapter writes the body"
        store.store(key, new ByteArrayInputStream(content), OptionalLong.of(content.length))

        then: "the failure is reported as storage unavailable"
        thrown(StorageUnavailableException)

        and: "the SDK tried part 3 more than once before giving up"
        stub.attempts.count { it == 3 } > 1

        and: "AbortMultipartUpload is called and no object exists under the key"
        stub.aborted.contains(key.value())
        !stub.objects.containsKey(key.value())
    }

    static byte[] random(long size) {
        def bytes = new byte[(int) size]
        new Random(size).nextBytes(bytes)
        bytes
    }

    /**
     * Just enough of the S3 API for multipart writes. Request bodies may arrive aws-chunked
     * (the SDK's default checksum trailer); they are decoded before being kept.
     */
    static class S3Stub {
        final Map<Integer, Integer> failures = new ConcurrentHashMap<>()
        final List<Integer> attempts = new CopyOnWriteArrayList<>()
        final Map<String, Map<Integer, byte[]>> parts = new ConcurrentHashMap<>()
        final Map<String, byte[]> objects = new ConcurrentHashMap<>()
        final List<String> completed = new CopyOnWriteArrayList<>()
        final List<String> aborted = new CopyOnWriteArrayList<>()
        private HttpServer server

        int getPort() {
            server.address.port
        }

        void start() {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
            server.createContext("/") { HttpExchange exchange -> handle(exchange) }
            server.start()
        }

        void stop() {
            server?.stop(0)
        }

        private void handle(HttpExchange exchange) {
            try {
                String key = exchange.requestURI.path.replaceFirst("^/files/", "")
                Map<String, String> query = (exchange.requestURI.rawQuery ?: "").split("&").findAll().collectEntries {
                    def pair = it.split("=", 2); [(pair[0]): pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""]
                }
                byte[] body = decode(exchange)
                String method = exchange.requestMethod
                if (method == "POST" && query.containsKey("uploads")) {
                    parts[key] = new ConcurrentHashMap<>()
                    xml(exchange, 200, "<InitiateMultipartUploadResult><Bucket>files</Bucket><Key>$key</Key><UploadId>u1</UploadId></InitiateMultipartUploadResult>")
                } else if (method == "PUT" && query.containsKey("partNumber")) {
                    int number = query.partNumber as int
                    attempts << number
                    int remaining = failures.getOrDefault(number, 0)
                    if (remaining > 0) {
                        failures[number] = remaining - 1
                        xml(exchange, 500, "<Error><Code>InternalError</Code><Message>try again</Message></Error>")
                        return
                    }
                    parts[key][number] = body
                    exchange.responseHeaders.add("ETag", "\"etag-$number\"")
                    exchange.sendResponseHeaders(200, -1)
                } else if (method == "POST" && query.containsKey("uploadId")) {
                    def out = new ByteArrayOutputStream()
                    parts[key].keySet().sort().each { out.write(parts[key][it]) }
                    objects[key] = out.toByteArray()
                    completed << key
                    xml(exchange, 200, "<CompleteMultipartUploadResult><Bucket>files</Bucket><Key>$key</Key><ETag>\"done\"</ETag></CompleteMultipartUploadResult>")
                } else if (method == "DELETE" && query.containsKey("uploadId")) {
                    aborted << key
                    parts.remove(key)
                    exchange.sendResponseHeaders(204, -1)
                } else if (method == "PUT") {
                    objects[key] = body
                    exchange.responseHeaders.add("ETag", "\"put\"")
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    xml(exchange, 400, "<Error><Code>NotImplemented</Code></Error>")
                }
            } finally {
                exchange.close()
            }
        }

        private static void xml(HttpExchange exchange, int status, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.sendResponseHeaders(status, bytes.length)
            exchange.responseBody.write(bytes)
        }

        /** Plain bodies as they are; aws-chunked bodies ("size;ext\r\ndata\r\n ... 0\r\ntrailers") decoded. */
        private static byte[] decode(HttpExchange exchange) {
            byte[] raw = exchange.requestBody.readAllBytes()
            String encoding = exchange.requestHeaders.getFirst("Content-Encoding") ?: ""
            if (!encoding.contains("aws-chunked") && !exchange.requestHeaders.containsKey("x-amz-decoded-content-length")) {
                return raw
            }
            def in = new ByteArrayInputStream(raw)
            def out = new ByteArrayOutputStream()
            while (true) {
                String header = readLine(in)
                int size = Integer.parseInt(header.split(";")[0].trim(), 16)
                if (size == 0) {
                    return out.toByteArray()
                }
                out.write(in.readNBytes(size))
                readLine(in)
            }
        }

        private static String readLine(InputStream in) {
            def line = new StringBuilder()
            int b
            while ((b = in.read()) != -1 && b != '\n') {
                if (b != '\r') {
                    line.append((char) b)
                }
            }
            line.toString()
        }
    }
}
