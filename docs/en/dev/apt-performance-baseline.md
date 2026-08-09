# Local APT / Debian Performance Baseline Against Nexus

This document records a targeted same-host baseline between kkRepo and Sonatype Nexus Repository
during the APT implementation. It helps identify protocol hot-path differences; it is not a
production capacity result. Cross-host networking, TLS, reverse proxies, OSS/S3, database high
availability, and mixed read/write workloads must be tested in the target environment.

The [Chinese version](../../zh/dev/apt-performance-baseline.md) contains the same measurements.

## Test Environment

- Time: `2026-08-08T17:46:11Z`.
- Host: Intel Core i9-9880H, 64 GiB RAM, macOS 14.7.8 x86_64.
- Container runtime: Docker 29.4.0 (OrbStack), with no separate CPU or memory limit on either
  container.
- Reference: Sonatype Nexus Repository `3.94.0`.
- Candidate: kkRepo `0.7.0` development build from this branch with MySQL 8.0. Both targets used
  local file-backed blob storage.
- Both targets received the same `4,196,374` byte `.deb` with SHA-256
  `b596e8368630f8befe2ea2079f929aab744d9fa48258e02370709df7cd93e975`.

## Method

The [comparison runner](../../../scripts/perf/compare-apt-nexus.py) measured five client-visible
read scenarios against the same hosted repository and package path. Each scenario used 32 warmups
followed by 250 requests at concurrency 16. The runner completed three rounds and alternated
Nexus/kkRepo order between adjacent rounds. The tables report the median of the three rounds for
each target.

Before timing, the runner verifies HTTP status, complete package bytes, the 64 KiB Range bytes, and
their SHA-256 values so an error page or different artifact cannot count toward throughput.

## Initial Results

| Scenario | Nexus req/s | kkRepo req/s | kkRepo / Nexus | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `InRelease` | 817.43 | 507.61 | 0.621x | 16.565 ms | 27.968 ms | 30.747 ms | 56.202 ms |
| `Packages.gz` | 1041.93 | 754.30 | 0.724x | 12.454 ms | 18.859 ms | 21.958 ms | 32.902 ms |
| 4 MiB package GET | 266.71 | 376.00 | 1.410x | 22.622 ms | 18.591 ms | 82.187 ms | 42.089 ms |
| package Range 64 KiB | 1166.84 | 1357.51 | 1.163x | 10.148 ms | 8.720 ms | 31.664 ms | 21.639 ms |
| package HEAD | 1506.32 | 1290.01 | 0.856x | 8.295 ms | 9.380 ms | 17.042 ms | 24.028 ms |

Median full-package GET throughput was `1067.37 MiB/s` for Nexus and `1504.75 MiB/s` for kkRepo.

## Initial Findings

- kkRepo performed better on hot package-body reads: full GET throughput was about 41% higher with
  a roughly 49% lower p95; 64 KiB Range throughput was about 16% higher with a roughly 32% lower
  p95.
- Nexus was faster for small metadata/HEAD requests: kkRepo `InRelease`, `Packages.gz`, and HEAD
  throughput were about 62%, 72%, and 86% of Nexus. This pointed optimization toward fixed database
  projection, authentication/filter, and response-assembly overhead rather than package streaming.
- Each server produces different valid `InRelease` sizes and signature bytes, so metadata results
  compare the cost of each valid response. Package GET and Range use identical artifact bytes.
- These are same-host, warmed, read-oriented results. They do not predict public-network latency,
  concurrent publication, metadata rebuild, proxy fetch, or object-storage performance.

## Retest After Hot-Path Optimizations (2026-08-09)

Four hot-path changes addressed the initial small-object overhead:

- Published APT snapshots use a typed node-local cache with MySQL version-watermark and TTL
  invalidation; stable metadata reads no longer query or lock the suite.
- Repository records, runtimes, and APT settings are reused between filters and controllers and
  invalidated after repository configuration broadcasts, removing duplicate SELECTs and JSON
  parsing.
- Asset metadata and Basic Auth use rebuildable typed node-local caches ahead of shared caching,
  reducing shared-cache reads and deserialization for hot hits.
- Anonymous requests without credentials and warmed permission-catalog decisions no longer open a
  request transaction. Database-backed authentication fallback still has an explicit transaction
  boundary.

The retest used the same containers, artifact, request count, concurrency, warmups, and alternating
three-round order. The table records the three-round medians from the second independent
confirmation run:

| Scenario | Nexus req/s | kkRepo req/s | kkRepo / Nexus | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `InRelease` | 2087.93 | 2740.20 | 1.312x | 6.512 ms | 3.910 ms | 18.992 ms | 12.611 ms |
| `Packages.gz` | 1705.69 | 2552.21 | 1.496x | 7.705 ms | 4.502 ms | 16.901 ms | 14.008 ms |
| 4 MiB package GET | 336.13 | 405.03 | 1.205x | 17.028 ms | 14.959 ms | 41.621 ms | 30.514 ms |
| package Range 64 KiB | 1557.66 | 1814.40 | 1.165x | 7.513 ms | 6.024 ms | 19.835 ms | 19.743 ms |
| package HEAD | 2301.23 | 2755.66 | 1.197x | 5.443 ms | 4.165 ms | 14.921 ms | 11.650 ms |

Median full-package GET throughput was `1345.17 MiB/s` for Nexus and `1620.92 MiB/s` for kkRepo.
Another independent run produced throughput ratios of `1.453x`, `1.197x`, `1.085x`, `1.348x`,
and `1.034x` for the five scenarios, consistent in direction with the confirmation run.

After warmup, 1,000 `InRelease` requests at concurrency 16 were also checked against the MySQL
statement digest. No APT suite, snapshot, or repository business query and no request transaction
occurred during those requests; all 14 observed transactions belonged to concurrent background
polling. The improvement therefore came from removing fixed database and serialization overhead,
not bypassing protocol checks or response transfer.

After the retest, throughput in all five client-visible read scenarios exceeded same-host Nexus.
The initial metadata and HEAD gap was gone. These remain directional local results; production
testing still needs TLS, remote OSS/S3, database HA, and multi-replica load balancing.

## Retest After Asynchronous Publication And Streaming Indexes (2026-08-09)

Both performance repositories received 100 additional identical small `.deb` files, growing from
3 to 103 packages. The same 250 requests, concurrency 16, 32 warmups, and three alternating rounds
were then repeated:

| Scenario | Nexus req/s | kkRepo req/s | kkRepo / Nexus | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `InRelease` | 1383.36 | 1985.02 | 1.435x | 8.677 ms | 5.119 ms | 22.275 ms | 12.934 ms |
| `Packages.gz` | 1572.25 | 2366.74 | 1.505x | 7.792 ms | 4.877 ms | 17.334 ms | 10.430 ms |
| 4 MiB package GET | 283.54 | 390.54 | 1.377x | 17.447 ms | 17.525 ms | 62.682 ms | 34.791 ms |
| package Range 64 KiB | 1644.48 | 2044.32 | 1.243x | 6.719 ms | 5.956 ms | 15.293 ms | 14.300 ms |
| package HEAD | 2275.14 | 3177.13 | 1.396x | 5.286 ms | 3.925 ms | 13.495 ms | 9.515 ms |

During a 100-package burst at concurrency 16, kkRepo combined 100 durable desired revisions into
one new snapshot. New metadata became visible `0.777 s` after upload completion versus `0.582 s`
for Nexus. kkRepo upload responses remained slower: `2.738 s` versus `2.124 s` wall time and
`354.06 ms` versus `108.10 ms` p50. Asynchronous publication moved complete index generation out of
the write request, but the write path still performs `.deb` unpacking and identity/checksum
validation, blob/asset/component persistence, audit, and security-scanning outbox work. Future write
optimization must profile those fixed costs rather than defer correctness to metadata projection.

The larger package set did not cause a read hot-path collapse; all five read scenarios still
outperformed same-host Nexus. One publication still grows roughly linearly with the Packages size.
Streaming bounds heap growth, and debounce removes redundant rebuilds during bursts; neither turns
complete index generation into O(1).

The final V44 dual-replica image deployment ran the same complete benchmark three more times. Each
run itself used three-round medians, and the median throughput ratios across the three runs were
`1.564x`, `1.251x`, `1.258x`, `1.176x`, and `0.988x` for `InRelease`, `Packages.gz`, full GET,
Range, and HEAD. Individual metadata results were noisy (`InRelease` `0.639x`-`1.582x` and
`Packages.gz` `0.924x`-`1.461x`); HEAD was roughly equal, while large GET and Range led in all
three runs. Treat local results as directional evidence, not a stable SLA.

## Reproduce

Upload the same package to both hosted repositories, then run:

```bash
python3 scripts/perf/compare-apt-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:58090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --package-path pool/k/kkrepo-apt-benchmark/kkrepo-apt-benchmark_1.0.0_amd64.deb \
  --requests 250 \
  --concurrency 16 \
  --warmups 32 \
  --rounds 3 \
  --output /tmp/apt-performance.json
```

Credentials are passed only to the local process. Do not commit commands or result files containing
real passwords.
