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

The adapter runs without a Docker socket, as uid/gid `10001`, with a read-only root filesystem.
Its PVC contains only the rebuildable Grype vulnerability database; candidates, leases, SBOM
references, findings, policies, and waivers remain in the shared relational database. The default
NetworkPolicy accepts scanner API traffic only from kkRepo pods and permits scanner egress only to
kkRepo, DNS, and public HTTPS for vulnerability database updates.

For multiple adapter replicas, provide
`securityScanning.scannerDatabase.persistence.existingClaim` backed by `ReadWriteMany`, or disable
scanner database persistence so each pod uses an ephemeral cache.
