# Domain (core): acceptance criteria

Conventions, test levels and the error format: see [README](README.md).

Status: **all 11 decisions closed** (DOM-01 to DOM-50).

Scope: what a file is and its rules, plus the use-case flow (what the file service does with the stream the HTTP layer hands it, and what is cleaned up when things fail).

## What the brief puts in this layer

- Upload a file with a filename, a visibility (PUBLIC/PRIVATE) and up to 5 tags. Tags are provided by the user and "if tag does not exist, then should be created"; "TAG and tAg is the same tag". The application does not have to provide a list of tags.
- "Prevent users from uploading the same file multiple times, based on content or filename. No global uniqueness of file. File is the same if one of the criteria is met: content is the same; filename already exists."
- Rename without re-upload.
- List all PUBLIC files, and all of the user's files. Filter by tag ("no tag guessing"), sort by filename, upload date, tag, content type and file size, paginate.
- Delete only own files.
- Identify the file type after upload if not provided ("can be done after uploading").
- A unique download link, relative to the service, not guessable, working for PUBLIC and PRIVATE files.
- Brief-required tests: parallel upload with the same filename, with the same content, delete a file that does not belong to the user, list all public files.

## Decisions (in order)

1. When the file record is written: after the bytes are stored, or reserved before (a pending state). **Closed: bytes first, record after** (DOM-10 to DOM-13).
2. What "same filename" means. **Closed** (DOM-01 to DOM-03).
3. Which filenames are valid (characters, length). **Closed** (DOM-04, DOM-05).
4. What "same content" means, and same content across users (stored twice or shared). **Closed** (DOM-06 to DOM-09).
5. The upload flow and cleanup on every failure path. **Closed** (DOM-14 to DOM-20).
6. Rename rules. **Closed** (DOM-21 to DOM-26).
7. Tag rules (normalisation, allowed characters, filter matching). **Closed** (DOM-27 to DOM-30).
8. The download link (token, scope, does it survive rename). **Closed** (DOM-31 to DOM-35).
9. Type detection (header vs content, when). **Closed** (DOM-36 to DOM-38).
10. Delete rules (403 or 404 for someone else's file, what happens to the bytes). **Closed** (DOM-39 to DOM-42).
11. Listing semantics (what "sort by tag" means for several tags, paging). **Closed** (DOM-43 to DOM-50).

## When the file record is written (decision 1)

The file record is the Mongo document in the `files` collection that holds a file's metadata (owner, filename and key, visibility, tags, size, SHA-256, content type, upload date, download token, storage key). The bytes live in storage; the record points to them.

### DOM-10 A record exists only for a completely stored file
```gherkin
Given alice's upload of "big.bin" is still streaming
When alice lists her files
Then "big.bin" is not listed
And no file id exists for it yet
```
Test level: integration.

### DOM-11 [BRIEF] Parallel uploads with the same filename
```gherkin
Given alice has no file named "report.pdf"
When two uploads of "report.pdf" with different content run at the same time
Then exactly one succeeds and the other is rejected as filename-exists
And exactly one record named "report.pdf" exists for alice
And the losing upload's stored bytes have been deleted
```
Both pass the pre-check; the unique index on `(ownerId, filenameKey)` decides at insert. Test level: integration.

### DOM-12 [BRIEF] Parallel uploads with the same content
```gherkin
Given alice has no file with content C
When two uploads of content C under different filenames run at the same time
Then exactly one succeeds and the other is rejected as content-exists
And exactly one record with content C exists for alice
And the losing upload's stored bytes have been deleted
```
The unique index on `(ownerId, sha256)` decides at insert. Test level: integration.

### DOM-13 A name taken by a completed file is caught before the body
```gherkin
Given alice has a file named "Report.pdf"
When an upload of "REPORT.PDF" asks the file service whether the name is free
Then the answer is "taken", with the existing file's id, size and SHA-256
And no bytes are stored
```
This is the pre-check the HTTP layer calls before reading the body (API-13, API-14). Test level: service and integration.

Decided: **bytes first, record after** (option A).
- Stream the bytes to storage while computing size and SHA-256, then insert the record. The unique indexes decide duplicates at the insert. On a duplicate: delete the stored bytes and report the conflict.
- This is what S3, Dropbox, Google Cloud Storage and Microsoft Graph do: conflicts are detected at commit.
- Known cost: two overlapping uploads of the same name by one user both stream until the end, which can be hours for huge files. The pre-check catches only a name already taken by a completed file.
- Documented upgrade (C): a separate `upload_reservations` collection with a lease (unique `(ownerId, filenameKey)`, ShedLock-style claim/takeover, heartbeat, release by token, TTL for garbage collection only). It is an efficiency lock in Kleppmann's sense; the unique index on `files` stays the correctness mechanism. Full design in `design-notes.md`.
- Same content: nothing is detected early. Documented upgrade: an optional `Repr-Digest` (RFC 9530) header checked against the user's own files before the body and verified against the computed hash at the end. Safe because it only rejects and only searches the user's own files.

## Same filename (decision 2)

### DOM-01 Filenames that differ only in case are the same
```gherkin
Given alice has a file named "Report.pdf"
When alice uploads a file named <name>
Then it is rejected as filename-exists
```
`where:` name = "report.pdf" / "REPORT.PDF" / "rEpOrT.pDf". The same rule applies to rename. Test level: service (key logic) and integration (unique index).

### DOM-02 The name is kept as typed
```gherkin
Given alice uploads a file named "Report.PDF"
When she lists her files
Then the file is shown as "Report.PDF"
```
Test level: service.

### DOM-03 Comparison does not depend on the server's locale
```gherkin
Given the JVM default locale is Turkish (tr)
And alice has a file named "TITLE.txt"
When alice uploads a file named "title.txt"
Then it is rejected as filename-exists
```
Under a Turkish locale `"TITLE".toLowerCase()` gives "tıtle" (dotless i); the key uses `Locale.ROOT`. Verified on Java 21. Test level: service.

Decided:
- Case-insensitive, case-preserving, like macOS (APFS default), Windows and Dropbox.
- Store `filename` as typed plus `filenameKey = filename.toLowerCase(Locale.ROOT)`; unique index on `(ownerId, filenameKey)`; the pre-check and rename use the key.
- A separate key field rather than a Mongo case-insensitive collation: collation indexes only serve queries that pass the same collation, and how a unique collation index treats case-only differences was not confirmed.
- Kept simple on purpose, known limits:
  - No Unicode normalisation, so a composed and a decomposed "café.pdf" count as different names. macOS's APFS is normalisation-insensitive, so Mac clients may send either form.
  - Lowercasing is not full case folding, so "STRASSE.txt" and "Straße.txt" stay different (verified on Java 21).

## Valid filenames (decision 3)

### DOM-04 Invalid filenames are rejected
```gherkin
Given alice uploads a file
When its filename is <name>
Then it is rejected as an invalid request, with the broken rule named
And nothing is stored
```
`where:` name = "" / "   " (only spaces) / 256 characters / "a/b.txt" / "a\b.txt" / "../x" / "." / ".." / a name containing a line break / a name containing a NUL character / a name containing DEL (127). The same rules apply to rename. The HTTP layer answers `400 /problems/invalid-request` with `detail` naming the rule. Test level: service.

### DOM-05 Unusual but valid filenames are accepted
```gherkin
Given alice uploads a file
When its filename is <name>
Then the upload is accepted and the name is kept exactly as typed
```
`where:` name = ".hidden" / "notes: v2.txt" / "what?.md" / "  leading space.txt" / "trailing space.txt " / "résumé.pdf" / 255 characters. Test level: service.

Decided: minimal rules (option A).
- Not blank · at most 255 characters · no `/` or `\` · no control characters (0–31, 127) · not `.` or `..`. No trimming; a name of only spaces is blank.
- RFC 6266 §4.3: recipients "MUST NOT be able to write into any location other than one to which they are specifically entitled", with folder parts delimited by "\" and "/". Rejecting separators keeps the stored name exactly what downloads.
- Control characters would break the download header. Windows forbids 1–31; Azure Files rejects "." and "..".
- Windows-only rules (`< > : " | ? *`, trailing space or period, CON/NUL/COM1) are left to the client that saves the file.
- 255 is our own cap.

## Same content (decision 4)

### DOM-06 Identical bytes are the same content, whatever else differs
```gherkin
Given alice has a file with content C
When alice uploads content C with <difference>
Then it is rejected as content-exists, identifying the existing file
```
`where:` difference = another filename / another visibility / other tags / another declared content type. Also: an empty file when alice already has one (all empty files share one SHA-256). Test level: service and integration.

### DOM-07 One differing byte is different content
```gherkin
Given alice has a file with content C
When alice uploads content C with one byte changed, under another filename
Then the upload is accepted
```
Test level: service.

### DOM-08 Content becomes free again after delete; rename keeps it taken
```gherkin
Given alice has a file with content C
When she <action> and then uploads content C under a new filename
Then the upload is <result>
```
`where:` action / result = deletes the file / accepted; renames the file / rejected as content-exists. Test level: integration.

### DOM-09 Users never share files, even with identical content
```gherkin
Given alice and bob have each uploaded content C
When alice deletes her file
Then bob's file still exists and downloads in full
```
Test level: integration.

Decided:
- Same content = same SHA-256 of the full body, computed while streaming; unique index on `(ownerId, sha256)`.
- Filename, visibility, tags and content type play no part. No byte comparison: NIST rates SHA-256 collision resistance at 128 bits.
- The same content from different users is **stored twice**: each file has its own stored bytes.
- Shared bytes (one copy per hash plus a reference count) is documented as the upgrade.
- Known contradiction for that question: copying your own file creates the same content, which the per-user content rule forbids. A copy feature would need an exception for copies.

## The upload flow (decision 5)

Flow:
1. Pre-check the name (DOM-13).
2. Open a storage write under a new random storage key.
3. Stream the body into storage, counting bytes and computing SHA-256, with no database connection held.
4. The storage write completes.
5. Insert the record; both unique indexes decide.

### DOM-14 A successful upload stores the bytes, then the record
```gherkin
Given alice has no file named "report.pdf" and no file with content C
When she uploads content C as "report.pdf"
Then a record exists with the size and SHA-256 computed by the server
And the record's storage key is random, not derived from the filename or content
And the stored bytes are identical to the uploaded bytes
```
Test level: service (fake storage) and integration.

### DOM-15 A failed body leaves nothing behind
```gherkin
Given an upload is streaming
When <failure> happens
Then the storage write is aborted and no bytes remain
And no record exists
```
`where:` failure = the client disconnects / the idle timeout fires / the body ends short of Content-Length / storage fails mid-write. Test level: service (fake storage) and integration.

### DOM-16 Losing on a duplicate at insert deletes the stored bytes
```gherkin
Given an upload's bytes were stored
When the record insert hits a duplicate on <index>
Then the stored bytes are deleted
And the conflict is reported as <conflict> with the existing file's id, size and SHA-256
```
`where:` index / conflict = name / filename-exists; content / content-exists; both (name and content each match a different existing file) / filename-exists. The existing file is found by looking up the name and then the content, not by parsing the error message. Test level: service and integration.

### DOM-17 A database failure at insert deletes the stored bytes, or logs them as orphaned
```gherkin
Given an upload's bytes were stored
When the record insert fails with an error other than a duplicate
Then deletion of the stored bytes is attempted
And if that deletion also fails, the storage key is logged as an orphan
And the error is reported as database unavailable or internal
```
Test level: service.

### DOM-18 No database connection is held while the body streams
```gherkin
Given the Mongo connection pool size is 1
And an upload is paused halfway through its body
When another request lists alice's files
Then the listing is served
```
Test level: integration.

### DOM-19 The same filename for different users never conflicts
```gherkin
Given alice has a file named "report.pdf"
When bob uploads a file named "report.pdf"
Then the upload is accepted
```
Test level: integration.

### DOM-20 A retried upload never creates a second file
```gherkin
Given alice's upload of "report.pdf" with content C completed
When the same upload is sent again
Then it is rejected as filename-exists with the existing file's id, size and SHA-256
And alice still has exactly one file
```
Test level: integration.

Decided:
- Random storage key. It must exist when the storage write starts (S3 multipart takes the key at creation) while the hash exists only at the end. Rename never moves bytes.
- After a duplicate error, look up both indexes instead of parsing Mongo's error text.
- When name and content both clash, the name is reported (same order as the pre-check).
- Cleanup is inline (abort or delete at once). **Bytes without a record are garbage**: a sweeper for what inline cleanup can't cover (a crash between storage completion and insert, a failed delete) is a Storage concern. Storage documents it but doesn't build it (Storage decision 5). Known risk, documented in Storage decision 6: if the insert fails after Mongo actually saved the record, deleting the bytes leaves a record without bytes.

## Rename (decision 6)

### DOM-21 Rename changes only the name
```gherkin
Given alice has "report.pdf"
When she renames it to "new_report.pdf"
Then the file is listed as "new_report.pdf"
And its bytes, size, SHA-256, content type, upload date and storage key are unchanged
And downloading it returns the same bytes
```
Brief: "Changing a name of file should not need reupload of a file." Test level: service and integration.

### DOM-22 Rename is rejected when the new name is not allowed
```gherkin
Given alice has "report.pdf" and "notes.txt"
When she renames "report.pdf" to <name>
Then it is rejected as <reason>
And "report.pdf" keeps its name
```
`where:` name / reason = "notes.txt" / filename-exists; "NOTES.TXT" / filename-exists; "a/b.pdf" / invalid filename; "" / invalid filename. Test level: service.

### DOM-23 Only the owner can rename
```gherkin
Given alice has "report.pdf"
When bob tries to rename it
Then it is rejected and the name is unchanged
```
The rejection is "not found", the same as delete (decision 10). Test level: service.

### DOM-24 A case-only rename is allowed
```gherkin
Given alice has "report.pdf"
When she renames it to "Report.pdf"
Then the file is listed as "Report.pdf"
```
The key "report.pdf" is unchanged and belongs to the same file. Test level: service and integration.

### DOM-25 Renaming to the same name succeeds without changes
```gherkin
Given alice has "report.pdf"
When she renames it to "report.pdf"
Then the rename succeeds and nothing changes
```
A retried rename after a lost response doesn't fail. Test level: service.

### DOM-26 Parallel renames to the same name: one wins
```gherkin
Given alice has "a.pdf" and "b.pdf"
When both are renamed to "final.pdf" at the same time
Then exactly one rename succeeds and the other is rejected as filename-exists
```
The unique index on `(ownerId, filenameKey)` decides. Test level: integration.

## Tags (decision 7)

### DOM-27 Tags are lowercased, trimmed and deduplicated
```gherkin
Given alice uploads a file with tags " Work ", "work" and "Machine Learning"
When the upload completes
Then the file's tags are "work" and "machine learning"
```
Lowercasing uses `Locale.ROOT`. Test level: service.

### DOM-28 Invalid tags are rejected
```gherkin
Given alice uploads a file
When one of its tags is <tag>
Then it is rejected as an invalid request, with the broken rule named
And nothing is stored
```
`where:` tag = "" / "   " / 51 characters / a tag containing a line break. Commas and more than 5 raw values are already rejected by the HTTP layer (API-04). Test level: service.

### DOM-29 A new tag needs no setup
```gherkin
Given no file has the tag "brand-new"
When alice uploads a file tagged "Brand-New"
Then the upload is accepted
And filtering by "brand-new" finds the file
```
Brief: "If tag does not exist, then should be created". Test level: integration.

### DOM-30 The tag filter is an exact, case-insensitive match
```gherkin
Given alice has a file tagged "work"
When she filters her files by <filter>
Then the file is <result>
```
`where:` filter / result = "work" / found; "WORK" / found; "wor" / not found; "work " / found (trimmed). Brief: "no tag guessing; TAG and tAg - is the same tag". Test level: integration.

Decided:
- Tags are an array of strings on the file record. "Created if they don't exist" is satisfied by accepting any new tag; no `tags` collection, since no tag-list endpoint is needed.
- Stored lowercase (`Locale.ROOT`), trimmed, and deduplicated within a file.
- Valid tag: not blank after trimming, at most 50 characters, no commas, no control characters; spaces inside are allowed.
- The filter is an exact match on the normalised tag.
- Tags can't be changed after upload (not asked for).

## Download link (decision 8)

### DOM-31 Every file gets its own random download link
```gherkin
Given alice and bob each upload content C
When both uploads complete
Then each file has a download link of the form /download/{token}
And the two tokens differ
And each token is 128 random bits (22 URL-safe base64 characters)
```
The link is relative, as the brief asks. Test level: service.

### DOM-32 Anyone with the link can download, PUBLIC or PRIVATE
```gherkin
Given alice has a <visibility> file
When someone without any user id downloads it by its link
Then they receive the file's bytes
```
`where:` visibility = PUBLIC / PRIVATE. Brief: "All files can be downloaded by the link (both PRIVATE and PUBLIC)". Test level: integration.

### DOM-33 An unknown token finds nothing
```gherkin
When someone downloads /download/<token>
Then the file is not found
And the response does not reveal whether any file or user exists
```
`where:` token = a well-formed random token that was never issued / a malformed string / another file's id. Test level: service and integration.

### DOM-34 The link survives a rename
```gherkin
Given alice has "report.pdf" with download link L
When she renames it to "final.pdf"
Then L still downloads the file
```
Test level: integration.

### DOM-35 Who sees the link
```gherkin
Given alice has a PRIVATE file P and a PUBLIC file Q
Then alice sees the links of P and Q in her own listing
And the public listing shows Q with its link and does not show P
```
The listing itself is decision 11. Test level: integration.

Decided:
- The link is a capability URL (W3C TAG): whoever holds it can download. PRIVATE therefore means "not in the public listing", not "protected link".
- A separate token, not the file id: a Mongo ObjectId is a timestamp plus a per-process random value and an incrementing counter, so it is predictable.
- 128 bits from `SecureRandom`, URL-safe base64. OWASP requires at least 64 bits of entropy for session ids and recommends generating 128.
- Stored plain with a unique index so the owner's listing can show it.
- Documented, not built: hashing the token at rest, link expiry, a "regenerate link" action (the W3C note recommends capability URLs eventually expire).

## Type detection (decision 9)

### DOM-36 A declared type is kept
```gherkin
Given alice uploads a file with Content-Type <type>
When the upload completes
Then the file's content type is <type>
```
`where:` type = "application/pdf" / "text/csv" / "image/png". Brief: identify the type only "if not provided by user". Test level: service.

### DOM-37 A missing or generic type is detected from the content
```gherkin
Given alice uploads <content> named <name> with <declared>
When the upload completes
Then the file's content type is <detected>
```
`where:` content / name / declared / detected = PDF bytes / "report.pdf" / no Content-Type / "application/pdf"; PNG bytes / "photo.dat" / "application/octet-stream" / "image/png"; plain text / "notes" / no Content-Type / "text/plain". Test level: service (with real Tika).

### DOM-38 Unrecognisable content falls back to the generic type
```gherkin
Given alice uploads random bytes named "blob" with no Content-Type
When the upload completes
Then the file's content type is "application/octet-stream"
```
Test level: service.

Decided (option A):
- Use the client's `Content-Type` if given. If it's missing or `application/octet-stream` (which means "unknown"), detect the type while streaming.
- Keep a copy of the first 64 KB as the bytes pass to storage, then run Apache Tika on those bytes plus the filename. Tika's `MimeTypes.getMinLength()` is 64 KB, the most its detector reads.
- No second read and no "type pending" state; the type is known at insert.
- `tika-core` only (no parsers). Tika 4.x is built on Java 17; latest `tika-core` 4.2.0 (Oct 2026).
- Not chosen: extension mapping (`Files.probeContentType` is "highly platform specific and is not infallible") and a background job after upload.
- For the download contract (HTTP layer, later): the stored type may be client-declared, so downloads send `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` (MDN: nosniff "prevents XSS attacks where user-uploaded content is executed as an HTML document").

## Delete (decision 10)

### DOM-39 [BRIEF] Deleting someone else's file
```gherkin
Given alice has a <visibility> file F
When bob tries to delete F
Then it is rejected as not found
And F still exists and downloads in full
```
`where:` visibility = PRIVATE / PUBLIC. Test level: service and integration.

### DOM-40 Deleting your own file
```gherkin
Given alice has "report.pdf" with content C and download link L
When she deletes it
Then the record is gone, then its stored bytes are deleted
And L finds nothing
And alice can upload a file named "report.pdf" with content C again
```
Test level: integration.

### DOM-41 A failed byte deletion still deletes the file
```gherkin
Given alice deletes a file
When the record is removed but deleting the stored bytes fails
Then the file is gone for every user
And the storage key is logged as an orphan
```
Bytes without a record are garbage (decision 5); the sweeper is a Storage concern. Test level: service.

### DOM-42 Deleting twice
```gherkin
Given alice has deleted file F
When she deletes F again
Then it is rejected as not found
```
Test level: service.

Decided:
- Not the owner → "not found", for delete and rename, whatever the visibility. RFC 9110: a server that wishes to "hide" a forbidden resource "MAY instead respond with a status code of 404". GitHub does this for private repositories "since we don't want to provide any information about private repositories".
- The record is deleted first, then the bytes. A failure leaves bytes without a record, the garbage the sweeper handles, never a record pointing at missing bytes.
- A second delete is "not found". RFC 9110 calls DELETE idempotent in its effect, not its status code.
- Hard delete, no trash.
- A download in progress during delete may break. Documented: deferring byte deletion to the sweeper would avoid it.

## Listing (decision 11)

### DOM-43 [BRIEF] The public list shows every user's PUBLIC files only
```gherkin
Given alice has PUBLIC file A and PRIVATE file B
And bob has PUBLIC file C
When anyone lists public files
Then the list contains A and C
And does not contain B
```
Test level: integration.

### DOM-44 "My files" shows all of the user's files and nobody else's
```gherkin
Given the files above
When alice lists her files
Then the list contains A and B
And does not contain C
```
Test level: integration.

### DOM-45 The optional tag filter works on both lists
```gherkin
Given alice has PUBLIC files tagged "work" and "home"
When <list> is filtered by "WORK"
Then only the file tagged "work" is returned
```
`where:` list = the public list / alice's files. Matching follows DOM-30. Test level: integration.

### DOM-46 Each sort field, both directions
```gherkin
Given alice has files that differ in <field>
When she lists her files sorted by <field> <direction>
Then they come back in <order>
```
`where:`
- filename asc: "apple.pdf", "Banana.pdf", "zebra.pdf". Compared by the lowercase key, so case doesn't decide the order.
- upload date desc: newest first.
- size asc: smallest first.
- content type asc: "application/pdf", "image/png", "text/plain".
- tag asc: by each file's alphabetically first tag; files without tags first.
- tag desc: by each file's alphabetically last tag; files without tags last.

Tag sorting is MongoDB's array sort: "In an ascending sort, the sort key is the lowest value in the array. In a descending sort, the sort key is the highest value"; an empty array sorts before null or missing. Test level: integration.

### DOM-47 The default order is newest first
```gherkin
When alice lists her files without choosing a sort
Then they are ordered by upload date, newest first
```
Test level: integration.

### DOM-48 Paging never repeats or skips a file
```gherkin
Given alice has 45 files, many with the same size
When she walks every page sorted by size with page size 20
Then she sees 3 pages of 20, 20 and 5 files
And every file appears exactly once
```
`_id` is always the last sort key. MongoDB: include "at least one field in your sort that contains unique values" before skipping. Test level: integration.

### DOM-49 Page size and bounds
```gherkin
When alice lists her files with <paging>
Then <result>
```
`where:` paging / result = none / page 0 with 20 items and the total count; size 100 / up to 100 items; size 101 / rejected as invalid; a page past the end / an empty page with the total count; a negative page / rejected as invalid. Test level: service.

### DOM-50 What a listed file shows
```gherkin
When a file appears in any list
Then it shows id, filename, visibility, tags, size, SHA-256, content type, upload date and download link
And it does not show the owner's id, the storage key or the lowercase name key
```
Test level: service.

Decided:
- Two lists: public (all users' PUBLIC files) and mine (all my files).
- One optional tag filter, exact and case-insensitive.
- Five sort fields, both directions. Filename sorts by the lowercase key, because MongoDB compares strings as simple binary by default. Tag sorting uses MongoDB's array sort. Default: newest first. `_id` always breaks ties.
- Page numbers (skip/limit), size default 20, max 100, with the total count. Keyset paging is documented as the upgrade, since `skip()` "will become slower" as the offset grows.
- The indexes for these queries are a Storage decision.

## Carried over from the HTTP API discussion

These become `DOM-` criteria as the decisions above are made.

- ~~Parallel same filename / same content~~ → DOM-11, DOM-12.
- ~~Different users never conflict~~ → DOM-09, DOM-19.
- ~~A user's second empty file is a content duplicate~~ → DOM-06.
- ~~A retry of a completed upload never creates a second file~~ → DOM-20.
- ~~An upload whose body fails creates no file metadata~~ → DOM-15.
- ~~An upload that loses on a duplicate has its bytes deleted~~ → DOM-16.
- ~~Metadata failure after storing: delete or log orphan~~ → DOM-17.
- ~~The filename check offered to the HTTP layer before the body is read~~ → DOM-13.
- ~~No database connection or transaction is held across the body stream~~ → DOM-18.
