# Changelog

All notable public changes to kkrepo are documented in this file.

This project follows a pragmatic early-stage release process. Until a stable `1.0.0` release is announced, minor versions may include behavior changes, but releases should call out migration impact, compatibility changes, and operational notes.

## 0.5.0 - 2026-07-18

### Added

- Nexus-compatible Terraform module and provider repositories with hosted, proxy, and group recipes, official discovery endpoints, checksums and detached PGP signatures, archive validation, component upload, Browse/Search/Admin integration, metrics, and Nexus migration support. (#124)
- Swift Package Registry v1 hosted, GitHub-backed proxy, and group repositories with immutable signed publication, manifests, range and cache behavior, identifier lookup, proxy pinning, Browse/Search/Admin integration, cleanup and rebuild workers, and Nexus migration support. (#128)
- Per-repository outbound HTTP and SOCKS5 proxy settings for proxy repositories, including optional credentials, Admin UI configuration, isolated client state, redirect and SSRF controls, and support across shared remote fetch paths and Docker registry authentication flows. (#134, #136)
- A main-merge development deployment workflow for the executable Java 25 jar with health verification, retained releases, and automatic rollback while PostgreSQL and Nginx remain containerized. (#121)

### Changed

- MySQL and PostgreSQL now include equivalent V30-V33 migrations for Terraform registry state and Swift release, manifest, proxy inventory, lease, cache, and group-binding state. (#124, #128)
- Quickstart defaults, Dockerfile packaging, deployment documentation, and the Helm application version now use `0.5.0`.
- Repository permission documentation now defines actions by protocol route and operation, including protocol-specific behavior for Cargo, Pub, Swift, Terraform, and Docker. (#132)
- Pull requests remain gated by Codecov project and patch checks, while main-branch coverage uploads update the baseline without publishing misleading post-merge statuses. (#133)
- Bouncy Castle OpenPGP support was updated to `bcpg-jdk18on` 1.84. (#126)

### Fixed

- PostgreSQL duplicate asset inserts and concurrent Docker manifest upserts now roll expected unique-key conflicts back to transaction savepoints before continuing, while preserving MySQL transaction behavior and database-backed cross-replica coordination. (#117, #122)
- Helm chart parsing is safe under concurrent uploads, and decompressed `Chart.yaml` metadata is capped at 1 MiB before YAML parsing to prevent disproportionate heap use. (#120, #130)
- Docker proxy redirects keep repository credentials on same-origin HTTP-to-HTTPS upgrades, drop them for cross-origin targets, and keep bearer-token retries on the validated HTTPS endpoint. (#136)

### Compatibility And Validation

- Terraform coverage includes official CLI 0.13 and current releases, Nexus 3.92 black-box behavior, hosted and proxy/group resolution, signing continuity, multi-replica coordination, and native proxy-cache migration. (#124)
- Swift coverage includes Nexus 3.94 comparisons, SwiftPM 5.7, 5.10, and 6.x clients, platform-specific lanes, S3-compatible dual-replica resilience, backup/restore, and H2/PostgreSQL source migration. (#128)
- HTTP and SOCKS5 outbound proxy behavior includes authenticated and anonymous isolation, timeout, redirect, TLS, Docker Basic and bearer exchange, and credential-forwarding regressions. (#134, #136)
- Existing Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Docker/OCI, NuGet, RubyGems, Yum, and Raw compatibility paths remain covered by the reactor and live compatibility workflows.

### Upgrade Notes

- Existing `0.4.0` MySQL and PostgreSQL deployments can upgrade in place through Flyway V30-V33. Back up the database and blob store together before upgrading production deployments.
- Terraform and Swift add shared relational coordination and metadata state while keeping artifact blobs behind the configured OSS/S3 storage abstraction. Do not run mixed application versions against a database after the new migrations are applied.
- Validate Terraform signing and proxy/group resolution, Swift publication and migration policy, and any authenticated outbound proxy configuration in staging before enabling the new formats or network path in production.

## 0.4.0 - 2026-07-14

### Added

- First-class PostgreSQL 12+ persistence alongside MySQL, including an equivalent V29 baseline, PostgreSQL-specific JSONB, search, upsert, coordination, locking, and timestamp behavior, and startup validation that the declared database type matches JDBC metadata. MySQL remains the default backend. (#111)
- Composer / PHP hosted, proxy, and group repositories for Composer 2, including Packagist proxy caching, Nexus-style semantic dist paths, Components API/UI archive upload, canonical group resolution, Browse/Search/Usage/HTML View integration, real Composer client E2E, required Nexus live comparison, and explicitly selected Nexus proxy-cache migration. (#100)
- MySQL and PostgreSQL quickstart, development, and compatibility Compose environments, plus a multi-replica Helm chart that supports either external database backend. (#111)
- Real MySQL/PostgreSQL persistence contract suites, two-instance server smoke tests, PostgreSQL 12 minimum-version coverage, PostgreSQL 16 E2E coverage, Flyway parity checks, and expanded protocol, storage, migration, and worker test coverage. (#105, #106, #111)

### Changed

- Persistence is split into a database-neutral `persistence-jdbc` API/shared implementation and ServiceLoader-selected MySQL or PostgreSQL dialect modules. Protocol, server business logic, and Nexus migration code no longer depend on a concrete database backend. (#109, #110, #111)
- The MySQL V1-V29 migration history is preserved byte-for-byte under a backend-specific Flyway location; future migrations must keep MySQL and PostgreSQL versions logically aligned. (#111)
- Quickstart defaults, Dockerfile packaging, deployment documentation, and the Helm application version now use `0.4.0`.
- Maven reactor versioning is centralized in the root `revision` property and flattened to concrete versions in installed or deployed POMs. (#112)
- Project positioning, dependency versions, Codecov reporting, CI coverage, and contributor-facing documentation were refreshed. (#101, #102, #103, #104, #105, #106)

### Compatibility And Validation

- PostgreSQL uses the same repository, security, session, audit, token, migration, cache-watermark, worker-claim, and upload-session contracts as MySQL, including multi-replica cross-node smoke coverage. (#111)
- Composer includes protocol/server tests, a non-skipping Nexus proxy comparison, hosted-to-proxy transitive dependency resolution, Basic-auth rejection, client-cache-cleared lock replay, and Nexus 3.92 datastore migration E2E coverage. (#100)
- The executable jar and container image contain both JDBC drivers, both Flyway database modules, and both persistence backends; the backend is selected at runtime. (#111)
- Existing Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Docker/OCI, NuGet, RubyGems, Yum, and Raw compatibility paths remain covered by the reactor and live compatibility workflows.

### Upgrade Notes

- Existing v0.3.0 MySQL deployments can upgrade in place. Back up the database and blob store together before upgrading production deployments; MySQL remains the default when `KKREPO_DATABASE_TYPE` is not set.
- PostgreSQL support is intended for new PostgreSQL-backed installations or a separately planned and validated data migration. Do not switch an initialized installation between MySQL and PostgreSQL by editing the JDBC URL.
- For PostgreSQL, set `KKREPO_DATABASE_TYPE=postgresql` together with the PostgreSQL JDBC URL and credentials before first startup. Use a currently maintained PostgreSQL release in production; PostgreSQL 12 is the compatibility floor.
- Validate Composer hosted archive policy, group member order, Basic credentials, proxy caching, and explicitly selected Nexus proxy migration in staging before cutover.

## 0.3.0 - 2026-07-12

### Added

- Dart / Pub hosted, proxy, and group repositories, including `dart pub publish`, `dart pub get`, Flutter package resolution, package metadata, archive downloads, `PubToken` authentication, MySQL-backed upload sessions, UI/API upload, browse metadata, cleanup, metrics, migration support, and Nexus 3.92.0 compatibility coverage. (#86)
- Repository-format and artifact-type iconography across Browse and Administration, including precise package/archive file icons and a custom Java archive icon for JAR, WAR, EAR, and AAR assets. (#99)
- Product version and GitHub project links in the Browse and Administration headers. (#97, #98)
- Nginx reverse-proxy deployment guidance and a Nexus-to-kkRepo migration case study. (#72, #78)
- Design references for OpenHarmony ohpm repositories and Dart / Pub repository compatibility. (#80, #85)

### Changed

- Quickstart defaults now use `ghcr.io/klboke/kkrepo:0.3.0`.
- Legacy Nexus Rapture, ExtDirect, Wonderland, internal UI, and legacy component-upload endpoints are disabled by default through `kkrepo.nexus.legacy-ui.enabled=false`; supported REST and repository protocol endpoints remain available. (#87)
- Fresh installations start with anonymous access disabled and explicitly choose anonymous access during initial administrator setup. Existing configured installations are not rewritten. Anonymous identity and role reads now use the refreshable security catalog snapshot with MySQL watermark propagation across replicas. (#96)
- Repository privilege filtering accepts wildcard action grants while preserving concrete action checks. (#73, #79)
- Admin create/edit flows use consistent modal dialogs, recipe-aware repository selectors, and clearer form filtering. (#82)
- Browse and Administration use a vendored Lucide icon system, clearer sortable-column indicators, synchronized tree/detail asset icons, quieter inline URL copy actions, and a less interactive-looking Welcome capability showcase. (#99)
- Project documentation, screenshots, support links, compatibility coverage, and GitHub Actions dependencies were refreshed. (#63, #64, #74, #75, #76, #77, #89, #99)

### Fixed

- Fixed RubyGems migration validation for dependency assets whose blob size differs from the generated dependency payload size. (#65)
- Fixed group repository cache expiry so member-specific maximum ages are honored. (#71)
- Fixed Nexus `ALL` action permission matching and wildcard privilege filtering without broadening concrete authorization checks. (#73, #79)
- Fixed credentialless proxy redirects to CDN-backed upstream content while retaining outbound host policy validation. (#86)
- Fixed fresh-install anonymous defaults and removed the configuration-file fallback as a competing source of truth. (#96)
- Fixed UI affordance and accessibility inconsistencies around icons, file types, URL copying, static capability cards, and sortable headers. (#99)

### Compatibility And Validation

- Dart / Pub includes focused protocol and server tests, Nexus reference black-box tests, real `dart`/Flutter client E2E coverage, and datastore-era migration coverage. (#86)
- Main-branch CI and CodeQL passed on the release baseline, including the Browse and Administration icon contract suites. (#99)
- Existing Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Docker/OCI, NuGet, RubyGems, Yum, and Raw compatibility paths remain covered by the reactor and live compatibility workflows.

### Upgrade Notes

- Existing 0.2.0 deployments can upgrade in place. Back up MySQL before upgrading production deployments.
- This release adds Flyway migrations for MySQL-backed Pub upload sessions and the secure anonymous-access default for databases that have not completed initial administrator setup.
- Legacy Nexus UI compatibility routes now default to disabled. Deployments that still run compatibility tests or integrations against those legacy UI-only endpoints must explicitly set `KKREPO_NEXUS_LEGACY_UI_ENABLED=true`; normal repository clients and supported REST APIs do not require it.
- Validate Dart / Pub repository configuration, PubToken handling, proxy/group behavior, and archive migration in staging before opening the new format to production clients.

## 0.2.0 - 2026-07-01

### Added

- Docker / OCI repository support for hosted, proxy, and group repositories, including Registry HTTP API V2 login, push/pull, tag and manifest handling, blob upload sessions, cross-repository blob mounts, proxy cache, group resolution, connector-port access, OCI referrers, Docker browse metadata, cleanup workers, and Docker-specific metrics. Docker V1 API and `docker search` remain non-goals unless a future migration case requires a search-only shim. (#39)
- Cargo / Rust repository support for hosted, proxy, and group repositories using sparse registries, including `cargo publish`, fetch/download, yank/unyank, Cargo search, `CargoToken` authentication, UI/API `.crate` upload, and Cargo metrics. (#49)
- Cargo repository migration support for datastore-era Nexus Repository sources, plus generated `config.json` visibility in browse and repository flows. (#62)
- Multi-version Nexus migration support with source profiles, adapter-specific migration planning, source/plan hashes, and expanded migration preflight details in the admin UI. The automated migration E2E matrix now covers Nexus 3.29.2, Nexus 3.77.2 with H2 datastore, and Nexus 3.77.2 with PostgreSQL datastore. (#51, #62)
- Real client E2E compatibility suite for Maven, npm, PyPI, Go, Helm, Cargo/Rust, NuGet, RubyGems, Yum, and Docker/OCI clients against a disposable kkRepo candidate. The suite can be triggered through the `client-e2e` live compatibility path or the `run-client-e2e` PR label. (#57)
- OCI Distribution conformance workflow for Docker/OCI repository behavior. (#39)
- `RubyGemsApiKey` and `GenericToken` are now exposed in user and admin token dropdowns. `GenericToken` supports domain-prefixed bearer authentication and custom HTTP clients that send the configured API-key header or bearer token. (#60)
- UI language settings backed by MySQL, with browser-following, English, and Chinese options shared across replicas. (#43)
- Maven and PyPI private repository blog/tutorial content, plus expanded Cargo, Docker/OCI, migration, compatibility, troubleshooting, monitoring, and security documentation in English and Chinese. (#46, #52, #57, #60, #62)

### Changed

- README, compatibility matrix, client recipes, operations docs, roadmap, and migration docs now list Docker/OCI and Cargo/Rust as implemented repository capabilities.
- Quickstart defaults now use `ghcr.io/klboke/kkrepo:0.2.0`.
- Admin navigation groups are collapsible, remember their state in `localStorage`, keep the active route identifiable, and scroll independently on dense screens. (#53)
- Admin forms now show required markers and perform submit-time validation consistently across security, blob store, and migration settings. Migration forms require the source password and no longer ask users to provide a source version manually. (#42, #44)
- Anonymous access and realm settings now keep the `Local` realm/source fixed for anonymous behavior, including backend validation when API callers submit another source. (#45)
- Packaged async request timeout is now 10 minutes in the runtime defaults and archive distribution config. (#37)
- Aliyun OSS client creation now honors configured connection pool limits and connection acquisition timeout on the Apache5 transport. (#32)
- Project and contributor automation docs were refreshed for agent-friendly issue templates, repository instructions, compatibility-risk prompts, CodeQL scanning, Dependabot, and current GitHub Actions versions. (#40)
- Core runtime, storage, and workflow dependencies were updated, including commons-lang3, RE2/J, AWS SDK, Aliyun OSS SDK, and GitHub Actions checkout. (#34, #35, #36, #54, #55, #56)

### Fixed

- Fixed CodeQL-reported security issues: component search tokenization no longer uses a vulnerable regex path, proxy remote URL construction cannot override the configured remote host, browse listings use framework HTML escaping with XSS regression coverage, and OIDC endpoints are validated against outbound policy and issuer/discovery host checks before redirect or token exchange. (#41)
- Fixed OIDC admin validation so incomplete OIDC settings are blocked before save instead of failing later during login. (#42)
- Fixed Docker/OCI edge cases around OCI referrer metadata, connector review issues, CodeQL findings, OCI conformance setup, group cache invalidation, remote client behavior, and Docker migration paths. (#39)
- Fixed Cargo index version alignment, upload/auth review feedback, robust missing-index handling with clean 404 behavior, and Cargo/RubyGems rebuild handling. (#49)
- Fixed NuGet client push endpoint variants and API-key handling in the real client E2E suite. (#57)
- Fixed RubyGems client E2E behavior for API-key push, build directories, install metadata, source ordering, and isolated GEM_HOME installs. (#57)
- Fixed Helm, npm, Cargo, and Docker/OCI client E2E setup issues, including Helm pull output directories, npm publish directories, Cargo project/credential setup, and relative ORAS artifact paths. (#57)
- Fixed migration E2E blob-storage race conditions discovered while expanding the Nexus version matrix. (#62)

### Compatibility And Validation

- Docker/OCI changes include server and protocol tests, Docker client compatibility scripts, migration scripts, and OCI Distribution conformance workflow coverage. (#39)
- Cargo/Rust changes include focused unit tests, Nexus 3.77.x+ live compatibility checks, hosted/proxy/group coverage, token/auth checks, conditional request behavior, and real client read/write flows. (#49)
- Migration changes include multi-version migration E2E coverage for OrientDB-era and datastore-era Nexus sources, with adapter expectations validated in CI. (#62)
- The new real client E2E suite validates package publish/download/resolve behavior through actual CLI clients instead of only protocol-level HTTP tests. (#57)

### Upgrade Notes

- Existing 0.1.0 deployments can upgrade in place. The release adds Flyway migrations for Docker registry metadata, Docker connector-port uniqueness, and shared UI settings.
- Run the normal database backup procedure before upgrading production deployments, then deploy the 0.2.0 image or archive package and allow Flyway to apply the new schema.
- Docker/OCI and Cargo/Rust are new public capabilities in this release. Validate repository configuration, anonymous access, token type, connector port, and proxy/group behavior in a staging environment before opening them to production clients.

## 0.1.0 - 2026-06-15

### Added

- First public release of kkrepo.
- Public Docker image on GitHub Container Registry.
- Archive distributions as `.zip` and `.tar.gz` with SHA-256 checksums.
- Quickstart script for local trials with Docker Compose and MySQL.
- Nexus-compatible repository entrypoint under `/repository/<repo>/...`.
- Initial support for Maven, npm, PyPI, Go, Helm, NuGet, RubyGems, Yum, and Raw repositories.
- Admin console under `/admin/` and user repository browser under `/browse/`.
- MySQL-backed metadata, identity, permissions, token, audit, migration, and coordination state.
- OSS/S3/File blob storage support.
- Nexus migration tooling for metadata and repository data migration.
- Compatibility test module for Nexus reference behavior checks.

### Notes

- Production deployments should use external MySQL and OSS/S3-compatible blob storage.
- File blob storage is intended for local trials, development, and specific deployments with carefully managed shared storage.
- Security fixes currently target the latest `main` branch unless a release branch is explicitly announced.
