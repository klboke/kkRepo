# Minimum Release Age (Quarantine)

A **proxy repository** can refuse to serve versions that were published less than a configurable number of minutes ago. Compromised releases (a hijacked maintainer account, a malicious package) are usually detected and withdrawn upstream within hours, so a cooldown of 48 hours (`2880` minutes) means a poisoned release is normally gone before any build can pull it through kkrepo. Hosted repositories are unaffected, and `0` (the default) disables the policy. For Go the age is measured from the commit time, not the publication time, which is a weaker guard (see [Upstream requirements](#upstream-requirements)).

Set **Proxy settings → Minimum release age (min)** on an npm, Go, Maven or PyPI proxy, or `proxy.minimumReleaseAgeMinutes` in `/internal/repositories`. Groups have no setting of their own: they apply the policy of their proxy members and never filter hosted members.

## Behavior

| Format | Publish time (Go: commit time) | Index / metadata | Direct download of a too-new version |
| --- | --- | --- | --- |
| npm | `time` in the packument | versions hidden, dist-tags repaired | 404 |
| Go | `Time` in `<version>.info` (the **commit** time, see below) | hidden from `@v/list`; `@latest` falls back to the newest mature release (404 if none) | 403 for `.info`, `.mod`, `.zip` |
| Maven | upstream `Last-Modified` of the version's `.pom` | hidden from artifact-level `maven-metadata.xml`; `latest`/`release` recomputed; metadata checksums are derived from the rewritten document | 403 for every file, checksum and signature of the version |
| PyPI | PEP 700 `upload-time` | files hidden from the project page (404 if none remain) | 403 |

The 403 body names the artifact and how long until it becomes available, and responses carry `Cache-Control: no-store`. A denial is never cached as "not found", so the version appears as soon as it matures. npm keeps its existing 404 so npm clients fall back to older versions.

A version whose publish time cannot be determined is **treated as quarantined** (fail closed). Maven SNAPSHOT versions and SNAPSHOT metadata are exempt because they are mutable and have no meaningful publish time.

## Client impact

- **Go**: `go` treats 403 as a hard error and does not try the next `GOPROXY` entry (unless separated by `|`). This is intentional so a too-new version cannot be fetched from the origin by accident.
- **Maven**: a 403 fails the build with a transfer error rather than being remembered as missing. Pinned versions that are still quarantined therefore fail loudly.
- **PyPI**: `pip` resolves against the filtered project page, so it picks an older release; pinning a quarantined file fails with HTTP 403.

## Upstream requirements

- **PyPI** needs an upstream that serves the PEP 691 JSON simple index with PEP 700 `upload-time` (for example pypi.org). Upstreams that only serve HTML (Nexus, Artifactory) fail with 502 and an explanatory message instead of silently serving unfiltered content.
- **Maven** relies on the upstream preserving the original `Last-Modified` of files. A mirror that stamps its own sync time makes every version look new.
- **Go** measures **commit age**, not publication age. In the GOPROXY protocol `Time` is the commit time for ordinary tagged versions as well as pseudo-versions, and the Go toolchain reads it from `git log %ct`. The cooldown therefore protects against recently *committed* code, but a tag created today on a commit older than the cooldown is admitted immediately, and a commit date can be backdated by its author. Go quarantine is a weaker guard than npm, Maven and PyPI, whose timestamps come from the registry. For internal namespaces that must never come from a public upstream, use [routing rules](routing-rules.md) as well.

## Multi-replica semantics

All decisions come from shared state (cached upstream responses in the database and blob storage). Each node keeps a bounded, rebuildable cache of per-version publish times (immutable values keyed by repository **and upstream URL**, so editing a proxy's remote URL never reuses a time learned from the former upstream; they expire 6 h after being written); losing it only costs a lookup. Filtered Go lists, Maven metadata and PyPI pages are recomputed on every request, so a version appears as soon as it matures even though the raw upstream document is cached for its normal max age. Merged group metadata (Maven) and group indexes (PyPI) that contain a gated proxy are re-merged at least every 10 minutes for the same reason.

## Limits

- The first `@v/list` of a Go module, and the first metadata request for a Maven artifact, perform one upstream lookup per version (bounded parallelism); later requests use the cache.
- Raising the minimum age does not evict content a Maven group already cached from a member; it expires with the group's content max age.
- Existing cached content is still gated: the age check runs before the cache is consulted.
