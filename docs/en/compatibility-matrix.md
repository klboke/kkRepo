# Compatibility Matrix

This matrix summarizes the public compatibility surface of kkrepo. It is intentionally user-visible: client commands, HTTP paths, repository recipes, migration support, and known limits. Internal Nexus implementation details are not compatibility targets unless they affect client behavior.

For deeper validation workflow details, see [Nexus Compatibility Testing](nexus-compatibility-testing.md).

## Compatibility Principles

- Keep the Nexus `/repository/<repo>/...` URL layout for existing client configuration.
- Match official protocol behavior and Nexus client-visible behavior before adding project-specific behavior.
- Prefer compatibility tests against a real Nexus reference instance for externally visible behavior.
- Keep stateful behavior multi-replica safe: MySQL is the source of truth for metadata and coordination; blob content lives in OSS/S3/File storage; in-process caches must be rebuildable.

## Repository Format Matrix

| Format | Repository types | Main client operations | Browse/search | Migration support | Compatibility validation |
| --- | --- | --- | --- | --- | --- |
| Maven | hosted / proxy / group | Maven deploy, PUT upload, GET/HEAD/checksum reads, snapshot and release metadata behavior, admin UI component upload | Supported | Hosted by default; proxy optional | `MavenRepositoryBlackBoxCompatibilityTest`, `MavenMetadataMergeCompatibilityTest`, `MavenWritePolicyCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| npm | hosted / proxy / group | `npm publish`, tarball download, package metadata, dist-tags, audit endpoint compatibility, admin UI upload | Supported | Hosted by default; proxy optional | `NpmProtocolCompatibilityTest`, `NpmRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| PyPI | hosted / proxy / group | `twine upload`, package download, simple index reads, admin UI upload | Simple index supported | Hosted by default; proxy optional | `PypiRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Go | proxy / group | Go module proxy reads: list, info, mod, zip, latest, group fallback | Supported | Proxy optional | `GoProxyBlackBoxCompatibilityTest` |
| Helm | hosted / proxy | Chart push, PUT upload, chart download, `index.yaml`, proxy index rewrite, admin UI upload | `index.yaml` supported | Hosted by default; proxy optional | `HelmRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Cargo / Rust | hosted / proxy / group | Sparse registry reads, `cargo publish`, `.crate` download, yank/unyank, Cargo search, CargoToken auth, UI/API `.crate` upload | Sparse index and Cargo search supported | TBD; not part of the current Nexus migration flow | `CargoRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| NuGet | hosted / proxy / group | Package push, package download, v3 service index, registration, flat container, search/autocomplete, admin UI upload | v3 service index/search supported | Hosted by default; proxy optional | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| RubyGems | hosted / proxy / group | Gem push/yank, gem download, compact and legacy index assets, admin UI upload | Supported | Hosted by default; proxy optional | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| Yum | hosted / proxy / group | RPM PUT/upload, package download, `repodata` metadata | `repodata` supported | Hosted by default; proxy optional | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| Raw | hosted / proxy / group | PUT upload, GET/HEAD reads, group/proxy fallback, admin UI upload | Supported | Hosted by default; proxy optional | `RawRepositoryBlackBoxCompatibilityTest`, `ComponentUploadBlackBoxCompatibilityTest` |
| Docker / OCI | hosted / proxy / group | Registry V2 login, hosted push/pull, proxy pull, group pull, manifests, blobs, tags, upload sessions, cross-repo mount, referrers, content cleanup, Docker Hub `library` namespace compensation | Manifest/tag/blob metadata supported | Hosted Docker repository data migration supported through Nexus Repository Data | `DockerRegistryBlackBoxCompatibilityTest`, Docker server/protocol tests, OCI conformance workflow, [Docker / OCI implementation notes](dev/docker-repository-implementation-plan.md) |

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
- Cargo / Rust repository data migration remains TBD. Nexus Community Cargo support starts from the 3.77.x datastore-era H2/PostgreSQL shape, so it is not covered by the existing old-Nexus migration path.
- Migration steps are designed for dry-run/preflight, resume, checksum validation, and reporting.
- Unsupported or blocked items should be reported rather than silently skipped.

See [Nexus Migration Guide](nexus-migration-guide.md).

## Known Limits

- kkrepo is not a full reimplementation of Nexus internals. Karaf, OSGi, OrientDB, embedded Elasticsearch, and the Nexus task subsystem are not compatibility goals.
- Docker / OCI uses Registry HTTP API V2 and OCI Distribution; Docker Registry V1 API and `docker search` are intentionally not part of the supported surface unless a future compatibility need requires a search-only shim.
- Docker connector listener changes can be refreshed through the Docker operations endpoint. Advanced connector TLS/SNI management remains deployment-specific.
- Cargo / Rust supports Cargo sparse registries. Cargo git index protocol, crates.io-style GitHub owner invitations, deleting published crate versions, and Nexus Cargo repository migration are not currently supported.
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
