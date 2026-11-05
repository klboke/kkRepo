# OHPM Proxy

The `ohpm-proxy` recipe serves OHPM package metadata and verified HAR/HSP files from an upstream registry. It is read-only; publish, stream publish, tag changes and unpublish return `405` after repository authorization.

## Configuration

Create the repository through the existing repository management API. For the public OpenHarmony registry, the following configuration includes the CDN observed during the OHPM 6.0.1 acceptance test:

```json
{
  "name": "ohpm-public",
  "recipe": "ohpm-proxy",
  "online": true,
  "blobStoreName": "default",
  "proxy": {
    "remoteUrl": "https://ohpm.openharmony.cn/ohpm",
    "contentMaxAgeMinutes": 60,
    "metadataMaxAgeMinutes": 5,
    "autoBlock": true,
    "allowedRedirectHosts": ["contentcenter-drcn.dbankcdn.cn"]
  }
}
```

The public registry redirects file GET requests to its CDN. Configure the actual upstream download hosts in `allowedRedirectHosts`; the shared outbound policy continues to enforce address and redirect restrictions. Cross-origin requests do not receive upstream credentials.

For a private OHPM upstream, set `proxy.remoteBearerToken` using the existing encrypted remote-credential configuration. For OHPM, this field produces the protocol's raw `Authorization` value without a Bearer prefix. Client credentials for accessing kkRepo remain separate and use the existing repository READ permission.

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-public/
ohpm config set //repo.example.com/repository/ohpm-public/:_auth <token>
ohpm install @ohos/axios@2.2.15
```

## Cache and failure behavior

- Metadata uses a blobless package-root asset with durable attributes. File bytes use the configured blob store; HAR and HSP assets share the package-version component. Lookups read the shared database, with no node-local package state.
- Metadata and content have separate TTLs and durable invalidation tokens. Zero revalidates on every request; negative TTL values follow the existing cache convention of no time-based expiry. ETag and Last-Modified validators are sent only for a compatible cached source.
- Source identities include the upstream URL, authentication and redirect policy. File identities also include the complete remote download URL and expected SHA-512. A configuration or integrity change cannot silently reuse bytes from another source.
- `404`, `410` and `451` retain their status and use the shared negative-cache abstraction. The standard `kkrepo.proxy.negative-cache.enabled` and `kkrepo.proxy.negative-cache.ttl-minutes` settings apply. A durable removal marker prevents old bytes or metadata from reappearing as stale data after a negative entry expires.
- Transport failures and upstream `5xx` can serve an existing compatible snapshot. Otherwise they return `502`. Auto-block uses the existing shared proxy state and bounded backoff. Upstream authentication failures, invalid metadata, prohibited URLs and checksum failures return `502` without serving stale data for that request.
- Metadata refreshes compare the stored snapshot under a row lock before replacing it. File publication also rechecks the current metadata, source and cache token. A slower request cannot overwrite a newer cached response or removal marker.

Metadata is limited to 16 MiB and bounded JSON depth, strings and token count. Downloads use fixed-size buffers and request-scoped temporary files, with the hosted per-file limit `kkrepo.ohpm.max-file-bytes` (default 1 GiB). SHA-512 validation completes before a file becomes visible in the durable cache. Staging files are removed on success and failure.

Both `dist.tarball` and `dist.resolved_hsp` are rewritten to kkRepo URLs. Remote filenames need not match the local scoped-package path. Public metadata does not expose signed upstream download queries; stored component metadata uses local paths.

## Validation

Focused tests:

```bash
mvn -pl server -am \
  -Dtest='OhpmPackageMetadataTest,OhpmProxyServiceTest,OhpmProtocolHandlerTest,HttpRemoteFetcherTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The runtime suite starts MySQL and PostgreSQL separately, with two kkRepo instances sharing each database and file test storage. A controlled authenticated HTTP upstream verifies metadata reuse, conditional refresh, concurrent download, HAR/HSP component bindings, invalidation, corruption rejection and read-only behavior. Enable real OHPM installation against that controlled upstream with:

```bash
mvn -pl server -am -Dtest=OhpmProxyRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

Add `-Dkkrepo.ohpm.publicProxy=true` to install `@ohos/axios@2.2.15` through the public upstream and assert that kkRepo persisted the downloaded HAR. This check requires public network access and a real client; it is not part of ordinary offline CI. Use `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm` when the client is not on PATH.

Historical clients, HSP execution on devices and cloud OSS/S3 fault injection remain outside the verified runtime coverage. [Group repositories](ohpm-group.md) are available; independent import is a later milestone.
