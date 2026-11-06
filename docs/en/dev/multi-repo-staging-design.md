# Multi-Repository Staging Design

Status: implemented for Raw, Maven, npm, PyPI, Helm, Open VSX, and Arch Linux hosted repositories.
The durable lifecycle, temporary session credentials, staged content isolation, preflight and
single-transaction promotion are available, together with Nexus-shaped asset/component/repository
and status APIs. Cross-format group membership remains future work (phases 3-5 below).

Implementation notes:

- The delivered model stores staged rows in dedicated `staging_*` tables instead of adding a
  `staging_session_id` column to `asset`/`component`. This keeps every existing live query
  unchanged while preserving the overlay semantics: staged content is never visible to normal
  clients, and promotion links the already content-addressed blob into the live asset tables.
- Staged blobs are retained through `blob_reference` with owner type `staging_item`, so the normal
  blob garbage collector cannot reclaim them while a session is open.
- Promotion runs in one transaction, locks the session and target repositories in ascending
  repository-id order, revalidates every item, applies ADD/REPLACE/DELETE through the normal Raw
  writer, releases the staging blob ownership and records an idempotent report.
- Session state, item state and promotion reports live only in MySQL/PostgreSQL. No node-local
  cache participates in staging correctness.


## Problem

Large releases often need to publish several related artifacts before any of them becomes visible:

- a Maven library plus its BOM and Gradle plugin;
- an npm package plus its platform-specific tarballs;
- an Open VSX extension plus an Arch package that ships the same CLI;
- several repositories that form one customer-facing group.

Publishing one repository at a time exposes partial releases, creates inconsistent group views, and
makes rollback manual. A **multi-repository staging session** should let a team stage a coherent set
of additions, replacements, and group-membership changes across repositories, validate them
together, and promote them atomically.

## Goals

1. **Isolation** — staged content is never visible to normal repository clients until promotion.
2. **Cross-format and cross-repository** — one session may contain Maven, npm, Open VSX, Arch,
   Docker, and other assets.
3. **Atomic visibility** — after promotion, every replica and every group view exposes the new
   release together (or the promotion fails without partial visibility).
4. **Multi-replica correctness** — session state, ownership, and promotion progress live in the
   shared database; node-local caches are rebuildable and cannot decide correctness.
5. **Auditability** — every staged write, preflight result, approval, promotion, and rollback is
   attributable.
6. **No client URL changes** — promotion does not require clients to change repository URLs.
7. **Idempotency** — retries after crashes must not double-promote or expose half a release.

## Non-goals

- Replacing normal hosted publication for single-artifact workflows.
- Cross-region transactional replication of blob storage.
- A general workflow engine or approval product.
- Mutating immutable published coordinates. Promotion can add or replace according to the existing
  write policy; it cannot bypass format immutability.

## Existing building blocks

The current codebase already provides most of the primitives:

- **Content-addressed blobs** in OSS/S3 (`asset_blob` rows with SHA-256/size and reusable blobs).
- **Generic component/asset rows** with format, namespace, name, version, path hash, and component
  hash.
- **Ordered group membership** in `repository_member`.
- **Fenced leases** (`FencedLeaseManager`) for cross-replica ownership.
- **Version watermarks** and the catalog cache broadcaster for rebuildable read state.
- **Outbox-style rebuild queues** (`RepositoryIndexRebuildDao`) and cache invalidation.
- **Format-specific generated metadata** (R `PACKAGES.gz`, Arch `<repo>.db`, APT/Alpine indexes,
  Open VSX JSON, npm packuments) derived from durable rows.

Staging should reuse these rather than introduce a parallel artifact store.

## Chosen model: asset overlay with an explicit promotion manifest

Two broad implementations were considered:

| Model | Pros | Cons |
| --- | --- | --- |
| Shadow repositories cloned per session | Simple isolation; existing protocol code mostly unchanged | Repository-count explosion, group/membership duplication, credentials/settings drift, expensive cleanup |
| **Asset overlay + session visibility** | One repository configuration, generic across formats, cheap reads, promotion is a row-state change | Every read/write path must be session-aware; generated metadata and caches must include the session key |

The recommended model is an **asset overlay**:

- Staged rows are ordinary component/asset/blob rows with a nullable `staging_session_id`.
- Normal reads see only `staging_session_id IS NULL`.
- Staging reads see the live row plus staged rows for that session, with staged rows taking
  precedence for the same logical path.
- Promotion changes visibility atomically instead of moving blobs.

This keeps content-addressed blob reuse and lets a staged asset reference an existing blob without
copying it.

## Data model

All tables are additive and live in both MySQL and PostgreSQL migrations.

### `staging_session`

| Column | Meaning |
| --- | --- |
| `id` | Surrogate key |
| `name` | Human-readable unique name |
| `state` | `OPEN`, `FROZEN`, `PREFLIGHTED`, `PROMOTING`, `PROMOTED`, `FAILED`, `CANCELLED` |
| `revision` | Monotonic optimistic-lock revision |
| `owner_source`, `owner_user_id` | Creator identity |
| `created_at`, `opened_at`, `frozen_at`, `finished_at` | Lifecycle timestamps |
| `expires_at` | Abandoned-session cleanup deadline |
| `promotion_id` | Current/last promotion attempt |
| `attributes_json` | Labels, release ticket, description, approval references |

### `staging_item`

| Column | Meaning |
| --- | --- |
| `id` | Surrogate key |
| `session_id` | Owning session |
| `repository_id` | Target repository |
| `format` | Denormalized for validation/reporting |
| `logical_path` | Client-visible path or generated-metadata key |
| `logical_path_hash` | Unique per `(session_id, repository_id, logical_path)` |
| `component_id`, `asset_id`, `asset_blob_id` | Staged rows; nullable for group changes |
| `action` | `ADD`, `REPLACE`, `DELETE`, `GROUP_MEMBER_CHANGE` |
| `status` | `STAGED`, `VALIDATED`, `CONFLICT`, `PROMOTED`, `FAILED` |
| `source_path` | Path in the staged write request |
| `expected_sha256`, `expected_size` | Optional client-declared integrity |
| `attributes_json` | Format-specific report data |

The unique key `(session_id, repository_id, logical_path_hash)` gives idempotent re-uploads. A
second write of the same path either replaces the staged row (normal write-policy rules) or fails
with a conflict.

### `staging_group_change`

| Column | Meaning |
| --- | --- |
| `session_id`, `group_repository_id` | Target group |
| `member_repository_id` | Member being added/removed/reordered |
| `action` | `ADD`, `REMOVE`, `REORDER` |
| `position` | New order position |
| `status` | Validation/promotion state |

### `staging_promotion`

| Column | Meaning |
| --- | --- |
| `id` | Surrogate key |
| `session_id` | Session |
| `state` | `PREFLIGHT`, `RUNNING`, `SUCCEEDED`, `FAILED`, `ROLLED_BACK` |
| `fence_token` | Promotion lease fence |
| `manifest_sha256` | Hash of the frozen manifest |
| `report_json` | Per-item result, conflict details, timings |
| `started_at`, `finished_at` | Timestamps |

### Optional `staging_snapshot`

For formats that materialize generated metadata (Arch database, R index, npm group packument), a
promotion may record the metadata revision it produced. The metadata itself should remain
rebuildable from durable rows.

## Session credentials

A session can issue a short-lived `StagingToken` API key that inherits the creator's roles:

- `POST /internal/staging/sessions` returns the first token in the
  `X-KkRepo-Staging-Token` response header;
- `POST /internal/staging/sessions/{id}/credential` rotates it;
- the token's scopes bind every repository request to the session, so clients do not need to send
  `X-KkRepo-Staging` explicitly;
- the binding is applied at every authentication boundary (repository filter, Git LFS, Docker,
  Pub), **including cache hits of the API-key auth cache**. An explicit selector that contradicts
  the bound session is rejected with 403, so a staging token cannot be replayed against another
  session of the same owner;
- a staging token is a repository-content credential only: management endpoints (API keys, users,
  roles, settings) reject it, so `Authorization: Bearer StagingToken.<secret>` can never mint an
  ordinary API key. Docker's `/service/rest/v1/docker/token` is the only non-content endpoint that
  accepts it, because it only mints registry tokens for repository actions;
- when the token scope cannot be interpreted, the request is bound to an invalid session and fails
  closed (404) instead of falling back to live content;
- the token expires with the session and is revoked on close, cancel, rollback, or successful
  promotion;
- revocation removes the API key from the shared `api_key` table, so every replica observes it.
  The stored `stagingTokenId` is user-editable through `PATCH /sessions/{id}`, so revocation first
  verifies that the key is a staging key owned by the same user and scoped to this session;
- the token does not invent permissions: authorization still evaluates the owner's roles and the
  repository/action rules.

## Nexus-compatible API surface

The durable staging model is exposed through Nexus-shaped resources:

| Nexus path | Staging adaptation |
| --- | --- |
| `GET /service/rest/v1/repositories` | `stagingSession` scopes the list to participating repositories |
| `GET /service/rest/v1/repositories/{name}` | Same, with a session-scoped 404 when the repository is not a member |
| `GET /service/rest/v1/assets` | Staged assets when `stagingSession` is present; live assets otherwise |
| `GET /service/rest/v1/search/assets` | Same, with Nexus `continuationToken` paging |
| `GET/DELETE /service/rest/v1/assets/{id}` | Opaque staged asset IDs (`sa:...`) or live asset IDs |
| `GET /service/rest/v1/components` | Staged components grouped by the stored `stagingComponent` projection |
| `GET /service/rest/v1/components/{id}` | Opaque staged (`sc:...`) or live component IDs |
| `GET /service/rest/v1/status*` | Nexus-compatible availability/writable probes |
| `/service/rest/v1/staging/...` | Alias of the staging lifecycle API |

Staged asset download URLs carry `?stagingSession=<id>`, so clients that follow Nexus asset links
stay in the staged view.

`POST /sessions/{id}/commit` runs preflight and promotion in one call, so it requires
`nexus:staging:promote` (not the create/upload permission).
`POST /sessions/{id}/rollback` discards the diff and releases staged blobs.
`close` remains the explicit freeze stage for clients that want to inspect the preflight report
before committing.

Session metadata updates (`PATCH /sessions/{id}`) only replace user metadata. Server-owned
attributes — the session credential identity (`stagingTokenId`, `stagingTokenIssuedAt`) and the
approval policy/proof (`approvalRequired`, `approvedBySource`, `approvedByUserId`, `approvedAt`) —
always survive, including an empty replacement map, and a request that names one of them is
rejected with 400 so a client can neither forge an approval nor drop a four-eyes requirement.

## Request routing

A request participates in a session by one of:

1. `X-KkRepo-Staging: <session>` header (CI/API clients),
2. a `stagingSession=<session>` query parameter, or
3. the session bound to a `StagingToken` request credential.

Every protocol handler resolves the selector through one shared resolver before it chooses the
live publication path, so no selector can silently fall through to live content: a Git LFS object
PUT, a Docker manifest PUT/DELETE, a Pub publish finalization and every raw/Maven content request
honour the header, the query parameter and the credential binding identically. A header and query
parameter that disagree are rejected with 400, and an explicit selector that contradicts the
credential binding is rejected with 403.

The security filter resolves the session once, validates state and permissions, and stores it in a
request attribute. From that point on:

- `RepositoryRuntime` is unchanged (same format/settings).
- Every asset lookup uses a `Visibility` value: `LIVE` or `STAGING(sessionId)`.
- Caches key by `(repositoryId, visibility, path)`.
- Generated metadata is built from rows matching the visibility.

Normal clients never send the header/prefix, so behavior and URLs are unchanged.

## Staged write flow

1. Validate session state (`OPEN`), permission (`staging-write` on the repository), and expiry.
2. Run the normal protocol write path, but persist rows with `staging_session_id = session`.
3. Reuse content-addressed blobs; increment staging blob references.
4. Insert or replace the `staging_item` row in the same transaction.
5. Emit a staging audit event and a rebuild marker with the session key.
6. Do not enqueue live security scans, live notifications, or live group publication until
   promotion. A staging-scoped scan may run explicitly through the preflight API.

For formats with immutable coordinates, the staged row can exist while the live coordinate does
not. Promotion re-checks immutability against the live rows.

## Preflight

Preflight is side-effect-free apart from storing the report:

- session is `OPEN`/`FROZEN`, not expired, and the actor has `staging-promote`;
- every item's blob exists, size/checksum matches, and the format parser accepts it;
- target repositories are online and writable;
- write policy and immutability rules will accept the item set;
- group members have compatible formats and no cycles;
- cross-repository dependencies declared in `attributes_json` resolve (optional extension);
- generated metadata can be rebuilt within configured limits (for example an Arch database with
  too many packages);
- scanning policy can classify the resulting assets; policy blocks are reported before promotion;
- no concurrent promotion holds a conflicting repository lease.

Preflight produces a deterministic manifest sorted by `(repository, logical_path)` with the final
blob identities. The manifest hash is stored in `staging_promotion.manifest_sha256`.

## Promotion algorithm

Promotion must be crash-safe, idempotent, and ordered to avoid deadlocks.

1. **Freeze** — transition `OPEN → FROZEN` with an optimistic revision check; reject new writes.
2. **Preflight** — run and require success, transition to `PREFLIGHTED`.
3. **Acquire leases** — acquire fenced leases for all target repositories in ascending repository-id
   order, plus one session lease. If any acquisition fails, release all and fail without changes.
4. **Revalidate** — inside the transaction (or per-repository transaction for large sets), reload
   the session revision and live rows; re-check immutability/group cycles.
5. **Promote rows** — for each item in manifest order:
   - `ADD`/`REPLACE`: set `staging_session_id = NULL`, update component/asset timestamps, bind the
     final blob, and retire the replaced live binding if the write policy allows replacement;
   - `DELETE`: remove or tombstone the live row according to the format's normal deletion semantics;
   - `GROUP_MEMBER_CHANGE`: apply the membership change with the usual same-format/cycle checks.
6. **Bump watermarks** — increment repository/session version watermarks, enqueue index rebuilds and
   cache invalidations through the existing outbox/rebuild tables.
7. **Commit** — mark the session `PROMOTED`, promotion `SUCCEEDED`, write the final report.
8. **Publish derived views** — asynchronous workers rebuild/materialize metadata (Arch database,
   R index, npm group packument, etc.) and notify other replicas through the catalog broadcaster.
9. **Release leases**.

If the transaction fails, nothing becomes live and the session returns to `PREFLIGHTED` or
`FAILED` with a report. If a replica crashes after commit but before the report/outbox work
finishes, another replica can resume from the promotion state and the idempotent outbox rows.

### Single-transaction vs per-repository

- Same database and a small manifest: one transaction gives the strongest all-or-nothing guarantee.
- Very large manifests: use one transaction per repository plus a promotion state machine and a
  compensating rollback for already-promoted repositories. The manifest must record the previous
  live binding for each item so compensation is possible.

The first implementation should start with a single transaction and a configurable item limit.

## Rollback and cancellation

- Before promotion commits: `CANCEL` marks staged rows deleted; blob GC reclaims unreferenced
  blobs after the normal grace period.
- After promotion commits: there is no true transactional rollback across previously committed
  live state. Instead:
  - keep the promotion manifest with the previous live blob/path bindings;
  - offer a **compensating promotion** that stages the inverse operations and promotes them through
    the same path;
  - record both promotions in the audit trail.
- A session that fails mid-way remains `FAILED` and cannot be reused; create a new session.

## Group staging

A staged group must not expose live-only or staged-only halves.

- `staging_group_change` describes membership changes.
- Group reads in staging visibility resolve each member with the same session visibility, so a
  staged member version appears if and only if it belongs to that session's view.
- Format-specific group metadata (npm packument, Helm index, Arch database, Open VSX metadata) is
  rebuilt from the staging view.
- Promotion applies membership changes and bumps the group watermark once.

Optional: a "canary group" can point at the staging view while production points at live content.
This is a deployment choice, not a protocol change.

## Security model

New permissions (dynamic repository privileges can follow the existing `nx-repository-*` pattern):

- `staging-create` — create/close/cancel sessions and add repositories.
- `staging-write` — write staged content in a repository.
- `staging-read` — read the staging view.
- `staging-promote` — run preflight/promotion and approve.
- `staging-admin` — inspect/abort any session.

Every state change writes a security audit event with session id, revision, repository, item,
actor, client address, and outcome. Promotion requires a distinct permission from staging write so a
CI token cannot self-promote by default.

## Multi-replica and failure semantics

- Session state, revisions, items, and promotion state live only in MySQL/PostgreSQL.
- Leases use the existing fenced lease manager; a lost lease aborts the transaction.
- Blob writes happen before the row visibility change and are cleaned by the existing
  `BlobTransactionCleanup` path if the transaction fails.
- Staged rows count as blob references, so GC must include `staging_session_id IS NOT NULL` rows.
- A node-local cache may accelerate staging reads only if its key includes the session id and it is
  rebuildable from the database.
- The catalog broadcaster must invalidate both live and staging cache keys on promotion.

## API sketch

```text
POST   /internal/staging/sessions
GET    /internal/staging/sessions?state=OPEN
GET    /internal/staging/sessions/{id}
PATCH  /internal/staging/sessions/{id}          # labels/description/expiry
POST   /internal/staging/sessions/{id}/close
POST   /internal/staging/sessions/{id}/cancel
GET    /internal/staging/sessions/{id}/items
POST   /internal/staging/sessions/{id}/preflight
POST   /internal/staging/sessions/{id}/promote
GET    /internal/staging/sessions/{id}/report
POST   /internal/staging/sessions/{id}/compensate
```

Content writes use the normal repository endpoints with `X-KkRepo-Staging: {id}`. This means every
existing protocol client and the Components API can participate without a new upload format.

## Implemented API and permissions

```text
POST   /internal/staging/sessions
GET    /internal/staging/sessions?state=OPEN
GET    /internal/staging/sessions/{id}
PATCH  /internal/staging/sessions/{id}
POST   /internal/staging/sessions/{id}/close
POST   /internal/staging/sessions/{id}/cancel
DELETE /internal/staging/sessions/{id}
POST   /internal/staging/sessions/{id}/credential
POST   /internal/staging/sessions/{id}/commit
POST   /internal/staging/sessions/{id}/rollback
POST   /internal/staging/sessions/{id}/repositories
DELETE /internal/staging/sessions/{id}/repositories/{repositoryId}
GET    /internal/staging/sessions/{id}/items
GET    /internal/staging/sessions/{id}/groups
POST   /internal/staging/sessions/{id}/groups/{groupId}/members
DELETE /internal/staging/sessions/{id}/groups/{changeId}
POST   /internal/staging/sessions/{id}/approve
POST   /internal/staging/sessions/{id}/compensate
POST   /internal/staging/sessions/{id}/preflight
POST   /internal/staging/sessions/{id}/promote
GET    /internal/staging/sessions/{id}/report
GET    /internal/staging/metrics
```

Content writes and reads use existing Raw repository routes with the `X-KkRepo-Staging` header:

```bash
curl -u ci:token -T dist/app.jar \
  -H 'X-KkRepo-Staging: release-2026-10' \
  https://repo.example/repository/raw-release/dist/app.jar

curl -u ci:token \
  -H 'X-KkRepo-Staging: release-2026-10' \
  https://repo.example/repository/raw-release/dist/app.jar

# Arch package on a format branch with an Arch staging adapter
curl -u ci:token -T dist/demo-1.0.0-1-x86_64.pkg.tar.zst \
  -H 'X-KkRepo-Staging: release-2026-10' \
  https://repo.example/repository/arch-release/demo-1.0.0-1-x86_64.pkg.tar.zst

# Open VSX publication on a format branch with an Open VSX staging adapter
curl -u ci:token -X POST \
  -H 'X-KkRepo-Staging: release-2026-10' \
  --data-binary @dist/demo-1.0.0.vsix \
  https://repo.example/repository/openvsx-release/api/-/publish
```

Without the header the same URLs serve and write live content, so no client URL changes.

Session endpoints require `nexus:staging:read`, `nexus:staging:create` or
`nexus:staging:promote`; deleting session repositories requires `nexus:staging:admin`. The built-in
`nx-admin` role has all of these through `nx-all`. Repository content requests still pass through
the normal repository permissions (`read`, `edit`, `delete`) before session state is evaluated, so a
staging session cannot widen repository access. Session operations additionally require the caller
to own the session unless it holds `nexus:staging:admin`.

Promotion is intentionally a distinct permission from staging writes so a CI token can be allowed
to upload staged content without being able to publish it.

## Delivered implementation

| Area | Location |
| --- | --- |
| Schema | `V61__multi_repo_staging.sql` and `V62__staging_group_change.sql` (MySQL and PostgreSQL) |
| Durable state | `StagingDao`, `JdbcStagingDao`, `staging_session`, `staging_session_repository`, `staging_item`, `staging_promotion` |
| Lifecycle | `server/staging/StagingService.java` |
| Internal API | `server/staging/StagingController.java` |
| Permission mapping | `SecurityManagementFilter` (`/internal/staging/**`) |
| Raw content | `X-KkRepo-Staging` dispatch through `StagingHttpSupport`; staged blob writes through `RawHostedService.storeStagedBlob` |
| Format adapters | `StagingContentAdapter` SPI; Raw is built in, Arch and Open VSX adapters live on their format branches |
| Operations | expiry sweeper, operator CLI (`scripts/kkrepo-staging.sh`), admin console (`admin/staging.html`), Micrometer gauges |
| Tests | `StagingServiceTest`, `StagingControllerTest`, per-format adapter tests, `RawStagingBlackBoxCompatibilityTest`, persistence contracts on MySQL and PostgreSQL |

Current format coverage:

| Format | Staging content | Generated metadata | Source |
| --- | --- | --- | --- |
| Raw | yes | n/a | built in |
| Maven 2 | yes | `maven-metadata.xml` | `MavenStagingContentAdapter` |
| npm | yes | packument | `NpmStagingContentAdapter` |
| PyPI | yes | simple index | `PypiStagingContentAdapter` |
| Helm | yes | `index.yaml` | `HelmStagingContentAdapter` |
| NuGet | yes | registration + flat-container version index | `NugetStagingContentAdapter` |
| RubyGems | yes | compact index (`versions`, `info/{gem}`) | `RubygemsStagingContentAdapter` |
| Cargo | yes | sparse index | `CargoStagingContentAdapter` |
| Swift | yes | release list, release metadata, default `Package.swift` | `SwiftStagingContentAdapter` |
| Pub | yes | package metadata | `PubStagingContentAdapter` |
| Composer | yes | `p2/{vendor}/{package}.json` | `ComposerStagingContentAdapter` |
| Conan | yes | n/a (clients query revisions directly) | `ConanStagingContentAdapter` |
| Terraform | yes | provider versions, download metadata, SHA256SUMS + signature | `TerraformStagingContentAdapter` |
| Go | yes | `@v/list` | `GoStagingContentAdapter` |
| Conda | yes | `repodata.json` (+ bz2/zst) | `CondaStagingContentAdapter` |
| Ansible | yes | collection version list + detail | `AnsibleStagingContentAdapter` |
| Git LFS | yes | n/a (batch API resolves objects) | `GitLfsStagingContentAdapter` |
| Yum | yes | `repomd.xml` + primary/filelists/other | `YumStagingContentAdapter` |
| APT | yes | `Packages` (+gz/xz/bz2), `Release`, `InRelease`, `Release.gpg` | `AptStagingContentAdapter` |
| Alpine | yes | signed `APKINDEX.tar.gz` | `AlpineStagingContentAdapter` |
| R | yes | `PACKAGES.gz` | `RStagingContentAdapter` |
| Docker / OCI | yes | tag listing merged with staged manifests | `DockerStagingContentAdapter` |
| Arch Linux | yes | `.db`/`.files` view | `feat/staging-arch` |
| Open VSX | yes | extension JSON view | `feat/staging-openvsx` |
| Hugging Face | n/a | n/a | proxy-only format, no hosted writes yet |

Current limitations:

- Group membership changes are staged explicitly and applied at promotion; cycles, self-membership
  and cross-format members are rejected during preflight.
- Every hosted format handled by this branch exposes a staging-aware index, so clients resolve
  staged content before promotion. Arch `.db`/`.files` and Open VSX extension metadata are
  delivered in the stacked format PRs.
- Swift staging serves the package's default `Package.swift`; alternate tools-version manifests
  (`Package@swift-X.swift`) and their `Link` alternates are advertised after promotion.
- An expired session is cancelled by the sweeper and its staged blob references are released; the
  session row stays visible for reporting until an operator deletes it.
- Concurrent direct live writes are revalidated at promotion; an ADD that loses the race fails
  atomically instead of overwriting the winner.

## Format-specific notes

| Format | Staging consequence |
| --- | --- |
| Maven / npm / PyPI / Raw / Helm | Mostly asset-row visibility; generated indexes/packuments must be rebuilt with the session view |
| Open VSX | Stage VSIX assets and component rows; metadata JSON is generated per visibility |
| Arch Linux | Stage package files; the `<repo>.db`/`.files` view is generated from the staging package set |
| APT / Alpine / R | Stage packages; signed metadata must be regenerated in an isolated staging namespace or with a staging signing key |
| Docker / OCI | Manifests and blobs use the same overlay; tag/manifest immutability revalidation is mandatory at promotion |
| Group repositories | Membership changes are staged explicitly; group metadata must never mix live and staging members outside the session view |

## Phased delivery

1. **Foundation** — migrations, session/item/repository membership tables, permissions, audit,
   header/prefix resolution, live-vs-staging visibility in `AssetDao` lookups, and a read-only
   staging view for Raw.
2. **Hosted write + promote** — staged writes for Raw and Maven, preflight, single-transaction
   promotion, outbox invalidations, GC accounting.
3. **Generated-metadata formats** — Arch database, Open VSX JSON, npm packuments, R indexes, APT/
   Alpine indexes; staging-scoped rebuilds and watermarks.
4. **Group staging and cross-repo dependencies** — staged membership changes, dependency checks,
   canary group support.
5. **Operations** — admin UI timeline/report, CLI, approvals, compensating promotion, metrics
   (`staging_sessions_open`, `staging_promotion_duration`, `staging_items_conflict`), and retention.

## Open questions

- Should promotion be allowed to change group membership and repository settings, or only content?
- Is a single-transaction limit acceptable for the largest expected release, or should
  per-repository compensation be the default?
- How should signed metadata (APT/Alpine/R) be handled when the staging key differs from the live
  key? Likely a staging-specific key id surfaced in the report.
- Should a promotion be approvable by a second user (four-eyes) in the first release, or only by a
  distinct permission?
- What is the retention window for failed/cancelled staged blobs before GC?

## CLI

`scripts/kkrepo-staging.sh` wraps the internal staging API for operators and CI bootstrap:

```bash
export KKREPO_URL=https://repo.example
export KKREPO_USER=admin
export KKREPO_PASSWORD=...

scripts/kkrepo-staging.sh create release-2026-10 raw-release maven-release --approval-required
scripts/kkrepo-staging.sh items 1
scripts/kkrepo-staging.sh add-group 1 10 11 ADD
scripts/kkrepo-staging.sh preflight 1
scripts/kkrepo-staging.sh approve 1
scripts/kkrepo-staging.sh promote 1
scripts/kkrepo-staging.sh compensate 1
scripts/kkrepo-staging.sh metrics
```

The CLI uses only `curl`; responses are returned as JSON and can be piped to `jq`.
