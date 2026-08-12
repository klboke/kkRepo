# Terraform Provider / Module 仓库使用指南

kkRepo 支持 Terraform Module Registry 与 Provider Registry 的 `hosted`、`proxy` 和 `group`
仓库。同一种仓库格式通过显式 Terraform CLI service 配置提供 module 与 provider 协议。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 module/provider | `terraform-hosted` | Blob store、write policy、签名配置 |
| 公共 registry 缓存 | `terraform-proxy` | Remote registry 和缓存 TTL |
| 统一读取入口 | `terraform-group` | Hosted 排在 proxy 前面 |

下例在 source address 中沿用公共 registry hostname，同时把它的 service 重定向到 kkRepo。
实际部署应选择合适的 hostname 与信任模型。

## 配置 Terraform CLI Service

Linux/macOS 配置 `~/.terraformrc`，Windows 配置 `terraform.rc`：

```hcl
host "registry.terraform.io" {
  services = {
    "modules.v1"   = "https://repo.example.com/repository/terraform-group/v1/modules/<generic-token>/"
    "providers.v1" = "https://repo.example.com/repository/terraform-group/v1/providers/<generic-token>/"
  }
}
```

创建 `GenericToken` 作为 `<generic-token>`，文件权限保持 `0600`，且不得提交。匿名仓库移除
token segment。系统也接受 Basic authentication，但生成的下载 URL 仍需要 Terraform 能安全
重放的凭据。

## 解析 Module 与 Provider

使用普通 registry source address 并固定版本：

```hcl
terraform {
  required_providers {
    null = {
      source  = "registry.terraform.io/hashicorp/null"
      version = "3.2.4"
    }
  }
}

module "network" {
  source  = "registry.terraform.io/acme/network/aws"
  version = "1.0.0"
}
```

运行 `terraform init -backend=false`，并在适合时提交生成的 dependency lock file。

## 发布 Hosted 内容

通过 Browse/Admin 或 Nexus 兼容 PUT 上传。Module 路径包含 namespace、name、system 和
version；provider 还要求 platform 与安全 filename：

```bash
curl -u user:password --upload-file network-1.0.0.zip \
  https://repo.example.com/repository/terraform-hosted/v1/modules/acme/network/aws/1.0.0/network-1.0.0.zip

curl -u user:password \
  -H 'Content-Disposition: attachment; filename=terraform-provider-demo_1.0.0_linux_amd64.zip' \
  -H 'X-Terraform-Provider-Protocols: 6.0' \
  --upload-file terraform-provider-demo_1.0.0_linux_amd64.zip \
  https://repo.example.com/repository/terraform-hosted/v1/providers/acme/demo/1.0.0/download/linux/amd64
```

同一 provider version 的所有 platform 必须声明相同 protocol set。kkRepo 不执行上传的
binary，而是生成 hosted provider checksum manifest 与 detached GPG signature。

## 仓库行为与限制

- Proxy 保留并验证上游 provider checksum 与 signing metadata。
- Group source binding 保证 version、platform、checksum、signature 和 archive 位于同一成员。
- 不暴露根域名 `/.well-known/terraform.json` discovery 与 Provider Network Mirror Protocol；
  应使用 CLI `host.services` 显式配置。
- Browse 与 Search 展示 module/provider coordinate、version、platform 和 source member。

## 相关文档

- [Terraform 客户端配置示例](../client-recipes.md#terraform-provider--module-registry)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Terraform Module Registry Protocol](https://developer.hashicorp.com/terraform/internals/module-registry-protocol)
- [Terraform Provider Registry Protocol](https://developer.hashicorp.com/terraform/internals/provider-registry-protocol)
