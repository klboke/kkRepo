# Proxy streaming ON/OFF performance

This is a controlled local comparison of **Allow streaming downloads**, not a production capacity
benchmark. The [behavior/scope guide](proxy-streaming-downloads.md) explains which requests are
eligible; the [Chinese report](../../zh/dev/proxy-streaming-performance.md) records the same results.

## Environment and method

- Date: 2026-11-16 (Asia/Shanghai).
- Implementation: `1b3b96de7b0bd9bcc035adb43b81c68fa541fa93`, kkRepo 1.4.0 development JVM build.
- Executable JAR SHA-256: `a8bc13070bbdc95fe5980ca7366b2c61b3972b2c25d77c16c18114b30f8467f3`.
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
| Cold first body byte | 16.3078 s | 0.0281 s |
| Cold total download | 16.4543 s | 16.0134 s |
| Warm first body byte | 9.34 ms | 9.37 ms |
| Warm total download | 168.11 ms | 169.60 ms |
| Cache visible after client completion | Already published | 0.9540 s |

The main improvement is removing the initial silent wait. Total download time still includes the
upstream link's approximately 16-second transfer; it does not become hundreds of times faster.
Warm-cache timings do not establish a throughput improvement. Cache publication after the client
finishes is part of the ON design and must be considered separately from client-visible completion.
These numbers do not measure public-network OSS/S3 latency, concurrent capacity, Native runtime,
real APT/DNF installation, or every supported repository format.

## Reproduce

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
  --build-label 1b3b96de7b0bd9bcc035adb43b81c68fa541fa93 \
  --output /tmp/proxy-streaming.json
```

For a containerized application, use an upstream host reachable from that container. The script
creates unique `stream-<run>-off/on` repositories and retains them for inspection. The output names
those repositories; remove their fixture assets and repositories through the normal management
API when finished, or discard the entire isolated test environment.

## Correctness checks accompanying the measurements

- 757 focused Java/reactor checks passed, including 15 progressive delivery tests, source/group scan
  policy gating, protocol regressions, repository settings, APT/Conda, Docker, redirect boundaries,
  and the UI test harness. The JavaScript fixture exercises all **23 proxy recipes**.
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
