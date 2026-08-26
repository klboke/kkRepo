# Local Go Hosted Performance Baseline Against Nexus

This document records the same-host baseline for Go hosted publication and hosted-first group
reads. It is a directional protocol-path comparison, not a production capacity claim. TLS, reverse
proxies, remote OSS/S3, database HA, load balancers, and mixed workloads still need
deployment-specific validation.

The [Chinese version](../../zh/dev/go-hosted-performance-baseline.md) contains the same results.

## Test environment

- Time: 2026-08-25.
- Host: MacBookPro16,1, 8 cores, 64 GiB RAM, macOS 14.7.8 x86_64.
- Container runtime: Docker 29.4.0 (OrbStack); neither repository container had a separate CPU or
  memory limit.
- Original reference: Sonatype Nexus Repository 3.94.0 using its local datastore and Docker
  volume. The final root-cause validation used Nexus Repository 3.94.0-12 with PostgreSQL 16 and
  the same Docker volume-backed blob store.
- Candidate: the kkRepo 0.9.0 development build from this branch, MySQL 8.0, and the file blob
  adapter on a Docker volume.
- Both targets used one hosted repository and a group containing only that hosted member. Cleanup
  had no policy attached and artifact scanning was disabled so the run measured the repository
  protocol and normal metadata/blob persistence paths.

## Method and correctness preflight

[`scripts/perf/compare-go-hosted-nexus.py`](../../../scripts/perf/compare-go-hosted-nexus.py)
builds deterministic Go module ZIPs and publishes the same 12 versions to both targets. Each archive
contains a canonical `go.mod`, a source file, and a stored 1 MiB payload. Before timing, the runner
requires Nexus and kkRepo to agree on version lists, selected versions, `go.mod` hashes, and the
name/size/SHA-256 manifest of ZIP responses for direct hosted and group reads.

The original baseline below used 32 warmups followed by 250 requests at concurrency 16 for each of
eight read scenarios. Three rounds alternated target order and the table reports the median
per-round statistics. Publication used 24 unique modules per target and round at concurrency 8,
including archive validation, three blob writes, and the atomic `.mod`/`.info`/`.zip` metadata
transaction. That run contains 12,144 timed HTTP requests. All correctness checks and timed
requests succeeded.

The runner now separates a pre-warm sample from steady-state results. By default it records 250
pre-warm requests, then warms each target until it has completed at least 2,000 requests **and** at
least 5 seconds before running three steady-state rounds. Target order still alternates. The
pre-warm sample follows correctness preflight and is not a process-cold measurement unless both
services are restarted externally.

## Original baseline results

Ratios above `1.0x` mean kkRepo completed more requests per second. Latencies are in milliseconds.

| Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| hosted list | 1182.50 | 634.87 | 0.537x | 8.858 | 12.566 | 25.489 | 65.226 |
| hosted info | 960.43 | 1714.04 | 1.785x | 11.361 | 6.474 | 33.318 | 20.064 |
| hosted mod | 1054.53 | 1463.42 | 1.388x | 9.709 | 7.578 | 30.384 | 28.116 |
| hosted zip (1 MiB payload) | 499.24 | 522.66 | 1.047x | 12.355 | 12.264 | 78.218 | 75.588 |
| hosted latest | 1225.00 | 1115.48 | 0.911x | 10.517 | 10.010 | 23.396 | 39.399 |
| group list | 931.13 | 393.85 | 0.423x | 12.048 | 26.749 | 44.224 | 78.880 |
| group latest | 332.10 | 259.68 | 0.782x | 32.657 | 38.120 | 108.493 | 99.818 |
| group zip (1 MiB payload) | 360.52 | 533.50 | 1.480x | 18.829 | 12.753 | 70.481 | 63.180 |
| hosted publish | 28.11 | 32.72 | 1.164x | 181.239 | 226.761 | 352.670 | 309.939 |

## Query-path optimization recheck

The list paths were first rechecked on the same 12-version repositories after changing hosted
lookup to project only version strings and letting groups consume hosted members' structured lists
directly. This run still used the original local-datastore Nexus reference.
The steady-state rows are medians of three 250-request rounds at concurrency 16. The adaptive
warmup completed 4,000 kkRepo and 6,000 Nexus requests for hosted list, and 8,000 kkRepo and 10,750
Nexus requests for group list; every target exceeded 5 seconds.

| Phase | Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| pre-warm | hosted list | 1486.55 | 397.21 | 0.267x | 8.047 | 27.861 | 20.166 | 76.367 |
| steady-state | hosted list | 1734.84 | 875.08 | 0.504x | 6.383 | 12.494 | 27.740 | 42.342 |
| pre-warm | group list | 1887.86 | 1672.62 | 0.886x | 5.757 | 6.731 | 24.462 | 27.202 |
| steady-state | group list | 1985.86 | 1553.17 | 0.782x | 6.055 | 7.346 | 17.874 | 25.659 |

The methodology is intentionally stronger than the original 32-request warmup, so the two tables
must not be treated as a strict before/after speedup calculation. It proved that the query and group
aggregation changes helped, but it did not explain the remaining hosted-list gap.

## Root-cause repair and external PostgreSQL recheck

Database tracing showed that both products still execute one indexed component lookup for every
hosted-list request. The measured MySQL and PostgreSQL statements were sub-millisecond and did not
explain the throughput difference. JFR identified the actual hot spot after the query returned:
kkRepo streamed an 85-byte generated list through the generic response copier, which allocated the
configured 1 MiB transfer buffer for every request. During an 8,000-request capture,
`TempBlobFiles.copyResponse` accounted for 72.48% of allocation pressure and the eight async task
threads allocated about 7.8 GiB in total. With an 8 KiB small-response buffer, those task-thread
allocations fell to about 84 MiB and the copier's allocation share fell to 1.64%.

The fix now chooses `min(configured transfer size, max(8 KiB, Content-Length))` when the response
length is known. Generated metadata therefore uses 8 KiB, while large artifacts and responses with
unknown length retain the configured 1 MiB streaming buffer. This path is node-local scratch memory
only and does not change multi-replica correctness, cache invalidation, or protocol semantics.

The final A/B used the same kkRepo MySQL database, blob volume, repositories, and Nexus 3.94.0-12
instance backed by external PostgreSQL 16. Only `/app/kkrepo.jar` changed between runs. Each run
used 250 pre-warm requests, adaptive warmup of at least 2,000 requests and 5 seconds per target,
then three 250-request rounds at concurrency 16. All version, info, mod, and ZIP correctness checks
passed.

| Scenario | kkRepo before req/s | kkRepo after req/s | Change | Nexus ratio before | Nexus ratio after | kkRepo p95 before | kkRepo p95 after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| hosted list | 293.38 | 535.34 | +82.5% | 0.627x | 0.929x | 194.017 | 49.251 |
| group list | 1084.88 | 1323.03 | +22.0% | 1.163x | 1.251x | 28.994 | 18.613 |

The complete post-fix steady-state result is below. Ratios use the Nexus measurement from the same
alternating run; values above `1.0x` mean kkRepo completed more requests per second.

| Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| hosted list | 576.07 | 535.34 | 0.929x | 20.595 | 23.182 | 56.620 | 49.251 |
| hosted info | 611.57 | 944.43 | 1.544x | 18.049 | 12.384 | 57.066 | 30.796 |
| hosted mod | 733.88 | 989.21 | 1.348x | 14.739 | 13.364 | 55.527 | 27.824 |
| hosted zip (1 MiB payload) | 369.14 | 716.90 | 1.942x | 22.438 | 17.090 | 56.098 | 43.243 |
| hosted latest | 476.43 | 643.54 | 1.351x | 18.183 | 14.669 | 104.275 | 60.539 |
| group list | 1057.76 | 1323.03 | 1.251x | 10.490 | 9.413 | 24.397 | 18.613 |
| group latest | 835.83 | 1026.38 | 1.228x | 13.286 | 11.362 | 37.779 | 30.102 |
| group zip (1 MiB payload) | 365.79 | 822.25 | 2.248x | 21.852 | 16.033 | 51.856 | 29.534 |

The repaired hosted list is within 7.1% of Nexus throughput and has a lower p95 latency in this run;
group list and every other steady-state scenario exceed Nexus throughput. The result also rules out
an external-database mismatch as the root cause: the material gap was response-buffer allocation,
not the indexed version query.

The benchmark also caught a real concurrent publication defect before this result was recorded. A
MySQL browse-node deadlock could roll back the current transaction while an ignored lock exception
allowed later release assets to continue. The final implementation propagates that transient error
to the transaction retry, which replays the complete `.mod`/`.info`/`.zip` set. A 12-version
concurrent preparation and an 8-way unique-module publication smoke test passed after the fix.

## Reproduce

Create equivalent Go hosted repositories and hosted-only groups in Nexus and kkRepo, then run:

```bash
python3 scripts/perf/compare-go-hosted-nexus.py \
  --nexus-hosted-url http://127.0.0.1:28090/repository/<nexus-hosted> \
  --nexus-group-url http://127.0.0.1:28090/repository/<nexus-group> \
  --kkrepo-hosted-url http://127.0.0.1:61090/repository/<kkrepo-hosted> \
  --kkrepo-group-url http://127.0.0.1:61090/repository/<kkrepo-group> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --requests 250 --concurrency 16 --prewarm-requests 250 \
  --warmups 2000 --warmup-seconds 5 --rounds 3 \
  --publish-requests 24 --publish-concurrency 8 \
  --output /tmp/go-hosted-performance.json
```

Use a fresh module name or fresh repositories for every preparation run because Nexus-compatible
write policies can reject redeployment. Credentials are used only for HTTP headers and are not
written to the JSON report. To recheck reads against already prepared repositories, add
`--skip-prepare --skip-publish`.
