# Changelog

All notable public changes to kkrepo are documented in this file.

This project follows a pragmatic early-stage release process. Until a stable `1.0.0` release is announced, minor versions may include behavior changes, but releases should call out migration impact, compatibility changes, and operational notes.

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
- Existing Maven, npm, PyPI, Go, Helm, Cargo/Rust, Docker/OCI, NuGet, RubyGems, Yum, and Raw compatibility paths remain covered by the reactor and live compatibility workflows.

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
