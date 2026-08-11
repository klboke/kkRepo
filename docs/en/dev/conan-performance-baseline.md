# Local Conan 2 Performance Baseline Against Nexus

This document records a targeted same-host comparison between kkRepo and Sonatype Nexus
Repository for the Conan 2 implementation. It is a protocol hot-path gate, not a production
capacity result. TLS, reverse proxies, remote OSS/S3, database HA, multi-replica load balancing,
and mixed read/write workloads still require deployment-specific testing.

The [Chinese version](../../zh/dev/conan-performance-baseline.md) contains the same measurements.

## Test Environment

- Time: `2026-08-11T15:41:19Z` (MySQL) and `2026-08-11T16:38:54Z` (PostgreSQL).
- Host: Intel Core i9-9880H, 64 GiB RAM, macOS 14.7.8 x86_64; Docker 29.4.0.
- Reference: Sonatype Nexus Repository `3.94.0`, backed by PostgreSQL 16.
- Candidates: the kkRepo `0.7.0` development build from this branch, once with MySQL 8.0 and
  once with PostgreSQL 17. No tested process/container had a separate CPU or memory limit.
- Both targets used local file-backed blob storage and the same Conan 2.31.2 logical fixture:
  `kkrepo-conan-performance/1.0.0@kkrepo/stable`, one recipe revision, one binary revision, and a
  `4,194,304` byte payload inside a `4,195,879` byte `conan_package.tgz`.

## Method And Correctness Preflight

The [comparison runner](../../../scripts/perf/compare-conan-nexus.py) covers ping, recipe search,
RREV/PREV latest and list, recipe/package file lists, package search, full package GET, a 64 KiB
Range, and Nexus-compatible HEAD behavior. Each scenario performs 32 warmups followed by 250
requests at concurrency 16. It runs three rounds, alternates target order, and reports the median.

Before timing, the runner requires equal status and canonical semantics at both targets:

- exact recipe-search results and package-search `(package ID, content)` entries;
- exact latest RREV/PREV values and revision/file-list membership;
- an equal logical tar tree, member sizes, and member SHA-256 values for the package archive;
- exactly 65,536 bytes for the Range request; and
- HTTP `404` for HEAD, matching Nexus 3.94 rather than synthesizing a GET-shaped response.

Tar container metadata can differ after independent uploads, so archive preflight compares the
logical tree rather than requiring byte-identical gzip headers. A real Conan 2.31.2 client also
completed login, upload, list, cache-clear, download/install, and group/proxy install against both
kkRepo database backends before the timed runs.

The metadata gate requires kkRepo throughput of at least `0.80x` Nexus and p95 no higher than
`1.25x`. Full GET and Range require at least `0.90x` throughput and no more than `1.15x` p95.
HEAD is a compatibility assertion, not a successful file-read throughput gate.

## PostgreSQL Result

| Scenario | Nexus req/s | kkRepo req/s | Throughput | Nexus p95 | kkRepo p95 | p95 ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ping | 2431.90 | 2804.03 | 1.153x | 14.272 ms | 9.523 ms | 0.667x |
| recipe search | 1836.92 | 2553.25 | 1.390x | 17.553 ms | 10.319 ms | 0.588x |
| recipe latest | 1923.89 | 1720.92 | 0.895x | 15.081 ms | 15.160 ms | 1.005x |
| recipe revisions | 1848.03 | 2270.21 | 1.228x | 17.102 ms | 9.685 ms | 0.566x |
| recipe files | 1265.82 | 2208.11 | 1.744x | 23.579 ms | 10.587 ms | 0.449x |
| package search | 1899.59 | 1999.74 | 1.053x | 18.674 ms | 14.537 ms | 0.778x |
| package latest | 994.52 | 1537.61 | 1.546x | 34.377 ms | 19.094 ms | 0.555x |
| package revisions | 1047.89 | 1869.82 | 1.784x | 32.311 ms | 13.189 ms | 0.408x |
| package files | 1017.28 | 2108.30 | 2.072x | 30.852 ms | 11.055 ms | 0.358x |
| package GET | 269.02 | 453.86 | 1.687x | 151.584 ms | 51.426 ms | 0.339x |
| package Range 64 KiB | 1068.67 | 1246.14 | 1.166x | 25.873 ms | 20.711 ms | 0.800x |
| HEAD compatibility (`404`) | 104.66 | 2201.72 | 21.037x | 197.649 ms | 13.900 ms | 0.070x |

Every PostgreSQL scenario passed its gate. The narrowest throughput margin was recipe latest at
`0.895x`; its p95 remained within the metadata gate at `1.005x`. Full package GET was `1.687x`
Nexus throughput with `0.339x` p95.

## MySQL Result

| Scenario | Nexus req/s | kkRepo req/s | Throughput | Nexus p95 | kkRepo p95 | p95 ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ping | 3173.70 | 3110.18 | 0.980x | 9.553 ms | 8.516 ms | 0.891x |
| recipe search | 1485.89 | 2493.93 | 1.678x | 38.354 ms | 9.163 ms | 0.239x |
| recipe latest | 2292.72 | 2344.56 | 1.023x | 12.181 ms | 9.647 ms | 0.792x |
| recipe revisions | 2409.73 | 2430.94 | 1.009x | 14.474 ms | 9.091 ms | 0.628x |
| recipe files | 2366.45 | 2095.00 | 0.885x | 13.047 ms | 11.332 ms | 0.869x |
| package search | 2273.58 | 2471.49 | 1.087x | 13.077 ms | 9.029 ms | 0.690x |
| package latest | 1814.53 | 2513.84 | 1.385x | 19.117 ms | 8.529 ms | 0.446x |
| package revisions | 1936.61 | 2541.28 | 1.312x | 14.048 ms | 8.393 ms | 0.597x |
| package files | 1789.46 | 2276.05 | 1.272x | 15.190 ms | 9.947 ms | 0.655x |
| package GET | 357.13 | 599.98 | 1.680x | 59.550 ms | 34.652 ms | 0.582x |
| package Range 64 KiB | 1497.42 | 1768.92 | 1.181x | 25.667 ms | 13.614 ms | 0.530x |
| HEAD compatibility (`404`) | 188.88 | 3500.74 | 18.534x | 103.716 ms | 8.712 ms | 0.084x |

Every MySQL scenario passed. The narrowest throughput margin was recipe file listing at `0.885x`,
with a lower p95 (`0.869x`). Full package GET reached `1.680x` Nexus throughput with `0.582x` p95.

## Database Access-Path Gate

Conan state is normalized and every request/worker lookup has a leading selective index. The
MySQL/PostgreSQL integration contracts assert both index definitions and optimizer plans for the
critical exact, prefix, and file lookups. The principal shapes are:

| Access shape | Index |
| --- | --- |
| Exact recipe and prefix search | `uk_conan_recipe_coordinate`; `idx_conan_recipe_name_page` |
| RREV exact/list/latest | `uk_conan_rrev`; `idx_conan_rrev_page`; latest FK to the revision PK |
| Package ID exact/list | `uk_conan_package`; `idx_conan_package_list` |
| PREV exact/list/latest | `uk_conan_prev`; `idx_conan_prev_page`; latest FK to the revision PK |
| Revision file exact/list | `uk_conan_revision_file`; `idx_conan_file_list` |
| Upload resume and expired-session claim | `uk_conan_upload_session`; `idx_conan_upload_claim` |
| Distributed coordinate lease | `PRIMARY(repository_id, coordinate_hash)`; `idx_conan_lease_expiry` |
| Group source binding/invalidation | `uk_conan_group_binding`; `idx_conan_group_member` |
| Bearer lookup/expiry | token primary key; `idx_conan_token_expiry` |
| Browse exact/root/child | `uk_browse_node_path`; `idx_browse_node_root`; `idx_browse_node_parent` |

Hash-index hits always recheck the stored canonical value, so fixed-width indexes do not turn a
hash collision into an identity match. Ordered multi-part database keys use a versioned,
length-prefixed encoding that distinguishes null/empty values and never stores NUL, which keeps
the same identity valid on MySQL and PostgreSQL. Pagination is keyset-based and bounded; Browse
reads persisted write-time projections instead of scanning or parsing Conan asset paths.

## Reproduce

Upload the same complete RREV/PREV to both hosted repositories, then run:

```bash
python3 scripts/perf/compare-conan-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:19090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --name kkrepo-conan-performance \
  --version 1.0.0 \
  --user kkrepo \
  --channel stable \
  --recipe-revision <rrev> \
  --package-id <package-id> \
  --package-revision <prev> \
  --requests 250 \
  --concurrency 16 \
  --warmups 32 \
  --rounds 3 \
  --enforce-gates \
  --output /tmp/conan-performance.json
```

Credentials are passed only to the local process. Do not commit commands or result files that
contain real passwords.
