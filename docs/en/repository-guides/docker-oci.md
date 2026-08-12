# Docker / OCI Repository Guide

kkRepo supports Docker Registry HTTP API V2 and OCI Distribution `hosted`, `proxy`, and `group`
repositories. Docker clients use registry `/v2/...` routes rather than the normal
`/repository/<repo>/...` artifact URL.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private images/artifacts | `docker-hosted` | Blob store, write policy, connector/routing settings |
| Upstream pull-through cache | `docker-proxy` | Remote registry, credentials, cache TTLs |
| Unified pulls | `docker-group` | Hosted before proxy |

For Docker Hub, use the registry endpoint such as `https://registry-1.docker.io/`; kkRepo handles the
client-visible `library` namespace behavior for official images.

## Choose An Access Layout

Shared-entrypoint deployments put the kkRepo repository name in the first image path segment:

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

Repository-level connector ports can expose the standard shape:

```text
<host>:<repo-port>/<image>:<tag>
```

Choose one layout, configure TLS and reverse proxy forwarding for `/v2/`, and give users the exact
host:port. Do not put `/repository/<repo>/` in Docker image references.

## Login, Push, And Pull

Shared-entrypoint examples:

```bash
docker login nexus.example.com
docker pull nexus.example.com/docker-proxy/library/alpine:3.20
docker tag alpine:3.20 nexus.example.com/docker-hosted/team/alpine:3.20
docker push nexus.example.com/docker-hosted/team/alpine:3.20
docker pull nexus.example.com/docker-group/team/alpine:3.20
```

Push only to hosted. Proxy and group are read endpoints even when the caller is authenticated.

## Repository Behavior

- Hosted supports blob upload sessions, manifests, tags, cross-repository blob mounts, and OCI
  referrers.
- Blobs are content-addressed and may be shared safely while repository-level references remain the
  source of authorization and lifecycle state.
- Proxy caches upstream manifests and blobs and preserves digest verification.
- Group resolves manifests by member order and keeps subsequent blob reads bound to the selected
  source.
- Browse and Search expose manifest, tag, media type, platform, blob, and referrer metadata.

## Cleanup And Security

Delete and cleanup logic distinguishes tags, manifests, and shared blob references; do not delete
objects directly from blob storage. Run cleanup preview and allow reference accounting to determine
when a blob is unreferenced. Security scanning should target the committed manifest and layer set,
not an incomplete upload session.

## Troubleshooting

Start with `curl -I https://<host>/v2/` and inspect the authentication challenge. A `401` before login
is normal for a private registry; repeated `401` after login usually means the advertised realm or
service does not match the client-visible host. Push failures behind a reverse proxy commonly involve
request-body limits, timeouts, or incorrect forwarding of upload-session locations.

## Related Documentation

- [Docker / OCI client recipe](../client-recipes.md#docker--oci)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
