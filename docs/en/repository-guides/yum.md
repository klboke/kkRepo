# Yum Repository Guide

kkRepo supports Yum/DNF `hosted`, `proxy`, and `group` repositories. Hosted accepts RPM uploads and
generates repository metadata, proxy caches an upstream RPM repository, and group presents one
ordered `baseurl`.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private RPMs | `yum-hosted` | Blob store, write policy, strict validation |
| Upstream cache | `yum-proxy` | Remote repository root and cache TTLs |
| Unified reads | `yum-group` | Hosted before proxy |

Configure a remote repository root that exposes `repodata/repomd.xml`; do not point the proxy at an
individual RPM or metadata file.

## Configure Yum Or DNF

Create `/etc/yum.repos.d/kkrepo.repo`:

```ini
[kkrepo]
name=kkRepo
baseurl=https://nexus.example.com/repository/yum-group/
enabled=1
gpgcheck=0
```

The example disables package-signature verification only because key management is deployment
specific. Production deployments should enable `gpgcheck=1` and configure `gpgkey` when their RPMs
are signed by a trusted key.

Verify the endpoint:

```bash
dnf clean metadata
dnf makecache --disablerepo='*' --enablerepo=kkrepo
dnf install --enablerepo=kkrepo demo-package
```

## Publish An RPM

Upload directly to hosted or use the Admin UI/component upload:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-1.x86_64.rpm \
  https://nexus.example.com/repository/yum-hosted/Packages/demo-1.0.0-1.x86_64.rpm
```

Only hosted accepts publication. Keep package paths stable so external scripts and repository
metadata refer to the same asset.

## Release Subdirectories

For a DNF base URL ending in `/fedora-$releasever/`, set **Repodata Depth** to `1` in the
hosted repository settings (API: `"yum": {"repodataDepth": 1}`). The default `0` generates
metadata at the repository root. Depth `2` generates it under paths such as `fedora-45/x86_64/`.
RPMs may be stored deeper than this depth, but uploads above it are rejected.

```bash
curl -u alice:"$KKREPO_PASSWORD" --upload-file demo-1.0.0-1.x86_64.rpm \
  https://nexus.example.com/repository/yum-hosted/fedora-45/
```

Metadata is rebuilt asynchronously under `fedora-45/repodata/`; package locations are relative to
`fedora-45/`. Each release directory has its own package index. Group metadata uses the same
subdirectory endpoint in its hosted members; configure their depths consistently.

Nexus migration preserves `yum.repodataDepth`. Existing imports also read it from the retained
source configuration, without re-importing packages. After upgrading an affected repository,
open its settings, confirm the depth, and save once to initialize the active Yum settings and enqueue
a rebuild of existing RPMs. Later configuration saves enqueue a rebuild only if the depth changes;
saving unchanged settings or editing unrelated fields does not rebuild metadata. If the source
export did not include the depth, enter it explicitly. Then run `dnf clean metadata` and retry.
Rebuild work uses the shared database queue and reads committed repository configuration directly,
without waiting for sibling cache invalidation. Changing depth rewrites old metadata roots as empty
indexes; the RPMs remain in place and are indexed under the new roots.

RPM uploads and deletions enqueue maintenance only for the affected metadata root. For example,
changing `fedora-45/Packages/demo.rpm` at depth `1` reads and regenerates `fedora-45/` only; the
`fedora-44/` snapshot is untouched. Group reads also query only the requested root. Pending changes
in the same root share a durable marker. Rebuild workers serialize work for a repository through
database row locks, so directory updates cannot race with a full repair or configuration change.
The last RPM deletion publishes an empty index at its root.

Full rebuilds are reserved for first initialization of legacy settings, depth changes, legacy full
markers, and recovery of markers queued under an outdated depth. A root exceeding the shared queue's
512-character scope limit also uses a full rebuild. At depth `0`, all packages share a single index,
so updating that index still processes the repository's RPMs. Stored RPM metadata is reused; missing
metadata is read from the RPM header and persisted. Upgrade all replicas before changing depth;
older workers do not implement directory scopes or the new rebuild coordination.

This follows [Nexus Repodata Depth](https://help.sonatype.com/en/yum-repositories.html).

## Repository Behavior

- Hosted parses RPM identity and rebuilds `repodata` from committed packages.
- Proxy caches `repomd.xml`, referenced metadata, and RPM files with validators and TTLs.
- Group resolves member repositories in order and exposes group-scoped metadata and package paths.
- Browse and Search expose RPM name, epoch, version, release, architecture, and assets.

## Operations And Troubleshooting

After publication, refresh client metadata before diagnosing missing packages. If `repomd.xml` is
available but a referenced file is not, check repository permissions and reverse-proxy caching. Use
cleanup preview before deleting RPM versions referenced by active deployment manifests.

## Related Documentation

- [Yum client recipe](../client-recipes.md#yum)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [DNF repository configuration reference](https://dnf.readthedocs.io/en/latest/conf_ref.html)
