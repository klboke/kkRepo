# Admin storage usage performance

Measured locally on 2026-09-19 using the production `StorageStatisticsDao` and
`StorageStatisticsService`, not a substitute SQL-only implementation. Each backend
contained **1,000,000 asset rows and 1,000,000 blob rows**, the full migrated schema
through V54, 100 repositories, and 10 blob stores. The dataset includes 200,000
shared asset references, 100,000 non-asset document references and 100,000 blobs
pending GC. Repository 1 owns 600,000 assets. Payload columns include 256-byte JSON
fixture data so covering-index scans are compared against realistic row widths.

## Environment and method

- MySQL 8.0.46 and PostgreSQL 12.22 (the minimum supported PG major version).
- Separate disposable x86_64 Docker containers, each limited to 2 CPUs / 3 GiB RAM.
- Both containers shared a development host; this was not an isolated benchmark
  machine. Do not interpret the numbers as a controlled comparison of engines.
- MySQL buffer pool 1 GiB; PostgreSQL shared buffers 1 GiB. Persistent container
  filesystem, not tmpfs. Docker host: 16 CPUs, approximately 16 GiB RAM.
- JDK 25.0.3 outside Docker; JDBC over loopback forwarded ports. The standalone
  persistence factory opens JDBC connections per aggregate; timings include that
  connection overhead. The application runtime uses its normal pool.
- 20 uncached samples per aggregate; 16 simultaneous callers on one cold service;
  1,000 cache-hit calls, each retrieving both store and repository snapshots.
- Initial data is analyzed; PG is vacuumed before the first run. Then 20,000 rows
  spread across **each** table are updated, with no subsequent VACUUM, and 20
  samples per aggregate are repeated. This exposes PG visibility-map/heap costs.
- The first load is an application-cache miss; **it is not a cold-disk benchmark**.
  Seed and EXPLAIN work have already accessed database pages. Measurements are
  local observations, not production SLAs or a cross-database ranking.

## Results

All times are milliseconds. Rows marked “pair” include both inventory types.

| Measurement | MySQL p50 / p95 | PostgreSQL p50 / p95 |
| --- | ---: | ---: |
| Blob aggregate, no application cache | 335.926 / 347.999 | 98.432 / 104.115 |
| Repository aggregate, no application cache | 248.793 / 261.695 | 78.796 / 88.792 |
| 16 concurrent cold service pairs | 643.703 / 647.584 | 200.007 / 203.943 |
| Cached service pair | 0.018 / 0.067 | 0.022 / 0.069 |
| Blob aggregate after scattered updates | 356.970 / 378.525 | 217.840 / 244.462 |
| Repository aggregate after scattered updates | 250.252 / 273.659 | 199.849 / 223.475 |

The 16 concurrent callers issued **exactly two** aggregate queries in each
backend, one per inventory type. The next 1,000 cached pairs issued **zero**
additional aggregate queries. Totals, bytes, store/repository cardinalities and
pending-GC counts were asserted against the generated fixture.

The packaged server was also started against each million-row fixture. Both
authenticated HTTP endpoints returned 200 with the expected 10 stores / 100
repositories and one million objects/assets. This was an integration smoke check,
not an HTTP latency benchmark. The fixture contains synthetic metadata; no million
object upload or S3 bandwidth test is implied.

MySQL used covering scans on `idx_asset_blob_usage` and
`idx_asset_repository_usage`. PostgreSQL used parallel index-only scans on those
indexes. After the scattered updates, PG's first repository plan needed 379,992
heap fetches and took 592 ms; subsequent samples remained about 200–223 ms. The
blob plan needed 359,994 heap fetches. “Index-only scan” does not guarantee zero
heap access on actively written PostgreSQL tables.

Raw JVM timings and post-update plans:
[MySQL](performance/admin-storage-usage-mysql.txt),
[PostgreSQL](performance/admin-storage-usage-postgresql.txt).
The initial setup/plan outputs are retained under `target/admin-storage-performance`
when the fixture script is run.

## Reproduce

The fixture script refuses to reuse existing containers. It never drops an
unrelated database. Keep the two backends idle during measurement. It applies all
production migrations directly as SQL; correctness/Flyway tests separately run
the migration chain through Flyway on both database engines.

```sh
python3 scripts/perf/run-admin-storage-usage.py mysql
python3 scripts/perf/run-admin-storage-usage.py postgresql

mvn -B -ntp -pl server -am -Dtest=StorageUsagePerformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dkkrepo.usagePerf.url=jdbc:mysql://127.0.0.1:23306/kkrepo_usage_perf?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC' test

mvn -B -ntp -pl server -am -Dtest=StorageUsagePerformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.usagePerf.url=jdbc:postgresql://127.0.0.1:25432/kkrepo_usage_perf \
  -Dkkrepo.usagePerf.username=usageperf test

# Remove only these two disposable benchmark containers and their volumes.
python3 scripts/perf/run-admin-storage-usage.py cleanup
```

The local-only fixture password defaults in the opt-in test and fixture script;
use `kkrepo.usagePerf.password` when connecting a differently configured fixture.
A repeated run without reseeding starts with updated heap pages, so distinguish
that run from the initial vacuumed baseline.

## Design implications and limits

A fresh aggregate is O(inventory size); a covering index does not make exact
counts O(1). The UI therefore fetches usage independently of its configuration
lists, and the service caches snapshots for 30 seconds with one loader per node
and inventory type. Authorization filtering remains outside the cached result.
There are no aggregate queries on Browse or artifact-write paths.

V54 adds one secondary index to `asset`; it costs disk space and index maintenance
on insert/delete/size changes. The tests above measure reads and post-update heap
behavior, not sustained upload throughput or online index construction on a live
million-row deployment. Multi-replica caches each refresh independently; these
figures prove per-node coalescing, not a cluster-wide single query. Consider a
shared scheduled snapshot if deployment size makes per-node refresh expensive.

Cache-hit figures measure the service and its filtering, not HTTP, authentication
or browser rendering. They must not be quoted as end-to-end endpoint latency.
