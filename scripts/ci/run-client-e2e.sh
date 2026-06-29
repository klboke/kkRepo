#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:${KKREPO_COMPAT_PORT:-18090}}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
KKREPO_MANAGEMENT_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:${KKREPO_MANAGEMENT_PORT:-18091}}"
KKREPO_DOCKER_HOSTED_REGISTRY="${KKREPO_DOCKER_HOSTED_REGISTRY:-127.0.0.1:${KKREPO_DOCKER_HOSTED_PORT:-18180}}"
ARTIFACT_DIR="${CLIENT_E2E_ARTIFACT_DIR:-$PROJECT_ROOT/artifacts/client-e2e}"
WORK_DIR="${CLIENT_E2E_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-client-e2e.XXXXXX")}"
STAMP="${CLIENT_E2E_STAMP:-$(date +%Y%m%d%H%M%S)}"
START_TIMEOUT_SECONDS="${LIVE_COMPAT_START_TIMEOUT_SECONDS:-240}"
KKREPO_AUTH_URL=""
REDACTION_VALUES=("$KKREPO_PASSWORD" "$KKREPO_AUTH")

mkdir -p "$ARTIFACT_DIR" "$WORK_DIR"
export DOTNET_CLI_TELEMETRY_OPTOUT="${DOTNET_CLI_TELEMETRY_OPTOUT:-1}"
export DOTNET_NOLOGO="${DOTNET_NOLOGO:-1}"
export DOTNET_SKIP_FIRST_TIME_EXPERIENCE="${DOTNET_SKIP_FIRST_TIME_EXPERIENCE:-1}"

log() {
  printf '[client-e2e] %s\n' "$*"
}

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "missing required command: $1"
    exit 2
  fi
}

add_redaction_value() {
  if [[ -n "$1" ]]; then
    REDACTION_VALUES+=("$1")
  fi
}

redact_text() {
  local text="$1"
  local value
  for value in "${REDACTION_VALUES[@]}"; do
    if [[ -n "$value" ]]; then
      text="${text//$value/******}"
    fi
  done
  if [[ -n "$KKREPO_AUTH_URL" ]]; then
    text="${text//$KKREPO_AUTH_URL/$KKREPO_URL}"
  fi
  printf '%s' "$text"
}

print_command() {
  local arg
  printf '$'
  for arg in "$@"; do
    printf ' %q' "$(redact_text "$arg")"
  done
  printf '\n'
}

redact_log_file() {
  local file="$1"
  python3 - "$file" "${REDACTION_VALUES[@]}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
try:
    text = path.read_text(encoding="utf-8", errors="replace")
except FileNotFoundError:
    sys.exit(0)

for value in sys.argv[2:]:
    if value:
        text = text.replace(value, "******")
path.write_text(text, encoding="utf-8")
PY
}

run_logged() {
  local name="$1"
  shift
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    print_command "$@"
    "$@"
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

run_logged_in() {
  local name="$1"
  local dir="$2"
  shift 2
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    printf '$ cd %q\n' "$(redact_text "$dir")"
    print_command "$@"
    (cd "$dir" && "$@")
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

run_logged_output() {
  local name="$1"
  local output="$2"
  shift 2
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    print_command "$@"
    "$@" >"$output"
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

run_logged_output_in() {
  local name="$1"
  local dir="$2"
  local output="$3"
  shift 3
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    printf '$ cd %q\n' "$(redact_text "$dir")"
    print_command "$@"
    (cd "$dir" && "$@" >"$output")
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

write_nuget_config() {
  local file="$1"
  local source_name="$2"
  local source_url="$3"
  cat >"$file" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <config>
EOF
  printf '    <add key="defaultPushSource" value="%s" />\n' "$source_url" >>"$file"
  cat >>"$file" <<'EOF'
  </config>
  <packageSources>
    <clear />
EOF
  printf '    <add key="%s" value="%s" protocolVersion="3" allowInsecureConnections="true" />\n' "$source_name" "$source_url" >>"$file"
  cat >>"$file" <<EOF
  </packageSources>
  <packageSourceCredentials>
    <$source_name>
      <add key="Username" value="$KKREPO_USER" />
      <add key="ClearTextPassword" value="$KKREPO_PASSWORD" />
    </$source_name>
  </packageSourceCredentials>
</configuration>
EOF
}

wait_for_http() {
  local label="$1"
  local url="$2"
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    if curl -m 5 -fsS "$url" >/dev/null 2>&1; then
      log "$label is ready"
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label at $url"
  return 1
}

wait_for_docker_registry() {
  local headers_file http_code
  headers_file="$(mktemp)"
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    http_code="$(curl -m 5 -sS -D "$headers_file" -o /dev/null -w '%{http_code}' \
      "http://$KKREPO_DOCKER_HOSTED_REGISTRY/v2/" 2>/dev/null || true)"
    if [[ "$http_code" == "200" || "$http_code" == "401" ]] \
      && grep -qi '^Docker-Distribution-API-Version:[[:space:]]*registry/2\.0' "$headers_file"; then
      rm -f "$headers_file"
      log "Docker registry is ready"
      return 0
    fi
    : >"$headers_file"
    sleep 1
  done
  rm -f "$headers_file"
  log "timed out waiting for Docker registry at $KKREPO_DOCKER_HOSTED_REGISTRY"
  return 1
}

create_api_key() {
  local domain="$1"
  local display_name="$2"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -H "Content-Type: application/json" \
    --data "{\"domain\":\"$domain\",\"displayName\":\"$display_name\"}" \
    "$KKREPO_URL/internal/security/api-keys/current" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

basic_auth_url() {
  python3 - "$KKREPO_URL" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import sys
from urllib.parse import quote, urlsplit, urlunsplit

url, user, password = sys.argv[1:4]
parts = urlsplit(url)
netloc = parts.netloc
if "@" in netloc:
    netloc = netloc.split("@", 1)[1]
auth = quote(user, safe="") + ":" + quote(password, safe="") + "@"
print(urlunsplit((parts.scheme, auth + netloc, parts.path.rstrip("/"), parts.query, parts.fragment)))
PY
}

wait_for_body_contains() {
  local label="$1"
  local needle="$2"
  local url="$3"
  local output="$4"
  for ((i = 1; i <= 60; i++)); do
    if curl -m 10 -fsS -u "$KKREPO_AUTH" "$url" -o "$output" 2>"$ARTIFACT_DIR/$label.curl.log" \
      && grep -Fq "$needle" "$output"; then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label to contain $needle at $url"
  return 1
}

capture_rubygems_metadata() {
  local name="$1"
  local dependencies="$ARTIFACT_DIR/rubygems-dependencies.marshal"
  local specs="$ARTIFACT_DIR/rubygems-specs.4.8.gz"
  local quick="$ARTIFACT_DIR/rubygems-quick-spec.rz"
  local gem="$ARTIFACT_DIR/rubygems-package.gem"
  run_logged_output rubygems-dependencies "$dependencies" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/api/v1/dependencies?gems=$name"
  run_logged rubygems-dependencies-decode ruby -e \
    'p Marshal.load(File.binread(ARGV.fetch(0)))' "$dependencies"
  run_logged_output rubygems-specs "$specs" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/specs.4.8.gz"
  run_logged rubygems-specs-decode ruby -rzlib -e \
    'p Marshal.load(Zlib.gunzip(File.binread(ARGV.fetch(0)))).select { |row| row[0].to_s == ARGV.fetch(1) }' "$specs" "$name"
  run_logged_output rubygems-quick-spec "$quick" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/quick/Marshal.4.8/$name-1.0.0.gemspec.rz"
  run_logged_output rubygems-package "$gem" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/gems/$name-1.0.0.gem"
}

test_maven() {
  need mvn
  local dir="$WORK_DIR/maven"
  local local_repo="$dir/.m2"
  local artifact="client-e2e-maven-$STAMP"
  local version="1.0.$STAMP"
  mkdir -p "$dir/src/main/java/com/example" "$local_repo"
  cat >"$dir/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.github.klboke.kkrepo.e2e</groupId>
  <artifactId>$artifact</artifactId>
  <version>$version</version>
  <properties>
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
  </properties>
  <distributionManagement>
    <repository>
      <id>kkrepo</id>
      <url>$KKREPO_URL/repository/maven-releases/</url>
    </repository>
  </distributionManagement>
</project>
EOF
  cat >"$dir/src/main/java/com/example/App.java" <<'EOF'
package com.example;
public final class App {
  public static String message() {
    return "kkrepo client e2e";
  }
}
EOF
  cat >"$dir/settings.xml" <<EOF
<settings>
  <servers>
    <server>
      <id>kkrepo</id>
      <username>$KKREPO_USER</username>
      <password>$KKREPO_PASSWORD</password>
    </server>
  </servers>
</settings>
EOF
  run_logged maven-publish mvn -B -ntp -s "$dir/settings.xml" -Dmaven.repo.local="$local_repo" -f "$dir/pom.xml" deploy
  rm -rf "$local_repo/com/github/klboke/kkrepo/e2e/$artifact"
  run_logged maven-resolve mvn -B -ntp -s "$dir/settings.xml" -Dmaven.repo.local="$local_repo" \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get \
    "-DremoteRepositories=kkrepo::::$KKREPO_URL/repository/maven-public/" \
    "-Dartifact=com.github.klboke.kkrepo.e2e:$artifact:$version"
  test -f "$local_repo/com/github/klboke/kkrepo/e2e/$artifact/$version/$artifact-$version.jar"
  wait_for_body_contains maven-metadata "$version" \
    "$KKREPO_URL/repository/maven-public/com/github/klboke/kkrepo/e2e/$artifact/maven-metadata.xml" \
    "$ARTIFACT_DIR/maven-metadata.xml"
}

test_npm() {
  need npm
  local dir="$WORK_DIR/npm"
  local install_dir="$WORK_DIR/npm-install"
  local package="@kkrepo-client-e2e/npm-$STAMP"
  local npm_registry_host token
  npm_registry_host="$(printf '%s' "$KKREPO_URL" | sed 's#^http[s]*://##')"
  token="$(create_api_key NpmToken "client e2e npm $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$dir" "$install_dir"
  cat >"$dir/package.json" <<EOF
{"name":"$package","version":"1.0.0","description":"kkrepo client e2e","main":"index.js"}
EOF
  echo 'module.exports = "kkrepo client e2e";' >"$dir/index.js"
  cat >"$dir/.npmrc" <<EOF
registry=$KKREPO_URL/repository/npm-hosted/
//$npm_registry_host/repository/npm-hosted/:_authToken=$token
//$npm_registry_host/repository/npm-group/:_authToken=$token
always-auth=true
EOF
  run_logged_in npm-publish "$dir" npm --userconfig "$dir/.npmrc" publish --registry "$KKREPO_URL/repository/npm-hosted/"
  cat >"$install_dir/package.json" <<EOF
{"name":"npm-install-$STAMP","version":"1.0.0","dependencies":{"$package":"1.0.0"}}
EOF
  run_logged npm-install npm --userconfig "$dir/.npmrc" --prefix "$install_dir" install \
    --registry "$KKREPO_URL/repository/npm-group/" --ignore-scripts
  test -f "$install_dir/node_modules/@kkrepo-client-e2e/npm-$STAMP/index.js"
  curl -m 10 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/repository/npm-group/@kkrepo-client-e2e%2fnpm-$STAMP" \
    -o "$ARTIFACT_DIR/npm-packument.json"
  grep -q '"1.0.0"' "$ARTIFACT_DIR/npm-packument.json"
}

test_pypi() {
  need python3
  need twine
  python3 -m pip --version >/dev/null
  local dir="$WORK_DIR/pypi"
  local install_dir="$WORK_DIR/pypi-install"
  local name="kkrepo_client_e2e_pypi_$STAMP"
  mkdir -p "$dir/src/$name" "$install_dir"
  cat >"$dir/pyproject.toml" <<EOF
[build-system]
requires = ["setuptools>=68", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "$name"
version = "1.0.0"
description = "kkrepo client e2e"
requires-python = ">=3.8"
EOF
  echo 'VALUE = "kkrepo client e2e"' >"$dir/src/$name/__init__.py"
  run_logged pypi-build python3 -m build "$dir" --wheel --outdir "$dir/dist"
  run_logged pypi-upload twine upload --non-interactive \
    --repository-url "$KKREPO_URL/repository/pypi-hosted/" \
    -u "$KKREPO_USER" -p "$KKREPO_PASSWORD" "$dir"/dist/*.whl
  run_logged pypi-install python3 -m pip install --disable-pip-version-check --no-deps \
    --target "$install_dir" \
    --index-url "$KKREPO_AUTH_URL/repository/pypi-group/simple/" \
    "$name==1.0.0"
  test -f "$install_dir/$name/__init__.py"
  wait_for_body_contains pypi-simple "$name-1.0.0" \
    "$KKREPO_URL/repository/pypi-group/simple/$name/" \
    "$ARTIFACT_DIR/pypi-simple.html"
}

test_go() {
  need go
  local dir="$WORK_DIR/go"
  mkdir -p "$dir"
  cat >"$dir/go.mod" <<'EOF'
module kkrepo-client-e2e.local/probe

go 1.22

require rsc.io/quote v1.5.2
EOF
  # Go refuses userinfo credentials on explicit HTTP module proxy URLs; the
  # disposable kkrepo fixture keeps anonymous read enabled for this resolve-only flow.
  run_logged go-download env \
    GOPROXY="$KKREPO_URL/repository/go-proxy/" \
    GONOSUMDB=rsc.io/quote \
    GOSUMDB=off \
    GOMODCACHE="$dir/gomodcache" \
    GOCACHE="$dir/gocache" \
    go mod download -json rsc.io/quote@v1.5.2
  test -f "$dir/gomodcache/cache/download/rsc.io/quote/@v/v1.5.2.info"
  test -f "$dir/gomodcache/cache/download/rsc.io/quote/@v/v1.5.2.mod"
  test -f "$dir/gomodcache/cache/download/rsc.io/quote/@v/v1.5.2.zip"
}

test_helm() {
  need helm
  local dir="$WORK_DIR/helm"
  local chart="kkrepo-client-e2e-helm-$STAMP"
  mkdir -p "$dir"
  run_logged helm-create helm create "$dir/$chart"
  python3 - "$dir/$chart/Chart.yaml" <<'PY'
import sys
path = sys.argv[1]
data = []
for line in open(path, encoding="utf-8"):
    if line.startswith("version:"):
        data.append("version: 1.0.0\n")
    else:
        data.append(line)
open(path, "w", encoding="utf-8").writelines(data)
PY
  run_logged helm-package helm package "$dir/$chart" --destination "$dir/dist"
  run_logged helm-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$dir/dist/$chart-1.0.0.tgz" \
    "$KKREPO_URL/repository/helm-hosted/$chart-1.0.0.tgz"
  wait_for_body_contains helm-index "$chart" \
    "$KKREPO_URL/repository/helm-hosted/index.yaml" \
    "$ARTIFACT_DIR/helm-index.yaml"
  run_logged helm-repo-add helm repo add "kkrepo-e2e-$STAMP" "$KKREPO_URL/repository/helm-hosted" \
    --username "$KKREPO_USER" --password "$KKREPO_PASSWORD"
  run_logged helm-repo-update helm repo update
  mkdir -p "$dir/pulled"
  run_logged helm-pull helm pull "kkrepo-e2e-$STAMP/$chart" --version 1.0.0 --destination "$dir/pulled"
  test -f "$dir/pulled/$chart-1.0.0.tgz"
}

test_cargo() {
  need cargo
  local dir="$WORK_DIR/cargo"
  local crate="kkrepo_client_e2e_cargo_$STAMP"
  local crate_dir="$dir/$crate"
  local cargo_home="$WORK_DIR/cargo-home"
  local cargo_target="$WORK_DIR/cargo-target"
  local token
  token="$(create_api_key CargoToken "client e2e cargo $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$crate_dir/src" "$crate_dir/.cargo" "$cargo_home" "$cargo_target"
  cat >"$crate_dir/Cargo.toml" <<EOF
[package]
name = "$crate"
version = "0.1.0"
edition = "2021"
description = "kkrepo client e2e"
license = "MIT"
repository = "https://example.invalid/kkrepo-client-e2e"

[lib]
path = "src/lib.rs"
EOF
  cat >"$crate_dir/src/lib.rs" <<'EOF'
pub fn message() -> &'static str {
    "kkrepo client e2e"
}
EOF
  cat >"$crate_dir/.cargo/config.toml" <<EOF
[registry]
global-credential-providers = ["cargo:token"]

[registries.kkrepo]
index = "sparse+$KKREPO_URL/repository/cargo-hosted/"
EOF
  run_logged_in cargo-publish "$crate_dir" env \
    CARGO_HOME="$cargo_home" \
    CARGO_TARGET_DIR="$cargo_target" \
    CARGO_REGISTRIES_KKREPO_TOKEN="$token" \
    cargo publish \
    --registry kkrepo --token "$token" --allow-dirty --no-verify
  local fetch_dir="$WORK_DIR/cargo-fetch"
  run_logged cargo-fetch-new cargo new --bin "$fetch_dir"
  mkdir -p "$fetch_dir/.cargo"
  cat >"$fetch_dir/.cargo/config.toml" <<EOF
[registry]
global-credential-providers = ["cargo:token"]

[registries.kkrepo]
index = "sparse+$KKREPO_URL/repository/cargo-group/"
EOF
  echo "$crate = { version = \"0.1.0\", registry = \"kkrepo\" }" >>"$fetch_dir/Cargo.toml"
  run_logged_in cargo-fetch "$fetch_dir" env \
    CARGO_HOME="$cargo_home" \
    CARGO_TARGET_DIR="$cargo_target-fetch" \
    CARGO_REGISTRIES_KKREPO_TOKEN="$token" \
    cargo fetch
  run_logged_output_in cargo-metadata "$fetch_dir" "$ARTIFACT_DIR/cargo-metadata.json" env \
    CARGO_HOME="$cargo_home" \
    CARGO_TARGET_DIR="$cargo_target-fetch" \
    CARGO_REGISTRIES_KKREPO_TOKEN="$token" \
    cargo metadata --format-version 1
  grep -q "\"$crate\"" "$ARTIFACT_DIR/cargo-metadata.json"
}

test_nuget() {
  need dotnet
  local dir="$WORK_DIR/nuget"
  local restore_dir="$WORK_DIR/nuget-restore"
  local packages_dir="$WORK_DIR/nuget-packages"
  local package="KkRepo.ClientE2E.NuGet.$STAMP"
  local token
  token="$(create_api_key NuGetApiKey "client e2e nuget $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$dir" "$restore_dir" "$packages_dir"
  write_nuget_config "$dir/NuGet.Config" "kkrepoHosted" "$KKREPO_URL/repository/nuget-hosted/index.json"
  write_nuget_config "$restore_dir/NuGet.Config" "kkrepoGroup" "$KKREPO_URL/repository/nuget-group/index.json"
  run_logged nuget-new dotnet new classlib -n "$package" -o "$dir/$package" --framework net8.0
  run_logged nuget-pack dotnet pack "$dir/$package/$package.csproj" \
    -p:PackageId="$package" -p:Version=1.0.0 -o "$dir/out"
  run_logged nuget-push dotnet nuget push "$dir/out/$package.1.0.0.nupkg" \
    --source kkrepoHosted \
    --configfile "$dir/NuGet.Config" \
    --api-key "$token" \
    --timeout 120
  run_logged nuget-consumer-new dotnet new console -n Consumer -o "$restore_dir/Consumer" --framework net8.0
  run_logged_in nuget-add-package "$restore_dir/Consumer" dotnet add package "$package" \
    --version 1.0.0 \
    --package-directory "$packages_dir"
  run_logged nuget-restore dotnet restore "$restore_dir/Consumer/Consumer.csproj" \
    --configfile "$restore_dir/NuGet.Config" \
    --packages "$packages_dir"
  test -f "$packages_dir/$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]')/1.0.0/$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]').1.0.0.nupkg"
}

test_rubygems() {
  need ruby
  need gem
  local dir="$WORK_DIR/rubygems"
  local name="kkrepo_client_e2e_rubygems_$STAMP"
  local gem_home="$WORK_DIR/gem-home"
  local gem_user_home="$WORK_DIR/rubygems-home"
  local gem_credentials="$gem_user_home/.gem/credentials"
  local token
  token="$(create_api_key RubyGemsApiKey "client e2e rubygems $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$dir/lib" "$gem_home" "$gem_user_home/.gem"
  printf -- '---\n:kkrepo: %s\n' "$token" >"$gem_credentials"
  chmod 0600 "$gem_credentials"
  cat >"$dir/$name.gemspec" <<EOF
Gem::Specification.new do |spec|
  spec.name = "$name"
  spec.version = "1.0.0"
  spec.summary = "kkrepo client e2e"
  spec.authors = ["kkrepo"]
  spec.files = ["lib/$name.rb"]
  spec.require_paths = ["lib"]
end
EOF
  echo 'module KkRepoClientE2ERubyGems; VALUE = "kkrepo client e2e"; end' >"$dir/lib/$name.rb"
  run_logged_in rubygems-build "$dir" gem build "$name.gemspec" --output "$dir/$name-1.0.0.gem"
  run_logged rubygems-push env HOME="$gem_user_home" gem push "$dir/$name-1.0.0.gem" \
    --host "$KKREPO_URL/repository/rubygems-hosted/" \
    --key kkrepo
  wait_for_body_contains rubygems-versions "$name" \
    "$KKREPO_URL/repository/rubygems-group/versions" \
    "$ARTIFACT_DIR/rubygems-versions"
  capture_rubygems_metadata "$name"
  run_logged rubygems-install env GEM_HOME="$gem_home" GEM_PATH="$gem_home" \
    gem install "$name" --version 1.0.0 --clear-sources \
    --source "$KKREPO_AUTH_URL/repository/rubygems-group/" \
    --no-document --user-install --verbose
  test -f "$gem_home/gems/$name-1.0.0/lib/$name.rb"
}

test_yum() {
  need docker
  local dir="$WORK_DIR/yum"
  local rpm_url="${CLIENT_E2E_YUM_FIXTURE_URL:-https://dl.fedoraproject.org/pub/epel/9/Everything/x86_64/Packages/6/6tunnel-0.13-1.el9.x86_64.rpm}"
  local rpm="$dir/$(basename "$rpm_url")"
  local upload_path="Packages/client-e2e-$STAMP/$(basename "$rpm_url")"
  mkdir -p "$dir"
  run_logged yum-fixture curl -L -m 120 -fsS "$rpm_url" -o "$rpm"
  run_logged yum-upload curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$rpm" \
    "$KKREPO_URL/repository/yum-hosted/$upload_path"
  wait_for_body_contains yum-repomd "primary" \
    "$KKREPO_URL/repository/yum-hosted/repodata/repomd.xml" \
    "$ARTIFACT_DIR/yum-repomd.xml"
  run_logged yum-dnf-download docker run --rm --network host \
    -v "$dir:/work" \
    fedora:41 \
    bash -lc "set -euo pipefail
cat >/etc/yum.repos.d/kkrepo-client-e2e.repo <<'EOF'
[kkrepo-client-e2e]
name=kkrepo client e2e
baseurl=$KKREPO_AUTH_URL/repository/yum-hosted/
enabled=1
gpgcheck=0
EOF
dnf -y --setopt=metadata_expire=0 makecache --repo kkrepo-client-e2e
dnf -y download --repo kkrepo-client-e2e --destdir /work 6tunnel"
  ls "$dir"/6tunnel-*.rpm >/dev/null
}

test_docker_oci() {
  need docker
  wait_for_docker_registry
  local image="kkrepo-client-e2e/docker-oci"
  local ref="$KKREPO_DOCKER_HOSTED_REGISTRY/$image:$STAMP"
  run_logged docker-login bash -lc "printf '%s\n' \"$KKREPO_PASSWORD\" | docker login '$KKREPO_DOCKER_HOSTED_REGISTRY' --username '$KKREPO_USER' --password-stdin"
  run_logged docker-pull-source docker pull alpine:3.20
  run_logged docker-push bash -lc "docker tag alpine:3.20 '$ref' && docker push '$ref'"
  run_logged docker-remove-local docker image rm "$ref"
  run_logged docker-pull docker pull "$ref"
  docker image inspect "$ref" >"$ARTIFACT_DIR/docker-image-inspect.json"
  if command -v oras >/dev/null 2>&1; then
    local oras_dir="$WORK_DIR/oras"
    mkdir -p "$oras_dir/pull"
    echo "kkrepo oci artifact $STAMP" >"$oras_dir/payload.txt"
    run_logged oras-login bash -lc "printf '%s\n' \"$KKREPO_PASSWORD\" | oras login --plain-http '$KKREPO_DOCKER_HOSTED_REGISTRY' --username '$KKREPO_USER' --password-stdin"
    run_logged oras-push oras push --plain-http "$KKREPO_DOCKER_HOSTED_REGISTRY/$image:oras-$STAMP" "$oras_dir/payload.txt:application/vnd.kkrepo.client-e2e"
    run_logged oras-pull oras pull --plain-http "$KKREPO_DOCKER_HOSTED_REGISTRY/$image:oras-$STAMP" -o "$oras_dir/pull"
    test -f "$oras_dir/pull/payload.txt"
  else
    log "oras not found; Docker image client flow completed, ORAS artifact flow skipped"
  fi
}

need curl
need python3

KKREPO_AUTH_URL="$(basic_auth_url)"
add_redaction_value "$KKREPO_AUTH_URL"
wait_for_http "kkrepo management health" "$KKREPO_MANAGEMENT_URL/actuator/health"

test_maven
test_npm
test_pypi
test_go
test_helm
test_cargo
test_nuget
test_rubygems
test_yum
test_docker_oci

log "real client E2E matrix completed"
