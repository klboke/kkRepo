# kkRepo Helm chart

This chart deploys the same kkRepo image with an external MySQL or PostgreSQL database. It defaults to two replicas, rolling updates, JDBC Spring Session, health probes, and Secret-based credentials. It intentionally does not install a database StatefulSet.

Create the required Secrets, then provide the database URL and type:

```bash
kubectl create secret generic kkrepo-database --from-literal=password='change-me'
kubectl create secret generic kkrepo-encryption \
  --from-literal=credential-secret='replace-with-at-least-32-random-characters' \
  --from-literal=api-key-payload-secret='replace-with-another-32-random-characters'

helm upgrade --install kkrepo deploy/helm/kkrepo \
  --set database.type=postgresql \
  --set database.url='jdbc:postgresql://postgresql.example:5432/kkrepo' \
  --set database.username=kkrepo
```

For production, supply S3/OSS credentials through `extraEnvFrom`. File blob storage is disabled by default; with multiple replicas it requires a strong-consistency `ReadWriteMany` PVC.

## Artifact security scanning

Scanning capability is disabled by default. To deploy the isolated Syft/Grype adapter and start
kkRepo's scan coordination workers, create a service credential and enable the chart option:

```bash
kubectl create secret generic kkrepo-scanner \
  --from-literal=service-credential="$(openssl rand -hex 32)"

helm upgrade --install kkrepo deploy/helm/kkrepo \
  --set database.type=postgresql \
  --set database.url='jdbc:postgresql://postgresql.example:5432/kkrepo' \
  --set database.username=kkrepo \
  --set securityScanning.enabled=true
```

This chart value is a deployment capability gate; it does not activate scanning for any
repository. After the deployment is ready, a repository administrator selects repositories and
their audit/enforcement policies in **Admin > Security > Artifact Scanning**. When the chart value
is disabled, that page remains visible but all scanning controls are disabled.

See the [Artifact Scanning Guide](../../../docs/en/artifact-scanning-guide.md) for repository
activation, policy, waiver, monitoring, and troubleshooting instructions.

The adapter runs without a Docker socket, as uid/gid `10001`, with a read-only root filesystem.
Its PVC contains only the rebuildable Grype vulnerability database; candidates, leases, SBOM
references, findings, policies, and waivers remain in the shared relational database. The default
NetworkPolicy accepts scanner API traffic only from kkRepo pods and permits scanner egress only to
kkRepo, DNS, and public HTTPS for vulnerability database updates.

The scanner workload is a StatefulSet so every replica has a stable network identity. kkRepo uses
the run hash to select a preferred ordinal, then fails retryable catalog, match, and OCI requests
over to the remaining ordinals. Before fallback, kkRepo makes a best-effort targeted cancellation
on the failed ordinal; administrative and worker cancellation is broadcast across all configured
ordinals in parallel under one five-second overall deadline because a timed-out primary request
can still be winding down. Durable task ownership and result finalization remain in the shared
kkRepo database, not in the StatefulSet. Capability and readiness observations also fail over
across ordinals under one 15-second end-to-end deadline, so a rollout or ordinal failure does not
hide healthy replicas or multiply outage delays by the replica count. For multiple adapter
replicas, provide
`securityScanning.scannerDatabase.persistence.existingClaim` backed by `ReadWriteMany`, or disable
scanner database persistence so each pod uses an ephemeral cache. Shared-database replicas use
cross-process read/update locks and a shared update marker; database update eligibility is checked
every minute so a busy scan only postpones the update instead of skipping it for the full update
interval. Each adapter also admits at most two active and four queued scans by default, returning a
retryable HTTP 429 with `Retry-After` when capacity is exhausted. Scratch admission is separately
weighted by each request's input, nested-archive, and output bounds: the default `7 GiB` shared
budget protects the `8 GiB` emptyDir from concurrent overcommit. Keep
`securityScanning.limits.maxScratchBytes` below
`securityScanning.limits.scratchVolumeSize` and leave additional room in the Pod
ephemeral-storage limit when tuning either value.

`securityScanning.metricsCountLimit` bounds each periodic status gauge query (default `10000`).
Gauge values saturate at this limit so a large task or finding history cannot turn the 15-second
metrics refresh into an unbounded table count. Management overview queries are separately
aggregated once across all repositories visible to the current operator.
