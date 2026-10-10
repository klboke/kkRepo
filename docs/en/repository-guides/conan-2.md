# Conan 2 Repository Guide

kkRepo supports Conan 2 hosted, proxy, and group repositories at the Nexus-compatible entrypoint:

```text
https://nexus.example.com/repository/<repo>/
```

Hosted repositories publish private recipes and binaries, proxy repositories cache a Conan 2
upstream, and groups provide one ordered read endpoint across compatible hosted/proxy members.
Conan 1 repositories are not mixed into this format.

## Create Repositories

Create the following recipes in the administration UI or repository API:

| Purpose | Recipe | Important settings |
| --- | --- | --- |
| Private publication | `conan-hosted` | Blob store, online state, write policy, strict content validation |
| Upstream cache | `conan-proxy` | Conan 2 remote URL, upstream credentials, metadata/content/negative TTL, auto-block |
| Unified reads | `conan-group` | Ordered Conan hosted/proxy/group members |

For ConanCenter, use `https://center2.conan.io/` as the proxy remote. A group is read-only; publish
to hosted and consume from the group.

## Configure And Authenticate The Client

Add the group as the normal read remote and hosted as the publication remote:

```bash
conan remote add kkrepo-group \
  https://nexus.example.com/repository/conan-group/ --force
conan remote add kkrepo-hosted \
  https://nexus.example.com/repository/conan-hosted/ --force

conan remote login kkrepo-hosted alice -p "$KKREPO_CONAN_PASSWORD"
```

The Conan client exchanges the supplied credential for a short-lived repository-scoped bearer
token. Keep passwords and scoped automation credentials outside source control. Explicit invalid,
expired, or revoked credentials return `401` and never fall back to anonymous access.

## Build And Publish

Build a recipe and its binary with the standard Conan 2 CLI:

```bash
conan create . \
  --name=acme-lib \
  --version=1.0.0 \
  --user=acme \
  --channel=stable

conan upload 'acme-lib/1.0.0@acme/stable:*' \
  -r=kkrepo-hosted \
  --confirm
```

Conan uploads every recipe/package revision as several files. kkRepo keeps those files in durable
staging and makes the RREV or PREV visible only when the final `conanmanifest.txt` validates all
file checksums. A retry can resume the same upload; an identity collision with different bytes
fails closed. UI and Components API uploads use the same manifest-gated publication path.

## Manifest Limits

Conan hosted and proxy repositories expose **Manifest entry limit** and **Manifest size limit
(bytes)** in the repository configuration dialog. For a large SDK, set the entry limit to
`50000` and the size limit to `16777216` (16 MiB), for example. Each blank field independently
inherits the server default. These limits bound `conanmanifest.txt` and the combined file tree
reconstructed from archives and loose files; they do not describe the compressed archive size.
Proxy settings also apply when importing cached content through the manifest-gated migration writer.
Groups use their members' settings and have no override of their own.

Saving repository settings requires no restart. Existing runtime caches are invalidated on the
saving node, and database-backed catalog notifications refresh sibling replicas (default poll
interval: 500 ms). The runtime cache TTL (default: 30 seconds) is the fallback for missed
notifications. An in-flight request retains its configuration snapshot; retry a rejected upload
after the new settings reach its serving replica. No extra database query is added to manifest validation.

The repository API accepts the same settings when creating or updating a repository:

```json
{
  "conan": {
    "manifestMaxEntries": 50000,
    "manifestMaxBytes": 16777216
  }
}
```

Omitting `conan` on an update preserves existing overrides. Supplying it replaces both overrides;
a null or omitted field inside that object inherits its server default. Send `"conan": {}` to
restore inheritance for both fields.

Server defaults are configurable in both JVM and Native deployments:

| Property | Environment variable | Default | Unit |
| --- | --- | --- | --- |
| `kkrepo.conan.manifest.max-entries` | `KKREPO_CONAN_MANIFEST_MAX_ENTRIES` | `4096` | File entries per manifest |
| `kkrepo.conan.manifest.max-bytes` | `KKREPO_CONAN_MANIFEST_MAX_BYTES` | `1048576` | UTF-8 manifest bytes (1 MiB) |

```yaml
environment:
  KKREPO_CONAN_MANIFEST_MAX_ENTRIES: "50000"
  KKREPO_CONAN_MANIFEST_MAX_BYTES: "16777216"
```

Changing these server defaults requires restarting the processes/containers; keep defaults
consistent across replicas. Repository overrides take precedence. Entry limits must be positive
integers up to `2147483647`; byte limits must be between `1` and `2147483646`. Zero and negative
values do not disable validation. Choose practical values for the available memory and workload:
manifests and their checksum maps are held in memory. Other archive protections (200,000 tar
entries per archive, expansion size/ratio, path safety, inspection time, and checksum matching)
remain in force. Limit errors report the effective limit and observed count or size; when reading
stops early, the observed value is a lower bound rather than the total input size.

## List, Download, Install, And Remove

Use the group for reads:

```bash
conan list 'acme-lib/1.0.0@acme/stable:*' -r=kkrepo-group
conan download 'acme-lib/1.0.0@acme/stable:*' -r=kkrepo-group
conan install --requires='acme-lib/1.0.0@acme/stable' \
  -r=kkrepo-group \
  --build=missing
```

Remove content from hosted only:

```bash
conan remove 'acme-lib/1.0.0@acme/stable:*' \
  -r=kkrepo-hosted \
  --confirm
```

Exact RREV/PREV references recorded in a lockfile remain addressable. Recipe, RREV, package,
PREV, and all-package deletion recompute the relevant latest pointer and invalidate group source
bindings. Like Nexus 3.94, Conan protocol routes do not synthesize `HEAD`; file `GET` and byte
Range requests are supported.

## Browse, Search, And Usage

Browse uses the Nexus 3.94 presentation tree, which intentionally differs from the protocol/blob
path:

```text
<user>/<name>/<version>/<channel>#<rrev>/conanfile.py
<user>/<name>/<version>/<channel>#<rrev>/packages/<package-id>/revisions/<prev>/files/conan_package.tgz
```

Missing user/channel values are shown as `_`. This path is projected and persisted in the same
write transaction as the final asset, component, typed Conan row, latest pointer, and scan outbox.
Normal Browse requests only read indexed `browse_node` rows; they do not reverse-map storage paths
or repair missing mappings on first read. Staging and proxy-discovery paths remain hidden.

Search exposes one component per recipe version with its canonical user/channel namespace. The
Usage view provides ready-to-copy remote, login, list, download, install, and hosted-upload
commands without exposing stored credentials.

## Cleanup, Security Scanning, And Multi-Replica Behavior

Cleanup treats a complete Conan recipe version—all RREV/PREV files—as one subject and uses Conan's
version ordering. It never deletes individual manifest members or leaves a partially visible
revision.

When artifact scanning is enabled for the repository, each package archive is classified as a
`CONAN_PACKAGE`. The asynchronous scanner receives the immutable package archive plus the exact,
independently checksummed `conaninfo.txt` sidecar. Recipe/source/manifest/metadata files are not
separate scan candidates. Audit/Enforce policy, waivers, SBOM reuse, and download decisions use the
shared scanning infrastructure; upload requests never invoke the scanner synchronously.

Upload sessions, short-lived bearer tokens, repository revisions, proxy/group source bindings,
leases, fencing tokens, cleanup claims, and scan tasks are durable in MySQL/PostgreSQL. Blob and
staging bytes stay in OSS/S3. Another replica can safely resume or take over work; process-local
caches are rebuildable optimizations only.

## Nexus Migration

Metadata migration recognizes Conan hosted/proxy/group definitions. Content migration is enabled
only for a verified Nexus 3.94 datastore shape with canonical Conan 2 revision paths, manifests,
and SHA-1 data. Conan 1, unknown/mixed shapes, incomplete revisions, damaged blobs, and unavailable
proxy secrets produce a manual action instead of a guessed import.

Hosted content is imported through the normal manifest-gated writer. Proxy cache migration remains
explicitly opt-in. The migration supports dry-run, resume, checksum verification, idempotent replay,
and reporting, and it writes the final Nexus-compatible Browse projection during publication—not
as a later backfill.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Login returns `401` | Verify repository URL, username/password or scoped token, expiry, repository permission, and anonymous policy |
| Upload remains invisible | Ensure the revision includes a valid final `conanmanifest.txt` and every declared file/checksum |
| Package upload returns `404` | Publish the parent recipe RREV before uploading its binary PREV |
| Proxy install fails | Check Conan 2 upstream URL, credentials, outbound policy, redirect, negative-cache/auto-block state, and checksum drift |
| Group resolves inconsistent files | Check member order and source-binding diagnostics; all files for one RREV/PREV must come from one member |
| Browse shows no revision | Inspect the publication failure; normal Browse deliberately does not backfill a missing projection |

## References

- [Conan 2 remotes](https://docs.conan.io/2/reference/config_files/remotes.html)
- [Conan 2 upload](https://docs.conan.io/2/reference/commands/upload.html)
- [Conan 2 list](https://docs.conan.io/2/reference/commands/list.html)
- [Conan 2 revisions](https://docs.conan.io/2/tutorial/versioning/revisions.html)
- [Conan implementation design](../../zh/dev/conan-repository-design.md)
- [Conan performance baseline](../dev/conan-performance-baseline.md)
