# Open VSX Repository Design

Status: v1 implemented (`RepositoryFormat.OPENVSX`, `protocol-openvsx`, `server.openvsx`).

This document records the protocol mapping, storage model, multi-replica semantics, integration
points, and remaining work for VS Code / Open VSX repositories.

## Scope

Supported recipes:

- `openvsx-hosted`
- `openvsx-proxy`
- `openvsx-group`

The implementation covers the Open VSX registry API at `/api/...` and the VS Code gallery
compatibility routes at `/vscode/...`, under the normal kkrepo `/repository/<repo>/` prefix.
Marketplace social features (reviews, ratings, publisher agreements, trusted publishing) are out of
scope.

## Official protocol mapping

The route table below is derived from the Open VSX OpenAPI document at
`https://open-vsx.org/v3/api-docs`.

| Client route | kkrepo behavior |
| --- | --- |
| `GET api/version` | Registry version and maximum extension size |
| `GET api/-/search` | Search over components with `query`, `category`, `targetPlatform`, paging, sort, `includeAllVersions` |
| `GET api/-/query`, `GET api/v2/-/query` | Namespace/extension/version/extensionId filters |
| `GET api/{namespace}` | Namespace metadata and extension URL map |
| `GET api/{namespace}/{extension}` | Latest stable version metadata |
| `GET api/{namespace}/{extension}/versions` | Version map with paging |
| `GET api/{namespace}/{extension}/version-references` | Version reference list |
| `GET api/{namespace}/{extension}/{version}` | Specific version metadata |
| `GET api/{namespace}/{extension}/{version}/file/**` | VSIX bytes or a bounded member extraction |
| `GET api/{namespace}/{extension}/{targetPlatform}/...` | Target-platform metadata/file variants |
| `POST api/-/publish?token=...` | Hosted VSIX publication |
| `POST api/{namespace}/{extension}/delete` | Not implemented in v1; deletion is available through `DELETE` on a concrete package/file path and the admin APIs |
| `GET`/`POST vscode/gallery/extensionquery` | Gallery query shape; URL parameters and the POST body criteria/page fields are interpreted |
| `GET vscode/gallery/{namespace}/{extension}/latest` | Latest metadata |
| `GET vscode/gallery/publishers/{namespace}/vsextensions/{extension}/{version}/vspackage` | VSIX download |
| `GET vscode/asset/{namespace}/{extension}/{version}/{assetType}/**` | Maps marketplace asset types to package members |
| `GET vscode/unpkg/{namespace}/{extension}/{version}/**` | Member extraction or directory listing |
| `GET`/`POST vscode/item?itemName=...` | Item projection for the requested `publisher.extension` |

## Path parsing

`protocol-openvsx` contains a dependency-free parser (`OpenVsxPathParser`) and identity rules
(`OpenVsxCoordinates`, `OpenVsxVersions`). It:

- decodes each segment once with `UriPathDecoder`;
- rejects encoded separators, traversal, empty segments, and control characters;
- recognizes the fixed target-platform set from the Open VSX specification;
- returns `UNKNOWN` for unrecognized routes so the server can produce a 404 without guessing;
- compares versions with SemVer-like numeric/pre-release ordering and ignores build metadata.

The server never re-derives identity from a filename alone for hosted publication; the VSIX
`extension/package.json` is authoritative and the submitted filename is only a display/download
name.

## Durable model

No new tables are required. The existing component/asset/blob model is reused:

| Concept | Storage |
| --- | --- |
| Extension | `component.format = openvsx`, `namespace = publisher`, `name = extension`, `kind = openvsx-extension` |
| Version | `component.version = <version>` (or `<version>@<targetPlatform>` for non-universal variants) |
| VSIX | asset at `/<namespace>/<extension>/<version>/<file>.vsix`; non-universal variants include the target platform in `<file>` |
| Package metadata | component attributes: `openvsxPackageJson`, `openvsxVersion`, `openvsxTargetPlatform`, `openvsxFileName`, `openvsxSha1`, `openvsxSha256`, `openvsxSize`, `openvsxTimestamp` |
| Proxy metadata cache | hidden `.openvsx/meta/<namespace>/<extension>[/<version>]` assets |
| Proxy member cache | hidden `.openvsx/files/<namespace>/<extension>/<version>/<member>` assets |

The database remains the source of truth. The only node-local state is the normal asset metadata
cache; losing it is harmless.

## Hosted publication

1. Authenticate the `OpenVsxToken` (query `token`, Bearer, or `X-API-Key`).
2. Enforce repository online/write policy.
3. Buffer the upload to a temporary file while computing MD5/SHA-1/SHA-256/SHA-512 and size.
4. Open the VSIX as a ZIP and read exactly one canonical `extension/package.json` (bounded).
5. Validate `publisher`, `name`, `version`, optional target platform, and the configured maximum
   extension size.
6. Reject an existing component coordinate.
7. Store the VSIX through the shared blob/asset/component transaction and index a Browse path.
8. Return the created extension metadata plus an absolute `Location` header.

The VSIX coordinate is immutable. Re-publishing the same version/target platform is rejected.

## Proxy behavior

- `api/<namespace>/<extension>` is fetched through `RawProxyService` with the metadata TTL.
- The upstream latest metadata is projected into components. Other versions listed in
  `allVersions` are projected lazily with the latest package fields so metadata paging works
  without a request per version.
- A concrete version request fetches the version metadata once and updates the component.
- VSIX downloads use `files.download`, are cached through the shared proxy cache, and are bound to
  the component so Browse/Search and scanning see them.
- Metadata responses rewrite `files`, namespace/reviews URLs, and `allVersions` to the proxy
  repository.

## Group behavior

- Members are filtered to online Open VSX repositories and read in order.
- Version identity is `(namespace, extension, version, targetPlatform)`; first member wins.
- Metadata is regenerated against the group URL.
- File requests try each member in order and support nested groups with cycle detection.
- No group-specific cache is used; correctness follows from reading the durable member projection.

## Multi-replica semantics

- Hosted publication uses the existing blob/asset/component transaction; the unique coordinate hash
  arbitrates concurrent publishers.
- Proxy metadata and content are durable assets in the shared blob store and database; every
  replica sees the same cache entries.
- Group resolution is stateless and deterministic for a given membership/version watermark.
- There is no in-process cache in v1, so no cross-replica invalidation protocol is required.

## Integration points added

- `RepositoryFormat.OPENVSX` and recipes.
- `protocol-openvsx` module (parser, identity, version ordering, capability).
- `server.openvsx` (`OpenVsxHostedService`, `OpenVsxProxyService`, `OpenVsxGroupService`,
  `OpenVsxAssetSupport`, `OpenVsxVsixInspector`, `OpenVsxMetadata`).
- `server.routing.openvsx.OpenVsxProtocolHandler`.
- Security filter: token authentication, publish ADD permission, Bearer challenge.
- Upload definition and Components API upload.
- Admin/Browse icon and label maps, welcome card, search option, remote default.
- Metrics operation taxonomy and scan classification.

## Remaining work

1. Validate against a real `ovsx` client and a VS Code/Code OSS instance in compat-test.
2. Implement `POST api/{namespace}/{extension}/delete` and version deactivation semantics.
3. Add target-platform-specific identity tests and a safer component version-key model.
4. Extend the gallery response metadata for marketplace clients that require additional fields.
5. Add publisher/namespace ownership and trusted-publishing policy if the product needs it.
6. Add protocol-aware Nexus migration only if a source holds Open VSX-shaped content; Nexus has no
   native Open VSX format.
7. Consider a rebuildable group/version cache through the `cache` module once profiling shows it is
   needed.
