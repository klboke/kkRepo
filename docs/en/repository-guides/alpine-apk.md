# Alpine / APK Repository Guide

kkRepo supports Alpine Package Keeper v2 hosted, proxy, and group repositories at the
Nexus-compatible entrypoint:

```text
https://nexus.example.com/repository/<repo>/
```

Hosted repositories publish private `.apk` packages and signed `APKINDEX.tar.gz` snapshots.
Proxy repositories either preserve upstream bytes or verify and re-sign a projected v2 index.
Groups publish one signed, ordered view across compatible hosted/proxy/group members.

## Create Repositories

Create these recipes in Admin UI or through the repository API:

| Purpose | Recipe | Important settings |
| --- | --- | --- |
| Private publication | `alpine-hosted` | Distribution/channel/architecture allowlists, write policy, RSA key filename/type, description |
| Upstream cache | `alpine-proxy` | Remote URL, TTL/negative cache/auto-block, `PASSTHROUGH` or verified `RESIGN`, stale policy, upstream public keys |
| Unified reads | `alpine-group` | Ordered Alpine members, namespace allowlists, repository-scoped RSA signing key |

Hosted and group repositories always publish locally signed v2 indexes. Each
`distribution/channel/repository-architecture` is an independent immutable snapshot namespace,
for example `v3.23/main/x86_64`. A proxy in `PASSTHROUGH` mode preserves upstream index and package
bytes. A `RESIGN` proxy must verify the upstream signature using configured public keys before it
projects cached package records and publishes its own snapshot. For Nexus-compatible grouping, a
`PASSTHROUGH` proxy may also be a group member. The group applies that proxy's configured signature
policy, signs the aggregate with the group key, and verifies every lazily fetched package against
the exact index identity and size captured in the group snapshot. Disabling upstream signature
verification is therefore an explicit transport-trust choice, not package-checksum bypass.

## Trust The Repository Key

Download the public key with repository-administration read permission and install it using the
exact configured filename:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -o kkrepo-alpine-group.rsa.pub \
  https://nexus.example.com/internal/repositories/alpine-group/alpine/public-key

sudo install -m 0644 kkrepo-alpine-group.rsa.pub \
  /etc/apk/keys/kkrepo-alpine-group.rsa.pub
```

Private key material is encrypted at rest and is never returned by read APIs. Admin UI exposes
the active filename, fingerprint, revision, signature type, namespace publication status, rebuild,
and explicit generate/import rotation actions. Keep `KKREPO_CREDENTIAL_SECRET` stable across
replicas and backups or restored private keys cannot be decrypted.

## Configure And Use apk

Add the group URL without an architecture suffix; `apk` appends its current architecture and
`APKINDEX.tar.gz`:

```bash
echo 'https://nexus.example.com/repository/alpine-group/v3.23/main' \
  | sudo tee /etc/apk/repositories

apk update
apk search -x acme-agent
apk policy acme-agent
apk fetch acme-agent=1.2.3-r0
apk add acme-agent=1.2.3-r0
apk info -e acme-agent=1.2.3-r0
```

For an authenticated repository, use a protected client configuration or a reverse proxy that
supplies a scoped credential. If user information is embedded in the URL, restrict
`/etc/apk/repositories` to the intended user and never commit it.

## Publish And Delete Packages

An APK v2 package is immutable at its package/version/repository-architecture coordinate. Upload
the canonical filename to a hosted namespace:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: application/vnd.alpine.apk' \
  --upload-file acme-agent-1.2.3-r0.apk \
  https://nexus.example.com/repository/alpine-hosted/v3.23/main/x86_64/acme-agent-1.2.3-r0.apk
```

The UI and Components API use the same protocol-aware publication path and require the package
file plus distribution, channel, and repository architecture. kkRepo safely parses `.PKGINFO`,
validates the raw compressed data-member SHA-256, computes the official Q1 identity over the raw
compressed control member, stores the package, and atomically advances a newly signed index.
Uploading different bytes to an existing coordinate fails closed; retrying identical bytes is
idempotent.

Delete only from hosted:

```bash
curl -u alice:"$KKREPO_PASSWORD" -X DELETE \
  https://nexus.example.com/repository/alpine-hosted/v3.23/main/x86_64/acme-agent-1.2.3-r0.apk
```

The old package blob remains snapshot-pinned until no retained signed generation references it.
Clients therefore see either the complete old snapshot or the complete new snapshot, never an
index that points to a missing package.

## Browse, Search, Cleanup, And Scanning

Browse projects distribution/channel/repository architecture/package/version and hides generated
snapshot blobs, proxy staging, leases, and tombstones. Search returns package name, version,
package architecture, repository architecture, Q1 identity, data SHA-256, whole-blob SHA-256,
origin, license, and source member where applicable.

For `noarch` uploads, the generated index `A:` field is the repository architecture from the URL
namespace so `apk` resolves the canonical package path. The original `.PKGINFO` architecture is
retained separately and remains visible in Browse and search details.

Cleanup treats one package coordinate as one subject, uses apk-tools version ordering, tombstones
the typed package row, and republishes every affected namespace before bytes become eligible for
garbage collection. Group source bindings prevent an index record from being served from one
member while its package bytes are read from another.

When artifact scanning is enabled, an `.apk` is classified as `ALPINE_PACKAGE`. The asynchronous
scanner catalogs the installed database and payload tree from bounded, non-executing archive
inspection. Upload never invokes Syft/Grype synchronously; Audit/Enforce decisions, waivers, SBOM
reuse, and download blocking use the shared scanning infrastructure.

## Multi-Replica And Migration Behavior

Package rows, desired/published revisions, immutable snapshots, encrypted signing-key revisions,
proxy state, group bindings, tombstones, leases, and fencing tokens live in MySQL/PostgreSQL. Blob
content lives in OSS/S3/File storage. In-process caches are version/TTL invalidated and are never
the correctness source. A replica can take over publication after a crash without exposing a
partial or unsigned generation.

Nexus definition migration recognizes Alpine hosted/proxy/group repositories. Hosted content is
`FULL` only for the exact verified Nexus 3.94 datastore shape and canonical package path, identity,
size, and SHA-256. Generated indexes are filtered and rebuilt. Signing private keys and proxy
secrets are never guessed: unavailable material leaves the target offline with a manual action.
Imports support dry-run, resume, checksum verification, and idempotent replay.

## Limits And Troubleshooting

| Symptom | Check |
| --- | --- |
| `UNTRUSTED signature` | Install the exact active group/hosted public-key filename and refresh after intentional key rotation |
| Package is uploaded but absent | Check namespace allowlists and publication status/error; rebuild the exact `distribution/channel/architecture` namespace |
| Upload is rejected | Confirm APK v2, canonical filename, `.PKGINFO` identity, path architecture, datahash, write policy, and add/edit permission |
| Proxy refresh fails | Check remote URL, outbound policy, validators, upstream key set, signature/checksum drift, auto-block, and stale policy |
| Group resolves the wrong bytes | Check member order, duplicate-coordinate diagnostics, and persisted source binding |

`Packages.adb` and APK v3 package containers are not served by this v2 implementation. DSA index
keys, unsigned hosted/group indexes, arbitrary generated directory listings, and private-key
download are intentionally unsupported.

## References

- [Alpine package specification](https://wiki.alpinelinux.org/wiki/Apk_spec)
- [Alpine repositories](https://wiki.alpinelinux.org/wiki/Repositories)
- [apk-tools](https://gitlab.alpinelinux.org/alpine/apk-tools)
- [Alpine implementation design](../../zh/dev/alpine-apk-repository-design.md)
- [Alpine performance baseline](../dev/alpine-performance-baseline.md)
