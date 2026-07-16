# FAQ

## What is kkrepo?

kkrepo is a Nexus-compatible, self-hosted artifact repository for common package formats such as Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform, Docker/OCI, NuGet, RubyGems, Yum, and Raw.

It keeps Nexus-like client URLs, protocol behavior, permissions, and migration goals while using MySQL for metadata and OSS/S3-compatible storage for blobs.

## Is kkrepo a fork of Sonatype Nexus?

No. kkrepo is an independent implementation. Nexus is used as a compatibility reference for client-visible behavior, but kkrepo does not copy Nexus internals such as OrientDB, embedded Elasticsearch, Karaf, OSGi, or the Nexus task subsystem.

## Is it a full replacement for Nexus?

It depends on your usage.

kkrepo is designed for teams that need Nexus-compatible client paths, common repository formats, MySQL- or PostgreSQL-backed metadata, object storage, multi-replica-friendly behavior, and migration from existing Nexus deployments.

It is not a full clone of every Nexus feature or every Nexus UI/API endpoint. Check the [Compatibility Matrix](compatibility-matrix.md) before planning production migration.

## Which repository formats are supported?

Current supported formats:

- Maven
- npm
- PyPI
- Go
- Helm
- Cargo / Rust
- Dart / Pub
- Composer / PHP
- Terraform Provider / Module Registry
- Docker / OCI
- NuGet
- RubyGems
- Yum
- Raw

## Does kkrepo keep the same client URLs?

For supported non-Docker formats, the main client URL shape is compatible with Nexus:

```text
/repository/<repo>/<artifact-path>
```

This helps preserve Maven, npm, pip, Helm, Cargo, Dart/Flutter Pub, Composer, Terraform, NuGet, RubyGems, Yum, Raw, and CI client configuration during migration for formats covered by the migration flow.

Docker / OCI uses the Registry HTTP API V2 `/v2/...` route instead of `/repository/<repo>/...`: shared-entrypoint deployments use `<host>/<repo>/<image>:<tag>`, and repository-level connector ports can expose `<host>:<repo-port>/<image>:<tag>`.

## Which Database Can kkrepo Use?

kkrepo supports MySQL 8 and PostgreSQL 12+; MySQL is the default for existing installations. PostgreSQL 12 is the compatibility floor, while production should use a maintained PostgreSQL release. The selected database is the source of truth for:

- Repository metadata.
- Components and assets.
- Users, roles, permissions, sessions, API keys, and audit logs.
- Migration state.
- Cross-replica coordination state.

This avoids relying on embedded databases or local-only state for production correctness.

Use the same executable/container image and set `KKREPO_DATABASE_TYPE`. Do not switch an existing installation between engines by only changing its JDBC URL. See [Database Backends](database-backends.md).

## Does kkrepo require Redis?

No. The default cache backend is process-local memory, and correctness is backed by the shared database. In-process caches are rebuildable hot caches with TTL or database-backed invalidation watermarks.

## Where are artifact files stored?

Artifact bytes are stored in blob storage:

- OSS/S3-compatible storage for production.
- File storage for local trials, tests, and carefully managed shared-filesystem deployments.

MySQL stores metadata and references, not large artifact bytes.

## Can I use File blob storage in production?

OSS/S3-compatible storage is recommended for production.

File blob storage is safe for production only when every replica mounts the same strongly consistent shared filesystem and file storage is explicitly enabled for production. For ordinary production deployments, use OSS/S3.

## Does kkrepo support high availability?

kkrepo is designed for multi-replica deployment:

- Sessions use Spring Session JDBC.
- Short-lived authentication tickets live in MySQL.
- Migration state and background worker coordination live in MySQL.
- Node-local caches are rebuildable.
- Blob content lives in shared object storage.

You still need a reliable MySQL deployment, shared blob storage, load balancing, monitoring, and backups.

## How do I migrate from Nexus?

Use the `/admin/` console:

1. Run `Nexus Metadata` preflight.
2. Run metadata migration.
3. Run `Nexus Repository Data` metadata sync.
4. Run package sync.
5. Repeat incremental sync before cutover.
6. Move traffic to kkrepo.

See [Nexus Migration Guide](nexus-migration-guide.md) and [Migration Playbook](migration-playbook.md).

## Do users need to change client configuration after migration?

If you move the original Nexus domain to kkrepo and keep repository names the same, most supported non-Docker clients can continue using the same `/repository/<repo>/...` URLs.

If the domain or repository names change, clients must update configuration.

## Does migration copy proxy repositories?

Hosted repositories are scanned by default. Proxy repositories can be migrated explicitly when you want historical cache data or upstream backup data. Otherwise, proxy repositories can refill from upstream after cutover.

## What happens to user passwords and API keys during migration?

Migration tries to preserve compatible security data where the source Nexus exposes enough information. Some local users may need password reset if password hashes cannot be compensated. API keys or protocol tokens may need to be reissued depending on source data availability and security policy.

Always run preflight and review migration reports before cutover.

## Is Docker / OCI supported?

Docker / OCI Registry hosted, proxy, and group repositories are implemented for Registry HTTP API V2 client workflows. Use Docker's `/v2/...` route: shared-entrypoint deployments use `<host>/<repo>/<image>:<tag>`, and repository-level connector ports can expose `<host>:<repo-port>/<image>:<tag>` when configured.

Hosted Docker repository migration is supported through the Nexus Repository Data flow. Docker Registry V1 API and `docker search` are not part of the current supported surface; modern Docker/OCI workflows use Registry V2 and OCI Distribution.

Do not assume Docker pull/push works through `/repository/<repo>/...`.

## Is Cargo / Rust supported?

Cargo hosted, proxy, and group repositories are supported through the Cargo sparse registry protocol. kkrepo supports `cargo publish`, fetch/download, yank/unyank, `cargo search`, `CargoToken` authentication, and hosted `.crate` upload through the UI/API.

Nexus Cargo hosted repository migration is supported for datastore-era H2/PostgreSQL sources when preflight proves the Cargo content model. Unknown schema fingerprints fail closed in the migration plan, and OrientDB-era sources do not enable Cargo content export.

## Is Dart / Pub supported?

Dart / Pub hosted, proxy, and group repositories are supported through the Hosted Pub Repository V2 protocol. kkrepo supports `dart pub publish`, `dart pub get`, `flutter pub get`, `PubToken` bearer authentication, package/version metadata, archive download, Pub search, and hosted `.tar.gz` upload through the UI/API.

Nexus 3.92.0 Pub hosted repository migration is supported when preflight proves the Pub content model. Explicitly selected Pub proxy cache migration is also supported when the migration plan is `FULL`; otherwise proxy repositories refill from upstream after cutover.

## Is Composer / PHP supported?

Composer hosted, proxy, and group repositories support Composer 2 repository metadata. kkrepo provides `packages.json`, stable/dev p2 metadata, Nexus-style `vendor/package/version/*.zip` dist paths, HTTP Basic, group canonical first-match behavior, Components API/UI archive upload, Browse/Search, HTML View, and Usage snippets.

Composer has no standard publish command, so hosted packages are zip/tar archives containing `composer.json` uploaded through the Components API or UI. Native Nexus Composer is proxy-only; migration preserves proxy semantics, and cache content is migrated only when an administrator explicitly selects the source repository and preflight proves the Composer content model.

## Is Terraform Provider / Module Registry supported?

Yes. Terraform hosted, proxy, and group repositories implement the Module Registry and Provider Registry protocols using Nexus-compatible `/repository/<repo>/v1/modules/...` and `/v1/providers/...` paths. kkRepo supports module/provider upload, versions and platforms, registry.terraform.io proxying, group resolution, URL-token authentication, provider SHA256SUMS, hosted detached GPG signing, Browse/Search/Usage, and real `terraform init` validation on Terraform 0.13 and the current stable release.

Configure Terraform CLI `host.services` to point `modules.v1` and `providers.v1` at the selected group. Root-domain discovery and the Provider Network Mirror Protocol are not part of the current public surface. Nexus Terraform hosted data and proxy/group configuration are migratable. When a native Nexus Terraform proxy is explicitly selected and its migration plan is `FULL`, kkrepo also restores module/provider archive caches through a proxy-only path. Restored module archives are discoverable without an upstream request; the target reconstructs and verifies the current provider route, checksum manifest, and signature snapshot before serving the provider cache pinned to that metadata snapshot.

## Is kkrepo production-ready?

kkrepo is early-stage open source software with a first public release. It already includes important production-oriented architecture choices, but each deployment should validate:

- Required repository formats.
- Client compatibility.
- Migration behavior.
- Backup and restore.
- Monitoring.
- Security model.
- Load and object-storage throughput.

Use [Production Hardening Guide](production-hardening.md) before exposing production traffic.

## How do I report a bug?

Use GitHub issues and choose the closest issue template. Include:

- kkrepo version or commit.
- Deployment mode.
- Repository format and type.
- Client command or HTTP request.
- Expected and actual behavior.
- Sanitized logs.

For Nexus behavior differences, use the compatibility issue template and include Nexus and kkrepo responses for the same request.

## How do I report a security issue?

Do not open a public issue for exploitable vulnerabilities.

Follow [SECURITY.md](../../SECURITY.md) and report privately through GitHub Security Advisories.

## What license does kkrepo use?

kkrepo is licensed under the [Apache License 2.0](../../LICENSE).

## Where can I ask questions?

Use:

- GitHub issues for bugs, compatibility differences, feature requests, and documentation problems.
- The [kkrepo Telegram group](https://t.me/+UbIsTKXTzxBhYjFl) for community discussion.
- GitHub Security Advisories for exploitable security issues.

See [SUPPORT.md](../../SUPPORT.md).
