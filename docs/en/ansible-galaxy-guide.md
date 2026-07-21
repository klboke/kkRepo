# Ansible Galaxy Repository Guide

kkrepo supports Ansible Galaxy v3 **collection** repositories with hosted, proxy, and group recipes. The client entrypoint keeps the Nexus layout:

```text
https://nexus.example.com/repository/<repo>/
```

Use a hosted repository to publish private collections, a proxy to cache an upstream Galaxy server, and a group as the single read endpoint for hosted and proxy members. Galaxy v1 roles, GitHub role import, and `ansible-galaxy role install` are not part of this repository format.

## Create The Repositories

Create these recipes from the administration UI or repository API:

| Purpose | Recipe | Important settings |
| --- | --- | --- |
| Private publication | `ansiblegalaxy-hosted` | Blob store, online state, write policy, strict content validation |
| Upstream cache | `ansiblegalaxy-proxy` | Remote Galaxy root, remote credentials, metadata/content/negative TTL |
| Unified reads | `ansiblegalaxy-group` | Ordered Ansible Galaxy hosted/proxy/group members |

For a public Galaxy proxy, configure `https://galaxy.ansible.com/` as the remote root. Keep the trailing slash. The proxy follows the upstream discovery document instead of assuming a fixed v3 path.

## Configure `ansible.cfg`

Use the group for install/download and the hosted repository for publish:

```ini
[galaxy]
server_list = kkrepo_group, kkrepo_hosted

[galaxy_server.kkrepo_group]
url = https://nexus.example.com/repository/ansible-group/
token = GenericToken.REDACTED
priority = 1

[galaxy_server.kkrepo_hosted]
url = https://nexus.example.com/repository/ansible-hosted/
token = GenericToken.REDACTED
priority = 2
```

The URL must end in `/`. `ansible-galaxy` reads the repository discovery response and then selects the advertised Galaxy v3 service path.

Create a `GenericToken` under **My Token** for automation and keep it outside source control. kkrepo also accepts HTTP Basic and the Nexus-compatible Base64 encoding of `username:password` on Ansible routes. Base64 is not encryption; use it only for Nexus client compatibility. Current ansible-core sends `Authorization: Bearer`, while Ansible 2.9 uses `Authorization: Token`; kkrepo accepts both schemes for these scoped credentials. An explicit invalid credential returns `401` and never falls back to anonymous access.

## Build And Publish

Create and build a collection with the standard CLI:

```bash
ansible-galaxy collection init acme.tools
cd acme/tools
ansible-galaxy collection build --output-path ../../dist
```

Publish the immutable collection version to hosted:

```bash
ansible-galaxy collection publish ../../dist/acme-tools-1.0.0.tar.gz \
  --server https://nexus.example.com/repository/ansible-hosted/ \
  --token "$KKREPO_ANSIBLE_TOKEN" \
  --import-timeout 120
```

Some Ansible 2.9 installations expose `--api-key` instead of `--token`; pass the same token value with the flag supported by that client. `--no-wait` is supported when the caller wants to poll the returned durable import task separately.

Each `(namespace, name, version)` is immutable. Re-publishing the same version fails even when the repository write policy otherwise allows updates. Build a new collection version instead.

The Nexus-compatible direct upload path is also available for existing automation:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file acme-tools-1.0.0.tar.gz \
  https://nexus.example.com/repository/ansible-hosted/api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.0.0.tar.gz
```

The CLI multipart path, direct PUT path, administration UI, and Components API all use the same archive validation and immutable publication service.

## Install And Download

Install through the group so private hosted collections and proxied public collections resolve from one endpoint:

```bash
ansible-galaxy collection install acme.tools:1.0.0 \
  --server https://nexus.example.com/repository/ansible-group/ \
  --token "$KKREPO_ANSIBLE_TOKEN"
```

`requirements.yml` supports exact versions, ranges, dependencies, and an explicit source:

```yaml
collections:
  - name: acme.tools
    version: ">=1.0.0,<2.0.0"
    source: https://nexus.example.com/repository/ansible-group/
```

```bash
ansible-galaxy collection install -r requirements.yml --force
ansible-galaxy collection download acme.tools:1.0.0 \
  --server https://nexus.example.com/repository/ansible-group/ \
  --token "$KKREPO_ANSIBLE_TOKEN"
```

Group member order is authoritative when two members contain the same version. kkrepo stores a source binding so version metadata, dependency metadata, signatures, checksum, and artifact bytes continue to come from the same selected member.

## Storage And Integrity

The relational database stores only bounded metadata and coordination state: collection coordinates, checksums, sizes, query projections, dependency projections, import tasks, proxy validators/negative cache, group bindings, and fenced leases.

Collection tarballs, complete `MANIFEST.json`, complete `FILES.json`, large upstream JSON documents, and signature payloads live in the configured blob store. The database keeps references, hashes, and bounded projections rather than large JSON payloads. Current hard limits include 64 KiB for version metadata, 192 KiB for dependency projections, and 256 KiB for proxy protocol projections; oversized upstream documents are reduced to the protocol fields that clients need or retained as blob content.

Before publication kkrepo verifies:

- The request and artifact SHA-256.
- The tar/gzip structure and canonical collection filename.
- `MANIFEST.json`, the referenced `FILES.json`, and per-file checksums.
- Namespace, collection name, semantic version, dependencies, and `requires_ansible`.
- Archive path, link, entry-count, expanded-size, compression-ratio, and parser limits.

Proxy artifacts are pinned to the upstream SHA-256. A checksum change for an existing upstream version fails closed instead of replacing cached content.

Proxy collection and version metadata requests persist only the bounded query projection and artifact identity; they do not download the collection tarball. The first artifact `GET` downloads and verifies the archive under a shared lease, then stores it in the blob store. Group repositories persist the selected member, filename, and checksum before that download and promote the same binding to the materialized version afterward, so another replica cannot select a different member between metadata and artifact requests.

## Multi-Replica Behavior

Import tasks, proxy state, source bindings, leases, fencing tokens, and repository revisions are shared in MySQL/PostgreSQL. Artifact and staging bytes are shared through OSS/S3. A publish or proxy miss can therefore be resumed or taken over by another replica; process-local caches and executor queues are rebuildable optimizations only.

Every replica also runs a bounded staging cleanup. By default it claims `.ansible/staging/` asset rows older than 24 hours with `FOR UPDATE SKIP LOCKED`, preserves rows owned by waiting/running import tasks, and unlinks rows whose task is missing or terminal. The global blob GC receives the blob only after its last asset reference is gone, so a crash between staging, task creation, task completion, and request cleanup cannot permanently leak collection bytes. Operators can tune the interval, initial delay, batch size, and grace period through the `KKREPO_ANSIBLE_STAGING_CLEANUP_*` environment variables; the grace period has a five-minute safety floor.

## Nexus Migration

Repository metadata migration recognizes Nexus 3.93+ `ansiblegalaxy-hosted`, `ansiblegalaxy-proxy`, and `ansiblegalaxy-group` definitions. Hosted collection data is imported only when a Nexus 3.93.x-3.94.x source profile proves the expected native datastore shape. Unknown versions, incomplete collection identity, missing integrity data, and shape drift produce a manual action rather than a guessed import.

Proxy cache migration is opt-in through **Optional proxy repositories** and requires a `FULL` source plan. Missing or masked proxy secrets leave the target proxy offline until an administrator supplies credentials. Migration supports preflight/dry-run, resume, checksum validation, idempotency, and reporting.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Discovery or install returns `404` | Confirm the repository recipe is `ansiblegalaxy-*`, the repository is online, the URL ends in `/`, and the group contains visible Ansible members |
| Publish returns `401` | Confirm the token domain, expiry/scope, Ansible 2.9 `--api-key` versus current `--token`, or the Base64 `username:password` compatibility value |
| Publish returns conflict | The collection version already exists and is immutable; increment `version` in `galaxy.yml` |
| Import task fails | Inspect the task message for filename, SHA-256, manifest/files, archive-safety, or size-limit errors |
| Proxy install fails | Verify upstream discovery, outbound/SSRF policy, credentials, redirect target, TTL state, and upstream artifact checksum |
| Group metadata and artifact differ | Check member order and source-binding diagnostics; do not combine separate repositories in the client for one coordinate |

## References

- [Ansible: Distributing collections](https://docs.ansible.com/projects/ansible/latest/dev_guide/developing_collections_distributing.html)
- [Ansible: Installing collections](https://docs.ansible.com/projects/ansible-core/devel/collections_guide/collections_installing.html)
- [Ansible: Collection metadata](https://docs.ansible.com/projects/ansible/latest/dev_guide/collections_galaxy_meta.html)
- [Sonatype: Configure Ansible with Nexus](https://help.sonatype.com/en/configure-ansible-with-nexus.html)
- [Ansible Galaxy design and compatibility notes](../zh/dev/ansible-galaxy-repository-design.md)
