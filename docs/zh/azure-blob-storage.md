# Azure Blob Storage Blob Store

kkRepo 使用 Azure 原生 Blob Storage API 将制品 blob 存储在 Azure 存储账户中。Azure Blob Storage 没有 S3 兼容端点，因此 `aws-s3` 引擎不能指向 `*.blob.core.windows.net`；请使用本文描述的专用 `azure` blob store 类型。

推荐使用 **managed identity** 和 **workload identity** 两种凭据模式。它们都不需要密钥：kkRepo 不在数据库中存储任何密钥，每个副本各自从平台获取并刷新短生命周期 token。

## 术语对应

| 通用 blob 模型 | Azure |
| --- | --- |
| `bucket` | container |
| `prefix` | container 内的虚拟目录 |
| `endpoint` | blob 服务 URL，例如 `https://myaccount.blob.core.windows.net` |

`endpoint` 留空时根据 `accountName` 推导。主权云、专用终结点或 Azurite 需要显式设置。

## 凭据模式

| `credentialType` | kkRepo 存储的密钥 | 适用场景 |
| --- | --- | --- |
| `default` | 无 | 默认值。依次解析 workload identity、managed identity、环境凭据 |
| `managed-identity` | 无 | VM、VM 规模集、App Service 和 AKS 节点身份 |
| `workload-identity` | 无 | AKS 联合 workload identity |
| `account-key` | 存储账户密钥 | 没有 Entra ID 的环境 |
| `sas` | SAS token | 受限、有时效的委派访问 |

`default` 适用于大多数部署：同一份配置在 AKS、VM 和开发机上都能工作。如果希望 kkRepo 快速失败而不是静默回退到链中的其它凭据，请显式选择 `managed-identity` 或 `workload-identity`。

将 blob store 切换到基于 token 的模式时会清除已存储的 `accountKey` 和 `sasToken`，数据库中不会残留无用密钥；与基于 token 的模式一起提交的密钥会被忽略，不会存储。使用账户密钥或 SAS token 时，它们与 S3/OSS 密钥一样使用 `kkrepo.security.encryption.credential-secret` 加密存储。无法识别的 `credentialType` 会返回 `400 Bad Request`，不会回退到 `default`。

## 所需 Azure 角色

为身份在 container（或存储账户）上分配 **Storage Blob Data Contributor**。管理控制台健康检查会列出 blob；上传、下载和删除使用数据权限。

```bash
az role assignment create \
  --role "Storage Blob Data Contributor" \
  --assignee-object-id "$PRINCIPAL_OBJECT_ID" \
  --assignee-principal-type ServicePrincipal \
  --scope "/subscriptions/$SUBSCRIPTION/resourceGroups/$GROUP/providers/Microsoft.Storage/storageAccounts/$ACCOUNT/blobServices/default/containers/$CONTAINER"
```

创建 container 属于外部部署步骤；kkRepo 使用已配置的既有 container。

## AKS 上的 workload identity

在集群上启用 OIDC issuer 和 workload identity，然后把托管身份与 kkRepo 的 service account 联合：

```bash
az identity federated-credential create \
  --name kkrepo \
  --identity-name "$IDENTITY_NAME" \
  --resource-group "$GROUP" \
  --issuer "$OIDC_ISSUER_URL" \
  --subject "system:serviceaccount:$NAMESPACE:kkrepo" \
  --audience api://AzureADTokenExchange
```

给 pod 加标签并为 service account 加注解，webhook 才会注入 token：

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

webhook 会注入 `AZURE_CLIENT_ID`、`AZURE_TENANT_ID`、`AZURE_FEDERATED_TOKEN_FILE` 和 `AZURE_AUTHORITY_HOST`。当 `credentialType` 为 `default` 或 `workload-identity` 时，blob store 无需额外配置。如果没有使用 webhook，请选择 `workload-identity`，并在 blob store 上设置 `clientId`、`tenantId` 和 `tokenFilePath`；配置不完整会被拒绝，并在错误信息中指出缺失的值。`tokenFilePath` 仅适用于 `workload-identity`：`default` 凭据链只从 `AZURE_FEDERATED_TOKEN_FILE` 读取 token 文件，因此该模式不会存储此设置。

## Managed identity

使用系统分配身份时，将 `credentialType` 设为 `managed-identity` 并将 `clientId` 留空。使用用户分配身份时，将 `clientId` 设为其 client ID。

## 创建 blob store

使用管理员认证调用 `POST /internal/blob-stores`：

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

account-key 模式需要显式提供密钥：

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

对于 `PUT /internal/blob-stores/{id}`，省略的字段保留原值，显式传空字符串表示清除。密钥沿用 S3 的编辑约定：省略或为空的 `accountKey`、`sasToken` 会保留已存储的值，因此管理控制台中可以将该字段留空。当前 `credentialType` 不使用的设置会被清除。`POST /internal/blob-stores/{id}/check` 会对 container 执行读写探测。

## 节点默认值

`kkrepo.storage.azure.*` 为 blob store 行中留空的值提供默认值。prefix 不会继承：blob store 的 prefix 为空即表示不使用前缀。

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `kkrepo.storage.azure.endpoint` | 空 | blob 服务 URL；为空时由 `accountName` 推导 |
| `kkrepo.storage.azure.account-name` | 空 | 存储账户名 |
| `kkrepo.storage.azure.container` | `kkrepo` | container 名称 |
| `kkrepo.storage.azure.credential-type` | `default` | 凭据模式 |
| `kkrepo.storage.azure.upload-threshold-bytes` | 67108864 | 超过该大小的上传拆分为 block |
| `kkrepo.storage.azure.block-size-bytes` | 16777216 | 分块上传的 block 大小 |
| `kkrepo.storage.azure.upload-concurrency` | 4 | 单次上传的并行 block 数 |
| `kkrepo.storage.azure.connection-timeout-ms` | 5000 | 连接超时 |
| `kkrepo.storage.azure.read-timeout-ms` | 120000 | 读取超时 |
| `kkrepo.storage.azure.max-retries` | 3 | 首次尝试之后的重试次数，指数退避 |

## 上传语义

大文件上传使用 block blob 协议。与 S3 multipart 不同，这里没有服务端 upload ID：block 直接以目标 blob 名暂存，只有提交 block list 后才可见。kkRepo 为每次校验上传分配唯一的 attempt object key，因此中断的尝试不会与已发布的 blob 冲突。被放弃尝试中的未提交 block 对读取不可见，Azure 会在七天后自动清理。

未做大小校验的上传会先写入本地临时文件，再按 `block-size-bytes` 分块发送，因此堆内存占用有上限。单个 blob 最多 50,000 个 block，因此这类上传最大可达 `block-size-bytes * 50000`。校验上传固定按 8 MiB 暂存 block，并受共享校验器 10,000 个分片的限制，约 78 GiB。

## 多副本行为

Blob 服务客户端按节点缓存，键为 blob store ID 和配置签名。编辑 blob store 会刷新 catalog 并广播给其它副本，各副本重建自己的客户端。token 不会被持久化或在副本间共享；请为 kkRepo 运行时使用的每个身份授予角色。

## 私有 CA 与 TLS 拦截

`kkrepo.tls.ca-certificates` 对 Azure blob store 同样生效。配置 PEM 证书包后，kkRepo 会构建一个信任该证书包的专用 HTTP 客户端，并同时用于 blob 流量和 Entra ID token 获取，因此在企业 TLS 拦截代理下，无密钥凭据模式也可以正常工作。kkRepo 在为某个 blob store 构建应用了证书包的客户端时，会在日志中记录该 blob store 名称。参见[自定义 CA 证书](outbound-tls.md)。

## 限制

- Azure blob 元数据名称必须是合法的 C# 标识符，因此 kkRepo 使用 `logicalpath` 而不是带连字符的键来存储逻辑路径。
- 创建 container 和生命周期策略属于外部部署步骤。

## 构建与测试

使用 Java 25 构建和测试该存储适配器：

```bash
mvn -pl storage-azure -am test
mvn -pl server -am -Dtest=BlobStorageRegistryProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`AzureBlobStorageAzuriteIntegrationTest` 基于 Azurite 模拟器运行适配器，覆盖 block blob 上传、range 读取、摘要校验和管理探测。该测试需要容器运行时，缺失时会自动跳过。

Azure SDK 自带 GraalVM 可达性元数据，因此 `storage-azure` 不再注册自己的 runtime hints。如果依赖升级后丢失该元数据，`AzureNativeImageMetadataTest` 会失败。
