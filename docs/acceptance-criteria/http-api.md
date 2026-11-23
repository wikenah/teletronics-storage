# HTTP API (inbound adapter): acceptance criteria

Conventions, test levels and the error format: see [README](README.md).

Status: **all endpoints agreed** (upload API-01 to API-24, other endpoints API-25 to API-31). The machine-readable contract is planned as an OpenAPI document; how it is produced is decided at build time (see design-notes, "OpenAPI contract").

| Endpoint | Who | Success |
|---|---|---|
| `POST /files` | Owner (`X-User-Id`) | 201 + file, `Location: /files/{id}` |
| `GET /files/{id}` | Owner | 200 + file |
| `PATCH /files/{id}` (rename) | Owner | 200 + file |
| `DELETE /files/{id}` | Owner | 204 |
| `GET /files` (my files) | Owner | 200 + page |
| `GET /files/public` | Anyone | 200 + page |
| `GET /download/{token}` | Anyone with the link | 200 + bytes |

Upload request: `POST /files?filename=…&visibility=PUBLIC|PRIVATE&tags=…&tags=…`, header `X-User-Id`, raw file bytes as the body.

## A. Validation (before any body byte is read)

### API-01 Missing or blank user id is rejected
```gherkin
Given a valid upload request
When it is sent <variant> the X-User-Id header
Then the response is 400 with type /problems/invalid-request
And the file service is not called
```
`where:` variant = without / with a blank value. Not 401: RFC 9110 requires a 401 to carry a `WWW-Authenticate` challenge, and there is no auth scheme. Test level: controller.

### API-02 Missing or blank filename is rejected
```gherkin
Given a valid upload request
When the filename parameter is <variant>
Then the response is 400 with type /problems/invalid-request
And the file service is not called
```
`where:` variant = missing / blank. Other filename rules (length, characters) and comparison (case-insensitive, DOM-01) are Domain. Test level: controller.

### API-03 Invalid visibility is rejected
```gherkin
Given a valid upload request
When visibility is <value>
Then the response is 400 with type /problems/invalid-request
And the file service is not called
```
`where:` value = missing / "SECRET" / "" / "public". Decided: required, exact values only. Test level: controller.

### API-04 Invalid tags are rejected
```gherkin
Given a valid upload request
When its tags are <tags>
Then the response is 400 with type /problems/invalid-request
And the file service is not called
```
`where:` tags = six repeated values (`tags=a&…&tags=f`) / a value containing a comma (`tags=a,b`) / a mix (`tags=a,b&tags=c`).

Decided: repeated parameter, read raw with `getParameterValues`, so Spring's `@RequestParam List<String>` comma-splitting never applies. Tag normalisation (case, allowed characters, duplicates in one request) is Domain. Test level: controller.

### API-05 Form content types are rejected
```gherkin
Given a valid upload request
When its Content-Type is <type>
Then the response is 415 with type about:blank
And the file service is not called
```
`where:` type = `multipart/form-data` / `application/x-www-form-urlencoded`. Form-urlencoded must be rejected because the servlet container consumes that body when parameters are read. Side effect: `curl --data-binary @file` without a Content-Type gets 415. Test level: controller.

### API-24 Compressed request bodies are rejected
```gherkin
Given a valid upload request
When it carries Content-Encoding: <coding>
Then the response is 415 with type /problems/unsupported-content-encoding
And the response carries Accept-Encoding: identity
And the file service is not called
```
`where:` coding = gzip / deflate / br. `Content-Encoding: identity` or no header is accepted.

Decided: reject. Tomcat's `compression` setting applies to responses only, and nothing found decodes request bodies. Accepting would store compressed bytes, so the SHA-256, size, type detection and download would all describe the compressed form. RFC 9110: servers failing a request over a content coding "ought to respond with a 415 … and include an Accept-Encoding header field", so clients can tell it apart from a media-type problem. Decompressing while streaming is the documented upgrade; it would need a guard against gzip bombs. Test level: controller.

### API-06 Rejections arrive before the body is sent
```gherkin
Given a request that API-01 to API-05 or API-24 would reject
And the client sends Expect: 100-continue and waits for the interim response
When the request headers are sent
Then the client receives the final 4xx without a 100 Continue
And the client sent zero body bytes
And the response carries Connection: close
```
Relies on Tomcat `continueResponseTiming=onRead`. Test level: HTTP (raw socket client).

## B. What the HTTP layer hands to the file service

### API-07 The request reaches the file service intact
```gherkin
Given alice sends filename "report.pdf", visibility PRIVATE, tags "work" and "2026",
  Content-Type application/pdf and a 1 KiB body with Content-Length 1024
When the request is processed
Then the file service receives owner "alice", filename "report.pdf", visibility PRIVATE,
  tags ["work", "2026"] exactly as sent, content type "application/pdf" and declared length 1024
And the body stream it reads yields exactly the 1024 bytes sent and then ends normally
```
Test level: controller (small body) and HTTP.

### API-08 Missing Content-Type is accepted
```gherkin
Given a valid upload request without a Content-Type header
When it is sent
Then the file service receives no content type
And the request is not rejected by the HTTP layer
```
RFC 9110 §8.3 allows treating it as `application/octet-stream`. Detecting the real type is Domain. Test level: controller.

### API-09 Body without Content-Length (chunked)
```gherkin
Given a valid upload request with Transfer-Encoding: chunked and no Content-Length
When 5 MiB are sent
Then the file service receives an unknown declared length
And the body stream yields exactly the 5 MiB sent and then ends normally
```
Test level: HTTP.

### API-10 Empty body
```gherkin
Given a valid upload request
When the body is empty (sent with <framing>)
Then the file service receives a body stream that ends immediately and normally
```
`where:` framing = `Content-Length: 0` / chunked with no data. Decided: empty uploads are accepted. Whether a second empty file is a duplicate is Domain. Test level: HTTP.

### API-11 A body cut short is never a complete upload
```gherkin
Given an upload of a 50 MiB body sent with <framing>
When the client closes the connection after 25 MiB
Then the body stream the file service reads fails with an error
And it never ends normally after 25 MiB
And no success response is produced
```
`where:` framing = `Content-Length: 52428800` / chunked. This guards against storing a truncated file as if it were complete. Test level: HTTP.

## C. Responses

### API-12 Successful upload
```gherkin
Given the file service accepts the upload and returns file F
When the request completes
Then the response is 201 Created
And Location is /files/{F.id}
And the body is F's JSON representation
```
The fields of the representation (size, SHA-256, download link, …) are defined in Domain. Test level: controller.

### API-13 Filename taken, detected before the body (client sends Expect)
```gherkin
Given the file service reports, before the body is read, that alice already has a file E named "report.pdf"
And the client sends Expect: 100-continue and waits
When alice uploads "report.pdf"
Then the response is 409 with type /problems/filename-exists
And it carries existingFile with E's id, size and SHA-256
And no 100 Continue was sent and the client sent zero body bytes
And the response carries Connection: close
And the file service never read the body
```
Test level: HTTP.

### API-14 Filename taken, detected before the body (no Expect header)
```gherkin
Given the file service reports, before the body is read, that the filename is taken
And the client does not send Expect: 100-continue but reads responses while sending
When the client uploads a body larger than 2 MiB
Then the client receives 409 with type /problems/filename-exists before it finishes sending
And the server closes the connection
```
Known limit, documented rather than tested: clients that only read after sending everything may see a connection reset instead (Tomcat drains up to 2 MiB, then closes). Test level: HTTP.

### API-15 Duplicate detected after the body was read
```gherkin
Given the file service reports, after reading the body, that alice already has a file E with the same <kind>
When the upload completes
Then the response is 409 with type <type>
And it carries existingFile with E's id, size and SHA-256
```
`where:` kind / type = filename / `/problems/filename-exists` (lost a parallel race); content / `/problems/content-exists`.

Decided: plain A, no idempotency key. A retry after a lost response gets this 409, and the client compares `existingFile` with what it sent. `Idempotency-Key` (IETF draft) is documented as the upgrade. Test level: controller.

### API-16 File service failures
```gherkin
Given the file service fails with <failure>
When the upload is processed
Then the response is <status> with type <type>
And the body contains no internal details
```
`where:`

| failure | status | type |
|---|---|---|
| storage unavailable | 503 | `/problems/temporarily-unavailable` |
| database unavailable | 503 | `/problems/temporarily-unavailable` |
| any other error | 500 | `about:blank` |

No `Retry-After`; any value would be a guess. 502/504 don't fit because RFC 9110 limits them to gateways and proxies. Known limit: on a failure mid-body Tomcat writes the response, drains up to 2 MiB, then closes, so clients that don't read while sending may see a reset instead. Test level: controller.

## D. Streaming and connection behaviour

### API-17 Valid request with Expect: 100-continue
```gherkin
Given a valid upload request for a filename the file service reports as free
And the client sends Expect: 100-continue and waits
When the request headers are sent
Then the server sends 100 Continue once the file service starts reading the body
And after the body is sent the response is 201 Created
```
Proves that `onRead` doesn't break the normal path. Test level: HTTP.

### API-18 [BRIEF] 2 GiB streamed through the HTTP layer with a small heap
```gherkin
Given the application runs with a 256 MB maximum heap
And the file service is faked to read the body to the end while counting and hashing it
And the client generates the 2 GiB body on the fly
When the body is streamed to the upload endpoint
Then the response is 201 Created
And the file service saw 2147483648 bytes with the SHA-256 the client computed
And no OutOfMemoryError occurs
And no temporary file grows with the upload
```
Decided: 256 MB target, confirmed by measuring the app's baseline once it exists. If the baseline doesn't fit, raise it but keep it far below 2 GiB.
- Proves the HTTP layer (Tomcat, Spring, controller) never buffers the body. The full path to storage is proven by [SYS-01](system.md).
- The client shares the JVM, so it must generate bytes on the fly. Outside the JVM, use `curl -T file -X POST -H 'Content-Type: application/octet-stream'`, because `curl --data-binary @file` loads the file into memory.

Test level: HTTP.

### API-19 Idle client is disconnected
```gherkin
Given the idle timeout is configured to T (a few seconds in the test)
When a client sends part of the body and then sends nothing for longer than T
Then the server closes the connection
And the body stream the file service reads fails with an error
```
Test level: HTTP.

### API-20 Slow but steady client succeeds
```gherkin
Given the idle timeout is configured to T
When a client sends the body with pauses shorter than T over a total time several times longer than T
Then the response is 201 Created
```
Proves the timeout is idle-per-read, not a limit on total time. Test level: HTTP.

## E. Concurrency

The cap is a **memory budget in MiB**, not a count of uploads (Storage decision 2). Each upload reserves its part size (STO-09) through the file service before the body is read.

### API-21 Upload capacity reached
```gherkin
Given the file service reports that the upload's part buffer does not fit in the remaining budget
And the client sends Expect: 100-continue and waits
When a valid upload arrives
Then the response is 503 with type /problems/upload-capacity-reached
And no 100 Continue was sent and the client sent zero body bytes
And no body byte was read
```
The reservation is made after the header checks (API-01 to API-05, API-24) and before the filename pre-check. Test level: HTTP (small budget in the test).

### API-22 A reservation is always given back
```gherkin
Given the budget fits exactly one upload
When an upload ends with <outcome>
Then the next valid upload is accepted
```
`where:` outcome = 201 / 409 after the body / file service failure (503 or 500) / client disconnect mid-body / idle timeout. Test level: HTTP.

### API-23 Other requests are served while uploads are at capacity
```gherkin
Given the upload budget is fully reserved by running uploads
When a non-upload request arrives (e.g. a file listing)
Then it is served normally
```
The cap is kept below Tomcat's `maxThreads`, so uploads can never take every thread. The request used in the test is `GET /files/public`. Test level: HTTP.

## F. Other endpoints

All of them: a missing or blank `X-User-Id` where an owner is required → 400 `/problems/invalid-request` (as API-01). A file that doesn't exist or isn't the caller's → 404 `/problems/file-not-found`, never 403 (DOM-39).

### API-25 Get one file
```gherkin
Given the file service returns file F for owner alice
When alice sends GET /files/{F.id}
Then the response is 200 with F's JSON representation
```
And: the file service reports "not found" → 404 `/problems/file-not-found`. This endpoint exists because the upload's 201 returns `Location: /files/{id}`. Test level: controller.

### API-26 Rename
```gherkin
Given alice owns file F
When she sends PATCH /files/{F.id} with Content-Type application/json and <body>
Then the response is <result>
```
`where:` body / result:
- `{"filename": "new.pdf"}` / 200 with the renamed file.
- `{"filename": "a/b.pdf"}` / 400 `/problems/invalid-request`.
- `{"filename": "taken.pdf"}` (the file service reports the name taken) / 409 `/problems/filename-exists` with `existingFile`.
- `{"tags": ["x"]}` or `{"filename": "x", "visibility": "PUBLIC"}` / 400 `/problems/invalid-request`, because only `filename` can change.
- `{}` / 400.
- Not JSON / 415.
- The file service reports "not found" / 404 `/problems/file-not-found`.

RFC 5789: PATCH applies "a set of changes" to an existing resource. Jackson 3 (Spring Boot 4) ignores unknown JSON properties by default, so the body's keys are checked explicitly: exactly `{"filename": string}`. Test level: controller.

### API-27 Delete
```gherkin
Given alice owns file F
When she sends DELETE /files/{F.id}
Then the response is 204 with no body
```
And: the file service reports "not found" → 404 `/problems/file-not-found`. RFC 9110: after a successful DELETE send "204 (No Content) … if the action has been enacted and no further information is to be supplied". Test level: controller.

### API-28 List my files
```gherkin
Given alice has files
When she sends GET /files with <params>
Then the response is <result>
```
`where:` params / result:
- none / 200 with `items`, `page`, `size`, `totalItems`, `totalPages`, page 0, size 20, newest first.
- `tag=Work&sort=size,desc&page=1&size=5` / 200, passed to the file service as tag "Work", sort size descending, page 1, size 5.
- `sort=storageKey,asc` / 400 `/problems/invalid-request`, because only `filename`, `uploadDate`, `tag`, `contentType` and `size` are accepted.
- `sort=size,sideways` / 400.
- `size=101` / 400.
- `page=-1` / 400.
- `tag=a,b` / 400 (comma, as API-04).
- `tag=a&tag=b` / 400 (one tag only).

Spring Data's parameter naming (`page` from 0, `size`, `sort=property,direction`), checked against an allow-list. The response is our own DTO, not Spring Data's `Page`, whose JSON "could change over time". Test level: controller.

### API-29 List public files
```gherkin
When anyone sends GET /files/public with the same parameters as API-28
Then the response has the same shape and the same validation
And no X-User-Id is needed (if sent, it is ignored)
```
Test level: controller.

### API-30 Download
```gherkin
Given the file service resolves token T to file F (name "résumé final.pdf", type application/pdf, size N)
When anyone sends GET /download/T
Then the response is 200 with the file's bytes, streamed
And Content-Type is application/pdf
And Content-Length is N
And Content-Disposition is attachment with filename*=UTF-8''r%C3%A9sum%C3%A9%20final.pdf
And X-Content-Type-Options is nosniff
```
And:
- An unknown token → 404 `/problems/file-not-found`.
- No `X-User-Id` is needed.
- A `Range` header is ignored and the full file returned with 200 (RFC 9110: "A server MAY ignore the Range header field"). Range support, i.e. resuming a download, is documented, not built.
- The name is sent once, exactly, as `filename*=UTF-8''…` (RFC 8187 percent-encoding). RFC 6266 suggests also sending a plain `filename` as an ASCII-only fallback for old clients; that is not built, so no second, altered copy of the name exists. Known cost: `curl -OJ` ignores `filename*` (checked with curl 8.7.1) and saves under the token, so the README uses `curl -o`.

Test level: controller and HTTP.

### API-31 A 2 GiB download streams with a small heap
```gherkin
Given the application runs with a 256 MB maximum heap
And the file service is faked to serve a generated 2 GiB stream
When a client downloads it and hashes it while reading
Then it receives 2147483648 bytes with the expected SHA-256
And no OutOfMemoryError occurs
```
The download path streams through a fixed-size buffer, like the upload. Test level: HTTP.

### API-32 A file that can never fit is rejected up front
```gherkin
Given the file service reports that the declared Content-Length needs a part larger than the whole upload budget
When the upload's headers arrive (with Expect: 100-continue)
Then the response is 413 with type /problems/file-too-large
And the client sent zero body bytes
```
With a 256 MiB budget, the largest part is 256 MiB, so the largest file is about 2.4 TiB (256 MiB × 10,000 parts). This is a narrow exception to "no size cap" (decision 10), caused by memory. A chunked upload that passes the unknown-length limit (64 MiB × 10,000 ≈ 625 GiB) fails mid-stream with the same type; that client may see a reset. Test level: controller.

## Configuration this layer relies on

| Setting | Why | Criteria |
|---|---|---|
| `spring.servlet.multipart.enabled=false` | Boot's multipart auto-configuration is on by default and parses multipart bodies, spooling them to disk, before our handler runs. Under `onRead` that parsing also triggers the 100 Continue, so our 415 would arrive only after the body. | API-05, API-06 |
| Tomcat connector `continueResponseTiming=onRead` | Lets the handler reject on headers before the client sends the body. No Boot property exists; set it with a connector customizer, using the typed setter `AbstractHttp11Protocol#setContinueResponseTiming("onRead")` (Tomcat 11). | API-06, API-13, API-17 |
| `spring.mvc.problemdetails.enabled=true` | Spring's own errors (e.g. 415) also come out as Problem Details. | API-05, API-16 |
| `server.tomcat.connection-timeout` = 60s (Tomcat's default, set explicitly) | Idle timeout per read, not total. | API-19, API-20 |
| `server.tomcat.max-swallow-size` = 2MB (default) | Bounds what Tomcat drains after an early rejection without `Expect`. | API-14, API-16 |
| Handler takes `HttpServletRequest`, never `@RequestBody`; params via `getParameterValues`; no body-caching filter (e.g. `ContentCachingRequestWrapper`) on `/files` | Anything that reads the body before our checks would trigger the 100 Continue early or buffer the body (`@RequestBody` peeks one byte). | API-06, API-13, API-18 |
| `uploads.buffer-budget` (our property, MiB; starting point 256, confirmed by measurement) | The upload cap: a `Semaphore` whose permits are MiB of part buffers. Size = measured heap − app baseline − margin. The storage client pool must allow at least as many connections as uploads the budget admits (decision 3 in Storage). | API-21 to API-23, API-32 |

## Decisions (all closed)

1. **Retry safety:** plain A, no idempotency key (API-15).
   - Both duplicate keys include the user id, and a retry always has the same user, filename and content, so it can't create a second file (Domain).
   - The 409 carries `existingFile` so the client can recognise its own upload.
2. **Visibility:** required, exact values `PUBLIC` / `PRIVATE` (API-03). The brief says "provide a visibility setting" and has no endpoint to change it, so a wrong default could only be fixed by deleting and re-uploading.
3. **Tags:** repeated parameter, read raw, any value containing a comma is rejected (API-04). Use the same syntax for the tag filter on the list endpoints.
4. **Empty body:** accepted (API-10). Domain decides that a user's second empty file is a content duplicate. Storage verifies how a 0-byte object is written.
5. **Failure status:** 503 when storage or Mongo is unreachable, 500 for everything else, no `Retry-After` (API-16).
6. **Error format:** RFC 9457 Problem Details (see [README](README.md)). Problem types used by the upload endpoint:

   | Status | `type` | Extra fields | Criteria |
   |---|---|---|---|
   | 400 | `/problems/invalid-request` (one type for every endpoint) | `detail` names the field | API-01 to API-04, API-26, API-28, API-29 |
   | 404 | `/problems/file-not-found` | none | API-25 to API-27, API-30 |
   | 409 | `/problems/filename-exists` | `existingFile` {id, size, sha256} | API-13, API-14, API-15 |
   | 409 | `/problems/content-exists` | `existingFile` {id, size, sha256} | API-15 |
   | 415 | `about:blank` | none | API-05 |
   | 415 | `/problems/unsupported-content-encoding` | `Accept-Encoding: identity` header | API-24 |
   | 503 | `/problems/temporarily-unavailable` | none | API-16 |
   | 503 | `/problems/upload-capacity-reached` | none | API-21 |
   | 413 | `/problems/file-too-large` | none | API-32 |
   | 500 | `about:blank` | none | API-16 |

7. **Heap for the 2 GiB tests:** 256 MB target for the HTTP-level test (API-18), confirmed by measurement. The built image with the brief's limits is tested in [SYS-01](system.md), with the production heap set explicitly in the image.
8. **User header:** `X-User-Id`. RFC 6648 says new names "SHOULD NOT" use `X-` because experimental names leak into standards and force a migration; that doesn't apply to an app-private header that will never be standardized.
9. **Concurrency:** our own cap on concurrent uploads (option 3), a `Semaphore` (API-21 to API-23). **Updated by Storage decision 2: the permits are MiB of part buffers, not uploads.** An upload reserves its part size (8 MiB to ~96 MiB, 64 MiB when the length is unknown), so memory stays bounded whatever mix of files arrives; a file whose part could never fit gets 413 (API-32).
   - Without it, Tomcat queues requests beyond its 200 threads silently (up to `maxConnections` 8,192, then the OS queue `acceptCount` 100). Uploads past the storage-connection or memory limit fail halfway or crash the app.
   - A slot is taken after the header checks and before the filename check, with no waiting. If none is free: 503 `/problems/upload-capacity-reached`.
     - 503, not 429: RFC 6585 defines 429 as one user sending "too many requests in a given amount of time".
     - No `Retry-After`.
   - A slot is given back in `finally` after every outcome. Only uploads take slots.
   - N is per app instance, which matches the per-instance resources it protects.
   - A per-user cap and virtual threads are documented upgrades, not built.
10. **Maximum file size:** no cap, as the brief says ("without limit on the file size"). Exception from memory, not policy: a known length above ~2.4 TiB, or a chunked upload above ~625 GiB, can't be stored with bounded buffers (API-32).
    - Tomcat sets no default limit on a raw body, so only timeouts and storage capacity stop a runaway upload.
    - Documented upgrade, not built: a configurable cap, off by default. When set, `413 Content Too Large` before the body if `Content-Length` exceeds it, or as soon as the running byte count passes it for a chunked body (that client may see a reset).
11. **Slow clients:** documented, not built.
    - Tomcat's timeout is idle-per-read with no minimum transfer rate, so a client sending a byte every 59 s keeps its upload going indefinitely.
    - With the upload cap (decision 9), only N such clients are needed to block all uploads; other endpoints stay available.
    - Fix if needed: a minimum-rate check in the copy loop after a grace period (risk: cutting off real slow links; the client most likely sees a reset rather than a 408), or rate enforcement in a proxy in front of the app.
12. **Compressed request bodies:** rejected with 415 `/problems/unsupported-content-encoding` + `Accept-Encoding: identity`, before the body (API-24).
13. **Other endpoints** (API-25 to API-31):
    - `GET /files/{id}` exists because the 201's `Location` points to it.
    - Rename is `PATCH /files/{id}` with `{"filename"}` only.
    - Separate `/files/public` path.
    - Spring Data parameter names with an allow-list, and our own page DTO.
    - One `/problems/invalid-request` type for every endpoint, renamed from the upload-only one.
    - Download sends `attachment` with the exact name in `filename*` (no ASCII fallback), plus `nosniff`. `Range` is ignored (not built).
