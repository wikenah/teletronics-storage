# STORAGE

A REST API for storing and sharing files.

- Upload a file of any size as PUBLIC or PRIVATE, with up to 5 tags.
- Rename, list, filter by tag, sort, page and delete your own files; list everyone's PUBLIC files.
- Every file gets an unguessable download link.
- Duplicates are refused per user, by filename or by content.

Java 21, Spring Boot 4.1, MongoDB (metadata), SeaweedFS (S3-compatible, file bytes), Apache Tika (type detection), Spock (tests).

## Run

Needs Java 21 and Docker.

```sh
./gradlew bootJar
docker compose up --build --wait     # http://localhost:8080
```

The app container is limited to 1 GB of memory, as the brief requires.

## API

There is no user management: the caller's id is sent in the `X-User-Id` header.

| Endpoint | Caller | Success |
|---|---|---|
| `POST /files?filename=…&visibility=PUBLIC\|PRIVATE&tags=…` | Owner | 201, file, `Location` |
| `GET /files/{id}` | Owner | 200, file |
| `PATCH /files/{id}` with `{"filename": "…"}` | Owner | 200, file |
| `DELETE /files/{id}` | Owner | 204 |
| `GET /files?tag=…&sort=…&page=…&size=…` | Owner | 200, page of own files |
| `GET /files/public?tag=…&sort=…&page=…&size=…` | Anyone | 200, page of PUBLIC files |
| `GET /download/{token}` | Anyone with the link | 200, file bytes |

- **Upload:** the request body is the raw file, not a form. `tags` is a repeated parameter. If `Content-Type` is missing or `application/octet-stream`, the type is detected from the content.
- **Listing:** `sort` is `filename`, `uploadDate` (default, newest first), `tag`, `contentType` or `size`, plus `,asc` or `,desc`. `page` starts at 0; `size` defaults to 20, maximum 100. `tag` matches one whole tag, ignoring case.
- **File:** `id`, `filename`, `visibility`, `tags`, `size`, `sha256`, `contentType`, `uploadedAt`, `downloadLink`.
- **Download link:** `/download/{token}` with a 128-bit random token. It works for PUBLIC and PRIVATE files and survives a rename.

Errors are RFC 9457 Problem Details (`application/problem+json`):

| Status | `type` | When |
|---|---|---|
| 400 | `/problems/invalid-request` | Missing `X-User-Id`; invalid filename, visibility, tags, sort or paging |
| 404 | `/problems/file-not-found` | No such file, or it belongs to another user |
| 409 | `/problems/filename-exists` | The user already has a file with this name (case-insensitive) |
| 409 | `/problems/content-exists` | The user already has a file with these bytes |
| 413 | `/problems/file-too-large` | The file can't be stored within the memory budget (above about 2.4 TiB) |
| 415 | `/problems/unsupported-content-encoding`, `about:blank` | Compressed body; form upload |
| 503 | `/problems/upload-capacity-reached` | Too many uploads in progress; retry later |
| 503 | `/problems/temporarily-unavailable` | MongoDB or the object store is unreachable |

## Usage

```sh
# Upload (-T streams the file from disk)
curl -T report.pdf -X POST -H 'X-User-Id: alice' -H 'Content-Type: application/pdf' \
  'http://localhost:8080/files?filename=report.pdf&visibility=PUBLIC&tags=work&tags=2026'

# List my files; list public files
curl -H 'X-User-Id: alice' 'http://localhost:8080/files?tag=work&sort=size,desc&page=0&size=20'
curl 'http://localhost:8080/files/public?sort=filename,asc'

# Rename
curl -X PATCH -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -d '{"filename": "final.pdf"}' http://localhost:8080/files/{id}

# Download by link
curl -o final.pdf http://localhost:8080/download/{token}

# Delete
curl -X DELETE -H 'X-User-Id: alice' http://localhost:8080/files/{id}
```

## Design

- **Streaming:** an upload goes from the socket to the object store in parts, with one part (8 MiB) held in memory. Nothing is written to the app's disk.
- **Early rejection:** parameters, capacity and the filename are checked before the body is read, so a client sending `Expect: 100-continue` (curl does, above 1 MB) is refused before it uploads anything.
- **Duplicates:** decided by unique MongoDB indexes on `(owner, filename)` and `(owner, sha256)`, so parallel uploads can't both succeed.
- **Memory:** upload buffers share a 256 MiB budget; an upload that doesn't fit gets 503 instead of queueing.
- **Disk limit:** "200MB for APPLICATION" is read as what the running app may write, not the image size.
- **Code:** DDD layers under `com.teletronics.storage`: `presentation`, `application`, `domain`, `infrastructure`.

## Tests

| Suite | Scope | Command |
|---|---|---|
| `test` | Domain, service, controllers | `./gradlew test` |
| `httpTest` | Web layer on a real Tomcat with a 256 MB heap | `./gradlew httpTest` |
| `integrationTest` | Whole app with MongoDB and SeaweedFS (Testcontainers) | `./gradlew integrationTest` |
| `containerTest` | The Docker image under the 1 GB limit | below |

```sh
./gradlew build                                                                        # first three suites
./gradlew bootJar && docker build -t teletronics-storage:local . && ./gradlew containerTest
```

The tests the brief asks for:

| Brief | Spec |
|---|---|
| Parallel upload, same filename | `[Checklist 1.1]` in `BriefScenariosSpec`; `DOM-11` in `StorageApplicationSpec` |
| Parallel upload, same content | `[Checklist 1.2]` in `BriefScenariosSpec`; `DOM-12` in `StorageApplicationSpec` |
| Upload of at least 2 GB | `SYS-01` in `LargeUploadSpec` (peak memory about 333 MB, 0 bytes written to disk) |
| Delete a file of another user | `[Checklist 1.4]` in `BriefScenariosSpec`; `DOM-39` in `FileServiceFilesSpec` |
| List all public files | `[Checklist 1.5]` in `BriefScenariosSpec`; `DOM-43` in `FileServiceFilesSpec` |

`BriefScenariosSpec` runs every requirement of the brief through the API against the Docker image.

## CI

GitHub Actions (`.github/workflows/ci.yml`) on every push and pull request: build and test, build the Docker image, run the container tests against it.

## More

- [`docs/acceptance-criteria/`](docs/acceptance-criteria/README.md): the acceptance criteria the specs are named after
