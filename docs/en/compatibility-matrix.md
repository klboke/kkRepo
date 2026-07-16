# Compatibility Matrix

This matrix summarizes the public compatibility surface of kkrepo. It is intentionally user-visible: client commands, HTTP paths, repository recipes, migration support, and known limits. Internal Nexus implementation details are not compatibility targets unless they affect client behavior.

For deeper validation workflow details, see [Nexus Compatibility Testing](nexus-compatibility-testing.md).

The validation classes listed below are black-box protocol checks. The `client-e2e` suite adds real package-client coverage across Maven, npm, PyPI, Go resolve, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform 0.13/current, SwiftPM/Xcode, NuGet, RubyGems, Yum, and Docker/OCI; see [compat-test README](../../compat-test/README.md) for the runner requirements and `artifacts/client-e2e/` diagnostics.

## Compatibility Principles

- Keep the Nexus `/repository/<repo>/...` URL layout for existing client configuration.
- Match official protocol behavior and Nexus client-visible behavior before adding project-specific behavior.
- Prefer compatibility tests against a real Nexus reference instance for externally visible behavior.
- Keep stateful behavior multi-replica safe: the selected MySQL/PostgreSQL database is the source of truth for metadata and coordination; blob content lives in OSS/S3/File storage; in-process caches must be rebuildable.

## Database Backend Matrix

| Backend | Runtime | Flyway | Shared persistence contracts | Two-instance server smoke |
| --- | --- | --- | --- | --- |
| MySQL 8 | Supported; default | Immutable V1-V29 history, paired migrations from V30 | Real MySQL container | Fresh/repeat startup and cross-node session |
| PostgreSQL 12+ | Supported; use a maintained release in production | Equivalent V29 baseline, paired migrations from V30 | PostgreSQL 12 minimum-version contract plus PostgreSQL 16 E2E | Fresh/repeat startup and cross-node session on PostgreSQL 12 |

Database choice does not change repository protocol behavior. CI runs the same JDBC API contract against both engines; see [Database Backends](database-backends.md).

## Repository Format Matrix

| Format | Repository types | Main client operations | Browse/search | Migration support | Compatibility validation |
| --- | --- | --- | --- | --- | --- |
| Maven | hosted / proxy / group | Maven deploy, PUT upload, GET/HEAD/checksum reads, snapshot and release metadata behavior, admin UI component upload | Supported | Hosted by default; proxy optional | `MavenRepositoryBlackBoxCompatibilityTest`, `MavenMetadataMergeCompatibilityTest`, `MavenWritePolicyCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| npm | hosted / proxy / group | `npm publish`, tarball download, package metadata, dist-tags, audit endpoint compatibility, admin UI upload | Supported | Hosted by default; proxy optional | `NpmProtocolCompatibilityTest`, `NpmRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| PyPI | hosted / proxy / group | `twine upload`, package download, simple index reads, admin UI upload | Simple index supported | Hosted by default; proxy optional | `PypiRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Go | proxy / group | Go module proxy reads: list, info, mod, zip, latest, group fallback | Supported | Proxy optional | `GoProxyBlackBoxCompatibilityTest` |
| Helm | hosted / proxy | Chart push, PUT upload, chart download, `index.yaml`, proxy index rewrite, admin UI upload | `index.yaml` supported | Hosted by default; proxy optional | `HelmRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Cargo / Rust | hosted / proxy / group | Sparse registry reads, `cargo publish`, `.crate` download, yank/unyank, Cargo search, CargoToken auth, UI/API `.crate` upload | Sparse index and Cargo search supported | Hosted on datastore H2/PostgreSQL when the source profile proves Cargo content; proxy optional only when explicitly selected and planned `FULL` | `CargoRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Dart / Pub | hosted / proxy / group | `dart pub publish`, `dart pub get`, `flutter pub get`, package metadata, archive download, Nexus `api/archives` download alias, `archive_sha256`, PubToken auth, UI/API `.tar.gz` upload | Package/version metadata and archive attributes supported | Hosted full migration when the Nexus 3.92.0 datastore source profile proves Pub content; proxy cache migration only when explicitly selected for backup and planned `FULL` | `PubRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Composer / PHP | hosted / proxy / group | Composer 2 `install/show`, `packages.json`, stable/dev p2 metadata, Nexus-style dist paths, Basic auth, Components API/UI archive upload, and group canonical first-match behavior | Package/version metadata, dist, HTML View, Browse/Search, and Usage supported | Nexus-native Composer is proxy-only; after configuration migration, cache content is migrated only when explicitly selected through `backupProxyRepositories` and the source profile proves the content model | `ComposerRepositoryBlackBoxCompatibilityTest`, Composer server/protocol tests, real Composer client E2E, migration E2E |
| Terraform Provider / Module Registry | hosted / proxy / group | Module/provider versions and downloads, Nexus-compatible PUT/UI/API upload, provider platforms, SHA256SUMS, detached GPG signatures, URL-token auth, registry.terraform.io proxying, and group source binding | Module/provider coordinates, versions, platforms, HTML View, Browse/Search, and Usage supported; internal route/cache assets are hidden | Nexus Terraform hosted full migration, explicitly selected proxy archive-cache migration, and proxy/group configuration migration | `TerraformRepositoryBlackBoxCompatibilityTest`, Terraform server/protocol tests, Terraform 0.13/current client E2E, real Nexus proxy migration E2E |
| Swift Package Registry | hosted / proxy / group | Registry v1 release list/metadata/manifest/archive/identifiers, `swift package-registry login/publish`, GitHub-backed proxy, SCM replacement, CMS signatures, immutable publication, Range/cache validators, and group source binding | Scope/package/version, checksum, signature, tools version, source member, Browse/Search, and Usage supported | Hosted data is `FULL` only for Nexus 3.92.x-3.94.x with a verified Swift datastore shape; out-of-range/drifted profiles and unavailable proxy secrets require manual action | `SwiftRepositoryBlackBoxCompatibilityTest`, Swift protocol/server contracts, SwiftPM 5.7/5.10/6.x, macOS Xcode, Windows proxy, S3-compatible dual-replica resilience, and migration E2E |
| NuGet | hosted / proxy / group | Package push, package download, v3 service index, registration, flat container, search/autocomplete, admin UI upload | v3 service index/search supported | Hosted by default; proxy optional | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| RubyGems | hosted / proxy / group | Gem push/yank, gem download, compact and legacy index assets, admin UI upload | Supported | Hosted by default; proxy optional | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| Yum | hosted / proxy / group | RPM PUT/upload, package download, `repodata` metadata | `repodata` supported | Hosted by default; proxy optional | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| Raw | hosted / proxy / group | PUT upload, GET/HEAD reads, group/proxy fallback, admin UI upload | Supported | Hosted by default; proxy optional | `RawRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Docker / OCI | hosted / proxy / group | Registry V2 login, hosted push/pull, proxy pull, group pull, manifests, blobs, tags, upload sessions, cross-repo mount, referrers, content cleanup, Docker Hub `library` namespace compensation | Manifest/tag/blob metadata supported | Hosted Docker repository data migration supported through Nexus Repository Data | `DockerRegistryBlackBoxCompatibilityTest`, Docker server/protocol tests, OCI conformance workflow, [Docker / OCI implementation notes](dev/docker-repository-implementation-plan.md) |

Swift evidence is intentionally separated by level. Nexus 3.94.x comparison covers canonical JSON/`Link` behavior, `v`/`V` tags, a renamed GitHub repository, immutable publication, group reorder/nesting, and concurrent cross-replica reads; candidate black-box checks cover active/revoked/expired `GenericToken` behavior and real 5 MiB rejection. Server and persistence contracts cover moving-tag immutability, 1,200-tag pagination bounds, cleanup, and failure propagation. Real-client/storage lanes cover SwiftPM 5.7/5.10/6.x, macOS Xcode, Windows proxy resolution, a multi-megabyte package, shared 429/5xx waterlines with stale fallback, MinIO through the AWS S3-compatible adapter, and destructive database/object backup-restore across two replicas. Alibaba OSS Native is validated by adapter contracts; this matrix does not claim a live OSS Native endpoint run.

## Admin And Security Compatibility

| Area | Current compatibility target | Validation |
| --- | --- | --- |
| Security admin APIs | Nexus-like users, roles, privileges, repository references, realm type names, and selected ExtDirect/UI contracts | `SecurityAdminBlackBoxCompatibilityTest` |
| Repository permission model | Nexus-style repository view, browse, read, edit, add, delete, and component-create semantics | Server security tests and live compatibility tests |
| Component upload API | Nexus-style `/service/rest/v1/components` upload specifications and selected format uploads | `ComponentUploadBlackBoxCompatibilityTest` |
| Browse API | Repository browse shape and permission filtering | `SecurityAdminBlackBoxCompatibilityTest` and server browse tests |
| Authentication realms | Local users, LDAP, OIDC bearer/auth-code flows, API keys, session subjects | Server security tests |

## URL Compatibility

The primary client entrypoint is:

```text
/repository/<repo>/<artifact-path>
```

Examples:

```text
/repository/maven-public/org/example/app/1.0.0/app-1.0.0.pom
/repository/npm-hosted/@scope/package
/repository/pypi-proxy/simple/demo/
/repository/helm-hosted/index.yaml
/repository/cargo-group/config.json
/repository/cargo-hosted/crates/demo/1.0.0/download
/repository/pub-group/api/packages/path
/repository/pub-hosted/api/archives/demo_package-1.0.0.tar.gz
/repository/composer-group/packages.json
/repository/composer-group/p2/vendor/package.json
/repository/terraform-group/v1/modules/acme/network/aws/versions
/repository/terraform-group/v1/providers/hashicorp/null/versions
/repository/nuget-group/v3/index.json
```

Docker / OCI is different because Docker clients use registry `/v2/...` routes. Shared-entrypoint deployments use the first image path segment as the kkrepo repository name:

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

Repository-level Docker connector ports can expose the standard image shape when configured:

```text
<host>:<repo-port>/<image>:<tag>
```

## Migration Compatibility

kkrepo migration is treated as a product feature rather than a one-off script:

- Metadata migration covers users, roles, privileges, blob stores, repository definitions, and related compatibility data.
- Repository data migration scans hosted repositories by default.
- Proxy repositories can be migrated explicitly as historical backup data or upstream cache data.
- Cargo / Rust hosted repository data migration is supported for datastore H2/PostgreSQL sources when preflight proves the Cargo content model; unknown schemas fail closed.
- Dart / Pub hosted repository data migration is supported for Nexus 3.92.0+ datastore sources when preflight proves the Pub content model; Pub proxy cache migration requires explicit selection and a `FULL` plan.
- Composer migration accepts only Nexus-native proxy repositories. Configuration is migrated without cache content by default; cache migration requires explicit selection and a source profile that proves the Composer datastore content model. Unknown or non-native Composer sources fail closed.
- Terraform hosted module/provider data is rebuilt through protocol-aware migration, including provider platform, checksum, and signing metadata. An explicitly selected native Nexus Terraform proxy uses a separate cache-restore path: module/provider archives retain their Nexus public paths. Module download discovery can select restored local archives directly; provider remote routes, validators, checksum manifests, and signature snapshots are reconstructed from the configured upstream and pin cached blobs for the metadata lifetime.
- Swift repository definitions preserve hosted/proxy/group configuration, TTLs, and ordered members. Recoverable proxy credentials are encrypted; a masked or missing source secret yields `NEEDS_MANUAL_ACTION`, leaves the target proxy offline, and never installs a placeholder credential. Hosted archives, manifests, signatures, original metadata, and repository URL mappings are `FULL` only for verified Nexus 3.92.x-3.94.x datastore shapes; out-of-range versions, unknown profiles, and shape drift fail closed. Migration E2E covers Nexus 3.94 H2 to MySQL and PostgreSQL-source to both MySQL and PostgreSQL targets, including restart/resume and exact-row-count idempotency.
- Migration steps are designed for dry-run/preflight, resume, checksum validation, and reporting.
- Unsupported or blocked items should be reported rather than silently skipped.

See [Nexus Migration Guide](nexus-migration-guide.md).

## Known Limits

- kkrepo is not a full reimplementation of Nexus internals. Karaf, OSGi, OrientDB, embedded Elasticsearch, and the Nexus task subsystem are not compatibility goals.
- Docker / OCI uses Registry HTTP API V2 and OCI Distribution; Docker Registry V1 API and `docker search` are intentionally not part of the supported surface unless a future compatibility need requires a search-only shim.
- Docker connector listener changes can be refreshed through the Docker operations endpoint. Advanced connector TLS/SNI management remains deployment-specific.
- Cargo / Rust supports Cargo sparse registries. Cargo git index protocol, crates.io-style GitHub owner invitations, and deleting published crate versions are not currently supported. Cargo migration requires datastore H2/PostgreSQL schema fingerprints; OrientDB Cargo content export is not enabled.
- Dart / Pub supports Hosted Pub Repository V2 hosted/proxy/group workflows. Pub.dev social, publisher, score, download-count, and advisory APIs are not treated as protocol correctness dependencies.
- Composer support targets Composer 2 metadata. Composer 1 `provider-includes`, Packagist security-advisories/metadata-changes, VCS source checkout, and a standard publish command are outside the current surface; hosted publication uses the Components API or UI archive upload.
- Terraform support targets the Module Registry Protocol and Provider Registry Protocol through explicit CLI `host.services` configuration. Root-domain discovery/virtual-host binding and the Provider Network Mirror Protocol are not currently exposed; proxy signing keys are preserved and verified rather than replaced with a kkrepo signature.
- Terraform proxy migration restores only protocol-recognized module/provider archive caches and never treats them as hosted publications. Module download metadata can resolve a restored local path without upstream access; provider metadata rebuilds and verifies the upstream route/checksum/signature snapshot. Unknown source schemas, community plugins, and plans below `FULL` still fail closed.
- Swift proxy mode intentionally targets GitHub source-to-registry behavior compatible with Nexus 3.94.x; generic registry chaining and an `/availability` endpoint are not exposed. `POST /login` is optional in the Swift specification (a server without it may return `501`), but kkrepo implements `200`/`401`; `501` is not an expected kkrepo response. Windows E2E covers proxy resolve/build rather than hosted publication.
- Go hosted upload is not supported; Go module proxy behavior is read-oriented.
- Full coverage of every Nexus UI endpoint is not guaranteed. Endpoints are added when they are needed for supported user workflows or migration compatibility.
- Exact ordering, timestamps, generated IDs, and hostnames may be normalized in tests when the protocol allows nondeterminism.
- File blob storage is available for local trials and development. Production deployments should prefer OSS/S3-compatible storage.

## How To Report A Compatibility Difference

Open a Nexus compatibility issue and include:

- Nexus version and kkrepo version or commit.
- Repository format and recipe.
- The exact client command or HTTP request.
- Nexus status, headers, and response body semantics.
- kkrepo status, headers, and response body semantics.
- Client-visible impact.

Use public issues for ordinary compatibility differences. Report exploitable security issues privately through [SECURITY.md](../../SECURITY.md).
