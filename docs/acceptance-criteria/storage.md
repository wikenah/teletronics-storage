# Storage (outbound adapters): acceptance criteria

Conventions, test levels and the error format: see [README](README.md).

Status: **all 8 decisions closed** (STO-01 to STO-16).

Scope: where the bytes live and how they are written, read and cleaned up (the `BlobStore` port and its adapter), plus how the metadata is persisted in Mongo (collections, indexes, write concern). The core only sees the port.

## Decisions (in order)

1. Where the bytes live: GridFS in our Mongo, or an S3-compatible server (and which one). **Closed: S3-compatible, SeaweedFS** (STO-01, STO-02).
2. How bytes are written: parts, part size, memory per upload, unknown length, retries. **Closed** (STO-04 to STO-10).
3. Connection pools and timeouts (storage client and Mongo). **Closed** (STO-11, STO-12).
4. Writing and reading a 0-byte object. **Closed** (STO-03).
5. Orphan cleanup: aborted writes, crash leftovers, race losers, failed deletes ("bytes without a record are garbage", decision 5 in Domain). **Closed: documented, not built.**
6. Mongo: collections, indexes (uniqueness, download token, listings, sweeper), write concern, explicit index creation. **Closed** (STO-13).
7. The download path: streaming from the store. **Closed** (STO-14 to STO-16).
8. Small-file tier: build or document only. **Closed: documented, not built.**

## Where the bytes live (decision 1)

### STO-01 The S3 adapter stores, reads and deletes bytes on SeaweedFS
```gherkin
Given a SeaweedFS container with an empty bucket
When the adapter writes a stream of <size> under a new random key
Then reading the key back returns identical bytes (same SHA-256)
And after deleting the key, reading it reports "not found"
```
`where:` size = 1 KiB / 20 MiB / 0 bytes. Larger sizes and parts are covered under decision 2. Test level: integration.

### STO-02 The store is chosen by configuration only
```gherkin
Given the application configuration names an S3 endpoint, bucket, region and credentials
When the application starts
Then the S3 adapter uses exactly those values
And no code outside the adapter refers to SeaweedFS, S3 or the AWS SDK
```
The second line is a design rule: the core depends only on the `BlobStore` port. Test level: integration (configuration) and code review.

Decided:
- **Bytes in an S3-compatible object store, behind the `BlobStore` port; metadata stays in Mongo.** Separate systems for metadata and bytes, as in Dropbox, Facebook and LinkedIn. Mongo keeps only kilobytes per file; moving to AWS S3, Cloudflare R2 or another provider is configuration.
- **SeaweedFS** for docker compose and tests: maintained (release 4.54, 16 Nov 2026), Apache-2.0, one command (`server -s3`), and its wiki lists multipart create, upload part, complete, abort and list.
- Not MinIO: the community repo is archived and no images or binaries have been published since Oct 2025.
- Not Garage: a reported failure of empty uploads with multipart (issue #759) collides with accepting 0-byte files.
- Adapters:
  - `S3BlobStore`, implemented (AWS SDK v2), configured for SeaweedFS.
  - **RustFS, documented and not built** (Apache-2.0, 1.0.6 on 15 Nov 2026). It speaks the S3 API, so it would run on the same `S3BlobStore` with different configuration; no new adapter code.
  - **GridFS, documented and not built.** A genuinely different adapter behind the same port: no extra container, but the bytes would live in the database.
- A spike when building checks SeaweedFS for: multipart upload and abort, a 0-byte object, a large object, and automatic cleanup of incomplete uploads. If any fails, switch the configuration to RustFS.
- **Spike result (build, SeaweedFS 4.54, `S3BlobStoreSeaweedSpec`):** multipart create, upload part and complete work, including a body of exactly one part. Abort leaves no object and no open upload. 0-byte objects read back empty. The AWS SDK's default request checksums are accepted, so the client keeps SDK defaults. A large object is covered by SYS-01. Automatic cleanup of incomplete uploads was not checked (the sweeper is documented only).

## How bytes are written (decision 2)

### STO-04 A body that fits in the first buffer is written with one PutObject
```gherkin
Given the part size for an upload is P
When the body ends before P bytes have arrived
Then exactly one PutObject is sent and no multipart call is made
And the stored bytes equal the body
```
Test level: service (fake S3 client records calls).

### STO-05 Part size follows the declared length
```gherkin
Given an upload declares Content-Length <length>
When the adapter writes it
Then every part except the last is <part> and at most 10,000 parts are used
```
`where:` length / part = 2 GiB / 8 MiB; 100 GB / ~10 MiB (length ÷ 10,000, rounded up to whole MiB); 1 TB / ~96 MiB; unknown (chunked) / 64 MiB. Test level: service (part-size function) and integration (a multi-part write on SeaweedFS reads back identical).

### STO-06 Parts are sent one at a time from a single buffer
```gherkin
Given a 50 MiB body with part size 8 MiB
When the adapter writes it
Then 7 UploadPart calls are made in order, never two at once
And at most one part buffer exists for this upload at any moment
```
Test level: service.

### STO-07 A failed part is retried from the buffer without a copy
```gherkin
Given the store fails the first attempt of part 3 with a retryable error
When the adapter writes the body
Then part 3 is sent again from the same buffer and the upload completes
And the stored bytes equal the body
```
Each part is sent with `RequestBody.fromContentProvider(...)`, which opens a fresh stream over our buffer for each attempt. `RequestBody.fromBytes` and the `ByteBuffer` variants copy the data (AWS SDK v2 source), which would double the memory. Test level: service (fake S3 client) and integration where the store can be made to fail.

### STO-08 When retries run out, the multipart upload is aborted
```gherkin
Given the store fails part 3 on every attempt
When the adapter writes the body
Then AbortMultipartUpload is called
And no object exists under the key
And the failure is reported as storage unavailable or internal (API-16)
```
Test level: service and integration.

### STO-09 Part buffers never exceed the memory budget
```gherkin
Given the upload buffer budget is B MiB
When uploads with part sizes <parts> ask to start at the same time
Then those whose part fits in what is left of B start, the rest are refused as capacity reached
And the total reserved never exceeds B
And every reservation is returned when its upload ends, whatever the outcome
```
`where:` B / parts = 256 / 8, 8, 96, 96, 64 (the last 64 is refused); 16 / 8, 8, 8 (the third is refused). Test level: service.

### STO-10 A part that could never fit is refused as file too large
```gherkin
Given the upload buffer budget is 256 MiB
When an upload declares a length that needs parts larger than 256 MiB
Then it is refused as file too large before any byte is read
```
And a chunked upload that reaches part 10,001 at 64 MiB parts is aborted and refused the same way. Test level: service.

Decided:
- **Buffer one part in memory and send parts one at a time.** Every library and server checked buffers parts so a failed one can be retried (AWS CRT, Azure, Google Cloud Storage, Hadoop S3A, rclone, the CNCF registry, GitLab, Nextcloud, tusd, Uppy). Microsoft: "individual transfers are either completed or lost". Streaming without a buffer gives up retries: the AWS SDK rewinds only 128 KiB, and Hadoop hit this in HADOOP-19221. Disk buffering doesn't fit 200 MB.
- **Part size:**
  - Known length: the larger of 8 MiB and length ÷ 10,000, rounded up to whole MiB. AWS's client, rclone and CRT also scale only when the length is known.
  - Unknown length: 64 MiB (Hadoop's default), so up to ~625 GiB.
- **A single `PutObject` when the body fits the first buffer**, as AWS's client (`thresholdInBytes`), Azure (`maxSingleUploadSize`) and Nextcloud (`putSizeLimit`) do.
- **The plain `S3Client` with our own loop:**
  - Not AWS's multipart client: its default sub-bodies aren't retryable without the newer buffered wrapper.
  - Not the CRT client: its native memory pool defaults to 2 GiB.
  - Not Spring Cloud AWS: its fixed 5 MiB parts cap files at ~48.8 GiB.
- **A memory budget** (`Semaphore` permits = MiB) shared by all uploads, like rclone's `--max-buffer-memory` and the CRT memory limit. The difference: we refuse at once (503) instead of making the request wait, since a waiting request holds a Tomcat thread.
- **A file that could never fit gets 413.** With 256 MiB that is files above ~2.4 TiB.
- **Known limits:**
  - Under constant load, large uploads can be starved by small ones. `tryAcquire` "barges" and ignores fairness, per the JDK source.
  - Growing part sizes within one upload was not used: no library does it, and Cloudflare R2 requires equal part sizes.

## Small-file tier (decision 8): documented, not built

- **The concern:** a tiny file costs a full round trip to the object store. AWS's own figure for small objects on S3 is roughly 100–200 ms.
- **SeaweedFS already solves it inside the store.** Its README says "The blob store started from Facebook's Haystack", that "Small files are packed into append-only volume files, so there is no per-file inode, no per-file metadata file", and that it "optimizes for small files, ensuring O(1) disk seek operation". That is where the industry research placed small-file handling (Haystack at Facebook, Ambry at LinkedIn).
- **On AWS S3, the options would be:** S3 Express One Zone (configuration only), or a routing adapter behind the same `BlobStore` port that sends small files to a faster store, chosen by `Content-Length`, with unknown sizes going to the object store.

## Download path (decision 7)

### STO-14 A download streams from the store through a fixed buffer
```gherkin
Given a stored file of 50 MiB
When it is downloaded by its link
Then the client receives bytes identical to the stored ones
And the app copies them through a fixed-size buffer, never holding the whole file
```
The 2 GiB small-heap check is API-31. Test level: integration.

### STO-15 The store failing mid-download cuts the connection
```gherkin
Given a download has started (200 and headers already sent)
When the store fails partway through
Then the connection is closed
And the client has received fewer bytes than Content-Length announced
And the failure is logged
```
Test level: service (fake store that fails mid-stream) and HTTP.

### STO-16 A record whose bytes are missing is an internal error
```gherkin
Given a record exists but its object is missing from the store
When the file is downloaded by its link
Then the response is 500 with type about:blank
And the inconsistency is logged as "record without bytes", with the storage key
```
Not 404: the file exists as far as users know; the missing bytes are our bug. Test level: integration.

Decided:
- **Stream through the app (A).** Each download holds a Tomcat thread and a storage connection (pool sized in decision 3); memory stays fixed.
- **Documented upgrade (B), redirecting to a short-lived presigned URL** so the app leaves the byte path, as GitLab does for downloads. Needs the store reachable by clients, exposes its address, and the `attachment`/`nosniff` headers must travel through the URL's parameters.

## Mongo (decision 6)

### STO-13 The three unique indexes exist and enforce the rules
```gherkin
Given the application has started against an empty database
Then the files collection has unique indexes on (ownerId, filenameKey), (ownerId, sha256) and (downloadToken)
And inserting a second record with the same <key> fails as a duplicate
```
`where:` key = ownerId + filenameKey / ownerId + sha256 / downloadToken. These indexes carry DOM-11, DOM-12, DOM-26 and DOM-31. Test level: integration.

Decided:
- **One collection, `files`.** Each document holds `_id`, `ownerId`, `filename`, `filenameKey`, `visibility`, `tags`, `size`, `sha256`, `contentType`, `uploadedAt`, `downloadToken` and `storageKey`. No `tags` collection (tags are an array, Domain decision 7) and no reservations (Domain decision 1).
- **Only the three unique indexes are built:** `(ownerId, filenameKey)`, `(ownerId, sha256)`, `(downloadToken)`. They are needed for correctness.
- **Created the simple way:** Spring Data annotations on the document class (`@CompoundIndex(unique = true)`, `@Indexed(unique = true)`) with `spring.data.mongodb.auto-index-creation=true` (a Spring Boot property, off by default).
- **Write concern: the default**, `w: "majority"` since MongoDB 5.0.

Documented, not built:
- **Listing indexes**, following MongoDB's ESR guideline (equality fields first, then sort fields, "to avoid in-memory sorts"):
  - `(ownerId, uploadedAt desc, _id desc)` and `(visibility, uploadedAt desc, _id desc)` for the default order;
  - the same with `tags` after the first field for the tag filter.
  - Covering all five sort fields for both lists, with and without a tag, would take about 20 indexes.
  - Without them Mongo sorts the matching files in memory: fine at test scale, slower as lists grow.
- **An insert with an unclear outcome:**
  - A network error can arrive after Mongo actually saved the record. Deleting the bytes then (DOM-17) would leave a record pointing at deleted bytes: listed, but not downloadable.
  - Fixes:
    - Run Mongo as a replica set so the driver's retryable writes (one automatic retry, recognised by the server) apply. MongoDB: retryable writes need "a replica set or sharded cluster" and don't work on standalone servers. Testcontainers' Java Mongo container already starts a single-node replica set.
    - Before deleting bytes after a non-duplicate failure, look up the record by `storageKey` (needs an index on it).

## Pools and timeouts (decision 3)

### STO-11 Storage connections never run out before Tomcat threads
```gherkin
Given the storage client pool equals Tomcat's maxThreads
When as many uploads as the budget admits run alongside downloads on every remaining thread
Then no request waits for a storage connection
```
Test level: integration (small numbers: maxThreads and pool both set to 4).

### STO-12 Mongo being down fails fast
```gherkin
Given Mongo is stopped
When an upload or a listing arrives
Then the response is 503 /problems/temporarily-unavailable within a few seconds, not 30
```
Test level: integration (stop the Mongo container).

Decided:
- **Storage pool = Tomcat `maxThreads` (200)**, set explicitly on the AWS SDK's Apache 5 HTTP client (`apache5-client`, the SDK's default sync client in 2.59.x). The AWS SDK default of 50 could run out: the budget admits up to 32 uploads at 8 MiB parts, and downloads hold a connection for their whole duration with no budget. The 10 s connection-acquire timeout stays as a safety net.
- **Storage timeouts: SDK defaults** (connect 2 s, socket read/write 30 s of inactivity, no total-call timeout, the SDK's default retries), checked in the SDK source. Idle-based limits suit multi-hour transfers.
- **Mongo pool: default 100.** Connections are held only for milliseconds (DOM-18).
- **Mongo timeouts, short:** server selection 2 s, connect 2 s, socket 10 s, wait for a pooled connection 2 s.
  - The defaults are 30 s / 10 s / 0 (never).
  - With them, a down database would hang the pre-check far beyond curl's 1 s `Expect` wait.
  - The newer `timeoutMS` is marked experimental in the Java driver, so it isn't used.
- **Known limit:** a download to a very slow client leaves its storage connection idle; the store may close it. SeaweedFS's idle limit is unverified (spike).

## Orphan cleanup (decision 5): documented, not built

No criteria: the brief doesn't ask for it, and leftovers never affect users. They have no record, so they are never listed or downloadable, and never block a name or content. The only cost is storage space. Inline cleanup and orphan logging stay (DOM-15 to DOM-17, DOM-41).

How leftovers appear:
- An **unfinished multipart upload**, when the app crashes mid-upload.
- A **complete object with no record**, after a crash between storing and inserting, or after a failed delete.

The sweeper, if it is ever built:
- In the app, hourly, every step safe to repeat, no lock.
- Abort multipart uploads started more than **7 days** ago. Real uploads can take about a day (100 GB at 10 Mbit/s), and 7 days matches Box and Dropbox session lifetimes.
- Delete objects older than **1 hour** with no record, found by storage key.
- Needs: all bytes under one prefix (`blobs/`), and an index on `storageKey`.
- Logs counts per run (input for monitoring).
- Limit: listing every object hourly slows down with millions of objects; inventory- or event-based approaches are the upgrade.

On AWS, unfinished uploads are cleaned by a bucket lifecycle rule (`AbortIncompleteMultipartUpload`), which AWS calls a best practice for storage costs. Support on SeaweedFS is unknown.

## 0-byte objects (decision 4)

### STO-03 An empty body is written as one empty object, never as multipart
```gherkin
Given the adapter is given a body stream that ends immediately
When it writes the stream under a new key
Then exactly one PutObject with an empty body is sent and no multipart call is made
And reading the key returns 0 bytes
And deleting the key succeeds
```
Test level: service (fake S3 client records the calls) and integration (SeaweedFS).

Decided (option A): an empty body always goes through a single `PutObject`. Multipart needs at least one part, and not every server accepts an empty part (Garage issue #759). Download, delete and the sweeper treat the empty object like any other.

## Carried over from the HTTP API and Domain discussions

These become `STO-` criteria as the decisions above are made.

- Stored bytes are identical to the bytes written.
- A failed or aborted write leaves no stored bytes (e.g. the multipart upload is aborted).
- Connection failures are reported as "storage unavailable", distinct from other storage errors (feeds API-16).
- A 0-byte object is written and read back correctly (an upload in parts may not allow an empty part).
- ~~The part buffer per upload fits within the heap~~ → STO-09 (memory budget).
- ~~A sweeper removes stored bytes that have no record~~ → decision 5, documented only.
- The storage key is random and set when the write starts (DOM-14).
