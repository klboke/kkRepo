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
