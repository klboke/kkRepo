# Changelog

All notable public changes to kkrepo are documented in this file.

This project follows a pragmatic early-stage release process. Until a stable `1.0.0` release is announced, minor versions may include behavior changes, but releases should call out migration impact, compatibility changes, and operational notes.

## 0.9.0 - 2026-08-20

### Added

- Nexus-compatible Alpine APK hosted, proxy, and group repositories with canonical APK v2 publication, signed immutable indexes, ordered group resolution, proxy passthrough or verified re-signing, migration, cleanup, security scanning, and real `apk` client coverage. (#215)
- Hugging Face Models proxy repositories with model, revision, tree, refs, paths-info, and commit-pinned file routes; server-side Git LFS/Xet bridging; bounded shared-database coordination; migration, cleanup, scanning, and real `huggingface_hub`, `hf`, Transformers, and Diffusers coverage. (#224)
- Permission-aware global component search across Browse and Admin, with repository and content-selector authorization, stable keyset pagination, and adaptive MySQL query planning for large installations. (#222)
- Product-version update notifications backed by the latest public GitHub Release, with bounded caching and non-blocking UI integration. (#233)
- Per-repository proxy redirect host allowlists for explicitly trusted upstream domains while retaining the global SSRF and redirect safety policy. (#230)

### Changed

- Node-local TTL caches now use the shared cache module and factory abstraction, preserving rebuildable multi-replica semantics while removing direct server/storage coupling to Caffeine. (#226)
- Browse repository details, OIDC/LDAP settings, account and sign-in surfaces, topbar controls, and welcome-page product copy were refined for denser operation and clearer navigation. The obsolete README architecture diagram was removed. (#212, #227, #229, #231, #234, #235, #237)
- Quickstart initializes its data volume with the UID/GID of the selected JVM or Native runtime, and its defaults, Dockerfile packaging, deployment documentation, Helm application version, and optional scanner profile now use `0.9.0`. (#213)
- zstd-jni, GraalVM Native Build Tools, and the AWS SDK were refreshed. (#216, #217, #218)

### Fixed

- Proxy cache writes now record the triggering client IP, or no IP for background work, instead of placing an upstream URL in the bounded audit column; this prevents valid long proxy URLs from breaking MySQL writes without changing package-client behavior. (#211)
- MySQL component search no longer makes coordinates unmatchable when they include terms shorter than InnoDB's default full-text token size. (#223)
- Repository content paths preserve percent-encoded plus signs instead of decoding them as spaces. (#232)

### Compatibility And Validation

- Alpine and Hugging Face include MySQL/PostgreSQL persistence contracts, multi-replica leases and recovery, Nexus-shape-gated migration, protocol-aware cleanup/scanning, real-client E2E coverage, and measured large-data or Nexus performance gates. (#215, #224)
- Global search authorization is enforced before lookup and remains exact for content selectors; its database paths include million-row MySQL validation and online/concurrent index migrations for both supported databases. (#222)
- Quickstart ownership checks cover JVM and Native runtimes with both MySQL and PostgreSQL, while the audit, redirect, path-decoding, cache, and UI changes retain existing repository client protocols. (#211, #213, #226, #230, #232)

### Upgrade Notes

- Existing `0.8.0` MySQL and PostgreSQL deployments can upgrade in place through Flyway V46-V48. Back up the relational database and blob store together, allow migrations to complete before serving traffic, and do not run mixed application versions after the new schema is applied.
- V46 adds Alpine package, index, signing, lease, and migration state; V47 replaces component-search ordering indexes using online/concurrent database operations; V48 adds Hugging Face model, revision, file, cache, lease, and migration state. Apply V47 during a lower-I/O window on installations with large component tables.
- New repository formats are not created automatically. Import or configure Alpine signing trust before publication, and validate Hugging Face credentials, redirect allowlists, cleanup policies, and scanner scope before production activation.

## 0.8.0 - 2026-08-13

### Added

- Nexus-compatible Conda hosted, proxy, and group repositories with root and nested channels, `.tar.bz2` and `.conda` packages, compressed/current repodata, channeldata, ordered group resolution, migration, cleanup, scanning, and real Conda client coverage. (#186)
- APT/Debian hosted and proxy repositories with validated `.deb` publication, canonical pool paths, signed and by-hash metadata, key rotation, durable asynchronous publication, proxy passthrough or re-signing, cleanup, migration, and Debian/Ubuntu client coverage. APT groups remain unsupported because signed metadata cannot be merged safely. (#188)
- Complete Conan 2 hosted, proxy, and group support with Bearer authentication, recipe/package revisions, atomic publication, resumable uploads, Nexus-aligned Browse paths, migration, cleanup, security scanning, and real Conan client coverage. (#198)
- Dedicated English and Chinese repository guides for all 19 implemented formats, organized under one discoverable documentation hierarchy. (#201)

### Changed

- The development deployment can run the exact merged revision as equal-weight JVM and Native replicas, with coordinated health checks and rollback, so runtime-specific and multi-replica regressions surface under normal traffic. (#192)
- UI session restoration, authentication context loading, and repository permission loading are parallelized and cached safely, reducing first-page and login-state rendering from about ten seconds to below one second on repository-heavy installations. (#205)
- Quickstart defaults, Dockerfile packaging, deployment documentation, and the Helm application version now use `0.8.0`; the optional scanner profile uses the matching `kkrepo-scanner:0.8.0` image.
- AWS SDK, GraalVM Native Build Tools, the Conda setup action, and other workflow dependencies were refreshed. (#189, #190, #191)

### Fixed

- Shared proxy fetching now permits the safe same-host HTTP-to-HTTPS upgrade used by upstream mirrors while preserving redirect allowlists for cross-host, downgrade, and arbitrary-port transitions. (#195)
- Development bootstrap preserves the Nginx bind-mounted configuration when enabling dual-runtime routing. (#193)
- Native replicas now include the complete Spring Session serialization metadata needed for browser login and repeated JDBC-backed session reloads. (#203)
- Conan Browse search is format-scoped, and sparse proxy observations no longer erase an existing package projection after `conaninfo.txt` has populated it. (#202, #204)

### Compatibility And Validation

- Conda, APT, and Conan include MySQL/PostgreSQL persistence contracts, multi-replica coordination, Nexus comparisons, migration coverage, protocol-aware cleanup/scanning, and real-client E2E validation. (#186, #188, #198)
- The release includes targeted Native client validation for JDBC browser-session recovery and client E2E coverage for Conan proxy package downloads. (#203, #204)
- CI no longer treats Conan test fixtures as C++ production sources during CodeQL language detection. (#200)

### Upgrade Notes

- Existing `0.7.0` MySQL and PostgreSQL deployments can upgrade in place through Flyway V41-V45. Back up the relational database and blob store together, allow the migrations to complete before serving traffic, and do not run mixed application versions after the new schema is applied.
- V41 adds Conda registry, metadata, revision, lease, and migration state; V42-V44 add APT package, suite, signing, proxy, publication, retention, and supporting indexes; V45 adds Conan recipe/package revision, upload, source-binding, lease, and migration state.
- The new repository formats are not created automatically. Configure signing keys before exposing an APT hosted repository, and validate proxy credentials, cleanup policies, and scanner scope before production activation.

## 0.7.0 - 2026-08-06

### Added

- Opt-in asynchronous artifact security scanning with Syft/Grype, durable MySQL/PostgreSQL task and result state, CycloneDX SBOMs, vulnerability findings, audit/enforcement policies, waivers, and Admin/Browse integration. A hardened standalone scanner adapter, isolated Docker Compose profile, and multi-replica Helm deployment keep untrusted scanning work outside the kkRepo JVM and off upload request paths. (#170)
- Repository cleanup policies for every current format, targeting Hosted and Proxy repositories with bounded Try Run, manual and Quartz-scheduled execution, run history, cancellation, retries, usage-aware retention, protocol-aware deletion, and database-backed leases and fencing across replicas. (#180)
- Nexus-compatible asset search and detail APIs plus Raw hosted asset deletion, with `nx-search-read`, per-asset repository/content-selector authorization, stable keyset pagination, audit coverage, and cross-replica cache invalidation. (#171)

### Changed

- Admin, Browse, and login surfaces now share a unified design-token foundation for typography, tables, buttons, elevation, and interaction states while preserving the existing DOM and JavaScript contracts. (#162)
- Quickstart defaults, Dockerfile packaging, deployment documentation, and the Helm application version now use `0.7.0`; the optional scanner profile uses the matching `kkrepo-scanner:0.7.0` image.
- AWS SDK, Alibaba Cloud OSS, zstd-jni, GraalVM Native Build Tools, Maven Jar/Flatten plugins, and GitHub Actions dependencies were refreshed. (#164, #165, #166, #167, #168, #169, #176, #177)

### Fixed

- npm hosted publication now stream-decodes base64 attachments to bounded request-local staging files, allowing large packages beyond Jackson's default 20-million-character string limit without materializing the full archive payload in heap. (#173)
- Repository upstream DNS can be delegated to an explicitly configured HTTP or SOCKS5 proxy while direct traffic and non-repository security endpoints retain local resolution, validation, and IP pinning. Explicit private or local upstream targets remain rejected by default. (#175)
- RubyGems hosted, proxy, and group repository roots now return Nexus-compatible HTML for trailing-slash requests and the compatible `400` HTML response for bare roots, without changing explicit metadata or gem download endpoints. (#182)

### Compatibility And Validation

- Security scanning includes MySQL/PostgreSQL persistence contracts, multi-replica coordination, hardened archive and OCI staging limits, Compose/Helm network isolation checks, and an end-to-end vulnerable Maven artifact scan. (#170)
- Cleanup policies are covered across every repository format, both database backends, clustered scheduling, worker takeover, fencing, bounded scans, group-to-source usage attribution, protocol metadata updates, and restart-safe online index migrations. (#180)
- Asset management and RubyGems root behavior include live Nexus comparisons, while npm publication coverage includes a 30 MiB incompressible package through the real npm client. (#171, #173, #182)

### Upgrade Notes

- Existing `0.6.0` MySQL and PostgreSQL deployments can upgrade in place through Flyway V36-V40. Back up the database and blob store together before upgrading, allow time for DDL and online-index work on large tables, and do not run mixed application versions after the migrations are applied.
- V36-V37 add artifact-change, Blob-reference, security-scan, policy, result, and online-index state. Scanning remains disabled by default after upgrade: enabling `KKREPO_SECURITY_SCANNING_ENABLED`, deploying the matching scanner image, and activating repositories are separate explicit steps.
- V38-V40 add cleanup runtime state, clustered Quartz JobStore tables, and cleanup/scan indexes. New cleanup policies always start paused and never delete content until an administrator runs or explicitly schedules them; use a bounded Try Run and retain a tested backup before the first execution.

## 0.6.0 - 2026-07-24

### Added

- Nexus-compatible Ansible Galaxy v3 hosted, proxy, and group repositories, including immutable collection publication, dependency and artifact APIs, import-task recovery, proxy/group coordination, Components API/UI upload, Browse/Search/Admin integration, migration, metrics, and current plus Ansible 2.9 client coverage. (#158)
- npm proxy repositories can enforce a configurable minimum release age across packuments, dist-tags, cached and uncached tarball requests, and group resolution. The default remains `0`, preserving existing repository behavior until administrators opt in. (#141)
- Opt-in Spring AOT and GraalVM Native Image builds, Docker images, archive distributions, automatic launcher runtime selection, runtime hints, and real-client E2E coverage. JVM remains the default runtime. (#151)

### Changed

- Release packages now use a reproducible GitHub Actions matrix: platform-independent JVM archives plus Native Linux `amd64` and `arm64` archives, each in tar.gz and zip formats, with one combined SHA-256 manifest. The Native builds are also published as the multi-architecture `0.6.0-native` and `native-latest` GHCR images.
- Quickstart defaults, Dockerfile packaging, deployment documentation, and the Helm application version now use `0.6.0`.
- Quickstart accepts `KKREPO_RUNTIME=jvm|native`, independently of `KKREPO_DATABASE_TYPE`, and selects the matching JVM or Native release image while preserving explicit image-tag overrides.
- Native startup and memory guidance reflects the measured roughly one-second readiness and below-200-MiB idle memory baseline, while retaining the JVM recommendation for maximum warmed throughput. (#154, #155, #156)
- Maven, Bouncy Castle, XZ, AWS SDK, and GitHub Actions dependencies were refreshed. (#143, #144, #145, #146, #147, #148, #149, #150)

### Fixed

- Docker Bearer challenges and upload, blob, and manifest response locations now honor forwarded host, scheme, and port values only from configured trusted proxies, without changing explicit connector public-URL precedence. (#142)

### Compatibility And Validation

- Native packaging has passed the full real-client E2E matrix on MySQL and PostgreSQL; the release matrix builds Linux `amd64` and `arm64` executables on matching GitHub-hosted runners. (#151)
- Ansible Galaxy behavior is covered against Nexus 3.93.x-3.94.x, current Ansible clients, Ansible 2.9, dual-replica coordination, MySQL/PostgreSQL persistence, and source migration. (#158)
- npm minimum-release-age coverage includes fail-closed timestamp handling, cache revalidation, group policy, indexed MySQL/PostgreSQL state, and existing-repository compatibility. (#141)

### Upgrade Notes

- Existing `0.5.1` MySQL and PostgreSQL deployments can upgrade in place through Flyway V34-V35. Back up the database and blob store together before upgrading, and do not run mixed application versions after the new migrations are applied.
- V34 adds the rebuildable npm release-age index, and V35 adds Ansible Galaxy registry, import, cache, lease, and group-binding state. Large collection archives and package blobs remain in the configured blob store.
- Native archives require Linux and the matching `amd64` or `arm64` architecture. The `0.6.0-native` container tag is multi-architecture and lets Docker select the matching image automatically. Use the JVM archive or container image when maximum warmed throughput matters more than startup time and memory.

## 0.5.1 - 2026-07-18

### Changed

- Quickstart defaults, Dockerfile packaging, deployment documentation, and the Helm application version now use `0.5.1`.
- DNS-pinned outbound requests use Apache HttpClient 5 classic HTTP/1.1. Deployments configured with `kkrepo.proxy.remote-http-version=HTTP_2` log an explicit downgrade warning.

### Fixed

- Outbound URL validation now binds the approved DNS answers to the addresses used by direct, HTTP CONNECT, and SOCKS connections while preserving the original hostname for HTTP Host, TLS SNI, and certificate verification. Redirects are re-resolved and revalidated before connecting, closing the DNS-rebinding/TOCTOU SSRF gap in shared proxy fetches and Docker authentication flows. (#138)
- Terraform upstream paths now use `RemoteUrlBuilder`, preventing request-derived paths from replacing the configured upstream authority. (#138)

### Compatibility And Validation

- Regression coverage verifies immutable DNS snapshots, multi-address failover, direct and proxied DNS pinning, redirect revalidation, TLS hostname verification, Docker registry/token routing, and Terraform upstream URL construction. (#138)
- The full Maven reactor, focused outbound/Terraform suites, GitHub CI, CodeQL, and Codecov patch checks pass for the fix. (#138)

### Upgrade Notes

- `0.5.0` deployments should upgrade promptly, especially when proxy repositories are reachable by untrusted users or can access private networks or cloud metadata endpoints.
- This patch does not add a database migration or require configuration changes. Existing MySQL and PostgreSQL installations can upgrade in place.

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
