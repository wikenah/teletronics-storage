# System (end to end): acceptance criteria

Not a layer: these check the brief's non-functional limits on the shipped Docker image, across all layers. Conventions, test levels and the error format: see [README](README.md).

Status: **all 4 decisions closed** (SYS-01, SYS-02).

The brief: "Container has to have a memory limit 1GB that can be used", "Container has to have a hard disk limit to 200MB for APPLICATION", and a test that will "Simulate UPLOAD of a FILE that is at least 2GB size".

## SYS-01 [BRIEF] 2 GiB upload against the running container
```gherkin
Given the built application image runs with a 1 GiB memory limit (swap equal to memory)
And Mongo and SeaweedFS run alongside it
When a client streams a generated 2 GiB body to the upload endpoint
Then the response is 201 Created
And the reported size is 2147483648 and the SHA-256 matches the client's
And the application container was not OOM-killed and is still running
And the application container's writable layer grew by far less than 200 MB (e.g. < 20 MB)
```
Test level: container.

## SYS-02 The memory budget holds under parallel load
```gherkin
Given the built application image runs with a 1 GiB memory limit (swap equal to memory)
And the upload buffer budget is fully used by parallel uploads of large files
And several downloads are streaming at the same time
When further uploads arrive
Then they are refused with 503 /problems/upload-capacity-reached
And every admitted upload completes with 201 and the right SHA-256
And the application container was not OOM-killed
And the container's peak memory (cgroup memory.peak) stayed below the limit
```
This is the proof that the MiB budget (Storage decision 2) and the JVM settings (decision S2) fit together. SYS-01 alone only shows one upload. Test level: container.

## Decisions

### S1. The image is built from a Dockerfile, not buildpacks
- Spring Boot 4.1's documented layered pattern: a multi-stage build that runs `java -Djarmode=tools -jar application.jar extract --layers --destination extracted` and starts with `ENTRYPOINT ["java", "-jar", "application.jar"]` in exec form. Docker warns that a shell-form entrypoint keeps the process from receiving SIGTERM.
- The base image is pinned by digest, so builds are repeatable.
- JVM options are set explicitly in the file (`JAVA_TOOL_OPTIONS`, decision S2), so the heap is fixed and visible.
- The process runs as an unprivileged user (uid 10001), not root.
- **Not chosen: `bootBuildImage` (Paketo).** Its memory calculator computes the heap at startup: heap = container memory − (10 MB direct + metaspace estimated from the class count + 240 MB code cache + 250 × 1 MB thread stacks). The result depends on the class count and the builder version, and drifts with `:latest`. Our heap must match the upload budget, so it can't be left to a calculation.

### S2. JVM memory inside the 1 GB container
Starting values, confirmed by measurement (baseline heap after startup, then SYS-02):

| Setting | Value | Why |
|---|---|---|
| Heap | `-XX:MaxRAMPercentage=55` (≈ 563 MiB at 1 GiB) | Java 21's default is 25% (256 MiB at 1 GiB, verified on Temurin 21), which can't even hold the 256 MiB upload budget. 55% fits the budget plus the app's baseline and leaves ~460 MiB outside the heap. Microsoft: "Don't set the maximum JVM heap memory to be equal to the amount of container memory." |
| Direct memory | `-XX:MaxDirectMemorySize=64m` | Its default equals the heap size. |
| Out of memory | `-XX:+ExitOnOutOfMemoryError`, no `HeapDumpOnOutOfMemoryError` | A clean, visible exit; a heap-sized dump would break the 200 MB disk. |
| Garbage collector | The JVM's own choice, Serial, stated in the docs | Java picks Serial below a "server-class machine" (two or more processors and at least 1,792 MB). Fine for this workload. |
| Upload buffer budget | `uploads.buffer-budget-mib=256` | Inside the heap (Storage decision 2). |

Memory outside the heap to keep in mind: metaspace (unbounded by default), code cache (240 MB reserved by default, committed as code is compiled), thread stacks (1 MB each on x64, committed as used; up to 200 Tomcat threads), direct buffers (64 MiB). The proposal to raise Java's container default to 75% (JDK-8356194, PR 25086) was closed without being integrated, so the default stays at 25%.

#### Measured (build, Nov 2026, docker compose on Docker Desktop)

**Idle, after startup: 295 MiB of container memory.** Read with Native Memory Tracking and `jcmd` from a JDK sidecar container.

| Part | MiB |
|---|---|
| Java heap, committed | 64 (about 30 in use) |
| Class metadata (metaspace, class space, shared archive) | 70 |
| Symbols | 20 |
| JIT-compiled code | 16 |
| Thread stacks, other | 7 |
| Freed by the JVM but kept by the C library (mostly JIT compiler scratch memory, peak 41 MB) | about 70; `System.trim_native_heap` returned 51 |
| Page cache (jar and JDK files; reclaimable) | 43 |

**One 2 GiB upload** raised container memory by about 50 MiB at most. That is one 8 MiB part buffer, 7 MiB of kernel socket buffers, one-time JIT and class loading, and heap slack. SYS-01's `memory.peak` of 333 MB is mostly the idle baseline.

**Parallel uploads.** N uploads of 24 MiB each, throttled to 1 MiB/s so all are in flight together. The live heap is read after a forced full GC, compared with idle just before.

| N | Live heap added | Per admitted upload | Responses |
|---|---|---|---|
| 1 | 8.4 MiB | 8.35 MiB | 1× 201 |
| 8 | 67.5 MiB | 8.44 MiB | 8× 201 |
| 16 | 135.7 MiB | 8.48 MiB | 16× 201 |
| 32 | 272.7 MiB | 8.52 MiB | 32× 201 |
| 40 | 266.7 MiB | 8.33 MiB | 32× 201, 8× 503 |

- Each upload holds its 8 MiB part buffer plus about 0.4 MiB of other request state. The budget counts only the part buffer, so a full budget (32 uploads) uses about 16 MiB more than its 256 MiB, well within the heap's headroom.
- Past the budget, uploads are refused at once: 40 started, 32 admitted, 8 got 503 `upload-capacity-reached`, and the live heap stayed at the 32-upload level.
- Container memory grew less than the live heap (e.g. +195 MiB at N = 32), because earlier runs had already made heap pages resident. The live heap is the reliable measure.

### S3. The 200 MB disk limit is measured, not enforced
- **How:**
  - Read the container's writable-layer size `SizeRw` (`inspectContainerCmd(id).withSize(true)`) before and after the upload, and require growth far below 200 MB (e.g. < 20 MB).
  - On failure, dump `docker diff` to name the files.
  - `SizeRw` excludes volumes, tmpfs and logs.
- **Why not enforce a real quota:**
  - `--storage-opt size` needs overlay2 on xfs with pquota (or btrfs/zfs).
  - On Docker Desktop's containerd store it is silently ignored: verified, 300 MB written past a 200 MB limit.
  - On GitHub's runners (overlay2, likely on ext4) it would most likely fail.
- **Documented, not built:** a hardened variant with a read-only root filesystem plus a small in-memory `/tmp` (e.g. 32 MB), where any large temp-file write fails outright. The `/tmp` counts against the 1 GB memory limit, and the JVM and Tomcat need it writable.
- **Interpretation stated in the README:** "200MB for APPLICATION" means runtime writable disk, not image size. The `eclipse-temurin:21-jre` base image alone is 476 MB, so no Java image could meet an image-size reading.

### S4. A second container test for parallel load (SYS-02)
SYS-01 proves one 2 GiB upload; the real memory risk is many uploads at once. SYS-02 fills the buffer budget with parallel uploads while downloads run, and checks refusals, completions, no OOM kill and the cgroup memory peak.

## How the container tests run (from the container research, Nov 2026)

**Memory limit**
- Testcontainers: `withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(1L<<30).withMemorySwap(1L<<30))`.
- Swap must equal memory. Docker otherwise allows as much swap again; verified, `-m 1g` alone gave 1 GiB of extra swap.
- docker compose for manual runs: `mem_limit: 1g` + `memswap_limit: 1g`. `deploy.resources.limits.memory` alone leaves the swap.

**Detecting an OOM kill**
- Assert `State.OOMKilled == false` and `State.Running == true` while the container is still running.
- Don't rely on the exit code: a killed child process can leave `ExitCode=0` with `OOMKilled=true`.
- Extra evidence: cgroup v2 `memory.events` `oom_kill == 0`, and `memory.peak` (both via `execInContainer`).

**CI (GitHub Actions `ubuntu-latest`)**
- Docker preinstalled; 14 GB SSD; 16 GB RAM for public repositories, 8 GB for private ones.
- Generate the 2 GiB on the fly rather than as a fixture file.
- Cap Mongo's cache (`--wiredTigerCacheSizeGB`).
- The container tests run as their own Gradle task after the image is built.
