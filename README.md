# kkRepo

[![CI](https://github.com/klboke/kkrepo/actions/workflows/ci.yml/badge.svg)](https://github.com/klboke/kkrepo/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/klboke/kkRepo/branch/main/graph/badge.svg)](https://codecov.io/gh/klboke/kkRepo)
[![Release](https://img.shields.io/github/v/release/klboke/kkrepo)](https://github.com/klboke/kkrepo/releases)
[![License](https://img.shields.io/github/license/klboke/kkrepo)](LICENSE)
[![Container](https://img.shields.io/badge/ghcr.io-kkrepo-blue)](https://github.com/klboke/kkrepo/pkgs/container/kkrepo)
[![Security Policy](https://img.shields.io/badge/security-policy-green)](SECURITY.md)

**English** | [中文](README.cn.md)

kkRepo is a community-driven, fully open-source, self-hosted artifact repository designed to address the limitations and pain points of Sonatype Nexus Community Edition and provide the community with an open, reliable, and sustainably evolving artifact management solution. It currently supports Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform, Swift Package Registry, Ansible Galaxy, Conda, APT/Debian, Conan 2, Alpine/APK, R/CRAN, Docker/OCI, NuGet, RubyGems, Yum, Raw, and other artifact formats.

## Features

- Support for 20+ mainstream repository formats across hosted, proxy, and group repository types.
- Supports AOT compilation and runtime, with about one-second startup readiness and memory usage below 200 MB.
- Per-repository outbound HTTP or SOCKS5 proxy configuration for proxy repositories, with optional proxy authentication and HTTPS upstream tunneling.
- Artifact security scanning with CycloneDX SBOM generation, known-vulnerability matching, policy and waiver management, and optional download enforcement.
- Rich artifact cleanup policies for enterprise-grade artifact lifecycle management.
- Compatibility with Sonatype Nexus APIs, user permission model, and the `/repository/<repo>/...` URL layout.
- Use kkRepo as a drop-in replacement for Sonatype Nexus, with one-click migration of existing data while preserving repository domains and URLs, so client configurations and CI workflows continue unchanged.
- Comprehensive identity and access control with Local, LDAP, and OIDC authentication, configurable anonymous access policies, and fine-grained permissions.
- Comprehensive observability with Prometheus metrics export and Grafana dashboards.
- MySQL or PostgreSQL-backed metadata and shared runtime state; MySQL remains the default.
- OSS/S3/File storage support for artifact blobs.
- Multi-replica high-availability deployment support.

## Trademark Notice

Sonatype, Nexus, and Nexus Repository are trademarks of Sonatype, Inc. kkRepo is an independent open source project and is not affiliated with, endorsed by, sponsored by, or connected to Sonatype, Inc. References to Sonatype Nexus Repository are used only to describe compatibility, migration, or interoperability.

## Quick Start

Start a local trial environment with the public release image and MySQL:

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

To run the same image with the default PostgreSQL 16 quickstart instead (the runtime supports PostgreSQL 12+):

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | KKREPO_DATABASE_TYPE=postgresql bash
```

To use the multi-architecture Native image instead of the default JVM image:

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | KKREPO_RUNTIME=native bash
```

`KKREPO_RUNTIME` and `KKREPO_DATABASE_TYPE` are independent and can be combined, for example with `KKREPO_RUNTIME=native KKREPO_DATABASE_TYPE=postgresql`.

Open:

- Admin console: `http://127.0.0.1:19090/admin/`
- User browser: `http://127.0.0.1:19090/browse/`
- Health check: `http://127.0.0.1:19091/actuator/health`

On the first visit, create the initial `Local/admin` administrator password in the UI. The quickstart uses File blob storage for local trials; use OSS/S3 and your own encryption secrets for production.

If you prefer to inspect the script before running it, download `scripts/quickstart.sh` first and then run it with `bash`.

## Build And Deployment

Local quick start, Spring Boot executable jar, Docker image, archive package, production deployment architecture, resource sizing, and upgrade flow are documented in the [Build And Deployment Guide](docs/en/build-deployment-guide.md).

For measured startup, memory, image-size, and warmed-throughput tradeoffs between the default JVM runtime and the opt-in Native Image runtime, see the [Native Image or JVM Selection Guide](docs/en/native-vs-jvm-guide.md).

If kkRepo is deployed behind Nginx or another HTTPS reverse proxy, follow the [Nginx Reverse Proxy Notes](docs/en/nginx-reverse-proxy.md) so generated repository URLs, such as npm `dist.tarball`, keep the public `https://` scheme and host.

Local hot-reload development and testing are documented in the [Development Guide](docs/en/development-guide.md).

## Supported Capabilities

| Format | Repository types | Client publish/upload | Browse and search | Nexus migration |
| --- | --- | --- | --- | --- |
| Maven | hosted / proxy / group | Maven deploy, PUT upload, and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| npm | hosted / proxy / group | `npm publish`, dist-tag, and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| PyPI | hosted / proxy / group | twine upload and admin UI upload | simple index supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Go | hosted / proxy / group | Nexus-compatible `<version>.zip` PUT plus UI/API upload; Go clients resolve private hosted and public proxied modules through one group | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Helm | hosted / proxy | chart push, PUT upload, and admin UI upload | index.yaml supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Cargo / Rust | hosted / proxy / group | `cargo publish`, yank/unyank, `CargoToken` auth, and UI/API `.crate` upload | Sparse index and `cargo search` supported | Cargo repository migration is supported |
| Dart / Pub | hosted / proxy / group | `dart pub publish`, `dart pub get`, `flutter pub get`, `PubToken` auth, and UI/API `.tar.gz` upload | Package/version metadata, archive attributes, and Pub search supported | Nexus 3.92.0 Pub hosted migration and explicitly selected proxy cache migration are supported |
| Composer / PHP | hosted / proxy / group | Composer has no standard publish command; Components API and UI zip/tar archive upload plus Composer 2 installation are supported | Package/version metadata, dist, HTML View, Browse/Search, and Usage supported | Native Nexus Composer proxy configuration is migrated; cache migration requires explicit administrator selection and a proven source profile |
| Terraform Provider / Module Registry | hosted / proxy / group | Nexus-compatible PUT and UI/API archive upload; `terraform init` resolves hosted and proxied modules/providers through groups | Module/provider coordinates, versions, platforms, Browse/Search, and Usage supported | Nexus Terraform hosted data and explicitly selected proxy archive caches are migrated; proxy/group configuration is also migrated |
| Swift Package Registry | hosted / proxy / group | `swift package-registry publish`, Basic/Bearer login, and UI/API source archive upload | Registry v1 release/manifest/archive metadata, Browse/Search, and Usage supported | Swift hosted data is `FULL` only for verified Nexus 3.92.x-3.94.x datastore shapes; drift and unavailable proxy secrets require manual action |
| Ansible Galaxy | hosted / proxy / group | `ansible-galaxy collection publish`, Nexus-compatible raw PUT, route-scoped Base64 Bearer/Ansible 2.9 Token credentials, GenericToken, and UI/API collection upload ([usage guide](docs/en/repository-guides/ansible-galaxy.md)) | Galaxy v3 collection/version/artifact metadata, dependencies, Browse/Search, and Usage supported | Nexus 3.93.x-3.94.x repository definitions and shape-gated hosted/proxy collection data are supported; unknown datastore shapes fail closed |
| Conda | hosted / proxy / group | Nexus-compatible raw PUT plus UI/API upload for `.tar.bz2` and `.conda` packages | Channel/subdir metadata, JSON/BZ2/ZSTD repodata, channeldata, Browse/Search, and Usage supported | Repository definitions and shape-gated Nexus 3.92.x-3.94.x hosted package data are supported; generated metadata is rebuilt on the target |
| APT / Debian | hosted / proxy | Nexus-compatible repository-root POST and Components API/UI `.deb` upload; real `apt update`, download, install, and upgrade ([usage guide](docs/en/repository-guides/apt-debian.md)) | Signed Packages/Release metadata, by-hash, Browse/Search, and Usage supported | Repository definitions and shape-gated Nexus 3.92.x-3.94.x hosted packages are supported; signing keys require explicit import before migrated repositories are enabled |
| Conan 2 | hosted / proxy / group | `conan upload`, Conan bearer login, and UI/API revision-file upload ([usage guide](docs/en/repository-guides/conan-2.md)) | RREV/PREV metadata, Nexus-aligned write-time Browse paths, Search, and Usage supported | Nexus 3.94 repository definitions and shape-gated hosted or explicitly selected proxy-cache revisions are supported; Conan 1 and unknown shapes fail closed |
| Alpine / APK | hosted / proxy / group | Canonical APK v2 PUT and UI/Components API upload; real `apk update`, search, policy, fetch, install, and upgrade ([usage guide](docs/en/repository-guides/alpine-apk.md)) | Signed `APKINDEX.tar.gz`, passthrough/verified re-sign proxy, ordered group source binding, Browse/Search, and Usage supported | Nexus 3.94 definitions and shape-gated hosted packages are supported; generated indexes are rebuilt and unavailable signing keys or proxy secrets fail closed |
| R / CRAN | hosted / proxy / group | Canonical source `.tar.gz` PUT and UI/Components API upload; real `available.packages()`, install, and update flows ([usage guide](docs/en/repository-guides/r-cran.md)) | Deterministic `PACKAGES.gz`, arbitrary-file proxy, snapshot-bound group, Browse/Search, Cleanup, scanning, and Usage supported | Nexus 3.94 definitions and shape-gated hosted source packages are supported; generated indexes and bindings are rebuilt |
| Hugging Face Models | proxy | `hf download`, `hf_hub_download`, `snapshot_download`, Transformers, and Diffusers through `HF_ENDPOINT` ([usage guide](docs/en/repository-guides/hugging-face-models.md)) | Commit-pinned model metadata/files, server-side LFS/Xet bridge, Browse/Search, Cleanup, and scanning supported | Nexus 3.77+ definitions are recognized; explicitly selected Nexus 3.94 proxy cache is shape-gated and unknown identities fail closed |
| Docker / OCI | hosted / proxy / group | Registry V2 login, hosted push/pull, proxy pull, group pull, OCI referrers, cleanup, and connector-port access | Manifest/tag/blob metadata supported | Hosted Docker repository data migration is supported through the Nexus Repository Data flow |
| NuGet | hosted / proxy / group | package push and admin UI upload | v3 service index / search supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| RubyGems | hosted / proxy / group | gem push/yank and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Yum | hosted / proxy / group | RPM upload and admin UI upload | repodata supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |
| Raw | hosted / proxy / group | PUT upload and admin UI upload | Supported | Hosted repositories are migrated by default; proxy repositories can be migrated optionally |

## Migrating From Sonatype Nexus Repository

Migration is available in the `/admin/` console:

1. Enable Script REST API script creation on the source Sonatype Nexus Repository deployment.
2. On the `Nexus Metadata` page, run `Run preflight` first, then run `Run migration` after blocking issues are resolved.
3. On the `Nexus Repository Data` page, run `Sync metadata` to migrate repository metadata, then run `Sync packages` to migrate the real blob data.
4. For the first repository data migration, leave `Metadata since` empty to scan all data. Later runs can set `Metadata since` for incremental migration.
5. After migration is complete, point the original repository domain to kkRepo. Non-Docker clients can keep the same `/repository/<repo>/...` URLs; Docker clients should keep the same `/v2/...` registry entrypoint, repository names, and connector/path-based routing shape.

Migration supports interruption and resume. Completed data is skipped on later runs. See the [Nexus Migration Guide](docs/en/nexus-migration-guide.md) for the full process.

## Compatibility And Migration Context

| Dimension | Sonatype Nexus Repository OSS / Community Edition | kkRepo |
| --- | --- | --- |
| Product positioning | A general-purpose artifact repository management platform with broad format and management coverage | Provides migration-oriented client behavior, permission model, and `/repository/<repo>/...` URL compatibility while using a relational-database, OSS/S3-first, multi-replica-friendly architecture |
| Supported formats | Officially supports more formats; exact capabilities vary by version and distribution | Focuses on common artifact formats. Currently supports Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform, Swift Package Registry, Ansible Galaxy, Conda, APT/Debian, Conan 2, Alpine/APK, R/CRAN, Docker/OCI, NuGet, RubyGems, Yum, and Raw. Each format is implemented as an independent protocol module for prioritized extension and validation |
| Usage limits | Community Edition targets individuals and small teams. Official limits are up to 40,000 components and 100,000 requests/day. When exceeded, new component creation is paused until usage returns below the limits | Does not include Community Edition-style license usage limits. Capacity is bounded by the selected relational database, OSS/S3, replica count, and deployment sizing, so it can scale with actual business needs |
| High availability deployment | Open source editions are suitable for a single instance or basic Kubernetes deployment; official HA deployment is a Pro capability | Designed for multi-replica deployment by default: session, authentication tickets, catalog watermarks, locks, migration progress, and short-lived coordination state are stored in MySQL or PostgreSQL. In-process cache is only a rebuildable hot cache |
| Stability and upgrade | Version boundaries are complex: 3.70.x is the last version supporting OrientDB; 3.71.0 defaults new installs to H2, but H2 is still embedded; Community Edition did not support free external PostgreSQL until 3.77.0+; search was fully moved to SQL and away from Elasticsearch only in 3.88.0. Older OrientDB/Elasticsearch/local-data-directory deployments carry heavy upgrade windows and recovery depends heavily on backups, repair tasks, and manual intervention | MySQL/PostgreSQL runtime with no dependency on OrientDB or embedded Elasticsearch. Core state is in the shared relational database, blobs are in OSS/S3/File blob store, and cache/index data is rebuildable, making rolling upgrade, failover, and recovery easier |
| Metadata storage | Historical versions moved across OrientDB, H2, PostgreSQL, and related migration paths. Older instances must handle database migration constraints during upgrade | Repositories, components, assets, permissions, tokens, audit logs, migration state, and rebuildable indexes use explicit MySQL or PostgreSQL table structures for easier troubleshooting, governance, and horizontal scaling |
| Blob storage | Common deployments use local file blob store; object storage availability depends on version and configuration | OSS/S3-first, with File blob store retained for development and testing. The relational database stores only metadata, state, indexes, and references, not large blobs |
| Search and indexing | Before 3.88.0, Nexus search and indexing were based on embedded Elasticsearch, with index files and database state separated. Index corruption or inconsistency requires Nexus repair/rebuild tasks | Uses relational-database denormalized indexes and protocol-derived metadata. browse/search/index data is designed to be rebuildable, and node-local cache loss does not affect correctness |
| Architecture complexity | Nexus Repository is feature-rich and carries many general management capabilities and historical architecture mechanisms | kkRepo keeps the architecture simple and focuses on repository management and client protocol implementation |

## Selection Guidance

- If your business scale is very small, package count and traffic are within Community Edition limits, and occasional maintenance downtime is acceptable, the open source Sonatype Nexus Repository edition may be sufficient.
- If stability, scalability, and multi-replica deployment matter, or if you manage a large number of packages, kkRepo is designed for that deployment shape.
- If an existing Sonatype Nexus Repository instance runs into component-count or daily-request limits after upgrading to a newer Community Edition version, kkRepo provides a one-click migration flow designed for zero-downtime migration.

## UI Overview

### User UI

The user UI is for artifact consumers. It provides repository lists, package search, directory browsing, artifact details, and upload entrypoints.

The repository list shows hosted, proxy, and group repositories with format, status, and access URLs so users can copy client configuration URLs directly.

![User repository list](docs/img/img_7.png)

Search components by format across Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform, Swift Package Registry, Ansible Galaxy, Conda, APT/Debian, Conan 2, Alpine/APK, R/CRAN, Hugging Face Models, Docker/OCI, NuGet, RubyGems, Yum, Raw, and other repository types.

![User artifact search](docs/img/img.png)

Directory browsing shows repository path trees, artifact summaries, checksums, content type, update time, and client usage snippets.

![User directory browsing and artifact details](docs/img/img_1.png)

The upload page lets users select a repository, upload files, and set asset paths for manual publishing to hosted repositories.

![User artifact upload](docs/img/img_2.png)

### Admin UI

The admin UI is for repository administrators and focuses on repository configuration, storage health, security configuration, audit, and migration.

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

Platform infrastructure roadmap:

1. ✅ PostgreSQL database backend - Implemented through the public `persistence-jdbc` contracts, semantic dialect SPIs, backend-owned Flyway migrations, dual-database contract tests, and multi-replica server smoke tests. MySQL remains the default backend ([database backend guide](docs/en/database-backends.md), [Chinese design plan](docs/zh/dev/pluggable-database-access-layer-design.md)).
2. ✅ Artifact security scanning - Implemented behind a disabled-by-default deployment capability gate. While disabled, upgrades do not process historical artifacts or add upload-outbox writes. After an operator explicitly enables capability, administrators still activate repositories individually in Admin UI; a feature-neutral transactional asset-change outbox then keeps uploads independent from asynchronous SBOM/known-vulnerability analysis. Durable multi-replica coordination, Docker/OCI multi-platform coverage, policy/waiver evaluation, and optional download enforcement are included ([usage guide](docs/en/artifact-scanning-guide.md), [Chinese design notes](docs/zh/dev/security-scanning-design.md)).

Repository format roadmap:

1. ✅ Docker / OCI Registry - Completed ([implementation notes](docs/en/dev/docker-repository-implementation-plan.md))
2. ✅ Cargo / Rust - Repository support completed, including search, UI/API upload, and migration ([design notes](docs/en/dev/cargo-rust-repository-design.md))
3. ✅ Dart / Pub - Repository support completed, including hosted/proxy/group, client E2E, UI/API upload, search, and Nexus migration ([Chinese design notes](docs/zh/dev/dart-pub-repository-design.md))
4. ✅ Composer / PHP - Hosted, proxy, group, UI/API upload, search, real-client E2E, required Nexus live comparison, and explicitly selected Nexus proxy-cache migration E2E implemented ([Chinese design notes](docs/zh/dev/composer-php-repository-design.md))
5. ✅ Terraform Provider / Module Registry - Hosted, proxy, group, provider GPG signing, Nexus-compatible paths, UI/API upload, search, real Terraform CLI E2E, Nexus hosted-data migration, and explicitly selected proxy-cache migration implemented ([Chinese design notes](docs/zh/dev/terraform-repository-design.md))
6. ✅ Swift Package Registry - Hosted, GitHub-backed proxy, group, Registry v1, immutable signed publication, UI/API upload, Browse/Search, multi-replica coordination, real SwiftPM/Xcode E2E, and shape-gated Nexus 3.92.x-3.94.x migration are implemented ([Chinese design notes](docs/zh/dev/swift-package-registry-design.md))
7. ✅ Ansible Galaxy - Galaxy v3 hosted/proxy/group, immutable collection publishing, dependency resolution, route-scoped Base64 Bearer/Ansible 2.9 Token and GenericToken authentication, UI/API upload, Browse/Search, durable multi-replica import/proxy coordination, real Ansible 2.9/current client E2E, Nexus black-box compatibility, and shape-gated Nexus 3.93.x-3.94.x migration are implemented ([usage guide](docs/en/repository-guides/ansible-galaxy.md), [Chinese design notes](docs/zh/dev/ansible-galaxy-repository-design.md))
8. ✅ Conda - Hosted, proxy, group, `.tar.bz2`/`.conda` publication, JSON/BZ2/ZSTD channel metadata, UI/API upload, Browse/Search, multi-replica coordination, real Conda client E2E, Nexus black-box fixtures, and shape-gated Nexus 3.92.x-3.94.x hosted-data migration E2E are implemented ([Chinese design notes](docs/zh/dev/conda-repository-design.md))
9. ✅ APT / Debian - Hosted/proxy repositories, signed metadata, UI/API upload, Browse/Search, multi-replica publication, real APT client and Nexus black-box checks, shape-gated migration, and a reproducible Nexus performance baseline are implemented ([usage guide](docs/en/repository-guides/apt-debian.md), [Chinese design notes](docs/zh/dev/apt-debian-repository-design.md), [performance baseline](docs/en/dev/apt-performance-baseline.md))
10. ✅ Conan 2 - Hosted/proxy/group, Conan bearer authentication, manifest-gated revision publication, write-time Nexus Browse projection, UI/API upload, Cleanup, composite package scanning, shape-gated migration, real Conan client E2E, and dual-database Nexus performance baselines are implemented ([usage guide](docs/en/repository-guides/conan-2.md), [Chinese design notes](docs/zh/dev/conan-repository-design.md), [performance baseline](docs/en/dev/conan-performance-baseline.md))
11. ✅ Alpine / APK - Hosted/proxy/group, APK v2 package validation, signed immutable indexes, passthrough/verified re-sign proxying, ordered group source binding, UI/API upload, Browse/Search, Cleanup, scanning, shape-gated Nexus 3.94 migration, apk-tools 2.14/3.0 client E2E, and reproducible Nexus performance gates are implemented ([usage guide](docs/en/repository-guides/alpine-apk.md), [Chinese design notes](docs/zh/dev/alpine-apk-repository-design.md), [performance baseline](docs/en/dev/alpine-performance-baseline.md))
12. ✅ Hugging Face Models - Models-only proxy repositories with commit-pinned metadata/files, server-side LFS/Xet bridging, real Hub/Transformers/Diffusers clients, multi-replica S3 coordination, scanning, Cleanup, and shape-gated Nexus migration ([usage guide](docs/en/repository-guides/hugging-face-models.md), [Chinese design notes](docs/zh/dev/hugging-face-models-repository-design.md), [performance baseline](docs/en/dev/hugging-face-models-performance-baseline.md)).
13. ✅ R / CRAN - Hosted/proxy/group, bounded source-package validation, deterministic `PACKAGES.gz`, lazy verified proxying, snapshot-bound group resolution, UI/API upload, Browse/Search, Cleanup, partial-coverage scanning, shape-gated Nexus 3.94 migration, R 4.5/4.6 client E2E, indexed dual-database access, and reproducible Nexus performance gates are implemented ([usage guide](docs/en/repository-guides/r-cran.md), [Chinese design notes](docs/zh/dev/r-cran-repository-design.md), [performance baseline](docs/en/dev/r-cran-performance-baseline.md)).
14. ✅ Go - Hosted/proxy/group recipes, Nexus-compatible module ZIP publication, official path/version/archive validation, ordered group aggregation, Cleanup, artifact scanning, UI/API upload, migration, real Go client E2E, Nexus 3.94 black-box comparison, and a reproducible performance baseline are implemented ([usage guide](docs/en/repository-guides/go.md), [performance baseline](docs/en/dev/go-hosted-performance-baseline.md)).
15. ohpm / HarmonyOS - Planned with hosted, proxy, group, import, and admin capabilities ([Chinese design notes](docs/zh/dev/ohpm-repository-design.md))
16. Helm group repositories - Planned to complete Nexus-compatible ordered aggregation across hosted, proxy, and group members.
17. Git Large File Storage (LFS) - Planned with Nexus-compatible hosted storage for large Git-managed assets.
18. Eclipse p2 - Planned with Nexus-compatible proxy repositories for Eclipse and Equinox update sites.
19. CocoaPods - Planned with Nexus-compatible proxy repositories for CocoaPods dependencies.

Token types exposed in the user and admin UI include protocol-specific tokens (`NpmToken`, `CargoToken`, `PubToken`, `NuGetApiKey`, `RubyGemsApiKey`) plus `GenericToken` for Terraform service URLs, Ansible Galaxy clients, CI, scripts, and custom HTTP clients that can send the configured API-key header or bearer token.

## Who Is Using kkRepo?

If your company, enterprise, or organization uses kkRepo, you are welcome to share your deployment in the [user registration issue](https://github.com/klboke/kkRepo/issues/94). This issue is for usage registration only; please use the regular issue templates for bug reports and feature requests.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the contributor workflow, PR expectations, compatibility testing expectations, and multi-replica design constraints. Community behavior expectations are documented in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Local development and testing are documented in the [Development Guide](docs/en/development-guide.md). Build and deployment are documented in the [Build And Deployment Guide](docs/en/build-deployment-guide.md). AI agent and contributor constraints are in [AGENTS.md](AGENTS.md).

## Support

Join the [kkRepo Telegram group](https://t.me/+UbIsTKXTzxBhYjFl) for community support and usage discussion. See [SUPPORT.md](SUPPORT.md) for issue routing, support scope, and security-reporting boundaries.

## Sponsors

Thank you to everyone supporting the ongoing development and maintenance of kkRepo. You can support the project through [GitHub Sponsors](https://github.com/sponsors/klboke).

### Organization Sponsors

Active organization sponsors will be featured here with their logo and link.

### Project Backers

Active project backers will be recognized here with their name or logo and optional link.

## Security

If you find a security issue, follow [SECURITY.md](SECURITY.md) and report it through GitHub Security Advisory first. Avoid disclosing exploitable details in public issues. Regular bugs, compatibility issues, and feature requests can be submitted as public issues.

## License

kkRepo is open sourced under the [Apache License 2.0](LICENSE).

## Documentation

- [Development Guide](docs/en/development-guide.md)
- [Build And Deployment Guide](docs/en/build-deployment-guide.md)
- [Artifact Scanning Guide](docs/en/artifact-scanning-guide.md)
- [Cleanup Policy Guide](docs/en/cleanup-policy-guide.md)
- [Repository Usage Guide](docs/en/repository-guides/README.md)
- [Native Image or JVM Selection Guide](docs/en/native-vs-jvm-guide.md)
- [Nginx Reverse Proxy Notes](docs/en/nginx-reverse-proxy.md)
- [Architecture](docs/en/architecture.md)
- [Compatibility Matrix](docs/en/compatibility-matrix.md)
- [Troubleshooting Guide](docs/en/troubleshooting.md)
- [Production Hardening Guide](docs/en/production-hardening.md)
- [Backup And Restore Guide](docs/en/backup-restore.md)
- [Security Model](docs/en/security-model.md)
- [MySQL ER Design](docs/en/mysql-er.md)
- [Database Backends](docs/en/database-backends.md)
- [Database Schema](docs/en/database-schema.md)
- [Nexus Migration Guide](docs/en/nexus-migration-guide.md)
- [Nexus Migration Playbook](docs/en/migration-playbook.md)
- [Monitoring And Observability Guide](docs/en/monitoring-observability-guide.md)
- [Nexus Compatibility Testing](docs/en/nexus-compatibility-testing.md)
- [FAQ](docs/en/faq.md)
- [Why We Built kkRepo](docs/en/why-kkrepo.md)
- [Changelog](CHANGELOG.md)
