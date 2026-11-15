# Proxy streaming ON/OFF performance

This report compares **Allow streaming downloads** using a real public PyPI package and a
controlled local baseline. It is not a production capacity benchmark. The [behavior/scope guide](proxy-streaming-downloads.md) explains which requests are
eligible; the [Chinese report](../../zh/dev/proxy-streaming-performance.md) records the same results.

## Real PyPI package: 906.5 MB

On 2026-11-16, JVM implementation `767c924bda69082ccfb4dd9cb1fb3d3c04b2bb19` downloaded
[`torch-2.5.1-cp311-cp311-manylinux1_x86_64.whl`](https://files.pythonhosted.org/packages/d1/35/e8b2daf02ce933e4518e6f5682c72fd0ed66c15910ea1fb4168f442b71c4/torch-2.5.1-cp311-cp311-manylinux1_x86_64.whl)
through actual **PyPI proxy** repositories pointing at `https://pypi.org/`.
[Official release metadata](https://pypi.org/pypi/torch/2.5.1/json) specifies **906,474,467 bytes
(906.5 MB / 864.5 MiB)** and SHA-256
`de5b7d6740c4b636ef4db92be922f0edc425b65ed78c5076c43c42d362a45457`.

Executable JAR SHA-256: `80ea33b9ed62cfabdfcca90742dc2a34a62d36556f36d5a93238c6aec7fc187e`. Hardware, JVM options and local PostgreSQL/MinIO
match the environment listed for the controlled baseline below. That earlier baseline used the prior
validator build; the real-package table uses the subsequent async-timeout fix in both modes.

- One complete OFF → ON pair, sequential downloads, **no artificial rate limit**. These are single
  observations, not three-sample medians. Public-network bandwidth limited the number of repetitions.
  A separate 4,661-byte `sampleproject` wheel check validated the runner before this measurement.
- Each mode used a fresh repository, a distinct Blob store and a unique S3 prefix. The actual Simple
  API index was fetched first and its rewritten package link was followed; the wheel itself was uncached.
  An independent upstream HEAD observed PyPI CDN `HIT, HIT`: cold means **kkRepo's cache**, not the CDN.
- Client socket timeout: 1,800 seconds. Timings include client-side incremental SHA-256 calculation.
  All four full responses (cold/warm × OFF/ON) were HTTP 200 and matched the official length and digest.
- Browse API polling every 100 ms observed cache publication before the warm GET. The per-repository
  `kkrepo_proxy_remote_requests_total` counter recorded two cold requests (index resolution + wheel)
  and **zero additional requests for each warm download**.

| Metric (one sample per mode) | OFF | ON |
| --- | ---: | ---: |
| Cold first body byte | 629.681 s | 0.418 s |
| Cold total download | 631.631 s | 883.207 s |
| Warm first body byte | 10.68 ms | 12.60 ms |
| Warm total download | 2.146 s | 2.145 s |
| Cache visible after client completion | Already published | 14.243 s |
| Warm upstream requests | 0 | 0 |

The earlier progress is directly visible in this run. ON was **251.576 seconds slower**
overall in this pair. This sequential, uncontrolled public-CDN comparison cannot separate network
variation from implementation overhead or establish a stable throughput gain. Warm samples do not establish
an ON/OFF performance difference either. ON cache publication completed after the client; the warm
sample starts only after publication. This is a real package transfer, not a complete `pip install`,
a concurrency test, or a public-cloud object-storage benchmark.

An initial OFF probe with a 600-second client timeout received no response headers/body before timing
out. Its backend fill subsequently completed; it is retained separately in the
[raw record](../../perf-data/proxy-streaming-real-pypi-2026-11-16.json) and is **excluded from the completed-download table**.
The table uses a new OFF repository/store and the longer client timeout; it does not reuse that fill.

The first ON attempt also exposed a product defect: MVC's global ten-minute **total-duration** timeout
interrupted an active stream. The client received only **741,959,347 / 906,474,467 bytes**; no package
cache entry was published. [Pre-fix data](../../perf-data/proxy-streaming-real-pypi-before-timeout-fix-2026-11-16.json)
retains that failed attempt and its preceding successful OFF measurement. The fix disables that total
deadline only for admitted progressive responses, retaining upstream I/O timeouts, disconnect cleanup
and resource limits. A real Tomcat regression completes a slow body past a 100 ms MVC deadline; a
separate check preserves the configured deadline for buffered fallback. The table above reruns both
modes with the fix and fresh storage; failed transfers are not reported as completed downloads.

### Reproduce the real package check

Use a disposable kkRepo instance and an accessible management metrics endpoint. Save a private JSON
S3 `BlobStoreRequest` template (the body accepted by `POST /internal/blob-stores`) with `type: "s3"`,
endpoint/region/bucket/prefix, credentials, and the desired storage options. The measured template used
`engine: "aws-s3"`, path-style access, a 64 MiB multipart threshold, 16 MiB parts and concurrency 4.
The runner generates unique store names/prefixes and repositories; it never copies credentials into
results. Keep the template outside the repository with permissions `0600`.

```sh
curl --fail --silent --show-error https://pypi.org/pypi/torch/2.5.1/json \
  -o /tmp/torch-2.5.1.json
export KKREPO_BENCH_USER=admin
# Set KKREPO_BENCH_PASSWORD in the local environment.
python3 scripts/perf/compare-real-pypi-streaming.py \
  --base-url http://127.0.0.1:38540 --management-url http://127.0.0.1:38544 \
  --metadata-file /tmp/torch-2.5.1.json \
  --filename torch-2.5.1-cp311-cp311-manylinux1_x86_64.whl \
  --blob-store-template /tmp/private-benchmark-s3.json --repeats 1 \
  --build-label 767c924bda69082ccfb4dd9cb1fb3d3c04b2bb19 \
  --output /tmp/proxy-streaming-real-pypi.json
```

The runner defaults to one pair. Each extra pair downloads another **1.81 GB from PyPI**, plus two
local warm downloads. It retains fixtures for inspection; the output lists their exact repository,
Blob store and prefix names. Remove those fixtures through the management API or discard the isolated
environment after inspection. Do not run a build or another transfer concurrently with measurements.

## Controlled 64 MiB baseline

### Environment and method

- Date: 2026-11-16 (Asia/Shanghai).
- Implementation: `f114fdb26cb215b7cae749806fb53f47d1a4e0ca`, kkRepo 1.4.0 development JVM build.
- Executable JAR SHA-256: `9d4d527dd3e82dfc6d8abea34b5ee9b6ace9f176f7bcd34291f4a836e00d340b`.
- macOS 14.7.8, Intel i9-9880H, 64 GiB RAM; OpenJDK 25.0.3; application `-Xms512m -Xmx2g`.
- PostgreSQL 16 and MinIO `RELEASE.2025-04-22T22-12-26Z` in local OrbStack/Docker containers.
  The application used the `dev` profile with application/JDBC debug logging disabled.
- Raw proxy, authenticated HTTP, one download at a time, 64 MiB per file; a local upstream emitted
  64 KiB chunks at **4 MiB/s**, including the final chunk's transfer time.
- Three trials per setting, alternating OFF/ON order. One 64 KiB warmup per repository. Each trial
  used new bytes, identical between modes; OFF/ON used distinct Blob stores/prefixes to prevent
  cross-mode deduplication. Full downloads and resumed ranges were checked for length and SHA-256.
- Cache visibility was polled through the browse API at 50 ms intervals, without fetching the
  artifact. Subsequent warm downloads were required to make **zero upstream requests**.

The runner measures the first **body byte**, not merely response headers. All times below are
medians of three samples. The [raw samples](../../perf-data/proxy-streaming-2026-11-16.json) include
individual timings, hashes, statuses, upstream request counts, cache lag, and warm Range results.

| Metric | OFF | ON |
| --- | ---: | ---: |
| Cold first body byte | 16.2989 s | 0.0303 s |
| Cold total download | 16.4598 s | 16.0148 s |
| Warm first body byte | 9.75 ms | 12.24 ms |
| Warm total download | 174.08 ms | 169.74 ms |
| Cache visible after client completion | Already published | 0.8841 s |

The main improvement is removing the initial silent wait. Total download time still includes the
upstream link's approximately 16-second transfer; it does not become hundreds of times faster.
Warm-cache timings do not establish a throughput improvement. Cache publication after the client
finishes is part of the ON design and must be considered separately from client-visible completion.
These numbers do not measure public-network OSS/S3 latency, concurrent capacity, Native runtime,
real APT/DNF installation, or every supported repository format.

### Reproduce the controlled baseline

Use a disposable running kkRepo instance with a database and object storage. Create **two distinct
S3 Blob store records**, for example `streaming-bench-off` and `streaming-bench-on`, pointing at
separate prefixes. Permit the controlled upstream address in that test environment. Supply an
administrator's credentials through environment variables; the runner never records them.

```sh
export KKREPO_BENCH_USER=admin
# Set KKREPO_BENCH_PASSWORD in the local environment.
python3 scripts/perf/compare-proxy-streaming.py \
  --base-url http://127.0.0.1:38540 \
  --blob-store-off streaming-bench-off --blob-store-on streaming-bench-on \
  --upstream-host 127.0.0.1 --upstream-port 38541 \
  --size-mib 64 --rate-mib 4 --repeats 3 \
  --build-label f114fdb26cb215b7cae749806fb53f47d1a4e0ca \
  --output /tmp/proxy-streaming.json
```

For a containerized application, use an upstream host reachable from that container. The script
creates unique `stream-<run>-off/on` repositories and retains them for inspection. The output names
those repositories; remove their fixture assets and repositories through the normal management
API when finished, or discard the entire isolated test environment.

## Correctness checks accompanying the measurements

- Focused Java/reactor checks passed for proxy/protocol behavior, source/group scan policy gating,
  repository settings, APT/Conda, Docker, redirect boundaries and the UI harness. The final validator
  build passed 28 progressive delivery and 50 Raw proxy tests, including conditional requests and
  supported artifact admission. The JavaScript fixture exercises all **23 proxy recipes**.
- Each measured cold, warm and resumed response had at most one ETag field; duplicate validator
  fields are rejected by the benchmark runner.
- Live Nexus 3.94 comparison passed for full GET, single/suffix Range, If-Range mismatch, 416,
  cache hit and HEAD. ON matches Nexus's Raw range behavior. OFF deliberately preserves kkRepo's
  pre-existing Raw full-200 response to Range requests. Test:
  `RawRepositoryBlackBoxCompatibilityTest#streamingTogglePreservesFullAndRangeHttpSemanticsAgainstNexus`.
- Two separate JVMs sharing PostgreSQL and MinIO each began an old cold download. While both
  upstream bodies were paused, the repository option was disabled and re-enabled, and newer bytes
  were downloaded and cached. Both old client responses completed correctly; their later cache
  fills did not replace the newer cache. Both nodes subsequently served the new SHA-256 and only
  one asset remained visible. This uses the committed implementation above.

The multi-process check is reproducible separately from timing:

```sh
python3 scripts/perf/verify-proxy-streaming-coordination.py \
  --base-url http://127.0.0.1:38540 --peer-url http://127.0.0.1:38545 \
  --blob-store streaming-bench-on --upstream-port 38546
```

The two instances must share repository metadata, Blob storage and encryption configuration. This
check intentionally edits only its newly created repository; it is not a rolling-deployment or
process-crash recovery benchmark. Browser visual automation was unavailable in the local session;
form behavior and information-popover markup were verified by the automated UI tests.
