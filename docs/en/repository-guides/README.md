# Repository Usage Guide

This page is the single entry point for configuring package clients, publishing artifacts, and
consuming packages from every repository format supported by kkRepo. Replace the example host,
repository names, usernames, and tokens in the linked recipes with values from your deployment.

Use a **hosted** repository for private publication, a **proxy** repository to cache an upstream,
and a **group** repository as a single ordered read endpoint when that recipe is available. The
[Compatibility Matrix](../compatibility-matrix.md#repository-format-matrix) is the source of truth for
the recipe combinations and protocol boundaries supported by each format.

## Repository Formats

| Format | Repository types | Client setup and usage | Repository guide |
| --- | --- | --- | --- |
| Maven | hosted / proxy / group | [Maven recipe](../client-recipes.md#maven) | [Maven Repository Guide](maven.md) |
| npm | hosted / proxy / group | [npm recipe](../client-recipes.md#npm) | [npm Repository Guide](npm.md) |
| PyPI | hosted / proxy / group | [PyPI recipe](../client-recipes.md#pypi) | [PyPI Repository Guide](pypi.md) |
| Go | hosted / proxy / group | [Go recipe](../client-recipes.md#go) | [Go Repository Guide](go.md) |
| Helm | hosted / proxy | [Helm recipe](../client-recipes.md#helm) | [Helm Repository Guide](helm.md) |
| Cargo / Rust | hosted / proxy / group | [Cargo / Rust recipe](../client-recipes.md#cargo--rust) | [Cargo / Rust Repository Guide](cargo-rust.md) |
| Dart / Pub | hosted / proxy / group | [Dart / Pub recipe](../client-recipes.md#dart--pub) | [Dart / Pub Repository Guide](dart-pub.md) |
| Composer / PHP | hosted / proxy / group | [Composer / PHP recipe](../client-recipes.md#composer--php) | [Composer / PHP Repository Guide](composer-php.md) |
| Terraform Provider / Module Registry | hosted / proxy / group | [Terraform recipe](../client-recipes.md#terraform-provider--module-registry) | [Terraform Repository Guide](terraform.md) |
| Swift Package Registry | hosted / proxy / group | [Swift recipe](../client-recipes.md#swift-package-registry) | [Swift Package Registry Guide](swift-package-registry.md) |
| Ansible Galaxy | hosted / proxy / group | [Ansible Galaxy recipe](../client-recipes.md#ansible-galaxy) | [Ansible Galaxy Repository Guide](ansible-galaxy.md) |
| Conda | hosted / proxy / group | [Conda recipe](../client-recipes.md#conda) | [Conda Repository Guide](conda.md) |
| APT / Debian | hosted / proxy | [APT / Debian recipe](../client-recipes.md#apt--debian) | [APT / Debian Repository Guide](apt-debian.md) |
| Conan 2 | hosted / proxy / group | [Conan 2 recipe](../client-recipes.md#conan-2) | [Conan 2 Repository Guide](conan-2.md) |
| Alpine / APK | hosted / proxy / group | [Alpine / APK recipe](../client-recipes.md#alpine--apk) | [Alpine / APK Repository Guide](alpine-apk.md) |
| R / CRAN | hosted / proxy / group | [R / CRAN recipe](../client-recipes.md#r--cran) | [R / CRAN Repository Guide](r-cran.md) |
| Hugging Face Models | proxy | [Hugging Face Models recipe](../client-recipes.md#hugging-face-models) | [Hugging Face Models Repository Guide](hugging-face-models.md) |
| NuGet | hosted / proxy / group | [NuGet recipe](../client-recipes.md#nuget) | [NuGet Repository Guide](nuget.md) |
| RubyGems | hosted / proxy / group | [RubyGems recipe](../client-recipes.md#rubygems) | [RubyGems Repository Guide](rubygems.md) |
| Yum | hosted / proxy / group | [Yum recipe](../client-recipes.md#yum) | [Yum Repository Guide](yum.md) |
| Raw | hosted / proxy / group | [Raw recipe](../client-recipes.md#raw) | [Raw Repository Guide](raw.md) |
| Docker / OCI | hosted / proxy / group | [Docker / OCI recipe](../client-recipes.md#docker--oci) | [Docker / OCI Repository Guide](docker-oci.md) |

The recipes cover the normal client URL, authentication, publication, and consumption commands.
Each repository guide adds format-specific repository creation, proxy/group behavior, operations,
and known limitations.

## Common Repository Operations

- [Artifact Scanning Guide](../artifact-scanning-guide.md) explains scanning configuration, policy
  decisions, and operational checks.
- [Cleanup Policy Guide](../cleanup-policy-guide.md) explains retention rules, preview, execution, and
  multi-replica behavior.
- [Security Model](../security-model.md) covers users, roles, privileges, and CI tokens.
- [Nexus Migration Guide](../nexus-migration-guide.md) covers repository migration and validation.
- [Troubleshooting Guide](../troubleshooting.md) covers common deployment and client failures.

For production deployments, use HTTPS and prefer user-specific or CI tokens over passwords stored
in client configuration files.
