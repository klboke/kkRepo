# nexus-plus

[Chinese version](README.cn.md)

`nexus-plus` is a Nexus-compatible, self-hosted artifact repository for Maven, npm, PyPI, Go, Helm, NuGet, RubyGems, and Yum artifacts.

The project keeps Nexus-compatible client protocols, the Nexus permission/authentication model, and the `/repository/<repo>/...` URL layout while addressing stability, external database support, multi-replica deployment, and usage-limit issues found in Nexus Repository OSS / Community Edition. It also supports smooth migration from an existing Nexus instance to nexus-plus.

- Java and Spring Boot based service
- MySQL metadata and identity data
- MySQL-coordinated runtime state plus in-process TTL cache
- OSS/S3/File blob storage
- Nexus-compatible `/repository/<repo>/...` protocol entrypoint and permission model
- One-click migration tooling for existing Nexus instances, with zero-downtime migration to nexus-plus
- Lightweight operations console under `/admin/`
- User-facing repository browser under `/browse/`

## Build And Deployment

Local quick start, Spring Boot executable jar, Docker image, archive package, production deployment architecture, resource sizing, and upgrade flow are documented in the [Build And Deployment Guide](docs/es/build-deployment-guide.md).

Local hot-reload development and testing are documented in the [Development Guide](docs/es/development-guide.md).

## Supported Capabilities

| Format | Repository types | Client publish/upload | Browse and search | Nexus migration |
| --- | --- | --- | --- | --- |
| Maven | hosted / proxy / group | Maven deploy, PUT upload, and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| npm | hosted / proxy / group | `npm publish`, dist-tag, and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| PyPI | hosted / proxy / group | twine upload and admin UI upload | simple index supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Go | proxy / group | Go module proxy is mainly read-only proxy; hosted upload is not supported | Supported | Proxy repositories can be migrated optionally |
| Helm | hosted / proxy | chart push, PUT upload, and admin UI upload | index.yaml supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| NuGet | hosted / proxy / group | package push and admin UI upload | v3 service index / search supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| RubyGems | hosted / proxy / group | gem push/yank and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Yum | hosted / proxy / group | RPM upload and admin UI upload | repodata supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |

Nexus Repository Data migration scans hosted repositories by default. If you need to migrate proxy repositories from the source Nexus as historical backup data or upstream cache data, explicitly specify repository names in `Optional proxy repositories` on the migration page.

## Migrating From Nexus

Migration is available in the `/admin/` console:

1. Enable Script REST API script creation on the source Nexus.
2. On the `Nexus Metadata` page, run `Run preflight` first, then run `Run migration` after blocking issues are resolved.
3. On the `Nexus Repository Data` page, run `Sync metadata` to migrate repository metadata, then run `Sync packages` to migrate the real blob data.
4. For the first repository data migration, leave `Metadata since` empty to scan all data. Later runs can set `Metadata since` for incremental migration.
5. After migration is complete, point the original Nexus domain to nexus-plus. Client configuration does not need to change.

Migration supports interruption and resume. Completed data is skipped on later runs. See the [Nexus Migration Guide](docs/es/nexus-migration-guide.md) for the full process.

## Comparison With Open Source Nexus

| Dimension | Nexus Repository OSS / Community Edition | nexus-plus |
| --- | --- | --- |
| Product positioning | A general-purpose artifact repository management platform with broad format and management coverage | Keeps Nexus client behavior, permission model, and `/repository/<repo>/...` URL layout compatible while addressing OSS embedded database stability issues, Community Edition usage limits, limited horizontal scaling in open source editions, and providing zero-downtime migration to nexus-plus |
| Supported formats | Officially supports more formats; exact capabilities vary by version and distribution | Focuses on common artifact formats. Currently supports Maven, npm, PyPI, Go, Helm, NuGet, RubyGems, and Yum. Each format is implemented as an independent protocol module for prioritized extension and validation |
| Usage limits | Community Edition targets individuals and small teams. Official limits are up to 40,000 components and 100,000 requests/day. When exceeded, new component creation is paused until usage returns below the limits | Does not include Community Edition-style license usage limits. Capacity is bounded by MySQL, OSS/S3, replica count, and deployment sizing, so it can scale with actual business needs |
| High availability deployment | Open source editions are suitable for a single instance or basic Kubernetes deployment; official HA deployment is a Pro capability | Designed for multi-replica deployment by default: session, authentication tickets, catalog watermarks, locks, migration progress, and short-lived coordination state are stored in MySQL. In-process cache is only a rebuildable hot cache |
| Stability and upgrade | Version boundaries are complex: 3.70.x is the last version supporting OrientDB; 3.71.0 defaults new installs to H2, but H2 is still embedded; Community Edition did not support free external PostgreSQL until 3.77.0+; search was fully moved to SQL and away from Elasticsearch only in 3.88.0. Older OrientDB/Elasticsearch/local-data-directory deployments carry heavy upgrade windows and recovery depends heavily on backups, repair tasks, and manual intervention | MySQL-first runtime with no dependency on OrientDB or embedded Elasticsearch. Core state is in MySQL, blobs are in OSS/S3/File blob store, and cache/index data is rebuildable, making rolling upgrade, failover, and recovery easier |
| Metadata storage | Historical versions moved across OrientDB, H2, PostgreSQL, and related migration paths. Older instances must handle database migration constraints during upgrade | MySQL-first: repositories, components, assets, permissions, tokens, audit logs, migration state, and rebuildable indexes use explicit table structures for easier troubleshooting, governance, and horizontal scaling |
| Blob storage | Common deployments use local file blob store; object storage availability depends on version and configuration | OSS/S3-first, with File blob store retained for development and testing. MySQL stores only metadata, state, indexes, and references, not large blobs |
| Search and indexing | Before 3.88.0, Nexus search and indexing were based on embedded Elasticsearch, with index files and database state separated. Index corruption or inconsistency requires Nexus repair/rebuild tasks | Uses MySQL denormalized indexes and protocol-derived metadata. browse/search/index data is designed to be rebuildable, and node-local cache loss does not affect correctness |
| Architecture complexity | Nexus is feature-rich and carries many general management capabilities and historical architecture mechanisms | nexus-plus keeps the architecture simple and focuses on repository management and client protocol implementation |

## Selection Guidance

- If your business scale is very small, package count and traffic are within Community Edition limits, and occasional maintenance downtime is acceptable, prefer the open source Nexus version.
- If stability, scalability, and multi-replica deployment matter, or if you manage a large number of packages, use nexus-plus.
- If an existing Nexus instance runs into component-count or daily-request limits after upgrading to a newer Community Edition version, you can use the nexus-plus one-click migration capability to migrate to nexus-plus with zero downtime.

## UI Overview

### User UI

The user UI is for artifact consumers. It provides repository lists, package search, directory browsing, artifact details, and upload entrypoints.

The repository list shows hosted, proxy, and group repositories with format, status, and access URLs so users can copy client configuration URLs directly.

![User repository list](docs/img/img_7.png)

Search components by format across Maven, npm, PyPI, Go, Helm, NuGet, RubyGems, Yum, and other repository types.

![User artifact search](docs/img/img.png)

Directory browsing shows repository path trees, artifact summaries, checksums, content type, update time, and client usage snippets.

![User directory browsing and artifact details](docs/img/img_1.png)

The upload page lets users select a repository, upload files, and set asset paths for manual publishing to hosted repositories.

![User artifact upload](docs/img/img_2.png)

### Admin UI

The admin UI is for repository administrators and focuses on repository configuration, storage health, security configuration, audit, and Nexus migration.

The Blob Store page supports OSS Native SDK, AWS S3 SDK, and File engines, and shows read/write probe health.

![Admin Blob Store management](docs/img/img_4.png)

The OIDC page manages issuer, JWKS, client, scope, claim mapping, and token validation parameters for integrating with centralized identity systems.

![Admin OIDC configuration](docs/img/img_3.png)

The Nexus Metadata migration page migrates users, roles, privileges, blob stores, and repository definitions, and supports preflight checks.

![Admin Nexus metadata migration](docs/img/img_5.png)

The Nexus Repository Data migration page shows hosted repository data migration tasks, concurrency settings, progress statistics, failure counts, and per-repository details.

![Admin Nexus repository data migration](docs/img/img_6.png)

AI agent and contributor development instructions are in [AGENTS.md](AGENTS.md).

## Roadmap

- Add support for more artifact repository formats based on community feedback.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the contributor workflow, PR expectations, compatibility testing expectations, and multi-replica design constraints.

Local development and testing are documented in the [Development Guide](docs/es/development-guide.md). Build and deployment are documented in the [Build And Deployment Guide](docs/es/build-deployment-guide.md). AI agent and contributor constraints are in [AGENTS.md](AGENTS.md).

## Security

If you find a security issue, follow [SECURITY.md](SECURITY.md) and report it through GitHub Security Advisory first. Avoid disclosing exploitable details in public issues. Regular bugs, compatibility issues, and feature requests can be submitted as public issues.

## License

nexus-plus is open sourced under the [Apache License 2.0](LICENSE).

## Documentation

- [Development Guide](docs/es/development-guide.md)
- [Build And Deployment Guide](docs/es/build-deployment-guide.md)
- [MySQL ER Design](docs/es/mysql-er.md)
- [Nexus Migration Guide](docs/es/nexus-migration-guide.md)
- [Monitoring And Observability Guide](docs/es/monitoring-observability-guide.md)
- [Nexus Compatibility Testing](docs/es/nexus-compatibility-testing.md)
- [Why We Built nexus-plus To Replace Nexus](docs/es/why-nexus-plus.md)
