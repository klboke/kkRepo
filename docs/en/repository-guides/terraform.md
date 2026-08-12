# Terraform Provider / Module Repository Guide

kkRepo supports Terraform Module Registry and Provider Registry `hosted`, `proxy`, and `group`
repositories. The same repository format serves module and provider protocols through explicit
Terraform CLI service configuration.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private modules/providers | `terraform-hosted` | Blob store, write policy, signing configuration |
| Public registry cache | `terraform-proxy` | Remote registry and cache TTLs |
| Unified reads | `terraform-group` | Hosted before proxy |

The examples use the public registry hostname in source addresses while redirecting its services to
kkRepo. Use a hostname and trust model appropriate for the deployment.

## Configure Terraform CLI Services

Configure `~/.terraformrc` on Linux/macOS or `terraform.rc` on Windows:

```hcl
host "registry.terraform.io" {
  services = {
    "modules.v1"   = "https://repo.example.com/repository/terraform-group/v1/modules/<generic-token>/"
    "providers.v1" = "https://repo.example.com/repository/terraform-group/v1/providers/<generic-token>/"
  }
}
```

Create `<generic-token>` as a `GenericToken`, keep the file mode at `0600`, and never commit it.
Anonymous repositories omit the token segment. Basic authentication is accepted, but generated
download URLs still need credentials that Terraform can replay.

## Resolve Modules And Providers

Use normal registry source addresses and pin versions:

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

Run `terraform init -backend=false`, then commit the generated dependency lock file when appropriate.

## Publish Hosted Content

Upload through Browse/Admin or Nexus-compatible PUT routes. Module paths include namespace, name,
system, and version. Provider uploads also require the platform and a safe filename:

```bash
curl -u user:password --upload-file network-1.0.0.zip \
  https://repo.example.com/repository/terraform-hosted/v1/modules/acme/network/aws/1.0.0/network-1.0.0.zip

curl -u user:password \
  -H 'Content-Disposition: attachment; filename=terraform-provider-demo_1.0.0_linux_amd64.zip' \
  -H 'X-Terraform-Provider-Protocols: 6.0' \
  --upload-file terraform-provider-demo_1.0.0_linux_amd64.zip \
  https://repo.example.com/repository/terraform-hosted/v1/providers/acme/demo/1.0.0/download/linux/amd64
```

All platforms for one provider version must declare the same protocol set. kkRepo generates hosted
provider checksum manifests and detached GPG signatures without executing uploaded binaries.

## Repository Behavior And Limits

- Proxy preserves and verifies upstream provider checksum/signing metadata.
- Group source binding keeps versions, platforms, checksums, signatures, and archives on one member.
- Root-domain `/.well-known/terraform.json` discovery and the Provider Network Mirror Protocol are
  not exposed; use explicit CLI `host.services` configuration.
- Browse and Search expose module/provider coordinates, versions, platforms, and source members.

## Related Documentation

- [Terraform client recipe](../client-recipes.md#terraform-provider--module-registry)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Terraform Module Registry Protocol](https://developer.hashicorp.com/terraform/internals/module-registry-protocol)
- [Terraform Provider Registry Protocol](https://developer.hashicorp.com/terraform/internals/provider-registry-protocol)
