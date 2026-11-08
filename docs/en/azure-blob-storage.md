# Azure Blob Storage blob stores

kkRepo stores artifact blobs in an Azure Storage account using the native Azure Blob Storage API. Azure Blob Storage does not expose an S3-compatible endpoint, so the `aws-s3` engine cannot be pointed at `*.blob.core.windows.net`; use the dedicated `azure` blob-store type described here.

The recommended credential modes are **managed identity** and **workload identity**. Both are secretless: kkRepo stores no key in the database, and every replica obtains and refreshes its own short-lived token from the platform.

## Terminology

| Generic blob model | Azure |
| --- | --- |
| `bucket` | container |
| `prefix` | virtual directory inside the container |
| `endpoint` | blob service URL, for example `https://myaccount.blob.core.windows.net` |

Leave `endpoint` empty to derive it from `accountName`; this is the right choice for a private endpoint reached through the standard `privatelink` DNS zone. Set it explicitly only when the URL cannot be derived from the account name: sovereign clouds such as `https://myaccount.blob.core.chinacloudapi.cn`, accounts using Azure DNS zone endpoints (`https://myaccount.z01.blob.storage.azure.net`), or Azurite. The token-based credential modes require an `https` endpoint, because the Azure SDK refuses to send a bearer token over plain HTTP; an `http` endpoint such as Azurite works only with `account-key` or `sas`.

## Credential modes

| `credentialType` | Secret stored by kkRepo | Use for |
| --- | --- | --- |
| `default` | none | Default. Resolves workload identity, then managed identity, then environment credentials |
| `managed-identity` | none | VMs, VM scale sets, App Service, and AKS node identities |
| `workload-identity` | none | AKS federated workload identity |
| `account-key` | storage account key | Environments without Entra ID |
| `sas` | SAS token | Delegated, time-limited access |

`default` suits most deployments: the same configuration works on AKS, on VMs, and on a developer machine. Choose `managed-identity` or `workload-identity` explicitly when you want kkRepo to fail fast rather than silently fall back to another credential in the chain.

Switching a store to a token-based mode clears any stored `accountKey` and `sasToken`, so no unused secret is left behind in the database; a secret sent together with a token-based mode is ignored and not stored. When a store does use an account key or SAS token, it is encrypted at rest with `kkrepo.security.encryption.credential-secret`, like S3/OSS keys. An unrecognised `credentialType` is rejected with `400 Bad Request` rather than falling back to `default`.

## Required Azure role

Assign **Storage Blob Data Contributor** on the container (or the storage account) to the identity. The admin console health check lists blobs; upload, download, and deletion use the data permissions.

```bash
az role assignment create \
  --role "Storage Blob Data Contributor" \
  --assignee-object-id "$PRINCIPAL_OBJECT_ID" \
  --assignee-principal-type ServicePrincipal \
  --scope "/subscriptions/$SUBSCRIPTION/resourceGroups/$GROUP/providers/Microsoft.Storage/storageAccounts/$ACCOUNT/blobServices/default/containers/$CONTAINER"
```

Container creation is an external deployment step; kkRepo uses the configured existing container.

## Workload identity on AKS

Enable OIDC issuer and workload identity on the cluster, then federate the managed identity with the kkRepo service account:

```bash
az identity federated-credential create \
  --name kkrepo \
  --identity-name "$IDENTITY_NAME" \
  --resource-group "$GROUP" \
  --issuer "$OIDC_ISSUER_URL" \
  --subject "system:serviceaccount:$NAMESPACE:kkrepo" \
  --audience api://AzureADTokenExchange
```

Label the pod and annotate the service account so the webhook projects the token:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kkrepo
  annotations:
    azure.workload.identity/client-id: "<managed-identity-client-id>"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kkrepo
spec:
  template:
    metadata:
      labels:
        azure.workload.identity/use: "true"
    spec:
      serviceAccountName: kkrepo
```

The webhook projects `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, and `AZURE_AUTHORITY_HOST`. With `credentialType` set to `default` or `workload-identity`, no further blob-store configuration is needed. If the webhook is not in use, select `workload-identity` and set `clientId`, `tenantId`, and `tokenFilePath` on the blob store; an incomplete configuration is rejected with a message naming the missing values. `tokenFilePath` applies only to `workload-identity`: the `default` chain reads the token file from `AZURE_FEDERATED_TOKEN_FILE` only, so the setting is not stored for that mode.

## Managed identity

For a system-assigned identity, set `credentialType` to `managed-identity` and leave `clientId` empty. For a user-assigned identity, set `clientId` to its client ID.

## Creating a blob store

`POST /internal/blob-stores` with administrator authentication:

```json
{
  "name": "azure-artifacts",
  "type": "azure",
  "accountName": "myaccount",
  "bucket": "artifacts",
  "prefix": "",
  "credentialType": "workload-identity"
}
```

Account-key stores supply the secret explicitly:

```json
{
  "name": "azure-artifacts",
  "type": "azure",
  "accountName": "myaccount",
  "bucket": "artifacts",
  "credentialType": "account-key",
  "accountKey": "<storage-account-key>"
}
```

For `PUT /internal/blob-stores/{id}`, omitted fields retain their stored values and an explicit empty string clears them. Secrets follow the S3 edit contract instead: an omitted or empty `accountKey` or `sasToken` keeps the stored value, so the admin console can leave the field blank. Settings the selected `credentialType` does not use are cleared. `POST /internal/blob-stores/{id}/check` runs a read/write probe against the container.

## Node defaults

`kkrepo.storage.azure.*` supplies defaults for values a blob-store row leaves blank. The prefix is not inherited: a blank prefix on a store means no prefix.

| Property | Default | Meaning |
| --- | --- | --- |
| `kkrepo.storage.azure.endpoint` | empty | Blob service URL; derived from `accountName` when empty |
| `kkrepo.storage.azure.account-name` | empty | Storage account name |
| `kkrepo.storage.azure.container` | `kkrepo` | Container name |
| `kkrepo.storage.azure.credential-type` | `default` | Credential mode |
| `kkrepo.storage.azure.upload-threshold-bytes` | 67108864 | Size above which uploads are split into blocks |
| `kkrepo.storage.azure.block-size-bytes` | 16777216 | Block size for chunked uploads |
| `kkrepo.storage.azure.upload-concurrency` | 4 | Parallel block uploads per upload |
| `kkrepo.storage.azure.connection-timeout-ms` | 5000 | Connection timeout |
| `kkrepo.storage.azure.read-timeout-ms` | 120000 | Read timeout |
| `kkrepo.storage.azure.max-retries` | 3 | Retries after the first attempt, with exponential backoff |

## Upload semantics

Large uploads use the block-blob protocol. Unlike S3 multipart uploads there is no server-side upload ID: blocks are staged against the destination blob name and become visible only when the block list is committed. kkRepo allocates an attempt-unique object key for every verified upload, so an interrupted attempt never collides with a published blob. Uncommitted blocks on an abandoned attempt are not visible to readers and Azure discards them automatically after seven days.

Uploads that are not size-verified are spooled to a local temporary file first and then sent in `block-size-bytes` blocks, so heap use stays bounded. A blob is limited to 50,000 blocks, so these uploads can reach `block-size-bytes * 50000`. Verified uploads stage fixed 8 MiB blocks and are capped by the shared verifier at 10,000 parts, about 78 GiB.

## Multi-replica behaviour

Blob service clients are cached per node and keyed by blob-store ID and configuration signature. Editing a store refreshes the catalog and broadcasts to sibling replicas, which rebuild their own clients. Tokens are never persisted or shared between replicas; grant the role to every identity kkRepo runs under.

## Private CA and TLS interception

`kkrepo.tls.ca-certificates` applies to Azure blob stores. When a PEM bundle is configured, kkRepo builds a dedicated HTTP client that trusts it and uses that client for both blob traffic and Entra ID token acquisition, so a TLS-intercepting corporate proxy works for the secretless credential modes as well. kkRepo logs the store name when it builds that store's client with the bundle applied. See [Custom CA certificates](outbound-tls.md).

## Limitations

- Azure blob metadata names must be valid C# identifiers, so kkRepo stores the logical path under `logicalpath` rather than a hyphenated key.
- Container creation and lifecycle policies are external deployment steps.

## Build and test

Build and test the storage adapter with Java 25:

```bash
mvn -pl storage-azure -am test
mvn -pl server -am -Dtest=BlobStorageRegistryProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`AzureBlobStorageAzuriteIntegrationTest` runs the adapter against the Azurite emulator and covers block-blob uploads, range reads, digest verification, and the admin probe. It requires a container runtime and skips when none is available.

The Azure SDK ships its own GraalVM reachability metadata, so `storage-azure` registers no runtime hints of its own. `AzureNativeImageMetadataTest` fails if a dependency upgrade drops that metadata.
