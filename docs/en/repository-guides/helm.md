# Helm Repository Guide

kkRepo supports classic Helm chart repository `hosted` and `proxy` recipes. The format serves
packaged charts and `index.yaml` through `/repository/<repo>/...`. Helm group repositories are not
currently exposed; Docker / OCI repositories are a separate format for OCI-based Helm charts.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private charts | `helm-hosted` | Blob store, online state, write policy, strict validation |
| Upstream chart cache | `helm-proxy` | Remote chart repository root and cache TTLs |

Use separate repository names when different teams or retention policies require isolation.

## Configure And Read Charts

Add a proxy or hosted repository to the Helm client:

```bash
helm repo add acme https://nexus.example.com/repository/helm-proxy/
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
- Proxy repositories fetch and rewrite the upstream index so chart URLs remain on the kkRepo host.
- `index.yaml`, chart archives, checksums, validators, Browse, and Search advance from committed
  repository state.
- There is no Helm group recipe today. Configure one supported endpoint per client or use a proxy
  whose upstream already provides the required combined index.

## Operations And Troubleshooting

Only hosted accepts publication. If `helm repo update` returns stale data, check both the client
cache and proxy metadata TTL. If the index lists a version but download fails, confirm that the chart
URL was rewritten to kkRepo and that the caller has read permission for the archive path.

## Related Documentation

- [Helm client recipe](../client-recipes.md#helm)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Helm Chart Repository Guide](https://helm.sh/docs/topics/chart_repository/)
