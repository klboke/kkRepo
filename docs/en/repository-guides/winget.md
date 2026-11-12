# WinGet Repository Guide

kkRepo implements the WinGet REST source API (`/api/...`) with `hosted`, `proxy`, and `group`
repositories. WinGet clients can search packages, resolve exact package versions, and read the
combined package manifest over HTTP.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private manifests | `winget-hosted` | Blob store is not used; manifests are metadata rows |
| Upstream mirror | `winget-proxy` | Remote URL of another WinGet REST source |
| Unified source | `winget-group` | Hosted first, then proxy members |

A proxy remote URL is the base URL of the upstream REST source. WinGet appends `/information`,
`/manifestSearch`, and `/packageManifests` to the source argument; kkRepo accepts those paths at the
repository root and also under an `/api/` prefix for compatibility with reference REST sources.

## Configure The Client

Add the group as a REST source:

```bash
winget source add \
  --name kkrepo \
  --arg https://nexus.example.com/repository/winget-group \
  --type Microsoft.Rest
winget source list
winget search --source kkrepo contoso
winget install --source kkrepo --id Contoso.Tool --version 1.2.3
```

The source argument is the repository URL only. WinGet appends `/information`, `/manifestSearch`,
and `/packageManifests`; kkRepo accepts both those paths and the `/api`-prefixed form used by the
reference REST source.

For a private source, pass a kkRepo API token through the WinGet custom header:

```bash
winget source add --name kkrepo-private \
  --arg https://nexus.example.com/repository/winget-hosted \
  --type Microsoft.Rest \
  --header "Bearer $KKREPO_TOKEN"
```

kkRepo accepts the `Windows-Package-Manager` header value as an API token (with or without the
`Bearer ` prefix), in addition to the normal `Authorization` header.

## Publish A Manifest

Hosted repositories accept the combined manifest used by Winget:

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $KKREPO_TOKEN" \
  -H "Content-Type: application/json" \
  --data @manifest.json \
  https://nexus.example.com/repository/winget-hosted/api/packageManifests
```

`POST /api/packageManifests` adds versions and rejects an existing
`(PackageIdentifier, PackageVersion, Channel)` with `409`. `PUT /api/packageManifests/{id}`
replaces all versions of a package, and `DELETE /api/packageManifests/{id}` removes the package or
one `PackageVersion` selected by query parameter. Publication requires add/edit/delete permission
on the hosted repository.

## API Behavior

- `GET /api/information` advertises the supported contract versions and reports no required
  authentication.
- `POST /api/manifestSearch` is a read operation and requires read permission. It supports query,
  inclusions, filters, maximum results, and continuation tokens.
- `GET /api/packageManifests/{PackageIdentifier}` returns the combined manifest, optionally filtered
  by `PackageVersion` and `Channel`.
- Unknown package match fields, malformed JSON, mismatched identifiers, and duplicate versions are
  rejected with a JSON problem body.
- Proxy and group repositories are read-only. Proxy manifests are cached in the shared metadata
  store for the repository content TTL; group reads merge members in configured order.

## Operations And Troubleshooting

- WinGet stores manifests as metadata, not blobs. Cleanup policies, artifact scanning, and Browse
  asset views are intentionally not applicable to this format.
- Proxy search returns the upstream first page. `ContinuationToken` values are not forwarded to the
  upstream because the outbound fetcher does not expose arbitrary request headers; group search
  returns one merged page. Hosted search implements continuation tokens.
- A `404` from `packageManifests` means the identifier/version is not present. A `409` on publish
  means the exact version and channel already exists; publish a new version or use `PUT` to replace.
- A `415` means the request `Content-Type` is not `application/json`.
- If `winget search` returns no results, confirm the source URL reaches
  `.../repository/<name>/information` (or the `/api/information` form) and that the client trusts the
  HTTPS certificate.

## Related Documentation

- [WinGet REST source reference](https://github.com/microsoft/winget-cli-restsource)
- [WinGet manifest schema](https://github.com/microsoft/winget-cli/tree/master/schemas/JSON/manifests)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
