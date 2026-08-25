# Client Recipes

This guide shows common client configuration examples for repositories exposed through kkrepo. Replace `https://nexus.example.com`, repository names, usernames, and tokens with values from your own deployment.

The main client URL pattern is:

```text
https://nexus.example.com/repository/<repo>/
```

For production, use HTTPS and avoid embedding passwords in source-controlled files. Prefer user-specific tokens or CI tokens when available.

## Maven

Use a group repository for dependency resolution and a hosted repository for deployment.

`settings.xml`:

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

`pom.xml` deployment snippet:

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

Deploy:

```bash
mvn deploy
```

Manual PUT upload:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file app-1.0.0.jar \
  https://nexus.example.com/repository/maven-releases/com/acme/app/1.0.0/app-1.0.0.jar
```

## npm

Project-level `.npmrc`:

```ini
registry=https://nexus.example.com/repository/npm-group/
always-auth=true
```

Login for a hosted repository:

```bash
npm login --registry=https://nexus.example.com/repository/npm-hosted/
```

Publish:

```bash
npm publish --registry=https://nexus.example.com/repository/npm-hosted/
```

For scoped packages:

```ini
@acme:registry=https://nexus.example.com/repository/npm-group/
//nexus.example.com/repository/npm-group/:_authToken=${NPM_TOKEN}
```

Use `npm whoami --registry=...` to verify credentials.

## PyPI

`pip.conf`:

```ini
[global]
index-url = https://nexus.example.com/repository/pypi-group/simple
trusted-host = nexus.example.com
```

`~/.pypirc`:

```ini
[distutils]
index-servers =
    kkrepo

[kkrepo]
repository = https://nexus.example.com/repository/pypi-hosted/
username = alice
password = ${KKREPO_PASSWORD}
```

Install:

```bash
pip install --index-url https://nexus.example.com/repository/pypi-group/simple demo-package
```

Upload with twine:

```bash
python -m build
twine upload -r kkrepo dist/*
```

## Go

Configure a Go module group as the client read endpoint:

```bash
go env -w GOPROXY=https://nexus.example.com/repository/go-group/,direct
```

For private modules:

```bash
go env -w GOPRIVATE=git.example.com/acme/*
go env -w GONOSUMDB=git.example.com/acme/*
```

Fetch:

```bash
go list -m github.com/pkg/errors@latest
go mod download github.com/pkg/errors
```

Publish a canonical module ZIP to a hosted repository. The archive must contain one
`<module>@<version>/` root and the upload filename must be `<version>.zip`:

```bash
curl --fail-with-body \
  -u "$KKREPO_USER:$KKREPO_PASSWORD" \
  --upload-file v1.2.3.zip \
  https://nexus.example.com/repository/go-hosted/v1.2.3.zip
```

Put `go-hosted` before `go-proxy` in `go-group` so private modules resolve before public fallback.
See the [Go Repository Guide](repository-guides/go.md) for archive validation, write policy,
Cleanup, and Artifact Scanning behavior.

## Helm

Add a proxy or hosted chart repository:

```bash
helm repo add acme https://nexus.example.com/repository/helm-proxy/
helm repo update
helm search repo acme
```

Push a chart to a hosted repository:

```bash
helm package ./charts/demo
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0.tgz \
  https://nexus.example.com/repository/helm-hosted/demo-1.0.0.tgz
```

If using a Helm push plugin, point it at:

```text
https://nexus.example.com/repository/helm-hosted/
```

## Cargo / Rust

Use a group or proxy repository for dependency resolution and a hosted repository for publishing.

`.cargo/config.toml`:

```toml
[registries.kkrepo]
index = "sparse+https://nexus.example.com/repository/cargo-group/"

[registries.kkrepo_hosted]
index = "sparse+https://nexus.example.com/repository/cargo-hosted/"
```

Use a token created with the `CargoToken` domain. For non-interactive clients:

```bash
export CARGO_REGISTRIES_KKREPO_TOKEN="$CARGO_TOKEN"
export CARGO_REGISTRIES_KKREPO_HOSTED_TOKEN="$CARGO_TOKEN"
```

For local Cargo credential storage:

```bash
cargo login --registry kkrepo_hosted "$CARGO_TOKEN"
```

Search and fetch:

```bash
cargo search serde --registry kkrepo
cargo fetch
```

Publish and manage a hosted crate version:

```bash
cargo publish --registry kkrepo_hosted
cargo yank demo-crate --version 1.0.0 --registry kkrepo_hosted
cargo yank demo-crate --version 1.0.0 --undo --registry kkrepo_hosted
```

Cargo source replacement should only be used when the replacement source is intentionally equivalent to the original source. For a group that mixes private hosted crates with a crates.io proxy, prefer alternate registries through `[registries]`.

## Dart / Pub

Use a group or proxy repository for dependency resolution and a hosted repository for publishing.

Add a token for the hosted repository and paste the full `PubToken.<secret>` value when prompted:

```bash
dart pub token add https://nexus.example.com/repository/pub-hosted
```

Use a group repository for dependency resolution:

```bash
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group dart pub get
```

For Flutter projects, use the same hosted URL:

```bash
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group flutter pub get
```

Configure a single private dependency in `pubspec.yaml`:

```yaml
dependencies:
  demo_package:
    hosted:
      url: https://nexus.example.com/repository/pub-group
      name: demo_package
    version: ^1.0.0
```

Publish to a hosted repository by setting `publish_to`:

```yaml
name: demo_package
version: 1.0.0
publish_to: https://nexus.example.com/repository/pub-hosted
```

Then publish:

```bash
dart pub publish
```

For package discovery through kkrepo search, use the Pub format filter in the Browse UI or the component search API with `format=pub`.

## Composer / PHP

Use a group or proxy repository for dependency resolution. Composer has no standard package publish command; upload private packages to a hosted repository through the administration UI or the Nexus-compatible Components API.

Project `composer.json`:

```json
{
  "repositories": [
    {"type": "composer", "url": "https://nexus.example.com/repository/composer-group", "canonical": true},
    {"packagist.org": false}
  ],
  "require": {
    "acme/demo": "^1.0"
  }
}
```

Configure HTTP Basic through `COMPOSER_AUTH` or Composer `auth.json`; do not commit passwords to the project:

```bash
export COMPOSER_AUTH='{"http-basic":{"nexus.example.com":{"username":"alice","password":"'"$KKREPO_PASSWORD"'"}}}'
composer install --prefer-dist --no-interaction
composer show acme/demo --locked
```

Upload a zip/tar archive that contains `composer.json`:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -F "composer.asset=@acme-demo-1.0.0.zip;type=application/zip" \
  -F "composer.name=acme/demo" \
  -F "composer.version=1.0.0" \
  "https://nexus.example.com/service/rest/v1/components?repository=composer-hosted"
```

Composer 2 uses `packages.json` and `p2/<vendor>/<package>.json`. Hosted, proxy, and group repositories keep the `/repository/<repo>/...` URL shape, and Browse Usage provides a copyable project snippet.

## Terraform Provider / Module Registry

Create `terraform-hosted`, `terraform-proxy`, and `terraform-group` repositories, then configure the group as the service endpoint for the registry hostname used in module/provider source addresses. kkRepo follows Nexus's explicit Terraform CLI `host.services` configuration instead of claiming the deployment root `/.well-known/terraform.json`:

```hcl
# ~/.terraformrc on Linux/macOS, terraform.rc on Windows
disable_checkpoint = true

host "registry.terraform.io" {
  services = {
    "modules.v1"   = "https://repo.example.com/repository/terraform-group/v1/modules/<generic-token>/"
    "providers.v1" = "https://repo.example.com/repository/terraform-group/v1/providers/<generic-token>/"
  }
}
```

Create `<generic-token>` as a `GenericToken` under **My Token**, keep the CLI configuration file mode at `0600`, and never commit it. Anonymous repositories can omit the token segment. Basic authentication is also accepted; generated download metadata carries an encoded URL token so Terraform can follow archive, checksum, and signature URLs without adding custom headers.

Use normal source addresses in Terraform configuration:

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

Then resolve through the configured group:

```bash
terraform init -backend=false
```

Hosted modules and providers can be uploaded through Browse/Admin or the Nexus-compatible PUT routes. A provider upload requires an exact platform path and a safe `Content-Disposition` filename:

```bash
curl -u user:password --upload-file network-1.0.0.zip \
  https://repo.example.com/repository/terraform-hosted/v1/modules/acme/network/aws/1.0.0/network-1.0.0.zip

curl -u user:password \
  -H 'Content-Disposition: attachment; filename=terraform-provider-demo_1.0.0_linux_amd64.zip' \
  -H 'X-Terraform-Provider-Protocols: 6.0' \
  --upload-file terraform-provider-demo_1.0.0_linux_amd64.zip \
  https://repo.example.com/repository/terraform-hosted/v1/providers/acme/demo/1.0.0/download/linux/amd64
```

Nexus-compatible provider PUTs that omit `X-Terraform-Provider-Protocols` retain Nexus's `5.0`
default. Protocol 6-only providers must send the explicit header; Browse/Admin and the component
upload API expose the same value as `terraform.protocols`. A comma-separated value such as
`5.0,6.0` is accepted when the release supports both major protocols. Every platform uploaded for
one provider version must declare the same protocol set. kkRepo never executes an uploaded provider
binary to infer this metadata.

kkRepo generates hosted provider SHA256SUMS and detached GPG signatures as one revision. Proxy repositories preserve and verify upstream checksum/signing metadata, and group source bindings keep metadata and archive downloads on the same member.

## Swift Package Registry

Create `swift-hosted`, `swift-proxy`, and `swift-group` repositories. Use the hosted URL for publication and the group URL for dependency resolution. Production SwiftPM login requires HTTPS:

```bash
swift package-registry set \
  https://nexus.example.com/repository/swift-group/

swift package-registry login \
  https://nexus.example.com/repository/swift-group/login \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --no-confirm
```

The Swift registry specification makes `POST /login` optional and permits `501 Not Implemented` from servers that do not provide it. kkrepo does provide the endpoint: valid Basic or Bearer/`GenericToken` credentials return `200`, and invalid credentials return `401`. SwiftPM 5.8+ can exercise the login command over HTTPS; `501` is therefore a protocol-reference case, not an expected kkrepo result.

Publish an immutable source archive from the package directory. The archive must contain one top-level package root and a valid `Package.swift`; versioned `Package@swift-X.Y.swift` manifests are preserved:

```bash
swift package-registry publish acme.demo 1.2.3 \
  --url https://nexus.example.com/repository/swift-hosted/ \
  --metadata-path package-metadata.json
```

Consume a registry identity from `Package.swift`:

```swift
dependencies: [
    .package(id: "acme.demo", exact: "1.2.3")
]
```

Then resolve and build through the configured group. GitHub SCM dependencies can be converted through the registry `/identifiers` mapping:

```bash
swift package resolve
swift build
swift package resolve --replace-scm-with-registry
```

Use a `GenericToken` with `swift package-registry login --token <token>` for CI. The GitHub-backed proxy accepts GitHub HTTPS/SSH repository identities, pins the first observed tag commit and archive checksum, and never rewrites an already cached release after a tag moves.

## Ansible Galaxy

Create `ansiblegalaxy-hosted`, `ansiblegalaxy-proxy`, and `ansiblegalaxy-group` repositories. Publish to hosted and use the group for install/download:

```ini
# ansible.cfg
[galaxy]
server_list = kkrepo

[galaxy_server.kkrepo]
url = https://nexus.example.com/repository/ansible-group/
token = GenericToken.REDACTED
```

```bash
ansible-galaxy collection build --output-path dist
ansible-galaxy collection publish dist/acme-tools-1.0.0.tar.gz \
  --server https://nexus.example.com/repository/ansible-hosted/ \
  --token "$KKREPO_ANSIBLE_TOKEN"
ansible-galaxy collection install acme.tools:1.0.0 \
  --server https://nexus.example.com/repository/ansible-group/ \
  --token "$KKREPO_ANSIBLE_TOKEN"
```

Ansible 2.9 may name the token option `--api-key`. Collection versions are immutable, and dependency, checksum, signature, metadata, and artifact reads through a group remain bound to the same priority member. See the [Ansible Galaxy Repository Guide](repository-guides/ansible-galaxy.md) for `requirements.yml`, Nexus-compatible credentials/PUT upload, proxy, migration, storage limits, and troubleshooting.

## Conda

Create `conda-hosted`, `conda-proxy`, and `conda-group` repositories. Point the proxy at a channel root such as `https://repo.anaconda.com/pkgs/main/`, add hosted before proxy in the group, and configure the group as the client channel:

```yaml
# ~/.condarc
channels:
  - https://nexus.example.com/repository/conda-group
channel_priority: strict
show_channel_urls: true
```

For a private repository, keep the password out of `.condarc` and use the standard netrc file:

```text
# ~/.netrc
machine nexus.example.com
  login alice
  password <password>
```

Protect the file with `chmod 600 ~/.netrc`. Then search and install through the group; Conda automatically requests the current platform subdir and `noarch` metadata:

```bash
conda search --override-channels \
  -c https://nexus.example.com/repository/conda-group demo
conda create -y -n demo-env --override-channels \
  -c https://nexus.example.com/repository/conda-group demo=1.0.0
conda list -n demo-env demo
```

Conda has no package publish command. Upload a validated `.conda` or `.tar.bz2` archive through the admin UI/Components API, or use the Nexus-compatible hosted PUT path. The path is `<optional-channel>/<subdir>/<filename>`:

```bash
curl -u "alice:$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-py_0.conda \
  https://nexus.example.com/repository/conda-hosted/team/release/noarch/demo-1.0.0-py_0.conda
```

The package filename, `info/index.json` name/version/build, and target subdir must agree. kkRepo rebuilds `repodata.json`, `repodata.json.bz2`, `repodata.json.zst`, `current_repodata.json*`, and `channeldata.json`; do not upload generated metadata as package content.

## APT / Debian

Download the repository public key into a scoped keyring:

```bash
sudo install -d -m 0755 /etc/apt/keyrings
curl --fail --show-error -u alice:"$KKREPO_PASSWORD" \
  -o /tmp/kkrepo-apt.asc \
  https://nexus.example.com/repository/apt-hosted/gpg.key
sudo install -m 0644 /tmp/kkrepo-apt.asc /etc/apt/keyrings/kkrepo.asc
rm -f /tmp/kkrepo-apt.asc
```

Configure `/etc/apt/sources.list.d/kkrepo.list`:

```text
deb [signed-by=/etc/apt/keyrings/kkrepo.asc] https://nexus.example.com/repository/apt-hosted stable main
```

For a private repository, keep credentials out of the URL and put them in
`/etc/apt/auth.conf.d/kkrepo.conf`:

```text
machine nexus.example.com/repository/apt-hosted/
login alice
password <password-or-token>
```

```bash
chmod 0600 /etc/apt/auth.conf.d/kkrepo.conf
apt-get update
apt-get install demo-package
```

Upload a binary package through the Nexus-compatible repository-root endpoint:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: multipart/form-data' \
  --data-binary @demo-package_1.0.0-1_amd64.deb \
  https://nexus.example.com/repository/apt-hosted/
```

The Components API and Admin UI use the single `apt.asset` field. Only
`apt-hosted` accepts uploads; `apt-proxy` is read-only and APT group is not exposed.

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -F apt.asset=@demo-package_1.0.0-1_amd64.deb \
  'https://nexus.example.com/service/rest/v1/components?repository=apt-hosted'
```

For signing-key lifecycle, asynchronous publication status, proxy modes, snapshot retention,
cleanup, migration, and recovery, see the [APT / Debian Repository Guide](repository-guides/apt-debian.md).

## Conan 2

Configure the group for reads and the hosted repository for publication:

```bash
conan remote add kkrepo \
  https://nexus.example.com/repository/conan-group
conan remote add kkrepo-hosted \
  https://nexus.example.com/repository/conan-hosted
conan remote login kkrepo alice -p "$KKREPO_PASSWORD"
conan remote login kkrepo-hosted alice -p "$KKREPO_PASSWORD"
```

Create and upload a recipe, then resolve it through the group:

```bash
conan create . \
  --name=demo --version=1.0.0 --user=team --channel=stable
conan upload 'demo/1.0.0@team/stable' \
  --remote=kkrepo-hosted --confirm
conan list 'demo/1.0.0@team/stable#*:*#*' --remote=kkrepo
conan install --requires='demo/1.0.0@team/stable' --remote=kkrepo
```

For revision commit semantics, Browse paths, cleanup, scanning, proxy/group behavior,
migration, and troubleshooting, see the [Conan Repository Guide](repository-guides/conan-2.md).

## Alpine / APK

Install the repository-scoped public key with the exact filename configured on the group:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -o kkrepo-alpine-group.rsa.pub \
  https://nexus.example.com/internal/repositories/alpine-group/alpine/public-key
sudo install -m 0644 kkrepo-alpine-group.rsa.pub \
  /etc/apk/keys/kkrepo-alpine-group.rsa.pub
```

Configure a group repository, then update, inspect, fetch, and install with the standard client:

```bash
echo 'https://nexus.example.com/repository/alpine-group/v3.23/main' \
  | sudo tee /etc/apk/repositories
apk update
apk policy acme-agent
apk fetch acme-agent=1.2.3-r0
apk add acme-agent=1.2.3-r0
apk info -e acme-agent=1.2.3-r0
```

Publish an immutable APK v2 package to a hosted namespace:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: application/vnd.alpine.apk' \
  --upload-file acme-agent-1.2.3-r0.apk \
  https://nexus.example.com/repository/alpine-hosted/v3.23/main/x86_64/acme-agent-1.2.3-r0.apk
```

Use protected client configuration for authenticated repository URLs. Hosted/group indexes are
locally signed; passthrough proxies retain the upstream signature. See the
[Alpine / APK Repository Guide](repository-guides/alpine-apk.md).

## R / CRAN

Use an R group as the normal source repository in `.Rprofile`:

```r
local({
  repos <- getOption("repos")
  repos["KKRepo"] <- "https://nexus.example.com/repository/r-group"
  options(repos = repos)
})
```

Publish a source package to hosted:

```bash
R CMD build acmepkg
curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: application/x-gzip' \
  --upload-file acmepkg_1.2.3.tar.gz \
  https://nexus.example.com/repository/r-hosted/src/contrib/acmepkg_1.2.3.tar.gz
```

Resolve and install through the group:

```r
available.packages(repos = getOption("repos")["KKRepo"], type = "source")
install.packages("acmepkg", repos = getOption("repos")["KKRepo"], type = "source")
```

Hosted/group are source-only; address the proxy directly for upstream `.zip`, `.tgz`, or
`PACKAGES.rds`. See the [R / CRAN Repository Guide](repository-guides/r-cran.md).

## Hugging Face Models

Create a Models-only `huggingface-proxy`, then point the Hub client at its repository root:

```bash
export HF_ENDPOINT='https://nexus.example.com/repository/huggingface-models'
export HF_HUB_DOWNLOAD_TIMEOUT=120
export HF_HUB_ETAG_TIMEOUT=1800

hf download sshleifer/tiny-gpt2 config.json
hf download sshleifer/tiny-gpt2 --include '*.json' '*.safetensors'
```

For local authentication, expose a read-scoped kkRepo `GenericToken` as `HF_TOKEN`. Configure a
separate remote bearer token on the repository only when the upstream model is private or gated.
`huggingface_hub`, Transformers, and Diffusers inherit the same `HF_ENDPOINT`. See the
[Hugging Face Models Repository Guide](repository-guides/hugging-face-models.md).

## NuGet

Add a source:

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-group/v3/index.json \
  --name kkrepo
```

Add a source with credentials:

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-hosted/v3/index.json \
  --name kkrepo-hosted \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --store-password-in-clear-text
```

Push:

```bash
dotnet nuget push bin/Release/Demo.1.0.0.nupkg \
  --source https://nexus.example.com/repository/nuget-hosted/ \
  --api-key "$KKREPO_API_KEY"
```

Use a `NuGetApiKey` token for `--api-key`, or use a source configured with username/password if your environment has not enabled NuGet API keys yet.

Restore:

```bash
dotnet restore --source https://nexus.example.com/repository/nuget-group/v3/index.json
```

## RubyGems

Add source:

```bash
gem sources --add https://nexus.example.com/repository/rubygems-group/ --remove https://rubygems.org/
gem sources --list
```

Push with Basic authentication:

```bash
gem push demo-1.0.0.gem \
  --host https://alice:${KKREPO_PASSWORD}@nexus.example.com/repository/rubygems-hosted/
```

Push with a RubyGems API key:

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

Create the key as a `RubyGemsApiKey` token in **My Token** and store the full generated token value, for example `RubyGemsApiKey.<secret>`, in the credentials file. RubyGems sends the selected key as the request `Authorization` value.

For CI jobs, scripts, and HTTP clients that are not tied to a protocol-specific token format, create a `GenericToken` and send the full generated token through the configured API-key header to the hosted upload endpoint:

```bash
curl -H "X-Nexus-Plus-Token: $KKREPO_GENERIC_TOKEN" \
  --data-binary @demo-1.0.0.gem \
  https://nexus.example.com/repository/rubygems-hosted/api/v1/gems
```

Avoid committing credentials into source control.

Push endpoint for low-level clients:

```bash
curl -u "alice:${KKREPO_PASSWORD}" \
  --data-binary @demo-1.0.0.gem \
  https://nexus.example.com/repository/rubygems-hosted/api/v1/gems
```

Install:

```bash
gem install demo --source https://nexus.example.com/repository/rubygems-group/
```

## Yum

Repository file `/etc/yum.repos.d/kkrepo.repo`:

```ini
[kkrepo]
name=kkrepo
baseurl=https://nexus.example.com/repository/yum-group/
enabled=1
gpgcheck=0
```

Install:

```bash
yum clean all
yum install demo-package
```

Upload RPM to a hosted repository:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-1.x86_64.rpm \
  https://nexus.example.com/repository/yum-hosted/Packages/demo-1.0.0-1.x86_64.rpm
```

## Raw

Upload:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file archive.tar.gz \
  https://nexus.example.com/repository/raw-hosted/releases/archive.tar.gz
```

Download:

```bash
curl -O https://nexus.example.com/repository/raw-group/releases/archive.tar.gz
```

## Docker / OCI

Docker / OCI Registry support uses the Registry HTTP API V2 `/v2/...` route, not the normal `/repository/<repo>/...` artifact route.

Shared-entrypoint or reverse-proxy deployments can expose path-based repository routing:

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

Examples:

```bash
docker login nexus.example.com
docker pull nexus.example.com/docker-proxy/library/alpine:3.20
docker tag alpine:3.20 nexus.example.com/docker-hosted/team/alpine:3.20
docker push nexus.example.com/docker-hosted/team/alpine:3.20
docker pull nexus.example.com/docker-group/team/alpine:3.20
```

Repository-level Docker connector ports can also expose the standard Docker image shape when configured:

```text
<host>:<repo-port>/<image>:<tag>
```

For local development, run the real client matrix script to cover hosted
push/pull, proxy pull, group pull, and optional ORAS/Skopeo smoke checks:

```bash
scripts/docker-compat/client-compat.sh
```

Do not assume Docker pull/push works through `/repository/<repo>/...`.

## Troubleshooting Client Configuration

- A `401` usually means missing or invalid credentials.
- A `403` usually means the user authenticated but lacks repository permission.
- A `404` on a group repository may mean no member contains the requested asset.
- Uploads require a hosted repository and add/edit permission.
- Large uploads may require reverse proxy body-size and timeout tuning.
- If a client behaves differently from Nexus, open a compatibility issue with the exact request and response from both systems.
