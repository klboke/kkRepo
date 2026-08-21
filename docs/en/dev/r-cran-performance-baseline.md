# Local R / CRAN Performance Baseline Against Nexus

This document records the same-host release baseline for kkRepo's R / CRAN repository
implementation. It is a protocol hot-path and database-access-path comparison, not a production
capacity claim. TLS, reverse proxies, remote OSS/S3, mixed read/write traffic, and deployment-specific
CPU and memory limits still require workload-specific validation.

The [Chinese version](../../zh/dev/r-cran-performance-baseline.md) contains the same results.

## Test environment

- Time: 2026-08-21.
- Host: Intel Core i9-9880H, 64 GiB RAM, macOS 14.7.8 x86_64.
- Container runtime: Docker 29.4.0 (OrbStack); neither repository container had a separate CPU or
  memory limit.
- Reference: Sonatype Nexus Repository 3.94.0 with PostgreSQL 16.
- Candidate: the kkRepo 0.9.0 development build from this branch with PostgreSQL 16.15. A second
  kkRepo replica shared the database and file-backed blob volume. The database retained the
  one-million-row R projection from the index gate, while the HTTP fixture used its own repository.
  Nexus also used local file-backed blob storage.
- Both hosted repositories contained the same 4,196,303-byte source package with MD5
  `a3ec5c3433f446c96584e0f50dfa2494` and SHA-256
  `a138ae4d1bafeb4689bb703e19ae06a4d55fc4771d14ca15430b405cf7cc4d0c`.

## Correctness preflight

The comparison runner rejects a measurement before timing when the logical DCF package records,
package bytes, 64 KiB Range bytes, required status codes, or a declared `MD5sum` differ. The live
Nexus/kkRepo black-box suite also passed before the benchmark. It compares the uploaded source
package, generated index, HEAD/Range behavior, and conditional request behavior.

Nexus 3.94 omits `MD5sum` for this directly uploaded source package, while kkRepo emits the checksum
expected by CRAN tooling. The runner therefore compares every other DCF field and validates every
declared checksum independently against the byte-identical package. Nexus also returns `200` to the
tested `If-None-Match` request, whereas kkRepo returns `304`; both expected statuses are validated and
reported rather than normalized away.

Real-client E2E ran in isolated R 4.5.3 and R 4.6.1 containers. Both versions completed
`available.packages()`, dependency installation, package update, proxy installation through a group,
direct gzip/RDS proxy reads, and a two-replica consistency check. Cleanup dry-run and execution also
removed the selected package and rebuilt the published group view.

## HTTP method and gates

[`scripts/perf/compare-r-nexus.py`](../../../scripts/perf/compare-r-nexus.py) measures warmed
`PACKAGES.gz` GET, HEAD, and conditional requests plus full package GET, 64 KiB Range, and package
HEAD. Each scenario uses 32 warmups followed by 250 requests at concurrency 16. It runs three rounds,
alternates target order, and reports the median of each per-round statistic. The recorded run contains
9,000 timed HTTP requests.

The enforced release gates are:

- metadata throughput at least `0.80x` Nexus and p95 at most `1.25x` Nexus;
- package GET/Range throughput at least `0.90x` Nexus and p95 at most `1.15x` Nexus;
- when paired R client commands are supplied, kkRepo client-flow p95 at most `1.25x` Nexus.

## HTTP results

All correctness checks and all 9,000 timed requests passed. The machine-readable result has an empty
`gate_failures` list.

| Scenario | Nexus req/s | kkRepo req/s | Throughput ratio | Nexus p95 | kkRepo p95 | p95 ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `PACKAGES.gz` GET | 1449.35 | 1412.80 | 0.975x | 27.288 ms | 25.160 ms | 0.922x |
| `PACKAGES.gz` HEAD | 1408.73 | 1736.60 | 1.233x | 29.062 ms | 17.849 ms | 0.614x |
| `PACKAGES.gz` conditional | 1395.73 | 1811.50 | 1.298x | 18.829 ms | 16.819 ms | 0.893x |
| 4 MiB package GET | 276.92 | 442.26 | 1.597x | 88.223 ms | 48.998 ms | 0.555x |
| package Range 64 KiB | 1146.96 | 1355.08 | 1.181x | 30.996 ms | 26.484 ms | 0.854x |
| package HEAD | 1389.28 | 1864.14 | 1.342x | 26.040 ms | 21.392 ms | 0.822x |

The paired R 4.6.1 client flow starts a fresh container, calls `available.packages()`, installs the
source package into an isolated library, and verifies the installed version. Across three alternating
rounds, Nexus p50/p95 was 3400.573/7437.542 ms and kkRepo p50/p95 was
4669.828/5118.196 ms. The kkRepo/Nexus p95 ratio was `0.688x`, so the real-client gate passed.

The complete result, including correctness preflights, all per-round measurements, and real-client
samples, is checked in as
[`docs/perf-data/r-cran-nexus-2026-08-21.json`](../../perf-data/r-cran-nexus-2026-08-21.json).
Its SHA-256 is `dcaacb60171fbb9e0dc2621751267d65369de3e5ca75b871b83fe143cc3c696f`.

## Million-row database index gate

The database gate loads the same logical scale into MySQL and PostgreSQL: 1,000,000 package
projections, 100,000 relations, 10,002 suite states with 100 pending publications, 100,002 snapshots,
100,000 group bindings, 100,000 tombstones, and 100,000 leases. Tables are analyzed before
`EXPLAIN ANALYZE` is captured.

| Query shape | Cardinality | MySQL | PostgreSQL | Indexed access path |
| --- | --- | ---: | ---: | --- |
| exact coordinate | 1 of 1,000,000 | 0.000130 ms | 0.178 ms | repository + coordinate SHA-256 unique index |
| exact asset path | 1 of 1,000,000 | 0.000126 ms | 0.206 ms | repository + path SHA-256 unique index, then full-path check |
| late package keyset page | 2,048 rows after package 900,000 | 13.300 ms | 7.928 ms | namespace/package/id range; 2,048 rows examined |
| latest package version | 1 of 100 versions | 0.193 ms | 0.185 ms | package/version-order index |
| relation lookup | 100 of 100,000 | 17.000 ms | 4.062 ms | relation token index + 100 package point probes |
| pending publication | 101 of 10,002 suites | 0.645 ms | 0.289 ms | pending-suite index + repository PK probes |
| snapshot cleanup | 256 of 100,002 | 8.290 ms | 5.191 ms | cleanup index + exactly 3 retention rows per candidate |
| exact group binding | 1 of 100,000 | 0.000118 ms | 0.065 ms | snapshot/path unique index, then full-path check |
| late group-binding keyset page | 2,048 of 100,000 | 5.220 ms | 1.218 ms | indexed ID range; 2,048 rows examined |
| expired lease page | 103 of 100,000 | 0.326 ms | 0.059 ms | expiry index range |

Times are one warmed executor capture from the checked-in gate, not application-level latency
claims. MySQL reports unique-key rows as `Rows fetched before execution`, which explains the
sub-microsecond point-lookup instrumentation.

The first MySQL retention plan exposed a real growth defect: `EXISTS` allowed the optimizer to drop
the requested revision ordering and examine an average of 132 rows per candidate in the first batch,
with later batches able to grow further. The final schema adds an indexed publish-complete
discriminator and compares each candidate with the Nth-newest revision waterline. Both engines now
examine exactly three retention rows per candidate; the MySQL 256-row batch fell from 28.7 ms to
8.29 ms.

The exact coordinate/path, latest-version, relation, group-binding, and expired-lease queries must
use their selective indexes. Package and group iteration use keyset pagination; publication and
cleanup claim only a bounded batch. Full scans, unbounded sorts, and row counts proportional to the
million-row package table fail this gate. The checked-in
[`docs/perf-data/r-cran-database-1m-2026-08-21.json`](../../perf-data/r-cran-database-1m-2026-08-21.json)
records every selected index, actual row/loop count, executor time, and the remediation evidence for
both database engines. Its `gate_failures` list is empty and its SHA-256 is
`c4d90b197ffaf3910c5e5104221a9e0070fa127d8bf4bdb5338c56b5029ffb59`.

## Security scanning and cleanup performance semantics

Only hosted/proxied source `.tar.gz` assets enter the existing durable scan candidate/outbox path;
generated `PACKAGES.gz`, snapshots, and group bindings do not. Scanner-disabled uploads therefore do
not add scan-table writes. Audit does not block publication, while Enforce applies the existing
pending/block decision without creating an R-specific synchronous scanner path.

Cleanup uses the R version comparator, keyset pagination, usage/protection checks, and the protocol
delete path. A deletion writes the package tombstone and advances durable suite revisions; index and
group publication is asynchronous and fenced across replicas. This keeps the cleanup scan and rebuild
cost explicit instead of hiding it inside an HTTP delete transaction.

## Reproduce

After uploading the same package to both hosted repositories, run:

```bash
python3 scripts/perf/compare-r-nexus.py \
  --nexus-base-url http://127.0.0.1:48380/repository/<nexus-repo> \
  --kkrepo-base-url http://127.0.0.1:59380/repository/<kkrepo-repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --package-path src/contrib/perfR_1.0.0.tar.gz \
  --requests 250 --concurrency 16 --warmups 32 --rounds 3 \
  --enforce-gates --output /tmp/r-cran-performance.json
```

Use paired `--nexus-r-command` and `--kkrepo-r-command` arguments with
[`scripts/perf/r-client-flow.sh`](../../../scripts/perf/r-client-flow.sh) to add isolated real-R
install timing. Credentials stay outside the result file.

For database plans, stop kkRepo workers after the repositories have been created, then run the script
matching the target database:

```bash
docker exec -i <mysql-container> mysql -u... -p... kkrepo \
  < scripts/perf/r-database-mysql.sql

docker exec -i <postgres-container> psql -U kkrepo -d kkrepo \
  < scripts/perf/r-database-postgresql.sql
```

These scripts replace R performance-fixture rows in `r-hosted`/`r-group`; they are destructive test
fixtures and must not be run against a production database.
