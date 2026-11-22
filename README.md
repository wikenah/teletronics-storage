# STORAGE

A REST service for storing and sharing files. Built for the Teletronics backend test project.

- Users upload files of any size, from a few KB to hundreds of GB, as PUBLIC or PRIVATE, with up to 5 tags.
- They can rename, list, filter, sort and delete their own files, and list everyone's public files.
- Every file gets an unguessable download link that works for anyone holding it.
- Duplicates are refused per user, by filename (case-insensitive) or by content (SHA-256).

Stack: Java 21, Spring Boot 4.1.3, MongoDB 8.0 for metadata, SeaweedFS (S3-compatible) for the bytes, Apache Tika for type detection, Spock for tests.

## Run it

Needs Java 21 and Docker.

```sh
./gradlew bootJar
docker compose up --build --wait     # app on http://localhost:8080, Mongo, SeaweedFS
```

The app container runs with the brief's limits: 1 GB of memory, with swap equal to memory, so it really is 1 GB.

### Try it with curl

```sh
# Upload: the raw file is the request body. curl -T streams the file from disk.
curl -T report.pdf -X POST -H 'Content-Type: application/pdf' -H 'X-User-Id: alice' \
  'http://localhost:8080/files?filename=report.pdf&visibility=PUBLIC&tags=work&tags=2026'

# My files, filtered and sorted; everyone's public files
curl -H 'X-User-Id: alice' 'http://localhost:8080/files?tag=work&sort=size,desc&page=0&size=20'
curl 'http://localhost:8080/files/public?sort=filename,asc'

# Rename, download by link, delete
curl -X PATCH -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -d '{"filename": "final.pdf"}' http://localhost:8080/files/{id}
curl -OJ http://localhost:8080/download/{token}
curl -X DELETE -H 'X-User-Id: alice' http://localhost:8080/files/{id}
```

Use `curl -T` for large files, not `curl --data-binary @file`: `--data-binary` reads the whole file into memory first. For files over 1 MB curl sends `Expect: 100-continue`, so a rejected upload (name taken, bad parameters) is answered before any of the body is sent.

## API

The brief has no user management, so the caller's id comes in the `X-User-Id` header. That is an identification, not authentication.

| Endpoint | Who | Success |
|---|---|---|
| `POST /files?filename=…&visibility=PUBLIC\|PRIVATE&tags=…` | Owner (`X-User-Id`) | 201 + file, `Location: /files/{id}` |
| `GET /files/{id}` | Owner | 200 + file |
| `PATCH /files/{id}` with `{"filename": "…"}` | Owner | 200 + file |
| `DELETE /files/{id}` | Owner | 204 |
| `GET /files?tag=…&sort=…&page=…&size=…` | Owner | 200 + page |
| `GET /files/public?tag=…&sort=…&page=…&size=…` | Anyone | 200 + page |
| `GET /download/{token}` | Anyone with the link | 200 + bytes |

**Upload**
- The body is the raw file. Form uploads (`multipart/form-data`, `application/x-www-form-urlencoded`) and compressed bodies are refused with 415.
- `visibility` is required: exactly `PUBLIC` or `PRIVATE`.
- `tags` are given as a repeated parameter, at most 5. They are stored lowercase and trimmed.
- If `Content-Type` is missing or `application/octet-stream`, the type is detected from the content.
- There is no size limit. The only exception is a file whose parts couldn't fit in the upload memory budget, which is above about 2.4 TiB.

**Listing**
- `sort` is one of `filename`, `uploadDate` (default, newest first), `tag`, `contentType`, `size`, followed by `,asc` or `,desc`.
- `page` starts at 0. `size` defaults to 20, maximum 100.
- `tag` filters by one exact tag, ignoring case.

**Files and links**
- A file is shown as `id`, `filename`, `visibility`, `tags`, `size`, `sha256`, `contentType`, `uploadedAt` and `downloadLink`. The owner's id is never shown.
- Another user's file is reported as 404, never 403, so nobody can probe which ids exist.
- The download link carries a 128-bit random token. It works for PUBLIC and PRIVATE files and survives a rename. Downloads are sent as attachments with `X-Content-Type-Options: nosniff`.

### Errors

Every error is RFC 9457 Problem Details (`application/problem+json`).

| Status | `type` | When |
|---|---|---|
| 400 | `/problems/invalid-request` (+ `field`) | Missing `X-User-Id`, invalid filename, visibility, tags, sort or paging |
| 404 | `/problems/file-not-found` | No such file, or not the caller's |
| 409 | `/problems/filename-exists` (+ `existingFile`) | The user already has a file with this name (case-insensitive) |
| 409 | `/problems/content-exists` (+ `existingFile`) | The user already has a file with these bytes |
| 413 | `/problems/file-too-large` | The file could never fit the upload memory budget |
| 415 | `/problems/unsupported-content-encoding` | A compressed request body (with `Accept-Encoding: identity`) |
| 415 | `about:blank` | A form upload |
| 503 | `/problems/upload-capacity-reached` | The upload memory budget is in use; try again later |
| 503 | `/problems/temporarily-unavailable` | Mongo or the object store is unreachable |
| 500 | `about:blank` | Anything else; no internal details are exposed |

`existingFile` holds the existing file's `id`, `size` and `sha256`. A client retrying after a lost response can use it to recognise its own earlier upload.

## How it works

**Uploads stream end to end.** The handler reads the raw request, never `@RequestBody`. Tomcat sends `100 Continue` only when the app first reads the body (`continueResponseTiming=onRead`). Before that point, every check that needs no body runs:
1. parameters;
2. the memory budget;
3. whether the name is taken.

The body then passes through a SHA-256 digest into the object store in parts. One part is buffered in memory, so a failed part can be retried. Parts are 8 MiB, or length ÷ 10,000 for very large files, or 64 MiB when the length is unknown.

**Duplicates are decided by unique indexes.** The bytes are stored first and the record inserted after. Mongo's unique indexes on `(owner, lowercase filename)` and `(owner, sha256)` reject the second of two parallel uploads, and its stored bytes are deleted. There is no lock and no reservation.

**Memory is budgeted.** Upload part buffers share a 256 MiB budget, counted in MiB. An upload that doesn't fit is refused with 503 straight away instead of waiting on a Tomcat thread. The heap is 55% of the container's memory.

**Code layout**: DDD layers under `com.teletronics.storage`:

| Layer | Contents |
|---|---|
| `presentation` | Controllers, request parsing, Problem Details, Tomcat setup |
| `application` | `FileService` (the use cases), the upload budget, the ports `BlobStore` and `ContentTypeDetector` |
| `domain` | The `StoredFile` aggregate, value objects (filename, tags, token, …), the `FileRepository` port |
| `infrastructure` | Mongo repository, S3 blob store, Tika detector, wiring |

`domain` depends on nothing. Switching the object store (RustFS, AWS S3) is configuration only (`storage.s3.*`).

## Tests

Spock specifications. Each feature method is named after its acceptance criterion (`DOM-11 …`), with the criterion's Given/When/Then sentences as block labels.

| Suite | What | Run |
|---|---|---|
| `test` | Domain, file service with in-memory fakes, controllers (`@WebMvcTest`), S3 write loop | `./gradlew test` |
| `httpTest` | The web layer on a real Tomcat with a 256 MB heap and a raw socket client | `./gradlew httpTest` |
| `integrationTest` | Mongo 8.0 and SeaweedFS in Testcontainers; the whole app over HTTP | `./gradlew integrationTest` (needs Docker) |
| `containerTest` | The built Docker image under the brief's limits | see below |

`./gradlew build` runs the first three. The container test needs the image:

```sh
./gradlew bootJar && docker build -t teletronics-storage:local . && ./gradlew containerTest
```

The tests the brief asks for:

| Brief | Spec |
|---|---|
| Parallel upload, same filename | `DOM-11 [BRIEF]` in `FileServiceMongoSpec` and, over HTTP, in `StorageApplicationSpec` |
| Parallel upload, same content | `DOM-12 [BRIEF]` in the same two specs |
| Upload of a file of at least 2 GB | `API-18 [BRIEF]` in `HttpUploadSpec` (256 MB heap), `SYS-01 [BRIEF]` in `LargeUploadSpec` (the image, 1 GB container) |
| Delete a file that doesn't belong to the user | `DOM-39 [BRIEF]` in `FileServiceFilesSpec`, `FileServiceMongoSpec`, `StorageApplicationSpec` |
| List all public files | `DOM-43 [BRIEF]` in the same three specs |

The parallel tests are deterministic. Both uploads are held past the name check before either inserts: a barrier in the service-level spec, `Expect: 100-continue` in the HTTP one. The unique index decides the race every run.

## The container limits

- **1 GB memory:** set in `docker-compose.yml` and in the container test, with swap equal to memory. Docker otherwise allows the same amount again as swap. In SYS-01, a 2 GiB upload peaked at about 333 MB of container memory.
- **200 MB disk "for APPLICATION":** read as the disk the running application may write, not the image size. The `eclipse-temurin:21-jre` base image alone is larger than 200 MB, so no Java image could meet the other reading.
  - Nothing is spooled to disk: uploads go from the socket to the object store through memory.
  - SYS-01 measures the container's writable layer before and after the 2 GiB upload: it grew by 0 bytes.
  - A hard disk quota (`--storage-opt size`) isn't used: it is ignored on Docker Desktop and needs xfs with project quotas on Linux.

## CI

`.github/workflows/ci.yml` runs on every push and pull request:
1. `./gradlew build`: unit, HTTP and integration tests.
2. `docker build`: the Docker image.
3. `./gradlew containerTest` against that image.

The image is built, not pushed.

## Documentation

| Document | Contents |
|---|---|
| [`docs/decisions.md`](docs/decisions.md) | Every design decision, with the reason |
| [`docs/acceptance-criteria/`](docs/acceptance-criteria/README.md) | The acceptance criteria per layer (HTTP API, domain, storage, system) |
| [`docs/design-notes.md`](docs/design-notes.md) | Research behind the decisions |
