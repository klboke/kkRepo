# R / CRAN Repository Guide

kkRepo supports CRAN-style source package repositories through `r-hosted`, `r-proxy`, and
`r-group`. All client URLs use the normal `/repository/<name>/...` layout. Hosted and group
repositories publish source packages under `src/contrib` and expose a generated
`src/contrib/PACKAGES.gz`; a proxy can additionally cache any safe path exposed by its upstream.

## Repository types

| Purpose | Recipe | Behavior |
| --- | --- | --- |
| Private source packages | `r-hosted` | Immutable `.tar.gz` uploads and deterministic generated `PACKAGES.gz` |
| Upstream CRAN mirror | `r-proxy` | Read-through cache for `PACKAGES*`, source packages, binary packages, and archive paths |
| Unified source endpoint | `r-group` | Ordered hosted/proxy members, merged `PACKAGES.gz`, snapshot-bound `.tar.gz` reads |

Hosted and group support is deliberately source-only in this release. Windows `.zip`, macOS
`.tgz`, uncompressed `PACKAGES`, and `PACKAGES.rds` remain available only by addressing an
`r-proxy` directly. A group never falls through to a different member after publishing an index:
each package path is bound to the member revision and checksum selected by that snapshot.

## Create repositories

Create an `r-hosted` repository for private packages. Create an `r-proxy` with a CRAN-style remote
root such as `https://cloud.r-project.org`, then create an `r-group` whose members are ordered with
the private hosted repository first and the proxy second. Group members must all use the R format;
cycles are rejected.

Repository read, browse, upload, and delete authorization uses the existing repository privileges.
For automation, prefer a user or CI token scoped to the target hosted repository.

## Configure R

Set the group endpoint in the site or user `.Rprofile`:

```r
local({
  repos <- getOption("repos")
  repos["KKRepo"] <- "https://repo.example.com/repository/r-group"
  options(repos = repos)
})
```

The first release serves hosted/group packages as source packages, so explicitly select source
when validating from a platform that would otherwise prefer a binary repository:

```r
available.packages(
  repos = "https://repo.example.com/repository/r-group",
  type = "source"
)
install.packages(
  "acmepkg",
  repos = "https://repo.example.com/repository/r-group",
  type = "source"
)
update.packages(
  repos = "https://repo.example.com/repository/r-group",
  type = "source",
  ask = FALSE
)
```

For private repositories, use normal HTTP Basic authentication or a read-scoped token through the
credential mechanism approved for your deployment. Do not commit credentials in `.Rprofile`.

## Publish source packages

Build a normal R source package on a trusted build worker, then upload the resulting archive to its
canonical coordinate:

```bash
R CMD build acmepkg

curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: application/x-gzip' \
  --upload-file acmepkg_1.2.3.tar.gz \
  https://repo.example.com/repository/r-hosted/src/contrib/acmepkg_1.2.3.tar.gz
```

You can also upload a source archive through the Admin UI or Components API. kkRepo validates the
gzip/tar structure and bounded `DESCRIPTION` metadata without executing package code. The package
name and version must match the filename and URL. Coordinates are immutable: retrying identical
bytes is idempotent, while different bytes at the same package/version are rejected.

The upload advances a durable namespace revision. A fenced publisher writes a byte-stable
`PACKAGES.gz` snapshot and atomically makes it current. Readers see either the previous complete
snapshot or the new complete snapshot, never a partially rebuilt index.

## Proxy and group behavior

An R proxy forwards only normalized, policy-approved relative paths and uses the repository's
configured upstream credentials; client credentials are never forwarded. `PACKAGES.gz` is parsed
with bounded DCF limits into a rebuildable database projection. `PACKAGES.rds` is cached as opaque
bytes and is never deserialized in the server process.

Group metadata selects one record per package according to R's version ordering and member
priority. Proxy package bytes are fetched lazily and checked against the MD5 declared in the bound
`PACKAGES.gz` record. Invalid or unverifiable proxy records fail closed instead of being exposed by
the group.

## Cleanup and security scanning

Cleanup policies can target R hosted repositories. Retain-count ordering uses R's numeric version
rules (for example, `0.9 < 0.75`) rather than lexicographic or SemVer ordering. A delete first
publishes an index that no longer advertises the package, then retires the package projection.
Generated metadata and group repositories are not independent cleanup subjects.

Only verified source package archives enter the artifact scanning pipeline. `PACKAGES*`, generated
snapshots, opaque proxy binary packages, and arbitrary proxy files are excluded. R ecosystem
vulnerability coverage is reported as partial because the scanner has no dedicated CRAN advisory
matcher; SBOM and generic/native findings still follow the configured Audit or Enforce policy.

## Operations and migration

The repository detail API exposes desired/published revisions, the latest publish error, and proxy
projection state. Administrators can request a rebuild; the operation records durable work and is
safe when multiple replicas compete for it.

Nexus 3.94 R hosted/proxy/group definitions are recognized. Hosted content is restored only when
the source datastore shape proves a canonical source-package path, typed identity, size, and
checksum. Generated indexes, group bindings, leases, and caches are rebuilt on kkRepo. Unknown
versions or shapes stay in `NEEDS_MANUAL_ACTION` rather than being guessed.

## Current limits

- Hosted/group support is limited to source `.tar.gz` packages and `PACKAGES.gz`.
- Automatic CRAN `Archive` creation and `renv`/`remotes` old-version aliases are not implemented.
- kkRepo does not run `R CMD build`, `R CMD check`, installation hooks, native code, or package tests.
- Bioconductor, R-universe, and Posit Package Manager-specific release semantics are not inferred
  from generic CRAN-like paths.

See the [implementation design](../../zh/dev/r-cran-repository-design.md),
[performance baseline](../dev/r-cran-performance-baseline.md), and
[R client recipe](../client-recipes.md#r--cran) for validation and reproducibility details.
