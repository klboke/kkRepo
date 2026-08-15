# Local Alpine / APK Performance Baseline Against Nexus

This document records a targeted same-host baseline between kkRepo and Sonatype Nexus Repository
for the Alpine APK v2 implementation. It is a protocol hot-path comparison, not a production
capacity result. TLS, reverse proxies, remote OSS/S3, database high availability, mixed reads and
writes, and large 1k/10k/100k-package namespaces still need workload-specific testing.

The [Chinese version](../../zh/dev/alpine-performance-baseline.md) contains the same measurements.

## Test environment

- Time: `2026-08-15T06:23:26Z`.
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

The release gates enforced by the runner are:

- metadata paths: kkRepo throughput at least `0.80x` Nexus and p95 at most `1.25x` Nexus;
- package GET/Range: kkRepo throughput at least `0.90x` Nexus and p95 at most `1.15x` Nexus;
- real `apk` client flow: kkRepo p95 at most `1.25x` Nexus.

## Results

| Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p95 | kkRepo p95 | p95 ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| signed index GET | 1785.99 | 2313.67 | 1.295x | 13.419 ms | 14.752 ms | 1.099x |
| signed index HEAD | 1771.19 | 1988.80 | 1.123x | 14.331 ms | 14.063 ms | 0.981x |
| signed index 304 | 1165.28 | 2106.58 | 1.808x | 37.765 ms | 15.122 ms | 0.400x |
| 4 MiB package GET | 298.50 | 348.02 | 1.166x | 72.028 ms | 71.143 ms | 0.988x |
| package Range 64 KiB | 970.00 | 1092.03 | 1.126x | 44.059 ms | 31.153 ms | 0.707x |
| package HEAD | 1335.40 | 2034.07 | 1.523x | 18.223 ms | 15.631 ms | 0.858x |

Full-package median throughput was `1194.56 MiB/s` for Nexus and `1392.76 MiB/s` for kkRepo.
Every HTTP gate passed.

| Alpine 3.23 client flow | p50 | p95 | kkRepo / Nexus p95 |
| --- | ---: | ---: | ---: |
| Nexus | 1148.923 ms | 1739.615 ms | — |
| kkRepo | 1094.289 ms | 1096.176 ms | 0.630x |

The real-client gate also passed. Container startup is included equally in both measurements, so
the absolute client times are less useful than the same-host comparison.

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
- This one-package run validates fixed read-path overhead and a 4 MiB payload. Publication time,
  heap bounds, index size, and solver behavior at 1k/10k/100k packages must be measured separately
  before setting production capacity limits.

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
runner alternates those commands between rounds and includes their p95 ratio in the gate. Keep
credentials and generated result files outside the repository.
