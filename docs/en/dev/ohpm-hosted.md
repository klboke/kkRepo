# OHPM Hosted HAR/HSP

The `ohpm-hosted` recipe supports normal and streaming HAR/HSP publication, metadata, downloads, dist-tags and unpublish. Protocol behavior follows Huawei's official documentation and OHPM 6.0.1; see the [protocol baseline](ohpm-protocol-baseline.md).

## Usage

Create a hosted repository with `recipe=ohpm-hosted` and a blob store through the repository management API. Issue a GenericToken using the existing token management APIs and grant the required repository READ, ADD, EDIT and DELETE permissions. Initial publication requires ADD; adding a version to an existing package also requires EDIT to protect shared metadata and tags. Ping is public. Anonymous reads use the existing anonymous user's permissions.

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-hosted/
ohpm config set publish_registry https://repo.example.com/repository/ohpm-hosted/
ohpm config set //repo.example.com/repository/ohpm-hosted/:_auth <token>
ohpm publish ./example.har
ohpm install
ohpm dist-tags add @scope/example@1.0.0 beta
ohpm unpublish @scope/example@1.0.0
```

Published versions cannot be overwritten. Unpublish removes tags targeting deleted versions; set `latest` explicitly when selecting a replacement default version. A version may be published again after deletion.

Base64 attachments stream to request-scoped temporary files. Identity, version, attachment count, length and SHA-512 are validated before persistence. The default per-file limit is 1 GiB, configurable with `kkrepo.ohpm.max-file-bytes`, subject to global upload limits. Metadata has separate aggregate character, token and nesting limits. Temporary files are cleaned after success or failure.

Streaming publication accepts exactly two multipart fields, `metadata` and `pkg_stream`. HAR streams contain the raw HAR; HSP streams contain a gzip/tar archive with an interface HAR and an HSP. Both attachments are verified before the shared publication transaction. HSP downloads use `dist.tarball` and `dist.resolved_hsp` with separate integrity values. The submitted filename and local client paths never determine staging paths.

Multipart metadata is limited to 8 MiB before the JSON limits above apply. HSP archives allow at most 32 headers, 16 KiB per extended header and two regular files, each within the configured per-file limit. Compressed and expanded totals are each limited to twice that limit plus 1 MiB. Safe directory and PAX long-path entries are supported; traversal, links, sparse files, duplicate/extra files, corrupt checksums and trailing archives are rejected. Servlet multipart storage uses a zero-byte memory threshold; service staging uses bounded buffers and removes temporary files on success or failure. Global multipart limits also apply.

Only exhausted transient database retries during publication return `598`; unexpected storage failures return `500`, invalid input `400`, and size-limit violations `413`. The tested OHPM 6.0.1 normal uploader does not automatically retry `598`. An explicit retry can succeed after rollback; a version already committed remains non-overwritable. Client-contract tests retain stream `404` fallback coverage against a recording server.

[Proxy repositories](ohpm-proxy.md) are also available. Login, group repositories, management file upload and independent import are not available yet. Security scanning currently classifies OHPM as not applicable. Automatic cleanup is disabled until it can maintain package metadata through the protocol service. Protocol mutations are audited and repository request metrics include OHPM operations.

## Consistency and storage

- A blobless package-root asset stores materialized metadata in its attributes. Each version has one component and a HAR asset, plus an HSP asset for HSP publication; artifact bytes reside in the configured blob store.
- Publication, tags and unpublish serialize through a database row lock on the package root. A unique path handles initial creation races. Locking reads query the current row by path, including rows absent from an earlier MySQL repeatable-read snapshot.
- Components, assets, blob references and package metadata commit together. Whole-package deletion retains an empty root as the stable locking identity. Reading that empty package returns `404`; subsequent publication reuses its identity.
- Metadata and download lookups read the shared database directly. Download URLs use the current request's external address and the existing trusted forwarded-header policy.
- If the outer transaction rolls back after upload, the generic writer checks durable references before deleting newly uploaded content. Reused blobs are preserved. Transaction retries reopen the validated staging files.

Database locking contracts and two-application runtime tests cover both MySQL and PostgreSQL. Runtime tests share file storage; production remains OSS/S3 based. These tests do not establish cloud-storage fault-injection coverage.

## Validation

Focused tests:

```bash
mvn -pl server -am \
  -Dtest='OhpmPublishParserTest,OhpmStreamParserTest,OhpmProtocolHandlerTest,OhpmAccessTest,RawAssetWriterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Runtime tests require Docker. Enable real-client checks against the running kkrepo instances with:

```bash
mvn -pl server -am -Dtest=OhpmHostedRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

Use `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm` if the client is not on PATH. Explicit client checks fail if the client is missing or its version differs. Test registries, tokens, client configuration and caches are isolated.

HTTP tests cover concurrent versions across replicas, duplicate-version races, tag/unpublish races, downloads and conditional requests, package recreation, HAR/HSP type changes, and rollback with new versus shared blobs, including failure after the second HSP attachment is written. Real-client checks cover normal and streaming HAR/HSP publication, byte-identical downloads of both HSP files, HAR installation, tag operations and version/whole-package unpublish. Audit assertions require successful stream requests for large HAR and HSP fixtures. Parser tests cover interrupted input, staging failure, archive bounds and cleanup, including a generated 64 MiB HAR read with bounded buffers. Historical clients and HSP device execution remain unverified.
