# OHPM Hosted HAR

The `ohpm-hosted` recipe supports normal HAR publication, metadata, downloads, dist-tags and unpublish. Protocol behavior follows Huawei's official documentation and OHPM 6.0.1; see the [protocol baseline](ohpm-protocol-baseline.md).

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

The stream endpoint currently returns `404`, allowing OHPM 6.0.1 to fall back to normal uploads for large HAR files. HSP and stream support follow in the next milestone. Login, proxy/group, management file upload and independent import are not available yet. Security scanning currently classifies OHPM as not applicable. Automatic cleanup is disabled until it can maintain package metadata through the protocol service. Protocol mutations are audited and repository request metrics include OHPM operations.

## Consistency and storage

- A blobless package-root asset stores materialized metadata in its attributes. Each version has a component and HAR asset; artifact bytes reside in the configured blob store.
- Publication, tags and unpublish serialize through a database row lock on the package root. A unique path handles initial creation races. Locking reads query the current row by path, including rows absent from an earlier MySQL repeatable-read snapshot.
- Components, assets, blob references and package metadata commit together. Whole-package deletion retains an empty root as the stable locking identity. Reading that empty package returns `404`; subsequent publication reuses its identity.
- Metadata and download lookups read the shared database directly. Download URLs use the current request's external address and the existing trusted forwarded-header policy.
- If the outer transaction rolls back after upload, the generic writer checks durable references before deleting newly uploaded content. Reused blobs are preserved. Transaction retries reopen the validated staging files.

Database locking contracts and two-application runtime tests cover both MySQL and PostgreSQL. Runtime tests share file storage; production remains OSS/S3 based. These tests do not establish cloud-storage fault-injection coverage.

## Validation

Focused tests:

```bash
mvn -pl server -am \
  -Dtest='OhpmPublishParserTest,OhpmAccessTest,RawAssetWriterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Runtime tests require Docker. Enable real-client checks against the running kkrepo instances with:

```bash
mvn -pl server -am -Dtest=OhpmHostedRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

Use `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm` if the client is not on PATH. Explicit client checks fail if the client is missing or its version differs. Test registries, tokens, client configuration and caches are isolated.

HTTP tests cover concurrent versions across replicas, duplicate-version races, tag/unpublish races, downloads and conditional requests, package recreation, and rollback with new versus shared blobs. Real-client checks cover small HAR publication, installation, stream fallback for a HAR larger than 5 MiB, tag operations and version/whole-package unpublish. Historical clients and HSP device execution remain unverified.
