# VS Code / Open VSX Repository Guide

kkRepo implements the Eclipse Open VSX registry HTTP API and the VS Code gallery compatibility
surface for hosted, proxy, and group repositories. Extensions are published as `.vsix` archives at
`/repository/<repo>/api/-/publish`, and clients can install them either through the Open VSX API or
through an editor configured with a custom gallery endpoint.

## Repository types

| Use case | Recipe | Notes |
| --- | --- | --- |
| Private extensions | `openvsx-hosted` | Publish with a token; immutable extension/version/target-platform coordinates |
| Mirror `open-vsx.org` or another registry | `openvsx-proxy` | Metadata and VSIX files are cached with the repository metadata/content TTLs |
| One URL for several registries | `openvsx-group` | Ordered members; the first online member wins each extension version |

Create repositories through **Administration → Repositories** or the `/internal/repositories` API.
The proxy default remote is `https://open-vsx.org/`; change it for a private registry or an
Open VSX mirror.

## Client configuration

### Install from the registry API

The canonical metadata URL is:

```text
https://nexus.example.com/repository/openvsx-hosted/api/<namespace>/<extension>
```

The response contains `files.download`, which points back to the same repository:

```text
https://nexus.example.com/repository/openvsx-hosted/api/<namespace>/<extension>/<version>/file/<file>.vsix
```

### VS Code custom gallery

Editors that support a custom VS Code gallery can point at the repository URL:

```json
{
  "extensionsGallery": {
    "serviceUrl": "https://nexus.example.com/repository/openvsx-group/vscode/gallery",
    "itemUrl": "https://nexus.example.com/repository/openvsx-group/vscode/item"
  }
}
```

kkRepo serves `vscode/gallery/extensionquery`, `vscode/gallery/publishers/.../vsextensions/.../vspackage`,
`vscode/asset/...`, and `vscode/unpkg/...` so the standard install/download flow resolves local
VSIX files.

### Publish with `ovsx`

Create an API key with domain `OpenVsxToken` (Administration → Security → API Keys) and publish:

```bash
ovsx publish \
  --registryUrl https://nexus.example.com/repository/openvsx-hosted \
  --pat "$OPENVSX_TOKEN" \
  acme-demo-1.0.0.vsix
```

The publish endpoint is:

```text
POST /repository/openvsx-hosted/api/-/publish?token=<OpenVsxToken>
Content-Type: application/octet-stream
<vsix bytes>
```

`Authorization: Bearer <OpenVsxToken>` and `X-API-Key` are also accepted. The response is the
created extension metadata and a `Location` header pointing at the version metadata.

### Publish through the Components API

The normal admin UI upload and `/service/rest/v1/components` endpoint accept a single `asset`
field containing a `.vsix` file.

## Hosted behavior

- The VSIX must contain `extension/package.json` with `publisher`, `name`, and `version`.
- The filename identity is validated against the archive metadata; traversal entries, unsafe ZIP
  paths, oversized `package.json` files, and archives larger than the configured maximum are
  rejected.
- Published extension/version/target-platform coordinates are immutable.
- Metadata is generated from durable component rows. Rebuilding a browse/search index never changes
  the served extension identity.
- `api/version` advertises the configured maximum extension size.

## Proxy behavior

- Upstream `api/<namespace>/<extension>` metadata is cached under a hidden `.openvsx/meta/...` path
  with the repository metadata TTL.
- VSIX files are fetched from the upstream `files.download` URL, verified by size, and cached under
  `/<namespace>/<extension>/<version>/<file>.vsix`.
- Extension metadata served by the proxy is rewritten so `files`, `namespaceUrl`, `reviewsUrl`, and
  `allVersions` point at the proxy repository.
- If the upstream is unavailable, a fresh cached copy is served; otherwise the request fails with a
  protocol-appropriate error.

## Group behavior

- Members are resolved in order; the first online member that contains an extension version wins.
- Metadata is merged per extension/version/target-platform; group URLs replace member URLs.
- VSIX and file requests are routed to the first member that can serve the path.
- Nested Open VSX groups are supported with cycle detection.

## Browse, search, cleanup, and scanning

- The Browse UI lists `/<namespace>/<extension>/<version>/<file>.vsix` assets.
- Component search exposes extension name, namespace, version, VSIX checksum, and size.
- VSIX files are treated as package archives by artifact scanning.
- Cleanup works on hosted components; proxy cache entries follow the normal proxy-content cleanup
  rules.

## Known limits

- VSIX signature verification and publisher-key trust are not part of this implementation; the
  archive identity is validated, but the VSIX is not cryptographically verified.
- Target-platform variants are represented as separate component versions. A metadata request for a
  platform falls back to the universal VSIX when no exact match exists.
- The VS Code gallery response is a compatibility projection over kkrepo metadata. Marketplace
  review, rating, Q&A, and publisher social APIs are not implemented.
- Open VSX trusted publishing, namespace ownership verification, and review workflows are outside
  the current scope.
- Proxy/group search and namespace listings are built from extensions that have already been
  fetched or cached. A cold proxy does not crawl the entire upstream registry; request a concrete
  extension to populate it.
- Nexus does not expose an Open VSX repository format, so repository-definition migration from
  Nexus does not apply.

## Related documentation

- [Open VSX registry API](https://open-vsx.org/v3/api-docs)
- [Open VSX project](https://github.com/eclipse-openvsx/openvsx)
- [Design notes](../dev/openvsx-repository-design.md)
