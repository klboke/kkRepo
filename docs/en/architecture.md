# Architecture

kkrepo is a Nexus-compatible artifact repository built around a MySQL-first metadata model, OSS/S3-first blob storage, and multi-replica-safe runtime behavior.

The goal is not to copy Nexus internals. kkrepo keeps client-visible protocol behavior, permissions, and `/repository/<repo>/...` URLs compatible while avoiding OrientDB, embedded Elasticsearch, Karaf, OSGi, and local-only blob state as production requirements.

## High-Level Topology

```text
Artifact clients
  Maven / npm / PyPI / Go / Helm / Cargo/Rust / Dart/Pub / Docker/OCI / NuGet / RubyGems / Yum / Raw
        |
        v
Load balancer / reverse proxy
        |
        v
kkrepo replicas
  server
  protocol modules
  admin-ui / browse-ui static assets
        |
        | metadata, users, permissions, sessions, locks, migration state
        v
      MySQL
        |
        | blob references, checksums, object keys
        v
  OSS / S3 / File blob storage
```

Every replica can serve repository traffic. Correctness must not depend on one JVM owning unique in-memory state.

## Request Flow

Repository client requests use the Nexus-compatible path:

```text
/repository/<repo>/<artifact-path>
```

Docker / OCI clients use Registry HTTP API V2 routes instead:

```text
/v2/<repo>/<image>/...
/v2/<image>/...
```

The second shape is used on repository-level Docker connector ports, where the local listening port maps to one Docker repository.

The server flow is:

1. `RepositoryContentController` resolves `<repo>` from MySQL-backed repository metadata.
2. Security filters authenticate the subject and check repository permissions.
3. The server dispatches to the protocol implementation based on repository format and type.
4. Hosted repositories read/write assets and metadata through MySQL transactions and blob storage.
5. Proxy repositories fetch upstream content, persist cacheable assets, and use negative cache where safe.
6. Group repositories resolve members in configured order and cache rebuildable member-hit decisions.
7. Responses are streamed from blob storage or generated metadata assets.

Admin UI and Browse UI are served by the Spring Boot service as static assets:

- Admin console: `/admin/`
- User browser: `/browse/`
- Health and metrics: management port, usually `8081`

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `core` | Repository, component, asset, blob, protocol abstractions, and shared auth contracts |
| `storage-s3` | OSS/S3-compatible blob storage implementation |
| `storage-file` | Local/test file blob storage implementation |
| `cache` | Rebuildable in-process TTL cache abstractions |
| `protocol-maven` | Maven parsing, metadata, layout, and policy helpers |
| `protocol-npm` | npm path and metadata helpers |
| `protocol-pypi` | PyPI protocol helpers |
| `protocol-go` | Go module proxy helpers |
| `protocol-helm` | Helm chart and index helpers |
| `protocol-cargo` | Cargo sparse registry path, index, publish body, checksum, and error helpers |
| `protocol-pub` | Dart Pub package name, hosted URL, path, archive, version metadata, and error helpers |
| `protocol-docker` | Docker Registry V2 / OCI path, digest, manifest, media type, and error helpers |
| `protocol-nuget` | NuGet path helpers |
| `protocol-rubygems` | RubyGems metadata helpers |
| `protocol-yum` | Yum/RPM metadata helpers |
| `persistence-mysql` | MySQL DAOs, models, JSON/enum/hash helpers |
| `migration-nexus` | Nexus metadata and security migration support |
| `server` | Spring Boot runtime, controllers, services, filters, workers |
| `admin-ui` | Static admin console resources |
| `browse-ui` | Static repository browser resources |
| `compat-test` | Black-box and protocol compatibility tests |

Protocol logic should live in services and protocol modules, not in controllers.

## Data Ownership

MySQL stores:

- Repository definitions and group membership.
- Components, assets, browse nodes, and search indexes.
- Blob references, checksums, object keys, sizes, and content type.
- Users, roles, privileges, realms, API keys, sessions, and audit logs.
- Migration jobs, migration assets, cursors, markers, and checkpoints.
- Cache version watermarks and cross-replica coordination state.

Blob storage stores:

- Artifact bytes.
- Cached upstream artifacts.
- Generated or migrated blob objects.

MySQL stores references to blobs, not large blob payloads.

## Blob Storage

Production deployments should use OSS/S3-compatible storage. File blob storage exists for local trials, tests, and carefully managed shared-filesystem deployments.

Blob writes follow this shape:

1. Stream request or upstream response to a temporary location.
2. Calculate checksums and size.
3. Persist or reuse a blob object in the selected blob store.
4. Write MySQL asset/blob metadata in a transaction.
5. Clean up temporary files or abandoned staging objects.

Blob metadata and object references should be transactionally visible only after the content is safely stored.

## Multi-Replica Model

kkrepo assumes multiple application replicas by default:

- HTTP sessions use Spring Session JDBC.
- Authentication tickets live in MySQL and expire quickly.
- Repository, security, and blob-store catalog changes bump MySQL-backed cache watermarks.
- Node-local caches are rebuildable and have TTL or explicit invalidation rules.
- Background workers use MySQL claims, markers, or cursors rather than local-only queues.
- Migration progress and retry state are persisted in MySQL.

If a replica restarts or loses local cache, correctness should remain intact. The worst acceptable result is extra database or object-storage reads while caches warm up.

## Caching Layers

The default cache backend is in-process memory. Caches are used for performance, not correctness:

- Asset metadata cache.
- Repository token/version local cache.
- Group member hit cache.
- npm group packument cache.
- PyPI group simple index cache.
- Basic-auth result cache keyed by a secret-derived HMAC.
- Security authorization cache.
- Catalog snapshots for repository/security/blob-store metadata.

Cache invalidation relies on TTLs and MySQL version watermarks. Operators can tune cache sizes and TTLs with `KKREPO_*` environment variables.

## Security Architecture

Authentication sources include:

- Local users.
- LDAP realm.
- OIDC realm.
- API keys and protocol tokens.
- HTTP sessions.

Authorization uses Nexus-style repository privileges with actions such as browse, read, add, edit, and delete. Security-sensitive configuration and user-facing API-key payloads are encrypted at rest with stable deployment secrets.

See [Security Model](security-model.md).

## Migration Architecture

Migration is split into two major surfaces:

- `Nexus Metadata`: preflight and migration of source metadata such as users, roles, privileges, blob stores, and repositories.
- `Nexus Repository Data`: repository asset discovery and package/blob migration.

Metadata migration may use source Nexus Script REST API when regular REST APIs cannot expose required data. Repository data migration records source assets in MySQL first, then workers migrate blobs with retry and checksum validation.

See [Migration Playbook](migration-playbook.md).

## Observability

The management port exposes:

- `/actuator/health`
- `/actuator/metrics`
- `/actuator/prometheus`

Repository traffic metrics are labeled by repository, format, method, status, and operation family where possible. See [Monitoring And Observability Guide](monitoring-observability-guide.md).

## What Is Not Copied From Nexus

kkrepo intentionally avoids copying these Nexus internals unless a user-visible compatibility need requires an adapter:

- OrientDB.
- Embedded Elasticsearch.
- Karaf and OSGi runtime model.
- Nexus task subsystem as an internal implementation dependency.
- Local persistent blob filesystem as the only production blob store.

The compatibility target is client-visible behavior and migration usability, not implementation parity.
