# S3 凭据与 IAM 角色

对于 `aws-s3` Blob Store，在 **Admin → Blob Stores** 中选择 **Default AWS credentials**，即可将两个密钥字段留空。kkRepo 将认证和临时凭据刷新交给 AWS SDK 默认凭据链，支持 EC2 实例角色、EKS Pod Identity、IRSA、容器凭据，以及包含 `AWS_SESSION_TOKEN` 的 AWS 环境变量。SDK 会优先检查 JVM 系统属性和环境变量中的凭据，再检查工作负载角色；使用角色时，应移除部署中不需要的静态凭据。

也可以选择 **Static access key / secret key** 并填写完整密钥对。现有静态凭据配置继续有效，`oss-native` 仍要求静态密钥。Endpoint、Region 和 Bucket 配置与认证方式无关，例如分别填写 `https://s3.us-east-1.amazonaws.com`、`us-east-1` 和已有的 bucket 名称。

## 创建与更新

通过 API 创建时，可以省略两个密钥：

```json
{
  "name": "aws-artifacts",
  "type": "s3",
  "engine": "aws-s3",
  "endpoint": "https://s3.us-east-1.amazonaws.com",
  "region": "us-east-1",
  "bucket": "my-artifact-bucket",
  "credentialSource": "default",
  "pathStyleAccess": false
}
```

使用管理员身份向 `POST /internal/blob-stores` 提交。创建时省略 `credentialSource` 也可以：两个密钥都未填写或为空时使用默认凭据链，两个都填写时使用静态凭据，只填写一个会被拒绝。

对于 `PUT /internal/blob-stores/{id}`，密钥字段省略或留空仍表示保留已有值。要把已有静态凭据切换为角色认证，需显式提交 `"credentialSource": "default"`，并携带要保留的 Endpoint、Region、Bucket 等其他配置。这会清除数据库中的两个密钥。选择 `"credentialSource": "static"` 时，合并已有值后必须有完整密钥对；留空的输入仍保留旧值。管理界面遵循相同规则。

空凭据以空字符串持久化，不会被全局 `kkrepo.storage.s3` 密钥替换。缺少密钥属性的旧记录仍保留原有全局配置回退行为。IAM 角色解析出的临时凭据和 session token 不会写入 Blob Store 记录。每个副本独立获取和刷新临时凭据，因此所有副本都需要配置角色权限；配置修改继续通过数据库目录同步和刷新广播传播。

## S3 权限

为角色授予已有 bucket 的访问权限，将下面的 `my-artifact-bucket` 替换为实际名称。管理界面的健康检查使用 `ListBucket`，对象权限用于上传、下载、删除及失败分片上传的清理。

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::my-artifact-bucket"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"],
      "Resource": "arn:aws:s3:::my-artifact-bucket/*"
    }
  ]
}
```

使用 SSE-KMS 时，还需授予角色相应的 KMS 权限，并在密钥策略中允许访问。Bucket 应在部署时预先创建，kkRepo 使用配置中指定的已有 bucket。

## EC2 实例角色

为 IAM 角色设置以下信任策略，附加上面的 S3 权限，将角色加入 instance profile，并把 instance profile 绑定到运行 kkRepo 的 EC2 实例：

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
```

应用必须能够访问 EC2 实例元数据服务 IMDS，包括 IMDSv2 的 token 请求。容器网络及实例元数据 hop limit 应允许这些请求，不要设置 `AWS_EC2_METADATA_DISABLED=true`。在 Blob Store 中选择 **Default AWS credentials**。参见 [EC2 IAM 角色文档](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html)。

## EKS Pod Identity

在支持的工作节点上安装 [EKS Pod Identity Agent](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-agent-setup.html)，并确保节点角色能够调用 `eks-auth:AssumeRoleForPodIdentity`。为工作负载角色设置以下信任策略，并附加 S3 权限：

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "pods.eks.amazonaws.com"},
    "Action": ["sts:AssumeRole", "sts:TagSession"]
  }]
}
```

在 `artifacts` namespace 中创建名为 `kkrepo` 的 Kubernetes ServiceAccount，然后创建角色关联：

```bash
aws eks create-pod-identity-association \
  --cluster-name my-cluster \
  --namespace artifacts \
  --service-account kkrepo \
  --role-arn arn:aws:iam::123456789012:role/kkrepo-s3
```

将 kkRepo Deployment 的 `spec.template.spec.serviceAccountName` 设置为 `kkrepo`，配置关联后重新创建 Pod。EKS 会注入 `AWS_CONTAINER_CREDENTIALS_FULL_URI` 和 `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE`，SDK 自动使用这些配置。Pod 网络应允许访问 Agent 凭据端点。**Pod Identity 不使用 ServiceAccount 的角色注解。** 参见 [Pod Identity 关联文档](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-association.html)。

使用项目 Helm chart 时，可通过 `serviceAccount.create=false` 和 `serviceAccount.name=kkrepo` 选择预先创建的账号。

## EKS IRSA

IRSA 使用集群的 IAM OIDC provider 和 STS，不使用 Pod Identity association。先为集群创建 IAM OIDC provider，再设置限定 namespace 与 ServiceAccount 的信任策略。替换账号 ID、区域及 `CLUSTER_OIDC_ID`：

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::123456789012:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/CLUSTER_OIDC_ID"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {"StringEquals": {
      "oidc.eks.us-east-1.amazonaws.com/id/CLUSTER_OIDC_ID:aud": "sts.amazonaws.com",
      "oidc.eks.us-east-1.amazonaws.com/id/CLUSTER_OIDC_ID:sub": "system:serviceaccount:artifacts:kkrepo"
    }}
  }]
}
```

附加 S3 策略，并为 ServiceAccount 添加注解：

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kkrepo
  namespace: artifacts
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/kkrepo-s3
```

在 Deployment 中使用该 ServiceAccount，并重新创建 Pod。EKS 会注入 `AWS_ROLE_ARN` 和 `AWS_WEB_IDENTITY_TOKEN_FILE`，Pod 需能够访问 STS 和 S3。kkRepo 已包含 Web Identity 所需的 AWS SDK `sts` 模块。项目 Helm chart 可通过 `serviceAccount.annotations` 设置此注解。参见 [IRSA 配置](https://docs.aws.amazon.com/eks/latest/userguide/associate-service-account-role.html)及 [Java SDK 要求](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts-minimum-sdk.html)。

## 验证

点击 Blob Store 的 **Check**，执行临时对象的写入、读取、内容比对和删除，再通过绑定该存储的仓库上传、下载制品。角色部署应在临时凭据刷新后重复验证，并覆盖所有副本。解析失败时，检查 Pod 或实例身份、信任策略、投射 token、网络，以及默认凭据链中优先级更高的凭据来源。

自动化测试使用真实 AWS SDK 配合本地 S3、IMDSv2、容器凭据和 STS 测试服务，覆盖 session token、刷新和独立客户端生命周期。这些测试不能验证 AWS IAM 信任策略或 EKS 注入及 Agent 配置，仍需在实际 AWS 部署中验证。

参考：[AWS 默认凭据链](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)、[Nexus S3 认证](https://help.sonatype.com/en/aws-simple-storage-service--s3-.html)。
