# FAQ

## What is kkrepo?

kkrepo is a Nexus-compatible, self-hosted artifact repository for common package formats such as Maven, npm, PyPI, Go, Helm, Cargo/Rust, Docker/OCI, NuGet, RubyGems, Yum, and Raw.

It keeps Nexus-like client URLs, protocol behavior, permissions, and migration goals while using MySQL for metadata and OSS/S3-compatible storage for blobs.

## Is kkrepo a fork of Sonatype Nexus?

No. kkrepo is an independent implementation. Nexus is used as a compatibility reference for client-visible behavior, but kkrepo does not copy Nexus internals such as OrientDB, embedded Elasticsearch, Karaf, OSGi, or the Nexus task subsystem.

## Is it a full replacement for Nexus?

It depends on your usage.

kkrepo is designed for teams that need Nexus-compatible client paths, common repository formats, MySQL-backed metadata, object storage, multi-replica-friendly behavior, and migration from existing Nexus deployments.

It is not a full clone of every Nexus feature or every Nexus UI/API endpoint. Check the [Compatibility Matrix](compatibility-matrix.md) before planning production migration.

## Which repository formats are supported?

Current supported formats:

- Maven
- npm
- PyPI
- Go
- Helm
- Cargo / Rust
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

This helps preserve Maven, npm, pip, Helm, Cargo, NuGet, RubyGems, Yum, Raw, and CI client configuration during migration for formats covered by the migration flow.

Docker / OCI uses the Registry HTTP API V2 `/v2/...` route instead of `/repository/<repo>/...`: shared-entrypoint deployments use `<host>/<repo>/<image>:<tag>`, and repository-level connector ports can expose `<host>:<repo-port>/<image>:<tag>`.

## Why MySQL?

MySQL is used as the source of truth for:

- Repository metadata.
- Components and assets.
- Users, roles, permissions, sessions, API keys, and audit logs.
- Migration state.
- Cross-replica coordination state.

This avoids relying on embedded databases or local-only state for production correctness.

## Does kkrepo require Redis?

No. The default cache backend is process-local memory, and correctness is backed by MySQL. In-process caches are rebuildable hot caches with TTL or MySQL-backed invalidation watermarks.

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

Nexus Cargo repository migration is currently TBD. Nexus Community Cargo support starts from the 3.77.x datastore-era H2/PostgreSQL shape, so Cargo repositories should not be treated as covered by the existing Nexus migration flow.

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
- The [kkrepo Telegram group](https://t.me/+M6prtFUGnF9kYTU1) for community discussion.
- GitHub Security Advisories for exploitable security issues.

See [SUPPORT.md](../../SUPPORT.md).
