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
- Reference: Sonatype Nexus Repository 3.94.0 using its local datastore and Docker volume.
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

Eight warmed read scenarios each use 32 warmups followed by 250 requests at concurrency 16. Three
rounds alternate target order and the table reports the median per-round statistics. Publication
uses 24 unique modules per target and round at concurrency 8, including archive validation, three
blob writes, and the atomic `.mod`/`.info`/`.zip` metadata transaction. The recorded run therefore
contains 12,144 timed HTTP requests. All correctness checks and timed requests succeeded.

## Results

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

The byte-serving paths (`info`, `mod`, and ZIP) and complete concurrent publication were at or above
Nexus throughput in this run. kkRepo's non-materialized hosted/group list aggregation and group
latest lookup were slower; their results remain visible here instead of being normalized away. This
baseline is intended to make future metadata caching or materialization work measurable without
changing protocol semantics.

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
  --requests 250 --concurrency 16 --warmups 32 --rounds 3 \
  --publish-requests 24 --publish-concurrency 8 \
  --output /tmp/go-hosted-performance.json
```

Use a fresh module name or fresh repositories for every preparation run because Nexus-compatible
write policies can reject redeployment. Credentials are used only for HTTP headers and are not
written to the JSON report.
