# OHPM management and browsing

Admin uses the standard repository editor for `ohpm-hosted`, `ohpm-proxy` and `ohpm-group`. Hosted versions are immutable. Proxy configuration includes the upstream URL, raw upstream Authorization value, cache lifetimes and redirect allowlist. Group members must be OHPM hosted/proxy repositories; their order determines the winning version and tags. See the [hosted](ohpm-hosted.md), [proxy](ohpm-proxy.md) and [group](ohpm-group.md) guides.

## Upload

Browse's Upload page accepts a HAR archive with an optional paired HSP, or one complete HSP tgz/tar.gz bundle, plus a distribution tag (default `latest`). Package coordinates are read from `package/oh-package.json5` inside the HAR. An interface HAR with `packageType: "InterfaceHar"` requires an HSP; an ordinary HAR cannot include one. Bundles use the same bounded two-file extractor as official stream publication and cannot be combined with an additional HSP field.

The same operation is available at:

```bash
curl --fail-with-body \
  -H "Authorization: Bearer ${KKREPO_TOKEN}" \
  -F 'ohpm.asset=@example.har' \
  -F 'ohpm.tag=beta' \
  'https://repo.example.com/service/rest/v1/components?repository=ohpm-hosted'
```

Add `-F 'ohpm.hsp=@example.hsp'` for an interface HAR, or use `-F 'ohpm.asset=@example.tgz'` alone for a complete HSP bundle. Management APIs use normal kkRepo authentication (Bearer token or the authenticated UI session); the OHPM protocol itself uses raw Authorization.

The server parses JSON5, including comments, single quotes, trailing commas, hexadecimal numbers and continued strings. Duplicate keys, trailing documents and non-finite numbers are rejected. The manifest is limited to 2 MiB, 64 nested values and 100,000 values. Archives are bounded by the configured per-file upload limit, at most four times that limit plus 1 MiB of expanded HAR data, 100,000 tar headers and 16 KiB extended headers. Unsafe paths, links, sparse entries, duplicate manifests, malformed archives and trailing non-padding data are rejected.

Archives are staged to temporary files, SHA-512 is computed from the original bytes, and publication uses the same package transaction as the OHPM API. Both HSP attachments commit or roll back together. Staging files are reclaimed on failure and success. Generated metadata sets `_ohpmVersion` to the tested protocol baseline, `6.0.1`, so that this client accepts the custom registry; this field does not identify an actual uploading CLI.

Authentication precedes archive processing. Once coordinates are known, ADD is checked against the actual package path inside the publication transaction; an existing package additionally requires EDIT. Content selectors can use `format == 'ohpm'` and canonical package paths. Denied uploads leave no package, component or blob references. Management publication emits an audit record with repository and package identity.

## Browse, search and delete

Browse shows stored package/version/file paths, checksums, source repository, distribution tags and manifest attributes. Search links to the version directory. Proxy and group browsing enumerate persisted content, not every package available upstream. Repository lists and search use the existing catalog refresh/broadcast behavior; protocol writes and reads use shared durable state directly.

Group download links include the source fingerprint required by the protocol. Browse derives this from persisted member metadata without contacting upstreams. If metadata, the winning member or its files change, the protocol rejects a stale link with 409 rather than returning a different file. Refresh package metadata through the group to obtain the current selection.

Administrators can delete a hosted package, version or archive through Browse or `DELETE /internal/browse/<repository>?path=<browse-path>`. Deleting either HSP attachment unpublishes the entire version, removes both files and clears tags referring to it. Deleting a package unpublishes all versions; arbitrary scope/directory deletion is rejected. The confirmation explains this scope. Group administration can select a hosted source member. Proxy content cannot be unpublished locally; use proxy cache invalidation instead.

This operation requires the existing administrative `nexus:*` grant. Repository DELETE grants remain sufficient for protocol unpublish. The generic Nexus asset-ID DELETE API remains unsupported for OHPM and returns 405; it cannot bypass the OHPM transaction.

## Shared capabilities and boundaries

- Routing rules evaluate decoded canonical OHPM paths, including encoded scope separators, before proxy reads and group member selection.
- Downloads use the shared download policy and usage tracking. OHPM remains `NOT_APPLICABLE` for security scanning; this does not claim a vulnerability scan of HAR/HSP contents.
- Automatic cleanup and generic directory deletion remain disabled for OHPM. Explicit protocol-aware unpublish is supported.
- [Independent resumable import](ohpm-import.md) supports directory and registry sources; OHPM is not a Nexus migration format. [Optional signed Login](ohpm-login.md) is disabled by default and is managed under **Security → Keys & Tokens → OHPM Public Keys**; registration and rotation use dialogs.
- External protocol links follow the shared trusted forwarded-header policy; Browse links are repository-relative.

## Verification

```bash
mvn -pl server -am \
  -Dtest='Ohpm*Test,ComponentUploadServiceTest,ComponentUploadErrorAdviceTest,ManagementAuditFilterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
node --test browse-ui/src/test/js/browse-ohpm-management.test.js
```

Runtime checks use two instances against each of MySQL and PostgreSQL: management HAR/HSP upload, protocol metadata/downloads, Browse URLs and attributes, search, group creation/editing, OHPM 6.0.1 installation, version/package deletion, selector-restricted publication, cross-instance permission updates and audit records. Archive and UI tests cover malformed input, limits, staging cleanup, paired-file submission and deletion confirmation.
