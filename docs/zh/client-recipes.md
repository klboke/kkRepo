# 客户端配置示例

本文提供 kkrepo 常见客户端配置示例。请把 `https://nexus.example.com`、仓库名、用户名和 token 替换为你自己的部署值。

主要客户端 URL 形态是：

```text
https://nexus.example.com/repository/<repo>/
```

生产环境请使用 HTTPS，避免把密码写入源码仓库。能使用用户 token 或 CI token 时，优先使用 token。

## Maven

依赖解析通常使用 group 仓库，发布使用 hosted 仓库。

`settings.xml`：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>kkrepo</id>
      <mirrorOf>*</mirrorOf>
      <url>https://nexus.example.com/repository/maven-public/</url>
    </mirror>
  </mirrors>

  <servers>
    <server>
      <id>maven-releases</id>
      <username>alice</username>
      <password>${env.KKREPO_PASSWORD}</password>
    </server>
    <server>
      <id>maven-snapshots</id>
      <username>alice</username>
      <password>${env.KKREPO_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

`pom.xml` 发布配置：

```xml
<distributionManagement>
  <repository>
    <id>maven-releases</id>
    <url>https://nexus.example.com/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>maven-snapshots</id>
    <url>https://nexus.example.com/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

发布：

```bash
mvn deploy
```

手工 PUT 上传：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file app-1.0.0.jar \
  https://nexus.example.com/repository/maven-releases/com/acme/app/1.0.0/app-1.0.0.jar
```

## npm

项目级 `.npmrc`：

```ini
registry=https://nexus.example.com/repository/npm-group/
always-auth=true
```

登录 hosted 仓库：

```bash
npm login --registry=https://nexus.example.com/repository/npm-hosted/
```

发布：

```bash
npm publish --registry=https://nexus.example.com/repository/npm-hosted/
```

scope 包配置：

```ini
@acme:registry=https://nexus.example.com/repository/npm-group/
//nexus.example.com/repository/npm-group/:_authToken=${NPM_TOKEN}
```

可以用 `npm whoami --registry=...` 验证凭据。

## PyPI

`pip.conf`：

```ini
[global]
index-url = https://nexus.example.com/repository/pypi-group/simple
trusted-host = nexus.example.com
```

`~/.pypirc`：

```ini
[distutils]
index-servers =
    kkrepo

[kkrepo]
repository = https://nexus.example.com/repository/pypi-hosted/
username = alice
password = ${KKREPO_PASSWORD}
```

安装：

```bash
pip install --index-url https://nexus.example.com/repository/pypi-group/simple demo-package
```

使用 twine 上传：

```bash
python -m build
twine upload -r kkrepo dist/*
```

## Go

配置 Go module proxy 或 group 仓库：

```bash
go env -w GOPROXY=https://nexus.example.com/repository/go-group/,direct
```

私有 module 示例：

```bash
go env -w GOPRIVATE=git.example.com/acme/*
go env -w GONOSUMDB=git.example.com/acme/*
```

拉取：

```bash
go list -m github.com/pkg/errors@latest
go mod download github.com/pkg/errors
```

Go 不支持 hosted 上传；当前以 Go proxy/group 读取代理行为为主。

## Helm

添加 proxy 或 hosted chart 仓库：

```bash
helm repo add acme https://nexus.example.com/repository/helm-group/
helm repo update
helm search repo acme
```

向 hosted 仓库推送 chart：

```bash
helm package ./charts/demo
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0.tgz \
  https://nexus.example.com/repository/helm-hosted/demo-1.0.0.tgz
```

如果使用 Helm push 插件，目标地址使用：

```text
https://nexus.example.com/repository/helm-hosted/
```

## Cargo / Rust

依赖解析使用 group 或 proxy 仓库，发布使用 hosted 仓库。

`.cargo/config.toml`：

```toml
[registries.kkrepo]
index = "sparse+https://nexus.example.com/repository/cargo-group/"

[registries.kkrepo_hosted]
index = "sparse+https://nexus.example.com/repository/cargo-hosted/"
```

凭据使用 `CargoToken` domain 创建的 token。非交互客户端可以使用环境变量：

```bash
export CARGO_REGISTRIES_KKREPO_TOKEN="$CARGO_TOKEN"
export CARGO_REGISTRIES_KKREPO_HOSTED_TOKEN="$CARGO_TOKEN"
```

本地 Cargo 凭据存储可以使用：

```bash
cargo login --registry kkrepo_hosted "$CARGO_TOKEN"
```

搜索和拉取：

```bash
cargo search serde --registry kkrepo
cargo fetch
```

发布和管理 hosted crate version：

```bash
cargo publish --registry kkrepo_hosted
cargo yank demo-crate --version 1.0.0 --registry kkrepo_hosted
cargo yank demo-crate --version 1.0.0 --undo --registry kkrepo_hosted
```

Cargo source replacement 只适合替换源与原始源内容等价的场景。如果 group 同时混合私有 hosted crate 和 crates.io proxy，优先通过 `[registries]` 使用 alternate registry。

## Dart / Pub

依赖解析使用 group 或 proxy 仓库，发布使用 hosted 仓库。

先为 hosted 仓库添加 token，命令提示输入时粘贴完整的 `PubToken.<secret>` 值：

```bash
dart pub token add https://nexus.example.com/repository/pub-hosted
```

依赖解析可以使用 group 仓库：

```bash
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group dart pub get
```

Flutter 项目使用同一个 hosted URL：

```bash
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group flutter pub get
```

在 `pubspec.yaml` 中配置单个私有依赖：

```yaml
dependencies:
  demo_package:
    hosted:
      url: https://nexus.example.com/repository/pub-group
      name: demo_package
    version: ^1.0.0
```

发布到 hosted 仓库时设置 `publish_to`：

```yaml
name: demo_package
version: 1.0.0
publish_to: https://nexus.example.com/repository/pub-hosted
```

然后执行发布：

```bash
dart pub publish
```

如果要通过 kkrepo 搜索发现包，可以在 Browse UI 选择 Pub 格式过滤，或调用 component search API 时带上 `format=pub`。

## NuGet

添加 source：

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-group/v3/index.json \
  --name kkrepo
```

添加带凭据的 source：

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-hosted/v3/index.json \
  --name kkrepo-hosted \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --store-password-in-clear-text
```

发布：

```bash
dotnet nuget push bin/Release/Demo.1.0.0.nupkg \
  --source https://nexus.example.com/repository/nuget-hosted/ \
  --api-key "$KKREPO_API_KEY"
```

`--api-key` 推荐使用 `NuGetApiKey` token；如果环境暂未启用 NuGet API key，也可以使用带用户名/密码的 source。

恢复依赖：

```bash
dotnet restore --source https://nexus.example.com/repository/nuget-group/v3/index.json
```

## RubyGems

添加 source：

```bash
gem sources --add https://nexus.example.com/repository/rubygems-group/ --remove https://rubygems.org/
gem sources --list
```

使用 Basic authentication 发布：

```bash
gem push demo-1.0.0.gem \
  --host https://alice:${KKREPO_PASSWORD}@nexus.example.com/repository/rubygems-hosted/
```

使用 RubyGems API key 发布：

```yaml
# ~/.gem/credentials
:kkrepo: $KKREPO_RUBYGEMS_API_KEY
```

```bash
chmod 0600 ~/.gem/credentials
gem push demo-1.0.0.gem \
  --host https://nexus.example.com/repository/rubygems-hosted/ \
  --key kkrepo
```

在 **My Token** 中创建 `RubyGemsApiKey` token，并把完整生成 token 值写入 credentials 文件，例如 `RubyGemsApiKey.<secret>`。RubyGems 会把选中的 key 作为请求 `Authorization` 值发送。

对于不绑定特定协议 token 格式的 CI、脚本和 HTTP 客户端，可以创建 `GenericToken`，并通过配置的 API-key header 把完整生成 token 传给 hosted 上传 endpoint：

```bash
curl -H "X-Nexus-Plus-Token: $KKREPO_GENERIC_TOKEN" \
  --data-binary @demo-1.0.0.gem \
  https://nexus.example.com/repository/rubygems-hosted/api/v1/gems
```

不要把凭据提交到代码仓库。

低层 HTTP 客户端的发布 endpoint：

```bash
curl -u "alice:${KKREPO_PASSWORD}" \
  --data-binary @demo-1.0.0.gem \
  https://nexus.example.com/repository/rubygems-hosted/api/v1/gems
```

安装：

```bash
gem install demo --source https://nexus.example.com/repository/rubygems-group/
```

## Yum

仓库文件 `/etc/yum.repos.d/kkrepo.repo`：

```ini
[kkrepo]
name=kkrepo
baseurl=https://nexus.example.com/repository/yum-group/
enabled=1
gpgcheck=0
```

安装：

```bash
yum clean all
yum install demo-package
```

上传 RPM 到 hosted 仓库：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-1.x86_64.rpm \
  https://nexus.example.com/repository/yum-hosted/Packages/demo-1.0.0-1.x86_64.rpm
```

## Raw

上传：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file archive.tar.gz \
  https://nexus.example.com/repository/raw-hosted/releases/archive.tar.gz
```

下载：

```bash
curl -O https://nexus.example.com/repository/raw-group/releases/archive.tar.gz
```

## Docker / OCI

Docker / OCI Registry 使用 Registry HTTP API V2 的 `/v2/...` 路由，不走普通制品仓库的 `/repository/<repo>/...` 路由。

共享入口或反向代理部署可以使用 path-based repository routing：

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

示例：

```bash
docker login nexus.example.com
docker pull nexus.example.com/docker-proxy/library/alpine:3.20
docker tag alpine:3.20 nexus.example.com/docker-hosted/team/alpine:3.20
docker push nexus.example.com/docker-hosted/team/alpine:3.20
docker pull nexus.example.com/docker-group/team/alpine:3.20
```

配置仓库级 Docker connector port 后，也可以暴露标准 Docker image 形态：

```text
<host>:<repo-port>/<image>:<tag>
```

本地开发环境可以用真实客户端矩阵脚本做 hosted push/pull、proxy pull、group pull
以及可选的 ORAS/Skopeo smoke：

```bash
scripts/docker-compat/client-compat.sh
```

不要假设 Docker pull/push 可以通过 `/repository/<repo>/...` 工作。

## 客户端配置排障

- `401` 通常表示缺少凭据或凭据无效。
- `403` 通常表示已认证，但缺少仓库权限。
- group 仓库上的 `404` 可能表示没有任何 member 包含目标 asset。
- 上传需要 hosted 仓库和 add/edit 权限。
- 大文件上传可能需要调整反向代理 body size 和 timeout。
- 如果客户端行为和 Nexus 不一致，请提交 compatibility issue，并附上两个系统的精确请求和响应。
