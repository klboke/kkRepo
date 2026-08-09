# APT / Debian Repository Guide

kkrepo supports binary Debian packages through `apt-hosted` and `apt-proxy` repositories while
keeping the Nexus-style client root:

```text
https://nexus.example.com/repository/<repo>/
```

Use hosted for private `.deb` publication and proxy for an upstream Debian/Ubuntu archive. APT
group, hosted source packages, flat hosted repositories, generated Contents/Translation indexes,
PDiff, and `.udeb` indexes are outside the current support boundary. The Chinese version is
available in the [APT / Debian 仓库使用指南](../zh/apt-debian-guide.md).

## Create And Configure A Repository

Create the recipe from **Admin > Repository > Repositories**. The APT settings have these
semantics:

| Setting | Hosted | Proxy |
| --- | --- | --- |
| Distribution | Required; defaults to `stable` | Optional for multi-distribution passthrough |
| Component | Defaults to `main` | Component used by locally re-signed metadata |
| Architectures | Defaults to `amd64`; `all` packages are accepted | Architectures projected when re-signing |
| Enforce distribution | Enabled by default | Rejects requests outside the configured distribution |
| Flat | Not supported | Supported only with `PASSTHROUGH` |
| Metadata mode | Always `RESIGN` | `PASSTHROUGH` by default, or `RESIGN` |
| Valid Until days | Optional, `0`-`3650` | Applies to locally re-signed Release metadata |
| Origin / Label | Defaults to `kkRepo` | Applies to locally re-signed Release metadata |

Distribution, component, and architecture values are validated path segments. A hosted upload whose
control archive architecture is neither `all` nor one of the configured architectures is rejected.

## Signing Keys

Hosted and re-signing proxy repositories publish `Release`, clear-signed `InRelease`, and detached
`Release.gpg` metadata. If no key has been configured, kkrepo creates a repository-scoped RSA-3072
key when signing material is first needed. For production, decide whether to retain that generated
identity or import an organization-managed OpenPGP private key before clients trust the repository.

The administration UI can import a private key, generate a new key, show the active fingerprint,
and rotate the key. The equivalent generate/rotate endpoint is:

```bash
curl -u "admin:$KKREPO_PASSWORD" \
  -X PUT \
  -H 'Content-Type: application/json' \
  --data '{"generate":true}' \
  https://nexus.example.com/internal/repositories/apt-hosted/apt/signing-key
```

Import private material through the UI or send `privateKey` and optional `passphrase` to the same
endpoint. Do not put private key JSON or passphrases in shell history. Private material is encrypted
with the configured kkrepo credential secret, never returned by the API, and must be recoverable
with the same secret after a restore.

Rotation stores the new key and synchronously rebuilds signed metadata. Until that rebuild commits,
clients continue to receive the previous complete snapshot. `/gpg.key` returns the retained current
and previous public keys so clients can survive an orderly rotation; distribute the refreshed
keyring before retiring the old trust anchor.

## Configure APT Clients

Create the scoped keyring directory, download the repository key, and make it readable by APT:

```bash
sudo install -d -m 0755 /etc/apt/keyrings
curl --fail --show-error -u "alice:$KKREPO_PASSWORD" \
  -o /tmp/kkrepo-apt.asc \
  https://nexus.example.com/repository/apt-hosted/gpg.key
sudo install -m 0644 /tmp/kkrepo-apt.asc /etc/apt/keyrings/kkrepo.asc
rm -f /tmp/kkrepo-apt.asc
```

Configure `/etc/apt/sources.list.d/kkrepo.list`:

```text
deb [signed-by=/etc/apt/keyrings/kkrepo.asc] https://nexus.example.com/repository/apt-hosted stable main
```

For a private repository, keep credentials out of the URL. Create
`/etc/apt/auth.conf.d/kkrepo.conf`:

```text
machine nexus.example.com/repository/apt-hosted/
login alice
password <password-or-token>
```

```bash
sudo chmod 0600 /etc/apt/auth.conf.d/kkrepo.conf
sudo apt-get update
sudo apt-get install demo-package
```

The key endpoint follows the repository READ/anonymous policy. Download it with an authorized
identity before configuring an entirely private client.

## Publish Binary Packages

Upload through the Nexus-compatible repository-root endpoint:

```bash
curl --fail --show-error -u "alice:$KKREPO_PASSWORD" \
  -H 'Content-Type: multipart/form-data' \
  --data-binary @demo-package_1.0.0-1_amd64.deb \
  https://nexus.example.com/repository/apt-hosted/
```

The Components API and administration UI use one `apt.asset` field:

```bash
curl -u "alice:$KKREPO_PASSWORD" \
  -F apt.asset=@demo-package_1.0.0-1_amd64.deb \
  'https://nexus.example.com/service/rest/v1/components?repository=apt-hosted'
```

Every entrypoint uses the same importer. Before a successful response, kkrepo has parsed the Debian
control archive, confirmed package/version/architecture identity, checked archive bounds and
checksums, derived the canonical `pool/` path, and durably stored the blob, asset, component, and APT
package record. Metadata projection is not used to decide whether a hosted write is valid.

### Asynchronous Metadata Publication

A successful upload means the package is durably accepted, but it may not appear in `Packages`
immediately. The publication worker debounces bursts, streams a complete set of `Packages`,
`Packages.gz`, `Packages.bz2`, `Packages.xz`, `Release`, `InRelease`, `Release.gpg`, and by-hash
assets, and then atomically switches the published snapshot. Readers see either the previous
complete signed snapshot or the new one, never a partial mix.

The administration UI shows publication status. Operators can also inspect it:

```bash
curl -u "admin:$KKREPO_PASSWORD" \
  https://nexus.example.com/internal/repositories/apt-hosted/apt/status
```

For each suite, `desiredRevision == publishedRevision` and an empty `lastError` mean publication has
caught up. `lastPublishedAt` records the last successful switch. A failed build leaves the previous
snapshot online and is retried from durable state.

Use the same synchronous pipeline for an explicit rebuild:

```bash
curl -u "admin:$KKREPO_PASSWORD" \
  -X POST \
  -H 'Content-Type: application/json' \
  --data '{"distribution":"stable"}' \
  https://nexus.example.com/internal/repositories/apt-hosted/apt/rebuild
```

These `/internal/repositories` operations require repository-administration privileges. Prefer the
administration UI for interactive work.

## Proxy Modes

| Mode | Trust and bytes | Local catalog |
| --- | --- | --- |
| `PASSTHROUGH` | Caches and returns the upstream signed Release, indexes, by-hash paths, and package bytes unchanged. Clients trust the upstream key. | A checksum-matched Release/Packages projection is best effort for Browse/Search. Projection failure never rewrites or blocks an otherwise valid passthrough response. |
| `RESIGN` | Builds a new local archive signed by the repository key. Clients trust the kkrepo key. | Verifies Release index size/SHA-256, then downloads and verifies every declared binary package before atomically publishing local metadata. |

The proxy projection is therefore not a deferred validation mechanism for hosted writes. It is a
bounded catalog/cache projection of an upstream archive. Re-signing fails closed and preserves the
previous snapshot if the projection is incomplete, exceeds 10,000 packages or 20 GiB, or violates
an expected checksum. Flat proxy repositories support passthrough only.

## Multi-Replica Publication And Retention

Suite revisions, snapshots, proxy observations, signing keys, and fenced leases live in the shared
relational database; package and generated metadata bytes live in the configured blob store. Any
replica can publish or take over an expired lease. Node-local settings and snapshot caches are
rebuildable hot caches and are invalidated by shared version watermarks.

Index construction uses a forward-only database cursor and temporary spool files. Heap use is
bounded by a package-name group and fixed I/O buffers instead of the total suite size. One publish
still rewrites the complete APT index, so CPU, temporary disk, and uploaded metadata grow roughly
linearly with the current package count. Debouncing collapses a write burst into one rebuild; it
does not make a complete signed index an O(1) operation.

Published metadata is immutable. Cleanup retains the current snapshot plus at least two historical
snapshots and applies a grace period before deleting anything older. This preserves by-hash reads
and gives in-flight clients time to finish. Deleted package blobs become collectible only after no
retained snapshot can reference them and normal blob garbage-collection safety checks pass.

Advanced publication and snapshot-retention controls are:

| Environment variable | Default | Purpose |
| --- | ---: | --- |
| `KKREPO_APT_PUBLICATION_ENABLED` | `true` | Run the durable background publisher |
| `KKREPO_APT_PUBLICATION_POLL_INTERVAL_MS` | `500` | Pending-suite polling interval |
| `KKREPO_APT_PUBLICATION_INITIAL_DELAY_MS` | `1000` | Delay before the first publisher poll |
| `KKREPO_APT_PUBLICATION_BATCH_SIZE` | `16` | Suites considered per poll, clamped to 1-256 |
| `KKREPO_APT_PUBLICATION_DEBOUNCE_MS` | `500` | Quiet period used to combine writes |
| `KKREPO_APT_PUBLICATION_MAX_DELAY_MS` | `30000` | Maximum delay before a busy suite is attempted |
| `KKREPO_APT_PUBLICATION_RETRY_MS` | `30000` | Minimum retry delay after a failed revision |
| `KKREPO_APT_SNAPSHOT_CLEANUP_ENABLED` | `true` | Run snapshot and tombstone cleanup |
| `KKREPO_APT_SNAPSHOT_CLEANUP_INTERVAL_MS` | `300000` | Cleanup interval |
| `KKREPO_APT_SNAPSHOT_CLEANUP_INITIAL_DELAY_MS` | `120000` | Delay before the first cleanup cycle |
| `KKREPO_APT_SNAPSHOT_CLEANUP_BATCH_SIZE` | `32` | Candidates processed per cycle, clamped to 1-256 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_MIN_SNAPSHOTS` | `3` | Retained snapshots; runtime minimum is 3 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_GRACE_SECONDS` | `86400` | Minimum age before old snapshots are eligible |

Keep publication and cleanup enabled in production unless an incident runbook explicitly requires
otherwise.

## Cleanup, Scanning, And Migration

Cleanup Policy treats one APT component as one
`(distribution, component, package, version)` across its architecture assets. **Keep newest
versions** uses Debian version ordering. Hosted deletion tombstones all architecture assets selected
for that component and publishes each affected distribution once, so signed metadata, Browse, and
Search move together. See the [Cleanup Policy Guide](cleanup-policy-guide.md).

When Artifact Scanning is enabled globally and on the repository, canonical `.deb` content is
scanned after it is stored. Generated `dists/`, `.apt/` snapshot, checksum, and signature assets are
not independent scan candidates. See the [Artifact Scanning Guide](artifact-scanning-guide.md).

Nexus migration supports shape-gated APT repository definitions and hosted package content from
verified Nexus 3.92.x-3.94.x datastore profiles. Generated source `dists/` metadata is rebuilt on
the target. Private signing material is never copied implicitly: the migrated hosted repository
stays offline until an administrator explicitly imports the intended key and rebuilds metadata.
See the [Nexus Migration Guide](nexus-migration-guide.md).

## Backup And Restore

Back up the relational database and blob store at a consistent recovery point. The database holds
encrypted signing material, package records, desired/published revisions, immutable snapshot
manifests, and leases; blob storage holds `.deb` bytes and generated snapshot assets. Preserve the
original `KKREPO_CREDENTIAL_SECRET` so restored private keys can be decrypted.

After restore, verify `/gpg.key`, `dists/<distribution>/InRelease`, a compressed Packages index,
and a representative package checksum, then run `apt-get update` and install from a disposable
client. Compare desired and published revisions and use the supported rebuild operation if the
catalog is stale; do not repair APT tables manually. See the
[Backup And Restore Guide](backup-restore.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `NO_PUBKEY` or invalid signature | Refresh the scoped `/gpg.key` keyring, verify `signed-by`, and confirm the expected fingerprint after rotation |
| Upload succeeds but `apt-get update` does not show the version | Check desired/published revisions and `lastError`; allow for debounce or trigger an authorized rebuild |
| Upload returns `400` | Verify the `.deb` control identity, canonical filename/path, configured distribution/component/architectures, and archive safety limits |
| `apt-get update` returns `401` | Check the path-scoped `auth.conf` entry, file mode `0600`, repository READ privilege, and token/password validity |
| Proxy metadata is available but Browse/Search is empty | In passthrough mode the checksum-verified projection is best effort; inspect the observed Release and index checksums |
| Re-sign proxy keeps the old snapshot | Check upstream Release/index/package checksums and the 10,000-package/20-GiB projection limits |
| Old by-hash data disappears too early | Keep cleanup enabled, retain at least three snapshots, and set a grace period longer than the client/update window |

## References

- [Client Recipes](client-recipes.md#apt--debian)
- [Compatibility Matrix](compatibility-matrix.md)
- [APT performance baseline](dev/apt-performance-baseline.md)
- [APT design and compatibility notes](../zh/dev/apt-debian-repository-design.md)
- [Debian Repository Format](https://wiki.debian.org/DebianRepository/Format)
- [Debian `apt-secure(8)`](https://manpages.debian.org/bookworm/apt/apt-secure.8.en.html)
- [Debian `sources.list(5)`](https://manpages.debian.org/bookworm/apt/sources.list.5.en.html)
- [Sonatype APT Repositories](https://help.sonatype.com/en/apt-repositories.html)
