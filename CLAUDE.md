# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

STORAGE: a REST file storage and sharing API, built as an interview test project. Java 21, Spring Boot 4.1.3 (Spring 7, Tomcat 11, Jackson 3), MongoDB 8.0 for metadata, SeaweedFS (S3-compatible) for the bytes, Apache Tika for type detection, Spock 2.5 (Groovy 5) for tests. The README describes the API, the error table and how to run it.

Boot 4 moved many classes: check an existing import before assuming a Boot 3 package name (e.g. `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.boot.mongodb.autoconfigure.*`, `org.testcontainers.mongodb.MongoDBContainer`).

## Commands

Use the Gradle wrapper (9.9.2); a system Gradle below 8.14 can't build Boot 4.1.

```sh
./gradlew build                 # compile + test, httpTest, integrationTest (integrationTest needs Docker)
./gradlew test                  # unit, service-with-fakes, @WebMvcTest, S3 write loop
./gradlew httpTest              # web layer on a real Tomcat, 256 MB heap, raw socket client
./gradlew integrationTest       # Mongo + SeaweedFS in Testcontainers, whole app over HTTP

# One spec / one feature (the task must match the suite the spec lives in)
./gradlew test --tests '*FilesControllerSpec'
./gradlew test --tests '*FilesControllerSpec.API-04*'
./gradlew integrationTest --tests '*StorageApplicationSpec'

# Container tests run against a built image and are NOT part of `build`
./gradlew bootJar && docker build -t teletronics-storage:local . && ./gradlew containerTest

# Run the stack locally (app on :8080, 1 GB memory limit)
./gradlew bootJar && docker compose up --build --wait
docker compose down -v
```

There is no linter or formatter configured. CI (`.github/workflows/ci.yml`) runs `./gradlew build`, `docker build`, then `./gradlew containerTest -PappImage=teletronics-storage:ci`.

## Architecture

DDD layers under `com.teletronics.storage`. The dependency rule is a convention, not enforced by a tool: `domain` depends on nothing (no Spring, no Mongo); `application` depends on `domain`; `presentation` and `infrastructure` depend on both.

- `domain/file`: the `StoredFile` aggregate and its `FileRepository` port at the top; `valueobject/` (self-validating: `Filename`, `Tags`, `OwnerId`, `FileId`, `DownloadToken`, …), `query/` (`FileQuery`, `SortField`, `PageResult`), `exception/`. Invalid input throws `InvalidFileException(field, message)`.
- `application`: `FileService` (all use cases); `download/` (`Download`: a file's metadata plus its byte stream); `upload/` (the upload command, `AdmittedUpload` (which carries out an upload once it is admitted), the memory budget, the hashing stream); `port/` (the outbound interfaces `BlobStore`, `ContentTypeDetector`); `exception/`. `FileService` is a `@Service` and `UploadBudget` a `@Component` (its size comes from `uploads.buffer-budget-mib`); nothing else in this layer uses Spring.
- `presentation/http`: `FilesController`, request parsers (`request/`), response DTOs (`response/`), `ProblemDetailsAdvice` mapping every exception to RFC 9457 Problem Details (`problem/`), the HTTP layer's own exceptions (`exception/`).
- `infrastructure`: `persistence/mongo` (MongoOperations, not Spring Data repositories), `storage/s3` (AWS SDK v2 with the apache5 client), `detection` (Tika).

### The upload path (the part that spans every layer)

The design goal is uploads of any size inside a 1 GB container, with rejections sent before the client transmits the body.

1. `FilesController.upload` takes the raw `HttpServletRequest`, never `@RequestBody` or multipart. Nothing may read the body before all checks pass.
2. `TomcatContinueTimingCustomizer` sets `continueResponseTiming=onRead`, so `100 Continue` is sent only when the app first reads the body. Changing this breaks the Expect-dependent specs in `HttpUploadSpec`.
3. `UploadRequestValidator` rejects what only HTTP knows (form bodies, compressed bodies) from the headers alone, and must run first; `UploadCommandMapper` then builds an `UploadCommand` of value objects (a missing `X-User-Id` is refused there). Input is validated once, by the value's own type; don't re-validate in the service.
4. `FileService.startUpload` checks, in order: can the part size ever fit the budget (413), is budget available now (503, never waits), is the filename taken (409). It returns a `PendingUpload` holding the budget reservation; the controller must close it.
5. `PendingUpload.complete(stream)` pipes the body through `DigestingInputStream` (SHA-256, byte count, first 64 KiB for Tika) into `BlobStore.store`. `S3BlobStore` buffers exactly one part in memory (8 MiB, or length ÷ 10,000, or 64 MiB for unknown length) and uses a single PutObject when the body fits one part.
6. Bytes first, record after. Duplicates are decided by Mongo's unique indexes on `(owner, filenameKey)` and `(owner, sha256)` at insert; the loser's bytes are deleted. There is no lock or reservation, by design.

### Conventions that are easy to break

- Another user's file is always 404, never 403. An unparseable id or token is also 404.
- Problem `type` values are in `ProblemTypes`. Spring 7 omits `type` when it is `about:blank`, so tests default it.
- The download link is built in the presentation layer (`FileResponse.DOWNLOAD_PATH` + token); the domain doesn't know URLs or header names.
- `uploadedAt` is truncated to milliseconds so every store returns the same value.
- The 200 MB disk limit is met by never spooling to disk: don't introduce temp files or multipart parsing on the upload or download path.

## Tests

Four JVM test suites, each its own source set: `src/test`, `src/httpTest`, `src/integrationTest`, `src/containerTest`. Shared fakes and HTTP clients live in `src/testFixtures` (`InMemoryFileRepository`, `InMemoryBlobStore`, `StoredFiles`, `TestBytes`, `StorageApiClient`, `RawHttpClient`); use them rather than adding per-spec helpers. Each suite declares its own dependencies in `build.gradle`, so a class that resolves in one suite may need a dependency added in another.

Specs are Spock, named after acceptance-criterion ids: one feature method per criterion (`"DOM-11 [BRIEF] …"`, `"API-04 …"`), with the criterion's Given/When/Then sentences as the block labels. The criteria are in `docs/acceptance-criteria/` (`http-api.md` API-xx, `domain.md` DOM-xx, `storage.md` STO-xx, `system.md` SYS-xx). When behaviour changes, update the criterion and the spec together.

`BriefScenariosSpec` (containerTest) walks the original brief through the API against the shipped image; `LargeUploadSpec` is the 2 GiB upload under the 1 GB limit. Both share one `ApplicationStack`.

Spock gotchas met here: `where:` block closures can't read instance fields (pass them as parameters), and Groovy closures don't coerce to `LongUnaryOperator` in the Java fakes (use the fixture helper methods).

## Docs

`docs/decisions.md` and `docs/design-notes.md` (the decision log and the research behind it) are kept locally and git-ignored; code comments such as "Domain decision 5" refer to that log, and the same decisions are restated in the "Decisions" sections of `docs/acceptance-criteria/`.
