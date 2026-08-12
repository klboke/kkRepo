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
