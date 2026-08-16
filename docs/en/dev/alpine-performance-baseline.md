# Local Alpine / APK Performance Baseline Against Nexus

This document records a targeted same-host baseline between kkRepo and Sonatype Nexus Repository
for the Alpine APK v2 implementation. It is a protocol hot-path comparison, not a production
capacity result. TLS, reverse proxies, remote OSS/S3, database high availability, mixed reads and
writes, and end-to-end publication at large namespace sizes still need workload-specific testing.

The [Chinese version](../../zh/dev/alpine-performance-baseline.md) contains the same measurements.

## Test environment

- Time: `2026-08-15T09:57:22Z`.
- Host: Intel Core i9-9880H, 64 GiB RAM, macOS 14.7.8 x86_64.
- Container runtime: Docker 29.4.0 (OrbStack), with no separate CPU or memory limit on either
  repository container.
- Reference: Sonatype Nexus Repository `3.94.0`.
- Candidate: kkRepo `0.8.0` development build from this branch with MySQL 8.0.46. A second kkRepo
  replica shared the same database and file-backed blob volume, while the benchmark targeted the
  primary replica. Nexus also used local file-backed blob storage.
- Both hosted repositories received the same `4,196,328` byte APK with SHA-256
  `ae4c61a2d34fa1e99962b38e138cd0ef5477097d0344833ffd9a077ad7e69da3`.
- Both repositories signed their v2 index with the same RSA private key. The real client flow
  trusted that public key under the signature filename emitted by each repository.

## Method

The [comparison runner](../../../scripts/perf/compare-alpine-nexus.py) checks the signed
`APKINDEX.tar.gz` GET, HEAD, and conditional `304` paths plus full APK GET, 64 KiB Range, and HEAD.
Before timing, it rejects mismatched package bytes, mismatched Range bytes, invalid signed-index
shape, and different logical `P/V/S/I` package sets. `C:` is validated as a Q1 identity but is not
compared because Nexus 3.94 can emit a non-apk-tools Q1 value for an unsigned direct upload.

Each HTTP scenario used 32 warmups followed by 250 requests at concurrency 16. The runner executed
three rounds, alternated Nexus/kkRepo order between rounds, and reports the median. A short
one-round prewarm was completed after both indexes became available and background repository work
settled. The Alpine 3.23 client flow ran `apk update`, exact `apk search`, and `apk policy` three
times, also alternating target order.

HTTP response time (RT) starts immediately before the client writes the request and ends after the
complete response body has been read. Each of the 16 workers reuses one connection for its assigned
requests, so the first request can include connection setup while later requests measure the warmed
connection. GET RT includes full payload transfer; HEAD and `304` RT includes response-header
processing and draining the expected empty body. Each round computes p50/p95/p99/max from its 250
requests. The summary reports the median of each per-round statistic rather than pooling the three
rounds. In total, the confirmation contains 9,000 timed HTTP requests.

The release gates enforced by the runner are:

- metadata paths: kkRepo throughput at least `0.80x` Nexus and p95 at most `1.25x` Nexus;
- package GET/Range: kkRepo throughput at least `0.90x` Nexus and p95 at most `1.15x` Nexus;
- real `apk` client flow: kkRepo p95 at most `1.25x` Nexus.

## Results

All 9,000 timed HTTP requests completed with the expected status and validated payload. The runner
reported an empty `gate_failures` list.

| Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p95 | kkRepo p95 | p95 ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| signed index GET | 1760.65 | 1609.49 | 0.914x | 19.300 ms | 15.291 ms | 0.792x |
| signed index HEAD | 1263.57 | 1566.47 | 1.240x | 24.054 ms | 24.249 ms | 1.008x |
| signed index 304 | 1286.85 | 1880.19 | 1.461x | 22.951 ms | 18.270 ms | 0.796x |
| 4 MiB package GET | 370.48 | 487.99 | 1.317x | 62.154 ms | 46.165 ms | 0.743x |
| package Range 64 KiB | 1361.38 | 1810.76 | 1.330x | 22.960 ms | 16.959 ms | 0.739x |
| package HEAD | 1980.28 | 2622.83 | 1.324x | 13.088 ms | 11.508 ms | 0.879x |

Full-package median throughput was `1482.64 MiB/s` for Nexus and `1952.88 MiB/s` for kkRepo.
Every HTTP gate passed.

### Response-time detail

These are the three-round medians of each round's 250-request distribution. `wall` is the median
elapsed wall time for one complete 250-request round.

| Scenario | Target | req/s | MiB/s | wall | p50 RT | p95 RT | p99 RT | max RT |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| signed index GET | Nexus | 1760.65 | 1.11 | 141.993 ms | 6.033 ms | 19.300 ms | 24.695 ms | 26.315 ms |
| signed index GET | kkRepo | 1609.49 | 1.06 | 155.328 ms | 6.807 ms | 15.291 ms | 18.566 ms | 24.399 ms |
| signed index HEAD | Nexus | 1263.57 | 0.00 | 197.853 ms | 9.648 ms | 24.054 ms | 29.264 ms | 39.092 ms |
| signed index HEAD | kkRepo | 1566.47 | 0.00 | 159.594 ms | 7.324 ms | 24.249 ms | 32.539 ms | 36.176 ms |
| signed index 304 | Nexus | 1286.85 | 0.00 | 194.273 ms | 9.683 ms | 22.951 ms | 33.204 ms | 34.021 ms |
| signed index 304 | kkRepo | 1880.19 | 0.00 | 132.965 ms | 6.314 ms | 18.270 ms | 24.343 ms | 32.273 ms |
| 4 MiB package GET | Nexus | 370.48 | 1482.64 | 674.799 ms | 29.671 ms | 62.154 ms | 84.836 ms | 218.357 ms |
| 4 MiB package GET | kkRepo | 487.99 | 1952.88 | 512.311 ms | 28.220 ms | 46.165 ms | 58.677 ms | 74.540 ms |
| package Range 64 KiB | Nexus | 1361.38 | 85.09 | 183.638 ms | 9.613 ms | 22.960 ms | 24.316 ms | 25.477 ms |
| package Range 64 KiB | kkRepo | 1810.76 | 113.17 | 138.064 ms | 6.937 ms | 16.959 ms | 24.845 ms | 29.775 ms |
| package HEAD | Nexus | 1980.28 | 0.00 | 126.245 ms | 6.547 ms | 13.088 ms | 21.860 ms | 23.863 ms |
| package HEAD | kkRepo | 2622.83 | 0.00 | 95.317 ms | 4.461 ms | 11.508 ms | 16.428 ms | 17.622 ms |

### Per-round throughput and RT

Each RT tuple below is `p50 / p95 / p99 / max` in milliseconds. The target order alternated by
round; it was not always Nexus first.

| Scenario | Round | Nexus req/s | Nexus p50/p95/p99/max ms | kkRepo req/s | kkRepo p50/p95/p99/max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| signed index GET | 1 | 1758.77 | 6.900 / 15.074 / 24.695 / 26.315 | 1860.63 | 6.665 / 13.860 / 18.566 / 20.216 |
| signed index GET | 2 | 1902.16 | 5.805 / 19.300 / 31.674 / 33.134 | 1609.49 | 6.807 / 23.202 / 43.001 / 44.755 |
| signed index GET | 3 | 1760.65 | 6.033 / 22.967 / 24.488 / 24.644 | 1599.50 | 7.332 / 15.291 / 17.175 / 24.399 |
| signed index HEAD | 1 | 1239.25 | 9.678 / 24.391 / 38.102 / 50.197 | 1566.47 | 7.324 / 30.141 / 38.179 / 51.923 |
| signed index HEAD | 2 | 1263.57 | 9.648 / 23.548 / 29.264 / 37.793 | 1960.21 | 5.683 / 19.549 / 28.286 / 30.477 |
| signed index HEAD | 3 | 1277.53 | 9.460 / 24.054 / 29.212 / 39.092 | 1498.05 | 7.431 / 24.249 / 32.539 / 36.176 |
| signed index 304 | 1 | 1329.09 | 9.617 / 23.490 / 33.204 / 34.021 | 2418.70 | 5.131 / 11.089 / 12.954 / 13.475 |
| signed index 304 | 2 | 1286.85 | 9.824 / 20.749 / 41.666 / 45.499 | 1751.37 | 6.314 / 18.270 / 24.343 / 34.435 |
| signed index 304 | 3 | 1281.03 | 9.683 / 22.951 / 26.289 / 30.674 | 1880.19 | 6.353 / 20.581 / 30.946 / 32.273 |
| 4 MiB package GET | 1 | 401.24 | 32.821 / 62.154 / 84.836 / 86.612 | 485.72 | 28.220 / 46.165 / 51.425 / 74.540 |
| 4 MiB package GET | 2 | 370.48 | 29.671 / 51.378 / 77.458 / 218.357 | 496.24 | 26.468 / 52.505 / 58.677 / 60.340 |
| 4 MiB package GET | 3 | 333.73 | 29.389 / 75.649 / 236.996 / 263.338 | 487.99 | 28.831 / 45.213 / 59.775 / 100.809 |
| package Range 64 KiB | 1 | 1381.76 | 9.613 / 22.960 / 24.316 / 25.477 | 1818.28 | 7.351 / 14.738 / 17.555 / 19.782 |
| package Range 64 KiB | 2 | 1361.38 | 9.577 / 20.891 / 22.650 / 23.246 | 1810.76 | 6.937 / 21.775 / 24.845 / 29.775 |
| package Range 64 KiB | 3 | 1267.61 | 10.569 / 23.712 / 28.927 / 30.413 | 1789.66 | 6.852 / 16.959 / 27.862 / 31.658 |
| package HEAD | 1 | 2424.55 | 5.489 / 13.088 / 15.547 / 15.988 | 2622.83 | 4.461 / 11.508 / 18.099 / 19.535 |
| package HEAD | 2 | 1980.28 | 6.547 / 12.582 / 21.860 / 23.863 | 2761.58 | 4.450 / 10.049 / 16.428 / 17.622 |
| package HEAD | 3 | 1538.17 | 7.624 / 20.253 / 33.648 / 34.613 | 2372.15 | 5.245 / 11.736 / 13.344 / 15.159 |

### Real Alpine 3.23 client RT

Each sample is one fresh container running `apk update`, exact `apk search`, and `apk policy`.

| Target | Round 1 | Round 2 | Round 3 | p50 | p95 | kkRepo / Nexus p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Nexus | 1215.133 ms | 1428.791 ms | 1290.416 ms | 1290.416 ms | 1428.791 ms | — |
| kkRepo | 1037.012 ms | 1099.141 ms | 923.475 ms | 1037.012 ms | 1099.141 ms | 0.769x |

The real-client gate also passed. Container startup is included equally in both measurements, so
the absolute client times are less useful than the same-host comparison.

The complete machine-readable result, including correctness preflights, all 36 per-round HTTP
measurements, all client samples, and the empty gate-failure list, is checked in as
[`docs/perf-data/alpine-nexus-2026-08-15.json`](../../perf-data/alpine-nexus-2026-08-15.json).
Its SHA-256 is `6ab6530dda96b5a8e5e489f241b1ef9715baa44b6df426fe246f98867717a550`.

## 100k-row database growth verification

The database access paths were tested separately after the HTTP baseline. Identical logical data
was loaded into MySQL 8.0.46 and PostgreSQL 12.22: 100,000 packages with deliberately long common
path prefixes, 100,000 relations with 100 matching rows, 10,000 suite states with 100 pending,
100,001 published snapshots, 100,000 live group bindings, and two group snapshot stages. The
tables were analyzed before measurement.

Each row below is database executor time from seven consecutive warmed `EXPLAIN ANALYZE` runs.
The p50 and p95 use nearest-rank selection over those seven samples. JDBC mapping, network transfer,
index serialization, signing, blob access, and application work are outside this measurement.

| Query shape | Cardinality | MySQL p50 / p95 | PostgreSQL p50 / p95 | Bounded access path |
| --- | --- | ---: | ---: | --- |
| exact asset path | 1 of 100,000 | 0.000114 / 0.000121 ms | 0.058 / 0.185 ms | repository + SHA-256 path key, followed by full-path collision check |
| namespace late keyset page | 2,048 rows after row 90,000 | 5.260 / 5.800 ms | 1.979 / 2.774 ms | repository/distribution/package/id index |
| pending publication worker | 100 pending of 10,000, limit 256 | 0.403 / 0.441 ms | 0.317 / 0.805 ms | pending partial/generated-column index, then repository PK probes |
| snapshot cleanup candidates | 32 of 100,001, retain 3 | 0.851 / 0.944 ms | 0.681 / 1.272 ms | created-at cleanup index plus bounded PK probes for newer snapshots |
| relation lookup | 100 matches of 100,000 | 0.992 / 1.190 ms | 1.438 / 3.334 ms | repository/kind/token-hash/package index |
| published group token | one snapshot | 0.000110 / 0.000119 ms | 0.065 / 0.267 ms | snapshot primary key |
| group binding late page | 2,048 rows after row 90,000 | 3.850 / 5.600 ms | 0.880 / 0.947 ms | group/distribution/revision/token/id index |
| group publication stage lock | one staged snapshot | 0.0668 / 0.0944 ms | 0.059 / 0.136 ms | stage primary key with `FOR UPDATE` |
| orphan group stage scan | 2 stages with 100,000 live bindings | 0.0482 / 0.0562 ms | 0.086 / 0.187 ms | stage cleanup index with `SKIP LOCKED`; binding deletion is an FK cascade |

MySQL reports the two primary/unique-key point lookups as `Rows fetched before execution`; their
sub-microsecond values are optimizer/executor instrumentation, not an application-level latency
claim. The page, worker, cleanup, and relation rows are the useful capacity signals.

The first natural-plan pass exposed backend-specific optimizer regressions, so the final DAO does
not force one SQL spelling onto both databases:

| Remediated plan | Before | Final p50 | Change |
| --- | ---: | ---: | ---: |
| PostgreSQL namespace page: boolean keyset filter to row-value range | 67.443 ms | 1.979 ms | 34.1x faster |
| PostgreSQL pending worker: repository-first join to pending-first correlated PK probes | 22.878 ms | 0.317 ms | 72.2x faster |
| PostgreSQL snapshot cleanup: broad join to cleanup-index-first correlated probes | 862.993 ms | 0.681 ms | 1,267x faster |
| MySQL pending worker: unselective scan to generated pending index | 145.0 ms | 0.403 ms | 360x faster |
| MySQL snapshot cleanup: global candidate enumeration to bounded cleanup scan | 1,824.0 ms | 0.851 ms | 2,143x faster |
| MySQL group binding: snapshot join to token point lookup plus indexed page | 20.2 ms | 3.850 ms | 5.2x faster |

PostgreSQL's row-value keyset predicate is intentionally dialect-specific. The same expression on
MySQL scanned about 92,000 rows and took 348 ms, while MySQL's equivalent boolean range predicate
returned the late page at a 5.260 ms p50. Orphan cleanup scans the lightweight stage table rather
than all live bindings, so the 100,000 binding rows do not participate until a selected stage is
deleted by foreign-key cascade. Publication locks its stage row and cleanup skips locked stages,
preventing a cleanup/publication race from exposing a snapshot whose bindings were removed.

All individual samples, cardinalities, index descriptions, and pre-remediation observations are in
[`docs/perf-data/alpine-database-100k-2026-08-15.json`](../../perf-data/alpine-database-100k-2026-08-15.json).
Its SHA-256 is `abbe1f1ade8088a0fc881bdb20de875b8bce6dd078447b0d2c1d4b151934582b`.
These results show that the database hot queries remain bounded at 100k rows; a complete index
publication still necessarily performs O(n) package parsing/serialization and needs a separate
capacity target for CPU, heap, blob I/O, and total wall time.

## Findings and limits

- The immutable snapshot and asset caches keep warmed index reads off Alpine business tables;
  MySQL is still the durable source of truth and version watermark for cross-replica invalidation.
- MySQL and PostgreSQL integration gates seed 2,048 decoy package/relation rows and assert the
  optimizer-selected indexes for exact coordinate, bounded namespace keyset, and relation lookup
  shapes. Namespace publication reads in 2,048-row keyset pages over
  `(repository_id, distribution_name, component_name, architecture, package_name, id)` rather than
  using an unbounded sort or `OFFSET`.
- kkRepo exceeded the throughput gate on all six HTTP paths. Full package and Range reads also met
  the stricter package latency gates.
- A deliberately immediate first run showed large local variance while repository tasks and JVM
  paths were still warming. The recorded confirmation waits for index availability and task
  quiescence, but it remains a directional local result rather than an SLA.
- The 100k-row database run validates bounded query plans, but full publication time, heap bounds,
  generated index size, blob I/O, and solver behavior still need end-to-end measurement before
  setting production capacity limits.

## Reproduce

Upload the same APK and import the same RSA signing key into both hosted repositories, wait until
both signed indexes expose the package, then run:

```bash
python3 scripts/perf/compare-alpine-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:59090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --package-path v3.23/main/x86_64/kkrepo-alpine-benchmark-1.0.0-r0.apk \
  --requests 250 \
  --concurrency 16 \
  --warmups 32 \
  --rounds 3 \
  --enforce-gates \
  --output /tmp/alpine-performance.json
```

Use `--nexus-apk-command` and `--kkrepo-apk-command` to add matching real-client commands. The
runner alternates those commands between rounds, records each sample, and includes their p95 ratio
in the gate. Keep credentials outside the repository; only review and check in a sanitized result
file that contains no authorization material.
