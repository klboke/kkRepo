# Docker Repository Research And Development Plan

This document plans the Docker/OCI repository format for nexus-plus. The goal is not to reinvent a container registry protocol, but to implement the compatible overlap between Nexus Docker repository behavior, Docker Registry HTTP API V2, and OCI Distribution, while fitting the nexus-plus MySQL + OSS/S3 + multi-replica architecture.

## Research Baseline

Implementation must be checked against these protocols and reference behaviors first:

- Docker Registry HTTP API V2 / CNCF Distribution: `/v2/` probing, manifests, blobs, uploads, tags, catalog, error model, and headers such as `Docker-Content-Digest`.
- OCI Distribution Specification: OCI pull, push, content discovery, content management, referrers API, and OCI conformance categories.
- OCI Image Spec: image manifest, image index, descriptor, digest, media type, and subject/referrers semantics.
- Docker Registry token authentication: `WWW-Authenticate: Bearer ...` challenges, token endpoint, and `repository:<name>:pull,push` scopes.
- Nexus Repository Docker Registry documentation: Docker hosted/proxy/group repositories, path-based routing, port connectors, Docker Hub `library` namespace behavior, OCI support, and Nexus UI/client access patterns.

Key conclusions:

- Docker clients do not use the normal artifact repository URL shape `/repository/<repo>/...`. Nexus path-based routing uses the first image path segment as the repository name. For example, `docker pull nexus.example/docker-group/library/alpine:latest` maps to the registry API path `/v2/docker-group/library/alpine/manifests/latest`. Therefore, nexus-plus needs a Docker-specific `/v2/...` route and must parse the first segment as the nexus-plus repository name.
- The minimum pull surface is `/v2/`, manifest GET/HEAD, blob GET/HEAD, tag list, and the authentication challenge. Docker push must support blob upload sessions. Real clients commonly use `POST /blobs/uploads/` + `PATCH` + `PUT ?digest=...`, so a single PUT-only upload path is not enough.
- Docker/OCI is content-addressed. Blob and manifest digests must be calculated from the exact original bytes on the server. Do not reorder JSON, rewrite timestamps, or canonicalize the body before calculating digests.
- OCI referrers are now important for signatures, SBOMs, attestations, and related artifacts. The first phase can avoid blocking normal `docker pull/push` on referrers, but the data model should reserve subject/referrers indexes from the start.
- Nexus Docker supports both path-based routing and port connectors. The first phase of nexus-plus should keep a dedicated Docker traffic port so large image layer uploads/downloads do not crowd out the main service port, Admin UI, REST APIs, or normal artifact protocol requests. Inside that port, repository resolution should still prefer path-based routing. Nexus-style per-repository connector ports can be added later as a compatibility enhancement.

## Feature Scope

### Required In The First Phase

1. Docker hosted repositories
   - Add the `docker-hosted` recipe, with `ALLOW`, `ALLOW_ONCE`, and `DENY` write policies.
   - Support `/v2/` probing and return `Docker-Distribution-API-Version: registry/2.0`.
   - Support Docker Bearer token challenge and token endpoint for `docker login`, `docker pull`, and `docker push`.
   - Support blob HEAD/GET with `Docker-Content-Digest`, `Content-Length`, `Content-Type`, and Range downloads.
   - Support blob upload sessions: start, status, chunk append, complete, and cancel.
   - Support cross-repository blob mount: when the blob store and permissions allow it, register a reference without duplicating the large blob.
   - Support manifest PUT/GET/HEAD/DELETE for Docker schema2 manifest, Docker manifest list, OCI image manifest, and OCI image index.
   - Support tag list and tag delete, including `n` and `last` pagination.
   - Persist manifest digest, tags, blob metadata in MySQL and blob bytes in OSS/S3.

2. Docker proxy repositories
   - Add the `docker-proxy` recipe, including remote URL, remote Basic/Bearer token authentication, and content/max-age settings.
   - Fetch and cache remote manifests, manifest lists/indexes, and blobs.
   - Revalidate tag references by TTL. Treat digest manifests and blobs as content-addressed cache entries.
   - Support Docker Hub single-segment image compensation: when proxying Docker Hub, map `alpine` to `library/alpine` according to Nexus reference behavior.
   - Support proxying another Nexus Docker repository by configuring the full remote repository URL.

3. Docker group repositories
   - Add the `docker-group` recipe, resolving manifests, blobs, and tags by member order.
   - Make groups read-only. Push/delete should return the status matching Nexus reference behavior.
   - Resolve tag conflicts by first matching member.
   - Prefer the member that served the manifest when looking up blobs, then fall back to member order, avoiding incorrect cross-member layer reads.

4. Compatibility tests and real client verification
   - Add Docker black-box compatibility tests against a Nexus reference instance.
   - Verify hosted push/pull, proxy pull, and group pull with `docker`, `oras`, or `skopeo`.
   - Add OCI Distribution conformance as supplemental verification, enabling Pull, Push, Content Discovery, and Content Management in phases.

### Recommended Second Phase

- OCI referrers API: `GET /v2/<repo>/<image>/referrers/<digest>` with `artifactType` filtering.
- Persist OCI subjects and backfill referrers indexes for cosign signatures, SBOMs, attestations, and similar artifacts.
- `/v2/_catalog`, available only to administrators or explicitly allowed registry scopes.
- Docker V1 API and `docker search` compatibility. Nexus documentation still mentions V1 fallback, but modern Docker/OCI workflows should not depend on it, so this is lower priority than V2/OCI.
- Nexus-style per-repository port connectors. The first phase should provide a shared dedicated Docker port for traffic isolation. If full compatibility with historical Nexus port-based image references is needed, add per-repository connector port mapping later.

## URL And Routing Design

Add `DockerRegistryController`. Do not attach it under the existing `RepositoryContentController` `/repository/{name}` route.

## Docker Traffic Port Design

Docker image layers are usually much larger than Maven/npm/PyPI packages, and their connections stay open longer. The first phase should split Docker registry traffic away from the main application port into a dedicated connector. Recommended defaults:

- Main service port: `8080`, continuing to serve `/repository/...`, `/admin/`, `/browse/`, and management REST.
- Management port: `8081`, continuing to serve actuator.
- Docker registry port: for example `8082`, serving only `/v2/...` and the Docker token flow.

The dedicated port does not replace path-based routing. It provides a traffic isolation boundary:

- The ingress layer can configure longer upload/download timeouts, larger body limits, wider connection pools, and independent rate limits for the Docker port.
- The main service port can keep shorter timeouts, avoiding image layer long connections slowing down the UI, permission management, migration console, and normal repository protocol requests.
- Kubernetes Service / Ingress can expose the Docker port independently and apply HPA, monitoring, alerts, and circuit breakers specifically to Docker traffic.
- The Tomcat layer should give the Docker connector an independent executor, `maxConnections`, `acceptCount`, `connectionTimeout`, and upload concurrency limits where possible. A separate listening port that still shares the same thread pool only isolates entry routing; it does not fully isolate JVM thread contention.
- The OSS/S3 client should also reserve an independent bulkhead or connection pool settings for Docker large-object reads and writes, preventing Docker layer pulls from exhausting object storage connections used by normal artifact requests.

Configuration keys to reserve:

- `nexus-plus.docker.connector.enabled=true`
- `nexus-plus.docker.connector.port=8082`
- `nexus-plus.docker.connector.threads.max`
- `nexus-plus.docker.connector.max-connections`
- `nexus-plus.docker.connector.accept-count`
- `nexus-plus.docker.connector.connection-timeout`
- `nexus-plus.docker.transfer.max-concurrent-uploads`
- `nexus-plus.docker.transfer.max-concurrent-downloads`
- `nexus-plus.docker.transfer.response-buffer-size`

Routing strategy:

- Primary Docker dedicated-port entrypoint: `http(s)://<host>:<docker-port>/v2/<repo>/<image...>/...`.
- Docker client image reference: `<host>:<docker-port>/<repo>/<image>:<tag>`.
- If per-repository connectors are added later, a port can be bound directly to a repository. The client reference becomes `<host>:<repo-port>/<image>:<tag>`, and the server maps that connector's local port to a fixed repository id.

Path parsing rules:

| Request | Resolution |
| --- | --- |
| `GET /v2/` | Registry probe, not bound to a specific repository |
| `/v2/<repo>/<image...>/manifests/<reference>` | `<repo>` is the nexus-plus repository name, and `<image...>` is the Docker repository name |
| `/v2/<repo>/<image...>/blobs/<digest>` | Digest is an OCI digest such as `sha256:<hex>` |
| `/v2/<repo>/<image...>/blobs/uploads/` | Create an upload session |
| `/v2/<repo>/<image...>/blobs/uploads/<uuid>` | Read, append, complete, or cancel an upload session |
| `/v2/<repo>/<image...>/tags/list` | Tag list |
| `/v2/<repo>/<image...>/referrers/<digest>` | OCI referrers |

Do not parse Docker image names by a fixed segment count. `<image...>` may have multiple path levels. Parse it by right-side sentinel segments: `/manifests/`, `/blobs/`, `/blobs/uploads/`, `/tags/list`, and `/referrers/`.

An internal route such as `/repository/<repo>/v2/<image...>/...` can be reserved for migration and proxy scenarios, but it should not be the primary Docker client entrypoint. Public client documentation should use path-based routing on the dedicated Docker port.

## Data Model Plan

The existing `component`, `asset`, and `asset_blob` tables can hold general content and browse/search metadata, but Docker needs additional relationship tables for tags, manifest digests, manifest references, and upload sessions.

Recommended Flyway migration additions:

### `docker_manifest`

Stores the original manifest JSON blob and digest metadata.

Core fields:

- `id`
- `repository_id`
- `image_name`
- `image_name_hash`
- `digest_algorithm`
- `digest`
- `digest_hash`
- `media_type`
- `artifact_type`
- `subject_digest`
- `subject_digest_hash`
- `asset_id`
- `size`
- `pushed_by`
- `pushed_by_ip`
- `deleted_at`
- `attributes_json`
- `created_at`
- `updated_at`

Constraints:

- `UNIQUE(repository_id, image_name_hash, digest_hash)`
- `KEY(repository_id, subject_digest_hash)`
- `KEY(repository_id, image_name_hash, updated_at)`

The manifest body should be stored as original bytes in `asset_blob`. `docker_manifest.asset_id` points to the corresponding asset. The asset path can use a stable internal path such as `docker/manifests/<image-name>/sha256/<hex>`.

### `docker_tag`

Stores the current pointer from a tag to a manifest digest.

Core fields:

- `repository_id`
- `image_name`
- `image_name_hash`
- `tag`
- `tag_hash`
- `manifest_id`
- `manifest_digest`
- `pushed_by`
- `pushed_by_ip`
- `created_at`
- `updated_at`

Constraints:

- `UNIQUE(repository_id, image_name_hash, tag_hash)`
- `KEY(repository_id, image_name_hash, tag)`

A tag is not a blob and should not be stored as a large object. It is a transactionally updated pointer in MySQL.

### `docker_manifest_reference`

Stores config, layer, and child manifest references inside a manifest for push validation, GC, referrers, and UI display.

Core fields:

- `manifest_id`
- `repository_id`
- `image_name`
- `reference_kind`: `CONFIG`, `LAYER`, `MANIFEST`
- `digest`
- `digest_hash`
- `media_type`
- `size`
- `platform_json`
- `annotations_json`

Constraints:

- `KEY(repository_id, digest_hash)`
- `KEY(manifest_id, reference_kind)`

### `docker_upload_session`

Stores blob upload sessions. MySQL must be the source of truth; this cannot live only in JVM memory.

Core fields:

- `uuid`
- `repository_id`
- `image_name`
- `status`: `STARTED`, `COMPLETING`, `COMPLETED`, `CANCELLED`, `EXPIRED`
- `next_offset`
- `digest_algorithm`
- `expected_digest`
- `created_by`
- `created_by_ip`
- `expires_at`
- `locked_by`
- `locked_until`
- `attributes_json`
- `created_at`
- `updated_at`

### `docker_upload_chunk`

If the underlying OSS/S3 storage does not provide cross-replica append-capable staging, each PATCH chunk should first be written as a temporary object and registered.

Core fields:

- `session_uuid`
- `chunk_index`
- `start_offset`
- `end_offset`
- `object_key`
- `sha256`
- `size`
- `created_at`

When completing an upload, merge chunks in order into the final digest object, then write `asset_blob`. The cleanup task should delete unfinished sessions and temporary chunk objects according to `expires_at`.

## Blob And Object Storage Strategy

Docker blobs are content-addressed. Final storage should use stable digest-based names, for example:

- Blob: `docker/blobs/sha256/<first-two>/<hex>`
- Manifest: `docker/manifests/<image-name>/sha256/<hex>`
- Upload chunk: `docker/uploads/<uuid>/<chunk-index>`

Write flow:

1. Put uploaded body data into cross-replica recoverable staging. Do not use a single-node temporary directory as the only source of truth.
2. On completion, calculate digest and size, then verify the client `digest` parameter.
3. Write the final digest object key to OSS/S3.
4. In a MySQL transaction, write `asset_blob`, `asset`, `docker_manifest`, or blob asset references.
5. If the transaction fails, delete the newly written object if it is unreferenced, or write a cleanup marker.

Deduplication strategy:

- Blobs with the same `sha256 + size` in the same blob store can be reused.
- Cross-repository mount must check both source repository pull permission and target repository push permission.
- If the source blob and target repository use different blob stores, the first phase can return `202` and start a normal upload. Cross-store copy optimization can be added later.

## Permission And Authentication Design

Docker clients prefer Bearer tokens. The existing `RepositorySecurityFilter` Basic challenge for `/repository/...` is not enough for Docker compatibility. Add a Docker-specific authentication chain.

### Challenge

Unauthenticated requests return:

```text
401 Unauthorized
WWW-Authenticate: Bearer realm="<base-url>/service/rest/v1/docker/token",service="<registry-service>",scope="repository:<repo>/<image>:pull"
Docker-Distribution-API-Version: registry/2.0
```

Push scenarios use `pull,push` scopes, and delete scenarios can map to `delete`.

### Token endpoint

Add:

- `GET /service/rest/v1/docker/token`
- Compatible handling for `service`, `scope`, `client_id`, and `offline_token`
- Support Basic auth, existing session, API key, or anonymous subject
- Return both `token` and `access_token`; `expires_in` must not be lower than Docker client compatibility expectations

Recommended token implementation:

- In the first phase, use short-lived opaque tokens verifiable through MySQL to avoid multi-replica signing-key distribution problems.
- If this later changes to JWT, the signing key must come from shared configuration and support key rotation.

Scope mapping to nexus-plus permissions:

| Docker action | nexus-plus permission |
| --- | --- |
| `pull` | `READ`, and `BROWSE` when needed |
| `push` | `ADD` for blob upload, `EDIT` for manifest/tag overwrite |
| `delete` | `DELETE` |
| catalog | `ADMIN` or a dedicated registry browse permission |

Anonymous pull must follow the existing anonymous-read configuration and repository permissions. It must not bypass `SecurityAuthorizationCache`.

## Hosted Service Design

Recommended split:

- `protocol-docker`: path parsing, digest/media type validation, manifest descriptor parsing, and error model.
- `server/docker/DockerRegistryController`: HTTP routing and response assembly.
- `DockerHostedService`: hosted pull/push/delete behavior.
- `DockerUploadService`: upload session, chunk, commit, and cancel.
- `DockerManifestStore`: original manifest bytes, tags, references, and referrers.
- `DockerBlobStore`: blob asset and `asset_blob` reads/writes, deduplication, and mount.
- `DockerErrorAdvice`: registry error JSON.
- `DockerResponseSupport`: headers such as `Docker-Content-Digest`, `Location`, `Range`, `Link`, and `OCI-Subject`.

Hosted behavior checklist:

| Capability | Behavior |
| --- | --- |
| `/v2/` | 200 or 401, always with the registry API header |
| manifest GET/HEAD | Return 200 for matching tag or digest; digest header is the manifest digest; perform basic media type selection from Accept |
| manifest PUT | Store original body, calculate digest, parse descriptors, validate referenced blob/manifest existence |
| tag PUT | `PUT /manifests/<tag>` updates the `docker_tag` pointer |
| digest PUT + tag parameters | Support OCI `?tag=...` bulk tagging |
| blob HEAD/GET | Find by digest, support Range; missing entries return 404 `BLOB_UNKNOWN` |
| upload POST | Return 202, `Location`, and `Range` |
| upload PATCH | Append chunk, validate offset, return 202 and `Range` |
| upload PUT | Verify digest, complete write, return 201, blob location, and digest header |
| upload DELETE | Cancel session, return 204 |
| tags/list | Lexicographic pagination, returning `{"name": "...", "tags": [...]}` |
| manifest DELETE tag | Delete the tag pointer |
| manifest DELETE digest | Delete manifest and related tags; physical blob removal is left to GC |
| referrers | Return OCI image index; empty results also return 200 with empty `manifests` |

## Proxy Service Design

Proxy service must support upstream registry Bearer challenges. It must not simply pass upstream 401 responses through to the local client.

Manifest pull:

1. Return from local tag cache if it is still fresh.
2. Revalidate with upstream HEAD/GET when the tag cache expires.
3. Treat digest manifest hits as immutable unless cleanup or manual invalidation happens.
4. Store original manifest body, media type, digest, and reference relationships.

Blob pull:

1. Return local digest blob hits directly.
2. On miss, GET from upstream, calculate digest while downloading, and store only after successful verification.
3. Large blob failures must clean up staging objects and must not leave visible assets behind.

Upstream authentication:

- Parse upstream `WWW-Authenticate` `realm`, `service`, and `scope`.
- Repository configuration supports remote Basic credentials or anonymous access.
- Token cache lives in shared TTL cache or MySQL. Its key should include remote URL, service, scope, and username hash. Token cache loss should only cause a token refresh.

Docker Hub special case:

- For single-segment image names, add `library/` according to Nexus reference behavior.
- Docker Hub rate-limit headers can be logged or surfaced as metrics, but should not pollute protocol responses.

## Group Service Design

Groups do not store new blobs. They only select, cache, and merge members.

Manifest lookup:

- Search tags/digests by member order.
- Resolve tag conflicts by first matching member.
- Record `group-member-asset` or a new Docker group manifest cache with key `groupId:image:reference` and value member repository id, manifest digest, and TTL.

Blob lookup:

- If the request follows a manifest response, prefer the member that served that manifest.
- Otherwise, search digest blobs by member order.
- After a member hit, write a rebuildable TTL cache entry. Invalidate it through repository cache tokens when members change or are deleted.

Tag list:

- Merge member tags, deduplicate, and paginate lexicographically.
- Large repository tag lists should avoid loading all tags at once. The first phase can implement basic pagination; the second phase can optimize cursor queries.

## Admin And Browse UI

Admin UI:

- Add `docker-hosted`, `docker-proxy`, and `docker-group` to the repository recipe dropdown.
- Show path-based pull/push examples for the dedicated Docker port.
- Add a Docker connector configuration view showing the current Docker port, entry URL, connection count, upload/download concurrency, and rate-limit state.
- Add proxy configuration fields for remote authentication, Docker Hub `library` namespace hints, and remote URL path validation.
- Reuse member order management for groups.
- Let the Repository cache page clear Docker manifest/tag/blob cache.

Browse UI:

- Show image name as the first-level object.
- Image detail shows tags, manifest digest, media type, size, platform, and updated time.
- Manifest detail shows config, layers, child manifests, subject, and referrers.
- Blob detail shows digest, size, content type, and last downloaded time.

## Migration Tooling

`migration-nexus` needs support for source Nexus Docker hosted repositories.

Migration order:

1. Metadata migration creates Docker repositories, permissions, and blob store mappings.
2. Repository data migration scans source Nexus Docker assets/components.
3. Migrate blobs first, then manifest/tag pointers.
4. Recalculate each manifest digest and compare it with source metadata.
5. Support incremental migration using source asset `blob_updated`, `last_updated`, or Docker tag/manifest update-time cursors.

Migration verification:

- Sample `docker pull`.
- Compare tag lists.
- Compare manifest digest, blob digest, and manifest media type.
- Compare common multi-arch manifest lists.

## Compatibility Test Plan

Add Docker tests under `compat-test`, following the same black-box style as Maven/npm/PyPI and other protocols.

### Nexus Reference Tests

Coverage:

- Hosted create, login, push, pull, delete.
- Proxy to Docker Hub or a test registry.
- Proxy to another Nexus Docker repository.
- Group member order, tag conflicts, and blob lookup.
- Anonymous pull and private repository challenge.
- Write policy: `ALLOW`, `ALLOW_ONCE`, `DENY`.
- Dedicated Docker port path-based routing: `<host>:<docker-port>/<repo>/<image>:<tag>`.

Comparison items:

- HTTP status.
- `WWW-Authenticate` challenge.
- `Docker-Distribution-API-Version`.
- `Docker-Content-Digest`.
- `Location`.
- Upload `Range`.
- Manifest `Content-Type`.
- Registry error JSON code/message.

### Real Client Tests

Suggested script:

```bash
docker login localhost:18090
docker pull localhost:18090/docker-proxy/library/alpine:latest
docker tag alpine:latest localhost:18090/docker-hosted/team/alpine:test
docker push localhost:18090/docker-hosted/team/alpine:test
docker pull localhost:18090/docker-group/team/alpine:test
oras push localhost:18090/docker-hosted/team/artifact:sbom ./sbom.spdx.json:application/spdx+json
```

### OCI Conformance

Enable in phases:

1. `OCI_TEST_PULL=1`
2. `OCI_TEST_PUSH=1`
3. `OCI_TEST_CONTENT_DISCOVERY=1`
4. `OCI_TEST_CONTENT_MANAGEMENT=1`

Content Management can pass last because blob/manifest DELETE interacts with GC, cleanup policies, and reference counting.

## Development Phase Breakdown

### M0: Protocol And Reference Behavior Baseline

- Create docker hosted/proxy/group repositories in the Nexus reference instance.
- Capture `docker login/pull/push/delete` request logs.
- Add failing cases in `compat-test` first, locking dedicated Docker port, path-based routing, 401 challenge, and upload response headers.
- Produce a Nexus behavior matrix for implementation acceptance.

### M1: Format Skeleton And Routing

- Add `DOCKER` to `RepositoryFormat`.
- Add `docker-hosted`, `docker-proxy`, and `docker-group` to `RepositoryRecipes`.
- Add `protocol-docker` to the root `pom.xml`.
- Add Docker path parser, digest parser, media type constants, and error payloads.
- Add `/v2/` and `/v2/<repo>/...` controller skeleton.
- Add a Docker-dedicated Tomcat connector or equivalent independent entrypoint configuration, mounted only to Docker registry routes by default.
- Let `RepositorySecurityFilter` or a new filter recognize `/v2/...`, while Docker auth support owns the challenge.
- Make Admin/Browse repository lists display Docker format.

Acceptance:

- `GET /v2/` on the dedicated Docker port matches Nexus behavior.
- Docker large-object requests do not go through the main service port, while `/admin/`, `/browse/`, and `/repository/...` still work on the main port.
- Docker hosted/proxy/group repositories can be created.
- Unsupported Docker endpoints return registry error JSON, not Maven/npm errors.

### M2: Hosted Pull/Push Core Loop

- Add Docker Flyway tables and DAO classes.
- Implement `DockerUploadService`, with all session/chunk state stored in MySQL/OSS staging.
- Implement blob HEAD/GET/POST/PATCH/PUT/DELETE.
- Implement manifest PUT/GET/HEAD.
- Implement tag list and tag delete.
- Implement digest, size, media type, and descriptor validation.
- Write asset/blob/browse metadata and hook into `AssetMetadataCache` invalidation.

Acceptance:

- `docker push` of a normal amd64 image succeeds.
- `docker pull` of the same tag succeeds.
- Manifest digest matches the client's local digest.
- After service restart or replica switch, unfinished uploads can be queried, cancelled, or expired, and completed content remains pullable.

### M3: Docker Bearer Token And Permissions

- Add token endpoint.
- Implement challenge scope generation.
- Implement token grants as the intersection with repository permissions.
- Validate Docker Bearer token and attach request subject.
- Test anonymous pull, authenticated pull, push, and delete permissions separately.
- Test API key/basic/session interaction with Docker login.

Acceptance:

- `docker login` succeeds.
- Unauthorized push returns 401/403 matching Nexus reference behavior.
- Users with only pull permission cannot push.
- Token validation in multi-replica deployment does not depend on single-JVM memory.

### M4: Proxy Repositories

- Implement upstream request builder and remote token auth.
- Implement manifest/tag TTL cache and revalidation.
- Implement blob caching by digest.
- Implement Docker Hub `library` compensation.
- Implement proxy negative cache with a short TTL and cache-token invalidation.

Acceptance:

- Pull images through proxy from Docker Hub or a test registry.
- First pull goes upstream; second pull hits local blob/manifest cache.
- Tags refresh after upstream changes and TTL expiry.
- Upstream 401 token flow does not leak to the local client.

### M5: Group Repositories

- Implement group manifest/blob/tag list.
- Implement group member hit cache.
- Invalidate Docker group cache after repository member changes.
- Cover tag conflicts, offline members, and mixed proxy+hosted scenarios.

Acceptance:

- Group pull from hosted image succeeds.
- Group pull from proxy image succeeds.
- Changing member order changes tag-conflict results according to Nexus reference behavior.

### M6: OCI Referrers And Advanced Compatibility

- Process the `subject` field on manifest PUT and write the index.
- Implement `/referrers/<digest>` and `artifactType` filtering.
- Add `OCI-Subject` to PUT manifest responses.
- Support basic cosign/oras artifact scenarios.
- Evaluate whether to support referrers tag schema fallback.

Acceptance:

- `oras attach` or cosign-style artifacts can be pushed and pulled.
- Empty referrers return 200 with an empty image index.
- The index stays consistent after deleting a subject or referrer.

### M7: Content Management, GC, And Cleanup

- Align DELETE manifest/tag/blob with Nexus reference behavior.
- Blob deletion must not immediately break content still referenced by manifests.
- GC worker determines removable objects from `docker_manifest_reference`, `asset`, and `asset_blob`.
- Cleanup policy supports tag, last downloaded, last updated, and untagged manifest cleanup.
- Upload session cleanup worker deletes expired sessions and staging objects.

Acceptance:

- OCI conformance Content Management passes, or any compatibility difference is explicitly documented.
- Large numbers of interrupted uploads do not accumulate uncleanable temporary objects.

### M8: Migration, UI, And Operations Observability

- Add Docker metadata and data migration support to `migration-nexus`.
- Add Docker detail views to Admin UI and Browse UI.
- Add Docker metrics: upload sessions, proxy upstream latency, manifest cache hit, blob cache hit, digest verification failure, and referrer count.
- Update docs: README supported formats, development guide, migration guide, and compatibility testing guide.

Acceptance:

- After migrating a batch of real images from Nexus Docker hosted repositories, clients can pull without changing image references.
- `/actuator/prometheus` exposes Docker request, upload, proxy, and cache metrics.

## Multi-Replica Consistency Requirements

Docker implementation must satisfy:

- Upload sessions, chunk offsets, and final manifest/tag pointers use MySQL as the source of truth.
- Unfinished uploads do not depend on local temporary files. After replica restart, they can be resumed, cancelled, or expired.
- Tag updates, manifest deletion, and group member changes invalidate sibling replicas through MySQL transactions and cache tokens.
- Manifest/tag writes must have unique constraints. Concurrent pushes to the same tag must produce one final pointer.
- Blob deduplication is coordinated through unique constraints and `FOR UPDATE`, not JVM locks.
- Proxy tokens, negative cache, and group member cache are rebuildable TTL caches. Losing them must not affect correctness.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Docker large-object traffic shares the main service port, thread pool, and object storage connections | Large image push/pull may slow down UI, management REST, and normal artifact protocols | Provide a dedicated Docker port in the first phase, with independent connector/executor, connection counts, timeouts, upload/download concurrency, and OSS/S3 bulkhead |
| Docker path-based routing does not match `/repository/<repo>` | Docker CLI cannot be used directly | Provide `/v2/<repo>/...` on the dedicated Docker port, matching Nexus Docker behavior |
| Chunked upload depends on local temporary files | Push fails or data is lost after replica switch | Persist upload sessions and chunk staging in MySQL/OSS |
| Manifest JSON is rewritten | Digest mismatch causes client rejection | Store original bytes and parse only for indexing |
| Proxy upstream Bearer token handling is incomplete | Docker Hub or remote registry pulls fail | Implement a dedicated upstream auth flow and token cache |
| Group blob lookup hits the wrong member | Layer 404 after manifest response | Couple manifest-serving member with blob lookup cache |
| Concurrent tag overwrite | Tag pointer corruption | `docker_tag` unique constraint plus transactional updates |
| DELETE and GC delete blobs too early | Existing images fail to pull | Reference-table-driven GC; DELETE first performs logical deletion |

## Recommended First Deliverable

Define the first deliverable as:

- Support `docker-hosted`.
- Support a dedicated Docker port and path-based `/v2/<repo>/<image>`.
- Support Bearer token login.
- Support Docker schema2 and OCI image manifest/index.
- Support normal `docker push`, `docker pull`, `docker tag`, and `docker manifest inspect`.
- Support tag list and manifest/blob HEAD.
- Do not yet commit to proxy, group, referrers, catalog, V1 search, or per-repository port connectors.

The second deliverable can add proxy/group, and the third can add referrers, migration, and full OCI conformance workflows. This closes the real Docker client loop early without forcing the hardest parts, such as remote authentication, group merging, and GC, into the first release.
