# Helm Repository Guide

kkRepo supports classic Helm chart repository `hosted`, `proxy`, and `group` recipes. The format
serves packaged charts, provenance files, and `index.yaml` through `/repository/<repo>/...`.
Docker / OCI repositories remain the separate format for OCI-based Helm charts.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private charts | `helm-hosted` | Blob store, online state, write policy, strict validation |
| Upstream chart cache | `helm-proxy` | Remote chart repository root and cache TTLs |
| Unified read endpoint | `helm-group` | Ordered hosted, proxy, or nested group members |

Put private hosted repositories before public proxies in a group. Use separate repository names
when different teams or retention policies require isolation.

## Configure And Read Charts

Add the group read endpoint to the Helm client:

```bash
helm repo add acme https://nexus.example.com/repository/helm-group/
helm repo update
helm search repo acme
helm pull acme/demo --version 1.0.0
```

For a private repository, pass `--username` and `--password` or use the credential mechanism chosen
for the deployment. Production clients should trust the deployment CA instead of disabling TLS
verification.

## Publish A Classic Chart

Package and upload a chart to hosted:

```bash
helm lint ./charts/demo
helm package ./charts/demo
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0.tgz \
  https://nexus.example.com/repository/helm-hosted/demo-1.0.0.tgz
```

The Admin UI and Nexus-compatible component upload path are also supported. Helm's built-in
`helm push` targets OCI registries; for a classic chart repository, use kkRepo's upload endpoints or
a plugin that speaks the classic repository upload convention.

## Repository Behavior

- Hosted publication parses `Chart.yaml`, stores the chart archive, and rebuilds `index.yaml` from
  committed metadata.
- Proxy repositories fetch the upstream index and rewrite chart and derived `.prov` URLs so reads
  remain on the kkRepo host.
- Group repositories merge eligible members in configured order. A repeated chart name and version
  keeps the first member's entry; unique releases from later members remain available. Chart and
  provenance reads use the same first-success member order.
- Groups may contain groups. Cycle checks protect runtime reads, while repository validation rejects
  invalid cross-format members and cyclic definitions.
- The merged index is a normal blob-backed asset. Its database cache watermark is invalidated after
  committed member index changes and repository configuration changes, including through nested
  groups, so every replica observes the same freshness boundary. Node-local metadata caches remain
  optional and rebuildable.
- `index.yaml`, chart archives, checksums, validators, Browse, and Search advance from committed
  repository state.
- Group index aggregation is bounded to 64 MiB. An unavailable, offline, or invalid member index is
  isolated so healthy members can still serve the repository.

## Operations And Troubleshooting

Only hosted accepts publication. If `helm repo update` returns stale data, check the client cache,
proxy metadata TTL, group member order, and member online state. If the index lists a version but
download fails, confirm that the chart URL was rewritten to a repository-local path and that the
caller has read permission for the group path.

## Related Documentation

- [Helm client recipe](../client-recipes.md#helm)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Helm Chart Repository Guide](https://helm.sh/docs/topics/chart_repository/)
- [Sonatype Helm repository configuration](https://help.sonatype.com/en/create-a-helm-repository.html)
