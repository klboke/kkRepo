# Composer Proxy Metadata Performance Baseline

This records the local investigation of [Issue #425](https://github.com/klboke/kkRepo/issues/425)
and the candidate fix in [PR #426](https://github.com/klboke/kkRepo/pull/426). It measures metadata
retrieval and dependency resolution with warm caches, excluding archive download/extraction and
project scripts/plugins. It is not a production capacity or complete installation benchmark.

The [Chinese version](../../zh/dev/composer-performance-baseline.md) contains the same measurements.

## Environment And Workload

- Date: October 22, 2026 (Asia/Shanghai).
- Unmodified main: `2d0ce09290dbd9a2f47a5d4df7a373337a503c36`.
- Fixed candidate: `662716578ce4a835ab0f3029c7dca785e52eb4bf`, built with JDK 25.
- kkRepo: local Docker JVM runtime, PostgreSQL 16, File blob storage, maximum Java heap 3 GiB.
- Nexus: `3.94.0`, local Docker JVM runtime, default H2 database, local blob storage,
  maximum Java heap 1 GiB. This comparison does not control for database or heap differences.
- Client: PHP `8.5.8`, Composer `2.10.2`, with `config.platform.php=8.3.0`.
- Workload: the report's 27 direct requirements, including the PHP requirement; use
  `symfony/twig-bundle:^7.4` consistently for every target. All compared runs resolved the same
  99 package names, versions, and dist references.
- Both proxies used `https://repo.packagist.org/`, content/metadata cache TTLs of 1440 minutes,
  anonymous reads, and direct local HTTP without Nginx. Each target had its own Composer cache.

## Root Cause And Change

Previously, reading cached p2 metadata persisted a private dist-route asset for every historical
version, including requests that ultimately returned `304`. Each unchanged route repeated blob/asset
persistence, component-search updates, and the shared repository cache-version bump.

The fix reads existing route assets and blob size/SHA-256 values in batches of at most 256 paths.
Only missing or changed routes use the existing transactional writer. Decisions use shared SQL state,
so a new replica also avoids rewriting unchanged routes without relying on a process-local cache.
Missing bindings and interrupted fills are repaired on the next metadata request. Updated upstream
URLs/checksums still change routes; removed versions' routes remain available to existing lock files.

No schema migration or extra configuration is required. First-time fills still create the required
routes; this measurement does not establish the fixed candidate's cold-fill performance.

## Before And After

Main and the fixed candidate ran sequentially against the same warm database, blob store and client
cache. Each invocation removed the project lock file to force resolution while retaining caches.

| Warm operation | Main | Fixed candidate |
| --- | ---: | ---: |
| Metadata retrieval and dependency resolution | 109.126 s | 9.101 s; repeat 5.723 s |
| `p2/symfony/console.json` GET | 5.731 s | 0.070 s |
| Same p2 conditional GET (`304`) | 7.692 s | 0.063 s |
| Route asset updates per resolution | 25,276 | 0 |
| Component-search upserts per resolution | 25,276 | 0 |
| Repository cache-version bumps per resolution | 25,276 | 0 |

SQL counts came from PostgreSQL `pg_stat_statements`. The p2 response bodies were byte-identical
across main and the fix. Each main GET/304 wrote 685 route records; the fixed requests wrote none.
The zero counts refer to these route-related writes, not all database activity.

## Nexus And Packagist Comparison

A follow-up used fresh project directories without `vendor`, warm client/server metadata caches,
and three sequential rounds in the order Nexus, fixed kkRepo, direct Packagist. Each round removed
only the project lock file. Package names, versions and dist references matched in all nine runs.

| Warm resolution | Run 1 | Run 2 | Run 3 | Median |
| --- | ---: | ---: | ---: | ---: |
| Nexus 3.94.0 | 9.491 s | 9.083 s | 9.176 s | **9.176 s** |
| Fixed kkRepo | 4.207 s | 4.471 s | 4.081 s | **4.207 s** |
| Direct Packagist | 7.569 s | 9.081 s | 9.191 s | **9.081 s** |

Nexus's initial run with an empty repository/client cache took 15.250 s. The warm results apply to
these local deployments; they are not a general performance ranking across storage backends, remote
databases, S3, network conditions or concurrent workloads. Live Packagist contents can change later.

## Reproduce The Measurement

1. Start with the `composer.json` from Issue #425, using the same 27 requirements,
   `symfony/twig-bundle:^7.4` and `config.platform.php=8.3.0` for every target.
2. Create separate disposable project directories and Composer cache directories. For each proxy,
   set its repository root URL and disable default Packagist; omit that override for direct Packagist.
3. Run once to populate caches. For warm runs, retain the repository and client caches, remove the
   disposable project's generated `composer.lock`, and run the following command unchanged:

```bash
composer update --no-install --no-scripts --no-plugins \
  '--ignore-platform-req=ext-*' --no-interaction --no-audit -vvv --profile
```

4. Measure process wall time, retain each generated lock file, and compare package names, versions
   and dist references. Dist URL hosts differ by target. Run targets sequentially to avoid benchmark
   contention, repeat three times and report all samples plus the median. Keep client versions and
   platform overrides unchanged; do not include dependency downloads or project scripts in this timing.

## Correctness Checks

- MySQL 8.0 and PostgreSQL 12 tests with two application replicas cover warm proxy/group GET, HEAD,
  conditional GET, unchanged route bindings/timestamps/watermarks, and downloading refreshed bytes
  through the other replica after an upstream route change.
- Live Nexus 3.94.0 compatibility checks cover repeated metadata bytes/validators, conditional `304`,
  public dist paths and archive download.
- A separate Composer install downloaded and extracted all 99 packages, with scripts/plugins disabled
  and extension platform requirements ignored. Its time is not included in the resolution tables.

See the [Composer repository guide](../repository-guides/composer-php.md) for client configuration.
