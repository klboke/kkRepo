# Cargo / Rust Repository Design Notes

This document records the Cargo / Rust repository design notes for kkrepo. The goal is not to reinvent the Rust package management protocol, but to implement the compatible overlap between the official Cargo registry protocol and Nexus Repository Cargo behavior, while fitting the kkrepo MySQL + OSS/S3 + multi-replica architecture.

## Current Support

Cargo / Rust first-phase repository support is implemented for hosted, proxy, and group repositories. This document remains the design and verification guide for protocol compatibility work. Nexus Cargo repository migration is currently TBD and is not part of the first phase.

First phase support:

- `cargo-hosted`, `cargo-proxy`, and `cargo-group` recipes.
- Cargo sparse registry protocol, with entrypoints such as `sparse+http(s)://<host>/repository/<repo>/`.
- Hosted publish, download, index, yank, and unyank.
- Proxy caching for the crates.io sparse index and `.crate` files.
- Group repositories that merge sparse index entries by member order and route downloads to the member that provides the crate version.
- Cargo token authentication, anonymous/read/write permissions, and CI-token publishing.
- Cargo black-box compatibility tests against a Nexus reference instance, plus real `cargo` client verification.

The first phase does not support the Cargo git index protocol, native `cargo search`, UI/API component upload, crates.io-style GitHub owner invitations, deletion of published crate versions, or Nexus Cargo repository migration. Nexus native Cargo support is also centered on sparse protocol and explicitly does not support native Cargo client search or UI/API component upload.

## Research Baseline

Implementation must be checked against these protocols and reference behaviors first:

- Cargo Book: Registries. A Cargo registry consists of an index and optional web API. Alternate registries are configured through `[registries]` in `.cargo/config.toml`; an index URL with the `sparse+` prefix uses sparse protocol.
- Cargo Book: Registry Index. `config.json` defines `dl`, `api`, and `auth-required`; each crate has one index file; each version is one JSON line; index JSON should not be modified after append except for the `yanked` field.
- Cargo Book: Registry Web API. `cargo publish` uses `PUT /api/v1/crates/new`; the body is `metadata length + metadata JSON + crate length + .crate bytes`; `yank` and `unyank` update the yanked state in the index.
- Cargo Book: Registry Authentication. Cargo places the saved registry token into the `Authorization` header; the token may come from `credentials.toml`, environment variables, or a credential provider.
- Cargo Book: Source Replacement. Source replacement requires the replacement source to be equivalent to the original source. When mixing private packages with a crates.io proxy, prefer alternate registries; do not present the mixed group as an exact crates.io replacement.
- Nexus Repository Rust / Cargo documentation. Nexus supports hosted, proxy, and group Cargo repositories, only supports sparse protocol, requires the proxy remote URL to keep the trailing `/`, requires Cargo 1.68+, and supports yank/unyank.
- Nexus Repository 3.73.0 and 3.77.0 release notes. Version 3.73.0 introduced Pro-only native Rust / Cargo hosted, proxy, and group repositories; it also states native Cargo is not compatible with the old community plugin and data migration is not supported. Version 3.77.0 later made previously Pro-only formats available through Community Edition. The kkrepo compatibility baseline should use Nexus 3.77.0+ reference instances.
- Current Nexus Repository database shape. Nexus 3.71.0 and later no longer support OrientDB; current deployments support embedded H2 and external PostgreSQL. The existing kkrepo Nexus migration path is mainly designed around older OrientDB / Script REST API compensation and must not be assumed to apply to Nexus 3.77.0+ Cargo data.

Key conclusions:

- kkrepo should implement only sparse registry in the first phase. Cargo git index would require maintaining a client-cloneable/fetchable git repository, which does not fit kkrepo's MySQL truth, OSS/S3 blob model, or the Nexus reference requirement for the first phase.
- Cargo sparse index produces many small HTTP requests. The server supports `ETag`, `Last-Modified`, and `304 Not Modified`, using generated body hashes and MySQL-backed updated timestamps as stable cache validators.
- `config.json` is the client entrypoint contract. `dl` should point to the kkrepo download endpoint, and `api` should point to the same repository's web API root. `auth-required` is a Cargo protocol client hint, not the kkrepo repository permission switch. Hosted repositories always return `auth-required: true` to match Nexus 3.77+; proxy/group repositories expose Authentication requirements in Admin UI to decide whether the local `config.json` contains that hint.
- Nexus 3.77+ Cargo UI states that leaving Authentication requirements unchecked only allows anonymous access when anonymous access is also enabled for the instance. Local reference-instance verification matches that: with global anonymous access disabled, `config.json`, sparse index files, and `.crate` downloads return `401`; with global anonymous enabled and the anonymous user granted repository read privileges, proxy/group reads are still allowed even when the hint is enabled. Therefore, kkrepo Cargo read requests use the normal repository read permission path, and unauthenticated requests pass only through the enabled and authorized anonymous fallback; write operations must be authenticated and authorized with add/edit privileges.
- `.crate` files are immutable published objects. The server must calculate SHA-256 from the exact original `.crate` bytes and write it to the index `cksum`; do not calculate checksum from unpacked content or formatted metadata.
- After publish succeeds, Cargo polls the index waiting for the new version to appear. kkrepo hosted publish should commit the MySQL version row and sparse-index visible state before returning success, avoiding short windows where the client cannot see its just-published version.
- `yank` does not delete the `.crate` file; it only changes the `yanked` field in the index JSON. Existing `Cargo.lock` files should still be able to download the yanked version, while new resolution should not select it.
- Crate index file names are lowercase, but the package name in index JSON is case-sensitive. To avoid case-only conflicts on the same index path, kkrepo should reject crate names that differ only by case within the same repository.
- Cargo migration needs a new source-read strategy. Native Cargo became broadly available while Nexus was already in the H2/PostgreSQL database era. Repository, component, asset, token, and blob metadata reads may no longer match older OrientDB script queries. The first phase implements protocol behavior and compatibility tests only; it does not promise Cargo repository data or Cargo token migration.

## Feature Scope

### Required In The First Phase

1. Cargo hosted repositories
   - Add `RepositoryFormat.CARGO` and the `cargo-hosted` recipe.
   - `GET /repository/{repo}/config.json` returns sparse registry configuration.
   - `GET /repository/{repo}/{index-path}` returns crate index files, supporting `ETag`, `Last-Modified`, `If-None-Match`, and `If-Modified-Since`.
   - `GET /repository/{repo}/crates/{crate}/{version}/download` returns the original `.crate` bytes; `config.json` advertises `/crates` as the `dl` base URL to match Nexus and Cargo's default URL expansion rule.
   - `PUT /repository/{repo}/api/v1/crates/new` supports `cargo publish --registry <name>`.
   - `DELETE /repository/{repo}/api/v1/crates/{crate}/{version}/yank` supports `cargo yank`.
   - `PUT /repository/{repo}/api/v1/crates/{crate}/{version}/unyank` supports `cargo yank --undo`.
   - Validate crate name, version, metadata, basic `.crate` gzip/tar structure, and package name/version in `Cargo.toml`.
   - Do not allow version overwrite. If `(repository_id, crate_name_lc, version)` already exists, return a Cargo-readable JSON error.
   - Write component, asset, asset_blob, browse node, and Cargo version/index metadata in component and asset attributes.

2. Cargo proxy repositories
   - Add the `cargo-proxy` recipe, including remote index URL, remote API/download URL, authentication, and cache TTL.
   - The first phase should recommend `https://index.crates.io/` as the remote sparse index and normalize/validate the trailing `/` at save time.
   - Read remote `config.json`, parse remote `dl` and `api`, then generate a local `config.json` pointing to the kkrepo proxy repository.
   - Fetch and cache remote index files by crate index path, saving `ETag`, `Last-Modified`, body hash, `cache_until`, and negative-cache state.
   - On `.crate` download, verify the remote response against the local index entry `cksum`, cache the file in OSS/S3, and register asset/component records.
   - Cache remote `404`, `410`, and `451` as short-TTL negative entries according to Cargo sparse semantics; do not permanently poison the repository.
   - If the remote is unavailable, serve cached index and `.crate` files when the cache is still valid or stale-cache is explicitly allowed; otherwise return a Cargo-readable JSON error or the matching HTTP status.

3. Cargo group repositories
   - Add the `cargo-group` recipe. Members may be hosted or proxy Cargo repositories.
   - `config.json` `dl` and `api` point to the group itself.
   - For the same crate index path, merge version lines by member order; if the same `(crate_name_lc, version)` appears in multiple members, keep the first match.
   - The group index response must record which member provided each version, so downloads resolve the `.crate` from the same member and do not download same-name versions from the wrong source.
   - Member repository changes must be observed by re-resolving member indexes; any cache may only be a TTL hot cache, never correctness truth.
   - Groups are read-only. Publish, yank, and unyank should return the status matching Nexus reference behavior.

4. Compatibility tests and real client verification
   - Add `CargoRepositoryBlackBoxCompatibilityTest` against a Nexus reference instance.
   - Compare `config.json`, missing crate index, index ETag/304, private repository auth challenge, publish, download, yank, unyank, proxy cache, and group merge behavior.
   - Verify `cargo fetch`, `cargo build --locked`, `cargo publish`, `cargo yank`, and `cargo yank --undo` with Cargo 1.68+ and the current stable Cargo version.
   - Verify both alternate registry and source replacement configurations in `.cargo/config.toml`, and document the boundary between them.

### Optional Hardening And Non-Goals

- Cargo git index protocol is a non-goal. Reconsider it only if a concrete Nexus migration or old-client requirement appears.
- `cargo search` is a non-goal. Admin UI and browse UI may provide kkrepo-native search, but native Cargo client search compatibility is not promised.
- UI/API component upload is a non-goal. Cargo package upload should use `cargo publish`, matching Nexus behavior.
- Owner list/add/remove endpoints may return not implemented or minimal read-only responses in the first phase; they are not permission truth. kkrepo permissions are based on users, groups, roles, repository permissions, and CI tokens.
- Deleting a published crate version is a non-goal. If a future admin quarantine feature is needed for compliance, it must be clearly separated from normal yank semantics.
- Nexus Cargo repository migration is TBD and is not implemented in the first phase. If started later, it needs a separate design for H2/PostgreSQL source reads, official/non-public API availability, token migration, blob verification, and cutover validation.

## URL And Routing Design

Cargo uses the normal `/repository/{repo}/...` repository entrypoint. It does not need a Docker-style dedicated `/v2/...` route.

Recommended client configuration:

```toml
[registries]
cargo-hosted = { index = "sparse+https://repo.example.com/repository/cargo-hosted/" }
cargo-proxy = { index = "sparse+https://repo.example.com/repository/cargo-proxy/" }
cargo-group = { index = "sparse+https://repo.example.com/repository/cargo-group/" }

[registry]
default = "cargo-group"

[registries.cargo-hosted]
token = "Basic <kkrepo-user-token>"
```

If kkrepo is only used as a crates.io mirror, source replacement can be configured:

```toml
[registries]
cargo-proxy = { index = "sparse+https://repo.example.com/repository/cargo-proxy/" }

[source.crates-io]
replace-with = "cargo-proxy"
```

If a group contains both private hosted packages and a crates.io proxy, do not describe it as an exact crates.io replacement. Business crates should use `registry = "cargo-group"` or `registry.default` instead.

Route table:

| Request | Behavior |
| --- | --- |
| `GET /repository/{repo}/config.json` | Return sparse index config with `dl`, `api`, and optional compatibility `auth-required` |
| `GET /repository/{repo}/1/{crate}` | One-character crate index file |
| `GET /repository/{repo}/2/{crate}` | Two-character crate index file |
| `GET /repository/{repo}/3/{first}/{crate}` | Three-character crate index file |
| `GET /repository/{repo}/{first2}/{second2}/{crate}` | Four-character-or-longer crate index file |
| `PUT /repository/{repo}/api/v1/crates/new` | Publish a new crate version |
| `GET /repository/{repo}/crates/{crate}/{version}/download` | Download `.crate` file; advertised endpoint |
| `GET /repository/{repo}/api/v1/crates/{crate}/{version}/download` | Backward-compatible download endpoint, not advertised in `config.json` |
| `DELETE /repository/{repo}/api/v1/crates/{crate}/{version}/yank` | Yank a published version |
| `PUT /repository/{repo}/api/v1/crates/{crate}/{version}/unyank` | Unyank a published version |
| `GET /repository/{repo}/api/v1/crates` | Return a Cargo JSON error for unsupported search in the first phase |
| `GET /repository/{repo}/me` | May return a login information page or 404; useful for `cargo login` display, but not API truth |

Example `config.json`:

```json
{
  "dl": "https://repo.example.com/repository/cargo-hosted/crates",
  "api": "https://repo.example.com/repository/cargo-hosted/"
}
```

Path parsing rules:

- The index path is calculated from the crate name according to official Cargo rules. The server must reject mismatched paths to avoid one crate being reachable through multiple paths.
- Index file names use the lowercase crate name. Preserve the original `name` field on write, but use lowercase names for uniqueness.
- Match Nexus reference behavior for index response content type, whether `application/json` or `text/plain`. The body is newline-delimited JSON, one version per line.
- `api/v1` endpoint errors use the Cargo-defined `{"errors":[{"detail":"..."}]}` shape.

## Data Model Implementation

The first phase uses the existing kkrepo MySQL model instead of adding Cargo-specific Flyway tables.

- Crate versions are `component` rows with `format=CARGO`, `namespace=NULL`, `name=<lowercase dash/underscore normalized crate name>`, `version=<Cargo version uniqueness key>`, and `kind=crate`.
- The existing `(repository_id, coordinate_hash)` uniqueness model protects the Cargo `(repository, crate, version)` identity.
- The Cargo index JSON line visible to clients is stored in `component.attributes.indexEntry`; the crate asset path and browse metadata are stored in component/asset attributes.
- `.crate` objects are `asset` rows bound to `asset_blob` rows. Large bytes live only in OSS/S3; MySQL stores blob reference, hashes, size, content type, and attributes.
- Hosted and proxy crate writes calculate SHA-256 from the exact `.crate` bytes. Proxy downloads reject checksum mismatches against the remote index `cksum` before persisting metadata.
- Proxy `config.json` and sparse index files are cached as metadata assets, so their bodies and remote validators (`ETag`, `Last-Modified`) are durable through MySQL asset/blob references and OSS/S3 bytes.
- Remote proxy auto-block state reuses the existing MySQL `ProxyStateDao`. Remote `404`, `410`, and `451` use the shared TTL `ProxyNegativeCache`, scoped by proxy repository and path.
- Group index responses are rebuildable from member indexes by member order. The first phase does not store a dedicated group merge table; correctness comes from resolving members against their MySQL/OSS-backed state on each request.

Dedicated tables such as `cargo_crate`, `cargo_version`, or `cargo_group_index_cache` can still be considered later if Cargo needs heavier queryability, richer audit reporting, or cross-replica lease semantics beyond the shared repository primitives.

## Hosted Publish Flow

`PUT /api/v1/crates/new` does not implement protocol details in the controller. The controller reads the HTTP request, authentication/authorization is handled by the repository security filter, and the request is delegated to `CargoHostedService`.

Implemented flow:

1. Validate repository online state and format/type `cargo-hosted`.
2. Enforce repository write policy and repository `ADD` or `EDIT` permission for publish.
3. Parse the Cargo publish body:
   - Read 4-byte little-endian metadata length.
   - Parse metadata JSON and validate `name` and `vers`.
   - Read 4-byte little-endian `.crate` length.
   - Buffer `.crate` bytes to a temp file while enforcing the declared length.
4. Validate `.crate`:
   - gzip/tar is readable.
   - `Cargo.toml` exists.
   - `Cargo.toml` package name/version match publish metadata.
5. Write to OSS/S3 and MySQL:
   - Upload the exact `.crate` bytes while calculating MD5, SHA-1, SHA-256, and SHA-512.
   - Insert `asset_blob`, `component`, `asset`, and browse nodes through existing DAO uniqueness constraints.
   - Store the Cargo index entry in `component.attributes.indexEntry`.
   - Evict asset metadata cache after commit.
6. Return Cargo publish success JSON. Index GET reads from committed component rows, so the published version is visible without relying on local process state.

Concurrency semantics:

- Concurrent publish of the same crate/version is resolved by the existing component coordinate unique constraint.
- Concurrent publish of different versions for the same crate is allowed; index responses are generated from MySQL component rows in stable order.
- If metadata persistence fails after upload, the writer deletes the newly uploaded blob if no live MySQL metadata references it.

## Proxy Cache Flow

Proxy repositories use MySQL/OSS-backed assets as cache truth and in-memory/shared caches only as TTL accelerators.

Index request flow:

1. Parse crate name and index path from the request path.
2. Look up the cached sparse index metadata asset by repository id and path.
3. If the cached asset is fresh under repository metadata max-age, return it directly.
4. If a shared negative-cache entry exists, return Cargo not found without hitting the remote.
5. If the proxy remote is auto-blocked, serve stale cache when present; otherwise return a Cargo-readable upstream error.
6. Revalidate the remote sparse index with stored remote `ETag` or `Last-Modified`.
7. On remote `304`, touch the cached asset verification time and return the cached body.
8. On remote `200`, write the index body as a metadata asset, store remote validators in blob attributes, and invalidate the negative cache.
9. On remote `404/410/451`, record remote success, serve stale cache if present, otherwise store a short-TTL negative entry and return Cargo not found.

Download request flow:

1. Resolve the crate/version by reading the remote index entries through the local index cache path.
2. Build the remote download URL from upstream `config.json` `dl`, including Cargo template replacement for `{crate}`, `{version}`, `{prefix}`, `{lowerprefix}`, and `{sha256-checksum}`.
3. If the `.crate` asset is cached and fresh under content max-age, return it directly.
4. Revalidate or download the remote `.crate` with stored remote validators.
5. On remote `304`, touch verification time and return the cached file.
6. On remote `200`, stream to OSS/S3 while calculating hashes, verify SHA-256 equals the index `cksum`, then register asset/component metadata.
7. On checksum mismatch, delete the newly uploaded blob when unreferenced and return a Cargo-readable upstream error. Do not cache the bad response.

## Group Merge Flow

Group repositories generate a sparse index that is consistent for Cargo clients.

Index merge rules:

- Request the same index path from eligible hosted/proxy members by group member order.
- Ignore a member's missing-crate state unless all members are missing.
- Parse `(name, vers)` from each index JSON line.
- For the same version, keep only the first member's line.
- Preserve member index JSON fields in the response and avoid unnecessary reformatting.
- Generate response `ETag` from the merged body and use the newest member `Last-Modified` as the group `Last-Modified`.

Download resolution rules:

- Iterate members in the same order used by index merge.
- Delegate the download to the first eligible member that can serve the crate/version.
- If a member reports upstream failure, remember it and continue looking for a later member; if no member has the crate, return the last upstream failure or not found.
- Groups do not copy `.crate` blobs; the member repository owns the cache.

## Permissions And Authentication

Cargo token storage does not need a completely separate model. Prefer reusing the existing `api_key` hash, encrypted payload, owner, status, expiration, audit, and cache invalidation capabilities, similar to npm `NpmToken` and NuGet `NuGetApiKey`, while separating protocol purpose through a domain or token type. The first phase should introduce a conventional `CargoToken` domain, but the final domain/scope design must be validated against a Nexus Cargo reference instance.

The addition needed is Cargo protocol token adaptation, not a new token truth table:

- When users create tokens themselves, create an API key with `domain=CargoToken`; the returned value still follows the existing `<domain>.<raw-token>` shape.
- Migration, if implemented later, must migrate every token shape supported by Nexus Cargo and must preserve original Cargo client credentials without reissuing. Do not only migrate data that happens to appear in the `api_key` table like `NpmToken`.
- If Nexus Cargo uses repository-scoped User Tokens or any token store not covered by the existing `api_key` export, the migration tool must add a matching export/import path. Only when the source Nexus truly does not expose verifiable token material may preflight/reporting mark this as `MANUAL`, and that state must not count as Cargo token migration acceptance.
- If Nexus allows one user to create multiple tokens for multiple Cargo repositories, kkrepo must preserve them all. The implementation must not collapse them into one token per user because of the current `UNIQUE(domain, owner_source, owner_user_id)` semantics. This can be solved by extending API-key uniqueness, adding repository scope, or using stable repository-scoped domain names, but compatibility tests must lock the behavior.
- Cargo endpoints should delegate parsed tokens to shared API-key validation and permission decisions. The authenticated subject remains the API-key owner user.
- API-key owner, expiration, status, last used, encrypted payload, audit, and cache invalidation all reuse the existing implementation.

The Cargo Web API `Authorization` header value is the token string Cargo saved. Unlike npm `_authToken`, which commonly sends `Authorization: Bearer <token>`, Cargo can send a raw token directly and Nexus documentation also shows a complete `Basic <base64-user-token>` header value. Therefore, to support Cargo and Nexus user-token examples, the first phase should accept:

- `Authorization: Basic <base64-user-token>`
- `Authorization: Bearer <kkrepo-token>`
- `Authorization: <raw-token>` for raw tokens stored by Cargo's default token provider
- `Authorization: CargoToken.<raw-token>` for kkrepo-generated Cargo API keys

Existing global API-key auth only reads the configured header, `X-Nexus-Plus-Api-Key`, and `Authorization: Bearer ...`. Cargo must not rely only on global Bearer parsing. The Cargo controller/filter should read the complete `Authorization` header according to Cargo rules and then call shared API-key parsing/validation. This avoids misclassifying ordinary HTTP Basic login as a Cargo token and avoids changing Maven/npm/PyPI authentication behavior.

Permission mapping:

| Cargo operation | kkrepo permission |
| --- | --- |
| Read `config.json` | repository read; unauthenticated requests pass only when global anonymous is enabled and the anonymous user has privileges |
| Read index file | repository read; unauthenticated requests pass only when global anonymous is enabled and the anonymous user has privileges |
| Download `.crate` | repository read; path is crate name or crate/version |
| publish | repository add/update; path is crate name |
| yank/unyank | repository update; path is crate name/version |
| group read | group repository read + target member read |
| proxy remote fetch | after user read permission passes, the server uses repository remote credentials |

Private repository behavior:

- Whether reads, downloads, and writes are allowed is controlled by users, roles, and repository privileges under `admin/security/privileges`, not by Cargo `auth-required`.
- Hosted repositories do not expose a Cargo Authentication requirements setting, and their generated `config.json` always includes `auth-required: true`. Proxy/group repositories expose the setting, but it only changes the Cargo client hint; it does not grant or revoke anonymous access.
- `config.json`, sparse index files, and `.crate` downloads all use the normal read permission decision path; anonymous access depends on global anonymous configuration and anonymous-user repository privileges. Publish, yank, and unyank requests must be authenticated and use add/edit permission decisions.
- Unauthenticated requests should return `401` and may include `www-authenticate: Cargo login_url="<url>"`.
- Authentication failure should return `403` or the status matching Nexus reference behavior.
- All API error bodies use `{"errors":[{"detail":"..."}]}` so Cargo CLI output remains readable.

## Multi-Replica Semantics

Cargo repository implementation must not rely on single-JVM in-memory state as the only truth.

- Published crate versions, index JSON, yank state, proxy revalidation state, and repository configuration are stored in MySQL-backed component/asset/repository rows.
- `.crate` files are stored only in OSS/S3; MySQL stores blob references, checksum, size, and state.
- In-memory/shared cache may only cache repository runtime snapshots, asset metadata snapshots, permission decisions, and negative results. It must have TTL or explicit invalidation and must be rebuildable from MySQL/OSS/S3.
- Repository runtime snapshots containing upstream proxy passwords or bearer tokens are not written to the shared runtime cache.
- Proxy revalidation and remote `.crate` downloads may duplicate remote work across replicas under concurrency, but correctness is protected by MySQL asset/component uniqueness and checksum validation.
- Publish and yank update component rows in MySQL; other replicas observe the committed state through DAO reads and bounded TTL cache invalidation.
- Blob cleanup uses metadata reference checks so failed writes do not leave newly uploaded unreferenced objects as live metadata truth.

## Browse, Admin, And Migration

Admin UI:

- Repository create pages add `cargo-hosted`, `cargo-proxy`, and `cargo-group`.
- Proxy configuration needs a remote sparse index URL and should remind users that it must end with `/`.
- Cargo proxy/group repositories show Authentication requirements to control the `auth-required` client hint in `config.json`; Cargo hosted repositories do not show the setting and always return `auth-required: true`. The UI copy must state that access control is still decided by anonymous settings and security privileges.
- Group configuration shows member order and notes that conflicting versions prefer the first member.

Browse UI:

- Crate list shows name, latest version, description, yanked count, and last published time.
- Crate details show versions, checksums, yanked state, dependencies, features, license, repository/homepage/documentation links.
- Yanked versions must be clearly marked, but still downloadable.

Migration:

- Nexus Cargo repository migration is TBD, not implemented in the first phase, and must not be promised in UI or docs.
- Background: Nexus Repository Pro 3.73.0 had initial Pro-only native Cargo support, but 3.77.0 made previously Pro-only formats officially available through Community Edition. At that point Nexus was already in the H2/PostgreSQL database era, so the existing old-Nexus OrientDB / Script REST API migration compensation path cannot be reused directly.
- If Cargo migration starts later, first confirm source-readable data entrypoints on Nexus 3.77.0+ reference instances: official REST APIs, database export, blob metadata, sparse index, token/User Token storage, and permission mapping.
- Future migration design must support dry-run, resume, checksum verification, and reports, and must explicitly define source-read strategies for both H2 and PostgreSQL.
- Future Cargo token migration must create every token/credential shape supported by that Nexus version in a reference instance. After migration to kkrepo, tokens in the original `.cargo/config.toml` and `credentials.toml` must continue to work without reissue or manual replacement for `cargo fetch`, `cargo publish`, `cargo yank`, and `cargo yank --undo` according to their original permissions.
- If any Nexus-supported Cargo token cannot be migrated automatically and continue working, Cargo migration acceptance must fail. It may only be reported as an explicit `MANUAL` blocker; do not silently downgrade to "reissue after migration."
- Old community-plugin data explicitly incompatible with Nexus documentation is not promised for automatic migration. If users need it, handle it as a separate source-data analysis and one-off conversion design.
- Migration state belongs in MySQL, not local files as the only checkpoint.

## Observability Metrics

Recommended metrics:

- `kkrepo_cargo_index_requests_total{repository,type,result}`
- `kkrepo_cargo_index_cache_hits_total{repository,type}`
- `kkrepo_cargo_download_requests_total{repository,type,result}`
- `kkrepo_cargo_publish_requests_total{repository,result}`
- `kkrepo_cargo_yank_requests_total{repository,result}`
- `kkrepo_cargo_proxy_revalidate_total{repository,result,status}`
- `kkrepo_cargo_proxy_download_bytes_total{repository}`
- `kkrepo_cargo_group_merge_total{repository,result}`
- `kkrepo_cargo_active_downloads{repository}`
- `kkrepo_cargo_active_publishes{repository}`

Log fields should include `repository`, `crate`, `version`, `operation`, `status`, `checksum`, `cache`, `remote_status`, `principal`, and `request_id`.

## Implementation Order

1. Add compatibility test skeleton
   - Add Cargo Nexus reference tests to `compat-test`.
   - Lock expected behavior for `config.json`, missing index, download, publish, yank, and unyank.

2. Add core recipe and protocol module
   - Add `RepositoryFormat.CARGO`.
   - Add `cargo-hosted`, `cargo-proxy`, and `cargo-group` recipes.
   - Add `protocol-cargo` for index path, publish body, index JSON, checksum, and error model.

3. Add persistence integration
   - Reuse `component`, `asset`, `asset_blob`, browse nodes, proxy state, shared negative cache, and asset metadata cache.
   - Add tests covering uniqueness, yank, generated validators, proxy cache, and checksum mismatch.

4. Hosted minimum viable behavior
   - Implement `config.json`, publish, index, download, yank, and unyank.
   - Verify with real `cargo publish` and `cargo fetch`.

5. Proxy minimum viable behavior
   - Implement remote `config.json` parsing, index cache, download cache, checksum verification, and negative cache.
   - Verify common dependency pulls against crates.io sparse index.

6. Group minimum viable behavior
   - Implement member index merge, download origin resolution, and cache invalidation.
   - Verify private hosted + crates.io proxy composition.

7. UI and operations
   - Admin UI create/edit for Cargo repositories.
   - Browse UI crate details.
   - Metrics, logs, alerts, and docs.

8. Migration research, not part of first-phase implementation
   - Confirm Cargo data readable entrypoints on Nexus 3.77.0+ reference instances with H2/PostgreSQL.
   - Map Cargo token/User Token storage and verifiable material.
   - Produce a separate Cargo migration design before deciding whether to implement.

## Acceptance Criteria

The first phase is complete only when:

- Cargo 1.68+ can publish, fetch, yank, and unyank through `sparse+.../repository/cargo-hosted/`.
- Current stable Cargo can pull private hosted crates and crates.io proxy crates through `cargo-group`.
- `cargo build --locked` does not depend on remote crates.io when dependencies are already cached.
- `.crate` download checksum matches index `cksum`.
- Index requests support `ETag` or `Last-Modified`, and client conditional requests can receive `304`.
- Reading new index state after publish/yank across replicas does not rely on local process state.
- Proxy remote `404/410/451`, remote `304`, checksum mismatch, and network failure all have explicit tests.
- The first phase does not provide Nexus Cargo repository migration. Migration pages, compatibility matrices, and release notes must not mark Cargo as migratable.
- Nexus reference compatibility tests record all known differences, and only normalize host, timestamp, or header ordering when the protocol allows it.

## References

- Cargo Book: Registries: https://doc.rust-lang.org/cargo/reference/registries.html
- Cargo Book: Registry Index: https://doc.rust-lang.org/cargo/reference/registry-index.html
- Cargo Book: Registry Web API: https://doc.rust-lang.org/cargo/reference/registry-web-api.html
- Cargo Book: Registry Authentication: https://doc.rust-lang.org/cargo/reference/registry-authentication.html
- Cargo Book: Source Replacement: https://doc.rust-lang.org/cargo/reference/source-replacement.html
- Sonatype Nexus Repository Rust / Cargo Repositories: https://help.sonatype.com/en/rust-cargo.html
- Sonatype Nexus Repository 3.73.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-73-0-release-notes.html
- Sonatype Nexus Repository 3.77.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-77-0-release-notes.html
- Sonatype Nexus Repository System Requirements: https://help.sonatype.com/en/sonatype-nexus-repository-system-requirements.html
