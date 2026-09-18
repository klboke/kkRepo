# S3 credentials and IAM roles

For an `aws-s3` blob store, select **Default AWS credentials** in **Admin → Blob Stores** to leave both keys empty. kkRepo delegates authentication and temporary credential refresh to the AWS SDK default credentials provider chain. This supports EC2 instance profiles, EKS Pod Identity, IRSA, container credentials, and AWS environment credentials (including `AWS_SESSION_TOKEN`). The SDK checks system properties and environment credentials before workload roles; remove unintended static credentials from the deployment when selecting a role.

Alternatively, select **Static access key / secret key** and supply both values. Existing static stores continue to work. `oss-native` still requires static keys. Endpoint, region, and bucket configuration are independent of the credential source; for example, use `https://s3.us-east-1.amazonaws.com`, `us-east-1`, and your existing bucket.

## Configuration and updates

An API create request can omit both keys:

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

Send this to `POST /internal/blob-stores` with administrator authentication. On creation, omitting `credentialSource` also selects the default chain when both keys are absent or blank; supplying both keys selects static credentials. Supplying only one key is rejected.

For `PUT /internal/blob-stores/{id}`, blank or omitted key fields retain the existing values. To change an existing static store to a role, explicitly send `"credentialSource": "default"` along with its endpoint, region, bucket, and other settings to retain. This clears both stored keys. Selecting `"credentialSource": "static"` requires a complete effective pair, retaining any existing key whose input is blank. The UI implements these same rules.

Empty credentials are stored as empty strings in the database and are not replaced by global `kkrepo.storage.s3` keys. Legacy records without credential attributes retain their global fallback. IAM role credentials and session tokens are never saved in the blob-store record. Each replica resolves and refreshes its own temporary credentials; configure the role on every replica. Catalog changes use the existing database-backed synchronization and refresh broadcast.

## S3 permissions

Create a role with permissions for the existing bucket, replacing `my-artifact-bucket` below. The console health check uses `ListBucket`; upload, download, deletion, and failed multipart-upload cleanup use the object permissions.

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

For SSE-KMS buckets, also grant the role the required KMS permissions and allow it in the key policy. Bucket creation is an external deployment step; kkRepo uses the configured existing bucket.

## EC2 instance profile

Use this IAM role trust policy, attach the S3 permissions above, add the role to an instance profile, and attach that instance profile to the EC2 instance running kkRepo:

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

Allow the application to reach EC2 Instance Metadata Service (IMDS), including IMDSv2 token requests. Container networking and the instance metadata hop limit must allow that access. Do not set `AWS_EC2_METADATA_DISABLED=true`. Select **Default AWS credentials** for the blob store. See [IAM roles for EC2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html).

## EKS Pod Identity

Install the [EKS Pod Identity Agent](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-agent-setup.html) on supported worker nodes and ensure its node role can call `eks-auth:AssumeRoleForPodIdentity`. Use the following trust policy for the workload role, and attach its S3 permissions:

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

Create the `kkrepo` Kubernetes ServiceAccount in the `artifacts` namespace, then associate it with the role:

```bash
aws eks create-pod-identity-association \
  --cluster-name my-cluster \
  --namespace artifacts \
  --service-account kkrepo \
  --role-arn arn:aws:iam::123456789012:role/kkrepo-s3
```

Set the kkRepo Deployment's `spec.template.spec.serviceAccountName` to `kkrepo` and recreate the pods after configuring the association. EKS injects `AWS_CONTAINER_CREDENTIALS_FULL_URI` and `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE`; the SDK uses them without kkRepo-specific configuration. Permit pod access to the agent's credential endpoint. **Pod Identity does not use a ServiceAccount role annotation.** See [Pod Identity associations](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-association.html).

With the bundled Helm chart, select a pre-created account using `serviceAccount.create=false` and `serviceAccount.name=kkrepo`.

## EKS IRSA

IRSA uses the cluster's IAM OIDC provider and STS instead of a Pod Identity association. Create the IAM OIDC provider for your cluster, then use a trust policy scoped to the namespace and ServiceAccount. Replace the account ID, region, and `CLUSTER_OIDC_ID`:

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

Attach the S3 policy and annotate the ServiceAccount:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kkrepo
  namespace: artifacts
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/kkrepo-s3
```

Use that ServiceAccount in the Deployment and recreate the pods. EKS injects `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE`. Allow network access to STS and S3. kkRepo includes the AWS SDK `sts` module needed for web identity. The bundled Helm chart can set this annotation through `serviceAccount.annotations`. See [IRSA setup](https://docs.aws.amazon.com/eks/latest/userguide/associate-service-account-role.html) and [Java SDK requirements](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts-minimum-sdk.html).

## Verification

Select **Check** on the blob store to write, read, compare, and delete a temporary object. Then upload and download an artifact through a repository bound to the store. For role deployments, repeat after temporary credentials have refreshed and exercise every replica. If resolution fails, check the pod/instance identity, trust policy, projected token, network access, and any credentials earlier in the SDK chain.

Automated tests exercise the real SDK against local S3, IMDSv2, container-credential, and STS fixtures, including session tokens, refresh, and independent client lifecycles. These fixtures do not validate AWS IAM trust policies or EKS admission/agent configuration; verify those in your AWS deployment.

References: [AWS default credentials chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html), [Nexus S3 authentication](https://help.sonatype.com/en/aws-simple-storage-service--s3-.html).
