#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:${KKREPO_COMPAT_PORT:-18090}}"
SWIFT_KKREPO_URL="${SWIFT_KKREPO_BASE_URL:-$KKREPO_URL}"
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
ARTIFACT_DIR="$(cd "$ARTIFACT_DIR" && pwd)"
WORK_DIR="$(cd "$WORK_DIR" && pwd)"
export CLIENT_E2E_WORK_DIR="$WORK_DIR"
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

run_logged_redacted_output() {
  local name="$1"
  local output="$2"
  shift 2
  local status=0
  run_logged_output "$name" "$output" "$@" || status=$?
  redact_log_file "$output"
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

set_anonymous_access() {
  local enabled="$1"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "{\"enabled\":$enabled}" \
    "$KKREPO_URL/internal/security/anonymous" >/dev/null
}

repository_proxy_remote() {
  local repository="$1"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    "$KKREPO_URL/internal/repositories/$repository" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["proxy"]["remoteUrl"])'
}

set_repository_proxy_remote() {
  local repository="$1"
  local remote_url="$2"
  local payload
  payload="$(python3 - "$remote_url" <<'PY'
import json
import sys
print(json.dumps({"proxy": {"remoteUrl": sys.argv[1]}}, separators=(",", ":")))
PY
)"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KKREPO_URL/internal/repositories/$repository" >/dev/null
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
  # Hosted simple indexes are rebuilt through the shared marker queue. Wait for the
  # uploaded release to become visible before asking pip to resolve it, otherwise a
  # faster client can race a healthy worker on a slower database backend.
  wait_for_body_contains pypi-simple "$name-1.0.0" \
    "$KKREPO_URL/repository/pypi-group/simple/$name/" \
    "$ARTIFACT_DIR/pypi-simple.html"
  run_logged pypi-install python3 -m pip install --disable-pip-version-check --no-deps \
    --target "$install_dir" \
    --index-url "$KKREPO_AUTH_URL/repository/pypi-group/simple/" \
    "$name==1.0.0"
  test -f "$install_dir/$name/__init__.py"
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

test_pub() {
  if ! command -v dart >/dev/null 2>&1; then
    log "dart not found; Pub client flow skipped"
    return 0
  fi
  local dir="$WORK_DIR/pub"
  local package_dir="$dir/package"
  local consumer_dir="$dir/consumer"
  local pub_cache="$WORK_DIR/pub-cache"
  local pub_home="$WORK_DIR/pub-home"
  local package="kkrepo_client_e2e_pub_$STAMP"
  local hosted_url="$KKREPO_URL/repository/pub-hosted"
  local group_url="$KKREPO_URL/repository/pub-group"
  local token
  token="$(create_api_key PubToken "client e2e pub $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$package_dir/lib" "$consumer_dir/bin" "$pub_cache" "$pub_home/.config"

  cat >"$package_dir/pubspec.yaml" <<EOF
name: $package
version: 1.0.0
description: kkRepo client E2E Pub package.
publish_to: "$hosted_url"
environment:
  sdk: ">=3.0.0 <4.0.0"
EOF
  cat >"$package_dir/README.md" <<'EOF'
# kkRepo client E2E Pub package

This package is generated by the kkRepo client E2E suite.
EOF
  cat >"$package_dir/CHANGELOG.md" <<'EOF'
## 1.0.0

- Initial client E2E package.
EOF
  cat >"$package_dir/LICENSE" <<'EOF'
MIT License
EOF
  cat >"$package_dir/lib/$package.dart" <<'EOF'
String message() => 'kkrepo client e2e';
EOF

  run_logged pub-token-hosted env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub token add "$hosted_url" --env-var KKREPO_PUB_TOKEN
  run_logged pub-token-group env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub token add "$group_url" --env-var KKREPO_PUB_TOKEN
  run_logged_in pub-publish "$package_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub publish --force

  cat >"$consumer_dir/pubspec.yaml" <<EOF
name: kkrepo_client_e2e_pub_consumer_$STAMP
version: 1.0.0
environment:
  sdk: ">=3.0.0 <4.0.0"
dependencies:
  $package:
    hosted:
      url: "$group_url"
      name: "$package"
    version: "1.0.0"
EOF
  cat >"$consumer_dir/bin/check.dart" <<EOF
import 'package:$package/$package.dart';

void main() {
  if (message() != 'kkrepo client e2e') {
    throw StateError('unexpected Pub package message');
  }
}
EOF
  run_logged_in pub-get "$consumer_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub get
  run_logged_in pub-run "$consumer_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart run bin/check.dart
  cp "$consumer_dir/pubspec.lock" "$ARTIFACT_DIR/pubspec.lock"

  run_logged_output pub-metadata "$ARTIFACT_DIR/pub-metadata.json" \
    curl -m 10 -fsS -H "Authorization: Bearer $token" \
    "$group_url/api/packages/$package"
  python3 - "$ARTIFACT_DIR/pub-metadata.json" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    data = json.load(source)
versions = {entry.get("version"): entry for entry in data.get("versions", [])}
entry = versions.get("1.0.0")
if entry is None:
    raise SystemExit("Pub metadata did not include version 1.0.0")
sha256 = entry.get("archive_sha256")
if not isinstance(sha256, str) or len(sha256) != 64:
    raise SystemExit("Pub metadata did not include a SHA-256 archive hash")
PY
  test_flutter_pub "$package" "$group_url" "$token" "$pub_home" "$pub_cache"
}

test_composer() {
  need composer
  need php
  local dir="$WORK_DIR/composer"
  local package_dir="$dir/package"
  local consumer_dir="$dir/consumer"
  local composer_home="$WORK_DIR/composer-home"
  local package="kkrepo-client-e2e/package-$STAMP"
  local archive="$dir/package-$STAMP.zip"
  local group_url="$KKREPO_URL/repository/composer-group"
  local auth_host composer_auth
  auth_host="$(python3 - "$KKREPO_URL" <<'PY'
import sys
from urllib.parse import urlsplit
print(urlsplit(sys.argv[1]).netloc)
PY
)"
  composer_auth="$(python3 - "$auth_host" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import json
import sys
host, user, password = sys.argv[1:]
print(json.dumps({"http-basic": {host: {"username": user, "password": password}}}, separators=(",", ":")))
PY
)"
  add_redaction_value "$composer_auth"
  mkdir -p "$package_dir/src" "$consumer_dir" "$composer_home"
  cat >"$package_dir/composer.json" <<EOF
{
  "name": "$package",
  "version": "1.0.0",
  "description": "kkRepo Composer client E2E package",
  "type": "library",
  "license": "MIT",
  "require": {"psr/log": "^3.0"},
  "autoload": {"psr-4": {"KkRepoClientE2E\\\\": "src/"}}
}
EOF
  cat >"$package_dir/src/Message.php" <<'EOF'
<?php
namespace KkRepoClientE2E;
final class Message {
    public static function value(): string { return 'kkrepo client e2e'; }
}
EOF
  python3 - "$package_dir" "$archive" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
archive = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            target.write(path, "package/" + path.relative_to(source).as_posix())
PY
  run_logged_in composer-validate "$package_dir" composer validate --strict --no-check-publish --no-check-version
  run_logged composer-upload curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -F "composer.asset=@$archive;type=application/zip" \
    -F "composer.name=$package" \
    -F "composer.version=1.0.0" \
    "$KKREPO_URL/service/rest/v1/components?repository=composer-hosted"

  cat >"$consumer_dir/composer.json" <<EOF
{
  "name": "kkrepo-client-e2e/consumer-$STAMP",
  "repositories": [
    {"type": "composer", "url": "$group_url", "canonical": true},
    {"packagist.org": false}
  ],
  "require": {
    "$package": "1.0.0"
  },
  "config": {
    "secure-http": false,
    "allow-plugins": {}
  }
}
EOF
  run_logged_in composer-install "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer install --prefer-dist --no-interaction --no-plugins --no-scripts
  run_logged_in composer-show "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer show "$package" --locked --no-interaction
  run_logged_in composer-show-transitive "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer show psr/log --locked --no-interaction
  run_logged_in composer-runtime "$consumer_dir" php -r \
    'require "vendor/autoload.php"; if (KkRepoClientE2E\Message::value() !== "kkrepo client e2e" || Psr\Log\LogLevel::INFO !== "info") { exit(1); }'

  local auth_consumer_dir="$dir/auth-consumer"
  local auth_composer_home="$WORK_DIR/composer-auth-home"
  local wrong_composer_auth auth_status
  wrong_composer_auth="$(python3 - "$auth_host" "$KKREPO_USER" <<'PY'
import json
import sys
host, user = sys.argv[1:]
print(json.dumps({"http-basic": {host: {"username": user, "password": "definitely-wrong"}}}, separators=(",", ":")))
PY
)"
  mkdir -p "$auth_consumer_dir" "$auth_composer_home"
  cp "$consumer_dir/composer.json" "$auth_consumer_dir/composer.json"
  set_anonymous_access false
  if run_logged_in composer-auth-rejected "$auth_consumer_dir" env \
      COMPOSER_HOME="$auth_composer_home" \
      COMPOSER_AUTH="$wrong_composer_auth" \
      composer --no-cache install --prefer-dist --no-interaction --no-plugins --no-scripts; then
    auth_status=0
  else
    auth_status=$?
  fi
  set_anonymous_access true
  if [[ "$auth_status" -eq 0 ]]; then
    log "Composer install unexpectedly succeeded with invalid credentials"
    exit 1
  fi
  if ! grep -Eqi '401|authentication required|invalid credentials' "$ARTIFACT_DIR/composer-auth-rejected.log"; then
    log "Composer invalid-credentials failure did not report authentication rejection"
    exit 1
  fi

  cp "$consumer_dir/composer.lock" "$ARTIFACT_DIR/composer.lock"
  run_logged_in composer-clear-cache "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer clear-cache --no-interaction
  rm -rf "$consumer_dir/vendor" "$composer_home/cache"
  local composer_proxy_remote replay_status
  composer_proxy_remote="$(repository_proxy_remote composer-proxy)"
  set_repository_proxy_remote composer-proxy "https://example.com/kkrepo-composer-client-e2e-offline/"
  if run_logged_in composer-lock-replay-offline "$consumer_dir" env \
      COMPOSER_HOME="$composer_home" \
      COMPOSER_AUTH="$composer_auth" \
      composer install --prefer-dist --no-interaction --no-plugins --no-scripts; then
    replay_status=0
  else
    replay_status=$?
  fi
  set_repository_proxy_remote composer-proxy "$composer_proxy_remote"
  if [[ "$replay_status" -ne 0 ]]; then
    log "Composer lock replay failed after client cache clear with proxy upstream unavailable"
    exit "$replay_status"
  fi
  test -f "$consumer_dir/vendor/kkrepo-client-e2e/package-$STAMP/src/Message.php"
  test -f "$consumer_dir/vendor/psr/log/src/LogLevel.php"
  run_logged_output composer-packages "$ARTIFACT_DIR/composer-packages.json" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" "$group_url/packages.json"
  run_logged_output composer-hosted-metadata "$ARTIFACT_DIR/composer-hosted-metadata.json" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" "$group_url/p2/$package.json"
  grep -q '"version":"1.0.0"' "$ARTIFACT_DIR/composer-hosted-metadata.json"
}

test_flutter_pub() {
  local package="$1"
  local group_url="$2"
  local token="$3"
  local pub_home="$4"
  local pub_cache="$5"
  if ! command -v flutter >/dev/null 2>&1; then
    log "flutter not found; Flutter Pub client flow skipped"
    return 0
  fi
  local flutter_dir="$WORK_DIR/flutter-pub"
  mkdir -p "$flutter_dir"
  cat >"$flutter_dir/pubspec.yaml" <<EOF
name: kkrepo_client_e2e_flutter_pub_$STAMP
version: 1.0.0
environment:
  sdk: ">=3.0.0 <4.0.0"
dependencies:
  flutter:
    sdk: flutter
  path: ^1.9.0
  $package:
    hosted:
      url: "$group_url"
      name: "$package"
    version: "1.0.0"
EOF
  run_logged_in flutter-pub-get "$flutter_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    PUB_HOSTED_URL="$group_url" \
    KKREPO_PUB_TOKEN="$token" \
    flutter pub get
  cp "$flutter_dir/pubspec.lock" "$ARTIFACT_DIR/flutter-pubspec.lock"
  grep -q "$package" "$ARTIFACT_DIR/flutter-pubspec.lock"
  grep -q "path" "$ARTIFACT_DIR/flutter-pubspec.lock"
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
    --no-document --verbose
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
    run_logged_in oras-push "$oras_dir" oras push --plain-http "$KKREPO_DOCKER_HOSTED_REGISTRY/$image:oras-$STAMP" "payload.txt:application/vnd.kkrepo.client-e2e"
    run_logged_in oras-pull "$oras_dir" oras pull --plain-http "$KKREPO_DOCKER_HOSTED_REGISTRY/$image:oras-$STAMP" -o "$oras_dir/pull"
    test -f "$oras_dir/pull/payload.txt"
  else
    log "oras not found; Docker image client flow completed, ORAS artifact flow skipped"
  fi
}

test_terraform() {
  need zip
  local terraform_013="${TERRAFORM_013_BIN:-}"
  local terraform_current="${TERRAFORM_CURRENT_BIN:-}"
  if [[ -z "$terraform_013" || ! -x "$terraform_013" ]]; then
    log "TERRAFORM_013_BIN must point to an executable Terraform 0.13 binary"
    exit 2
  fi
  if [[ -z "$terraform_current" || ! -x "$terraform_current" ]]; then
    log "TERRAFORM_CURRENT_BIN must point to an executable current stable Terraform binary"
    exit 2
  fi

  local dir="$WORK_DIR/terraform"
  local fixture_version="1.0.$STAMP"
  local fixture_os="${TERRAFORM_E2E_OS:-$(uname -s | tr '[:upper:]' '[:lower:]')}"
  local fixture_arch="${TERRAFORM_E2E_ARCH:-$(uname -m)}"
  [[ "$fixture_arch" == "x86_64" ]] && fixture_arch="amd64"
  [[ "$fixture_arch" == "aarch64" ]] && fixture_arch="arm64"
  local module_dir="$dir/module"
  local provider_dir="$dir/provider"
  local module_zip="$dir/kkrepo-client-e2e-module_${fixture_version}.zip"
  local provider_zip="$dir/terraform-provider-fixture_${fixture_version}_${fixture_os}_${fixture_arch}.zip"
  local token config basic_token basic_token_encoded module_headers module_download_url
  mkdir -p "$module_dir" "$provider_dir"

  cat >"$module_dir/main.tf" <<'EOF'
output "message" {
  value = "kkrepo Terraform module client e2e"
}
EOF
  run_terraform_fixture
}

file_sha256() {
  python3 - "$1" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
}

assert_swift_registry_pin() {
  local resolved_file="$1"
  local scope="$2"
  local package_name="$3"
  local expected_version="$4"
  local label="$5"
  python3 - "$resolved_file" "$scope" "$package_name" "$expected_version" "$label" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
scope, package_name, expected_version, label = sys.argv[2:]
payload = json.loads(path.read_text(encoding="utf-8"))
pins = payload.get("pins")
if pins is None:
    pins = payload.get("object", {}).get("pins")
if not isinstance(pins, list):
    raise SystemExit(f"{label}: unsupported Package.resolved shape in {path}")

coordinate = f"{scope}.{package_name}".lower()
package_name_lower = package_name.lower()

def target(pin):
    identity = str(pin.get("identity") or pin.get("package") or "").lower()
    location = str(pin.get("location") or pin.get("repositoryURL") or "").lower()
    return identity in {package_name_lower, coordinate} or location.rstrip("/") in {
        coordinate,
        f"https://github.com/{scope}/{package_name}.git".lower(),
        f"https://github.com/{scope}/{package_name}".lower(),
    }

matches = [pin for pin in pins if isinstance(pin, dict) and target(pin)]
if not matches:
    summary = [
        {
            "identity": pin.get("identity") or pin.get("package"),
            "kind": pin.get("kind"),
            "location": pin.get("location") or pin.get("repositoryURL"),
        }
        for pin in pins if isinstance(pin, dict)
    ]
    raise SystemExit(f"{label}: target dependency is absent from Package.resolved: {summary}")

registry_matches = [pin for pin in matches if pin.get("kind") == "registry"]
if not registry_matches:
    raise SystemExit(
        f"{label}: target dependency did not resolve through the registry: {matches}"
    )
if not any(str(pin.get("state", {}).get("version") or "") == expected_version
           for pin in registry_matches):
    raise SystemExit(
        f"{label}: registry pin does not contain expected version {expected_version}: "
        f"{registry_matches}"
    )
PY
}

swift_version_line() {
  "$1" --version | head -n 1
}

swift_proxy_fixture_version() {
  local swift_bin="$1"
  if [[ -n "${SWIFT_E2E_PROXY_VERSION:-}" ]]; then
    printf '%s' "$SWIFT_E2E_PROXY_VERSION"
    return 0
  fi

  local version_line major minor
  version_line="$(swift_version_line "$swift_bin")"
  if [[ "$version_line" =~ Swift[[:space:]]version[[:space:]]([0-9]+)\.([0-9]+) ]]; then
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    if (( major < 5 || (major == 5 && minor < 9) )); then
      # swift-log 1.6.x requires swift-tools-version 5.9. Keep the oldest
      # registry clients on the latest fixture they can actually resolve.
      printf '1.5.4'
      return 0
    fi
  fi
  printf '1.6.3'
}

swift_registry_set() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  local help
  local write_config=false
  help="$("$swift_bin" package-registry set --help 2>&1 || true)"
  if [[ "$registry" == *://*'@'* ]]; then
    write_config=true
  elif [[ "$registry" == https://* ]]; then
    run_logged_in "swift-$label-registry-set" "$directory" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry set "$registry/"
  elif grep -q -- '--allow-insecure-http' <<<"$help"; then
    run_logged_in "swift-$label-registry-set" "$directory" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry set --allow-insecure-http "$registry/"
  else
    write_config=true
  fi
  if [[ "$write_config" == "true" ]]; then
    local config_file="$directory/.swiftpm/configuration/registries.json"
    mkdir -p "$(dirname "$config_file")"
    run_logged "swift-$label-registry-config" python3 - "$config_file" "$registry/" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({
    "registries": {"[default]": {"url": sys.argv[2]}},
    "version": 1,
}, separators=(",", ":")), encoding="utf-8")
PY
    chmod 0600 "$config_file"
  fi
}

swift_registry_login() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  run_logged_in "swift-$label-registry-login" "$directory" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package-registry login "$registry/login" \
    --username "$KKREPO_USER" --password "$KKREPO_PASSWORD" --no-confirm
}

assert_swift_invalid_login() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  local status=0
  if run_logged_in "swift-$label-registry-login-invalid" "$directory" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry login "$registry/login" \
      --username "$KKREPO_USER" --password 'definitely-invalid' --no-confirm; then
    status=0
  else
    status=$?
  fi
  if [[ "$status" -eq 0 ]]; then
    log "Swift $label registry login unexpectedly accepted invalid credentials"
    return 1
  fi
  grep -Eqi '401|unauthorized|authentication|login failed|invalid credentials' \
    "$ARTIFACT_DIR/swift-$label-registry-login-invalid.log"
}

swift_registry_token_login() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  local token
  token="$(create_api_key GenericToken "Swift client E2E $label $STAMP")"
  add_redaction_value "$token"
  run_logged_in "swift-$label-registry-token-login" "$directory" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package-registry login "$registry/login" \
    --token "$token" --no-confirm
}

swift_supports_registry_login() {
  local swift_bin="$1"
  local registry="$2"
  local help
  [[ "$registry" == https://* ]] || return 1
  help="$("$swift_bin" package-registry login --help 2>&1 || true)"
  grep -q -- '--username' <<<"$help" \
    && grep -q -- '--password' <<<"$help" \
    && grep -q -- '--no-confirm' <<<"$help"
}

swift_supports_registry_publish() {
  local help
  help="$("$1" package-registry publish --help 2>&1 || true)"
  grep -q -- '--url' <<<"$help" \
    && grep -q -- '--metadata-path' <<<"$help" \
    && grep -q -- '--scratch-directory' <<<"$help"
}

authenticated_registry_url() {
  local registry="$1"
  local repository_base_url="${2:-$KKREPO_URL}"
  local authenticated_base_url
  if [[ "$registry" != "$repository_base_url"* ]]; then
    log "cannot embed credentials into registry outside repository base URL: $registry"
    return 2
  fi
  authenticated_base_url="$(python3 - "$repository_base_url" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import sys
import urllib.parse

url = urllib.parse.urlsplit(sys.argv[1])
username = urllib.parse.quote(sys.argv[2], safe="")
password = urllib.parse.quote(sys.argv[3], safe="")
print(urllib.parse.urlunsplit((url.scheme, f"{username}:{password}@{url.netloc}", url.path, url.query, url.fragment)))
PY
)"
  printf '%s%s' "$authenticated_base_url" "${registry#"$repository_base_url"}"
}

swift_registry_login_is_required() {
  local label="$1"
  case ",${SWIFT_E2E_REQUIRE_REGISTRY_LOGIN_LABELS:-}," in
    *,"$label",*) return 0 ;;
    *) return 1 ;;
  esac
}

is_windows_runner() {
  [[ "${OS:-}" == "Windows_NT" ]] || [[ "$(uname -s)" =~ ^(MINGW|MSYS|CYGWIN) ]]
}

test_swift_proxy_binary() {
  local label="$1"
  local swift_bin="$2"
  local home="$3"
  local dir="$4"
  if [[ "${SWIFT_E2E_PROXY_ENABLED:-true}" != "true" ]]; then
    log "Swift $label SCM-to-registry proxy flow skipped by SWIFT_E2E_PROXY_ENABLED=false"
    return 0
  fi
  local group_url="$SWIFT_KKREPO_URL/repository/swift-group"
  local proxy_dir="$dir/proxy-consumer"
  local proxy_scope="${SWIFT_E2E_PROXY_SCOPE:-apple}"
  local proxy_name="${SWIFT_E2E_PROXY_NAME:-swift-log}"
  local proxy_version
  proxy_version="$(swift_proxy_fixture_version "$swift_bin")"
  mkdir -p "$proxy_dir/Sources/ProxyConsumer"
  cat >"$proxy_dir/Package.swift" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "ProxyConsumer",
    dependencies: [
        .package(url: "https://github.com/$proxy_scope/$proxy_name.git", exact: "$proxy_version")
    ],
    targets: [
        .executableTarget(
            name: "ProxyConsumer",
            dependencies: [.product(name: "Logging", package: "$proxy_name")]
        )
    ]
)
EOF
  cat >"$proxy_dir/Sources/ProxyConsumer/main.swift" <<'EOF'
import Logging
var logger = Logger(label: "kkrepo.swift.client-e2e")
logger.info("Swift proxy client E2E")
EOF
  swift_registry_set "$label-proxy-group" "$swift_bin" "$proxy_dir" "$home" "$group_url"
  run_logged_in "swift-$label-proxy-resolve" "$proxy_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package resolve --replace-scm-with-registry
  run_logged_in "swift-$label-proxy-build" "$proxy_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" build --replace-scm-with-registry
  cp "$proxy_dir/Package.resolved" "$ARTIFACT_DIR/swift-$label-proxy-Package.resolved"
  assert_swift_registry_pin "$ARTIFACT_DIR/swift-$label-proxy-Package.resolved" \
    "$proxy_scope" "$proxy_name" "$proxy_version" "Swift $label proxy"
}

test_swift_binary() {
  local label="$1"
  local swift_bin="$2"
  local ordinal="$3"
  local label_slug package_name module version dir package_dir consumer_dir home target_declaration
  local hosted_url="$SWIFT_KKREPO_URL/repository/swift-hosted"
  local group_url="$SWIFT_KKREPO_URL/repository/swift-group"
  local hosted_access_url group_access_url
  label_slug="$(printf '%s' "$label" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-//;s/-$//')"
  package_name="client-e2e-${STAMP}-${label_slug:-swift}"
  module="KkRepoSwiftE2E${ordinal}"
  version="1.0.$ordinal"
  dir="$WORK_DIR/swift-$label_slug"
  package_dir="$dir/package"
  consumer_dir="$dir/consumer"
  home="$dir/home"
  mkdir -p "$package_dir/Sources/$module" "$consumer_dir/Sources/Consumer" "$home"
  if is_windows_runner; then
    log "Swift $label hosted publish skipped on Windows; running documented proxy resolve/build path"
    test_swift_proxy_binary "$label" "$swift_bin" "$home" "$dir"
    return 0
  fi
  if ! swift_supports_registry_publish "$swift_bin"; then
    log "Swift $label has no package-registry publish command; running its supported proxy resolve/build path"
    test_swift_proxy_binary "$label" "$swift_bin" "$home" "$dir"
    return 0
  fi

  target_declaration=".target(name: \"$module\")"
  if [[ "${SWIFT_E2E_LARGE_FIXTURE_BYTES:-0}" =~ ^[0-9]+$ ]] \
      && [[ "${SWIFT_E2E_LARGE_FIXTURE_BYTES:-0}" -gt 0 ]]; then
    target_declaration=".target(name: \"$module\", resources: [.copy(\"LargeFixture.bin\")])"
    python3 - "$package_dir/Sources/$module/LargeFixture.bin" \
        "$SWIFT_E2E_LARGE_FIXTURE_BYTES" <<'PY'
import hashlib
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
remaining = int(sys.argv[2])
counter = 0
with path.open("wb") as stream:
    while remaining:
        block = hashlib.sha256(f"kkrepo-swift-e2e-{counter}".encode()).digest()
        chunk = block[:remaining]
        stream.write(chunk)
        remaining -= len(chunk)
        counter += 1
PY
  fi
  cat >"$package_dir/Package.swift" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "$module",
    products: [.library(name: "$module", targets: ["$module"])],
    targets: [$target_declaration]
)
EOF
  cat >"$package_dir/Sources/$module/$module.swift" <<EOF
public enum $module {
    public static let marker = "kkrepo Swift client E2E $label"
}
EOF
  cat >"$package_dir/Package@swift-5.9.swift" <<EOF
// swift-tools-version:5.9
import PackageDescription
let package = Package(
    name: "$module",
    products: [.library(name: "$module", targets: ["$module"])],
    targets: [$target_declaration]
)
EOF
  cat >"$package_dir/package-metadata.json" <<EOF
{
  "description": "kkrepo Swift client E2E $label",
  "repositoryURLs": ["https://github.com/kkrepo-fixtures/$package_name.git"]
}
EOF

  run_logged_in "swift-$label-version" "$package_dir" "$swift_bin" --version
  hosted_access_url="$hosted_url"
  if swift_supports_registry_login "$swift_bin" "$hosted_url"; then
    swift_registry_set "$label-hosted" "$swift_bin" "$package_dir" "$home" "$hosted_url"
    swift_registry_login "$label-hosted" "$swift_bin" "$package_dir" "$home" "$hosted_url"
    local invalid_login_home="$dir/invalid-login-home"
    mkdir -p "$invalid_login_home"
    assert_swift_invalid_login "$label-hosted" "$swift_bin" "$package_dir" \
      "$invalid_login_home" "$hosted_url"
    swift_registry_token_login "$label-hosted" "$swift_bin" "$package_dir" \
      "$home" "$hosted_url"
  else
    if swift_registry_login_is_required "$label"; then
      log "Swift $label must execute package-registry login over HTTPS, but the command or HTTPS registry is unavailable"
      return 2
    fi
    hosted_access_url="$(authenticated_registry_url "$hosted_url" "$SWIFT_KKREPO_URL")"
    add_redaction_value "$hosted_access_url"
    swift_registry_set "$label-hosted-embedded-auth" \
      "$swift_bin" "$package_dir" "$home" "$hosted_access_url"
    log "Swift $label registry login CLI skipped: it requires HTTPS and Swift 5.8+; embedded credentials are used"
  fi
  local -a publish_transport_args=()
  if [[ "$hosted_access_url" == http://* ]]; then
    publish_transport_args+=(--allow-insecure-http)
  fi
  mkdir -p "$dir/publish-scratch" "$dir/duplicate-scratch"
  run_logged_in "swift-$label-publish" "$package_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package-registry publish "kkrepo.$package_name" "$version" \
    --url "$hosted_access_url/" --metadata-path "$package_dir/package-metadata.json" \
    --scratch-directory "$dir/publish-scratch" "${publish_transport_args[@]}"

  local duplicate_status=0
  if run_logged_in "swift-$label-publish-duplicate" "$package_dir" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry publish "kkrepo.$package_name" "$version" \
      --url "$hosted_access_url/" --metadata-path "$package_dir/package-metadata.json" \
      --scratch-directory "$dir/duplicate-scratch" "${publish_transport_args[@]}"; then
    duplicate_status=0
  else
    duplicate_status=$?
  fi
  if [[ "$duplicate_status" -eq 0 ]]; then
    log "Swift $label duplicate publish unexpectedly succeeded"
    return 1
  fi
  if ! grep -Eqi '409|conflict|already exists|already published' \
      "$ARTIFACT_DIR/swift-$label-publish-duplicate.log"; then
    log "Swift $label duplicate publish did not report immutable conflict"
    return 1
  fi

  run_logged_output "swift-$label-release-list" "$ARTIFACT_DIR/swift-$label-releases.json" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+json' \
    "$group_url/kkrepo/$package_name"
  run_logged_output "swift-$label-release-metadata" "$ARTIFACT_DIR/swift-$label-metadata.json" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+json' \
    "$group_url/kkrepo/$package_name/$version"
  run_logged_output "swift-$label-manifest" "$ARTIFACT_DIR/swift-$label-Package.swift" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+swift' \
    "$group_url/kkrepo/$package_name/$version/Package.swift"
  run_logged_output "swift-$label-versioned-manifest" \
    "$ARTIFACT_DIR/swift-$label-Package@swift-5.9.swift" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+swift' \
    "$group_url/kkrepo/$package_name/$version/Package.swift?swift-version=5.9"
  run_logged_output "swift-$label-archive" "$ARTIFACT_DIR/swift-$label-source.zip" \
    curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+zip' \
    "$group_url/kkrepo/$package_name/$version.zip"
  python3 - "$ARTIFACT_DIR/swift-$label-releases.json" \
      "$ARTIFACT_DIR/swift-$label-metadata.json" \
      "$ARTIFACT_DIR/swift-$label-source.zip" "$version" <<'PY'
import hashlib
import json
import pathlib
import sys

releases = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
metadata = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
archive = pathlib.Path(sys.argv[3]).read_bytes()
version = sys.argv[4]
assert version in releases["releases"]
resource = next(item for item in metadata["resources"] if item["name"] == "source-archive")
assert resource["type"] == "application/zip"
assert resource["checksum"] == hashlib.sha256(archive).hexdigest()
PY
  cmp "$package_dir/Package.swift" "$ARTIFACT_DIR/swift-$label-Package.swift"
  cmp "$package_dir/Package@swift-5.9.swift" \
    "$ARTIFACT_DIR/swift-$label-Package@swift-5.9.swift"

  cat >"$consumer_dir/Package.swift" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "Consumer",
    dependencies: [.package(id: "kkrepo.$package_name", exact: "$version")],
    targets: [
        .executableTarget(
            name: "Consumer",
            dependencies: [.product(name: "$module", package: "kkrepo.$package_name")]
        )
    ]
)
EOF
  cat >"$consumer_dir/Sources/Consumer/main.swift" <<EOF
import $module
print($module.marker)
EOF
  group_access_url="$group_url"
  if swift_supports_registry_login "$swift_bin" "$group_url"; then
    swift_registry_set "$label-group" "$swift_bin" "$consumer_dir" "$home" "$group_url"
    swift_registry_login "$label-group" "$swift_bin" "$consumer_dir" "$home" "$group_url"
  else
    group_access_url="$(authenticated_registry_url "$group_url" "$SWIFT_KKREPO_URL")"
    add_redaction_value "$group_access_url"
    swift_registry_set "$label-group-embedded-auth" \
      "$swift_bin" "$consumer_dir" "$home" "$group_access_url"
  fi
  run_logged_in "swift-$label-resolve" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package resolve
  run_logged_in "swift-$label-build" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" build
  cp "$consumer_dir/Package.resolved" "$ARTIFACT_DIR/swift-$label-Package.resolved"
  assert_swift_registry_pin "$ARTIFACT_DIR/swift-$label-Package.resolved" \
    kkrepo "$package_name" "$version" "Swift $label hosted"
  local first_lock_hash
  first_lock_hash="$(file_sha256 "$consumer_dir/Package.resolved")"
  rm -rf "$consumer_dir/.build"
  run_logged_in "swift-$label-resolve-replay" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package resolve
  [[ "$first_lock_hash" == "$(file_sha256 "$consumer_dir/Package.resolved")" ]]
  run_logged_in "swift-$label-build-replay" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" build

  if [[ -n "${SWIFT_KKREPO_SECONDARY_BASE_URL:-}" ]]; then
    local secondary_consumer_dir="$dir/secondary-consumer"
    local secondary_home="$dir/secondary-home"
    local secondary_group_url="${SWIFT_KKREPO_SECONDARY_BASE_URL%/}/repository/swift-group"
    mkdir -p "$secondary_consumer_dir/Sources" "$secondary_home"
    cp "$consumer_dir/Package.swift" "$secondary_consumer_dir/Package.swift"
    cp -R "$consumer_dir/Sources/Consumer" "$secondary_consumer_dir/Sources/Consumer"
    swift_registry_set "$label-secondary-group" "$swift_bin" \
      "$secondary_consumer_dir" "$secondary_home" "$secondary_group_url"
    run_logged_in "swift-$label-secondary-resolve" "$secondary_consumer_dir" env \
      HOME="$secondary_home" XDG_CONFIG_HOME="$secondary_home/.config" \
      "$swift_bin" package resolve
    run_logged_in "swift-$label-secondary-build" "$secondary_consumer_dir" env \
      HOME="$secondary_home" XDG_CONFIG_HOME="$secondary_home/.config" \
      "$swift_bin" build
    cp "$secondary_consumer_dir/Package.resolved" \
      "$ARTIFACT_DIR/swift-$label-secondary-Package.resolved"
    assert_swift_registry_pin "$ARTIFACT_DIR/swift-$label-secondary-Package.resolved" \
      kkrepo "$package_name" "$version" "Swift $label secondary hosted"
  fi

  if [[ "$(uname -s)" == "Darwin" && -z "${SWIFT_XCODE_E2E_GENERATED_PACKAGE:-}" ]]; then
    export SWIFT_XCODE_E2E_GENERATED_PACKAGE="$consumer_dir"
    export SWIFT_XCODE_E2E_GENERATED_SCHEME=Consumer
    export SWIFT_XCODE_E2E_GENERATED_HOME="$home"
    export SWIFT_XCODE_E2E_GENERATED_SCOPE=kkrepo
    export SWIFT_XCODE_E2E_GENERATED_NAME="$package_name"
    export SWIFT_XCODE_E2E_GENERATED_VERSION="$version"
  fi

  test_swift_proxy_binary "$label" "$swift_bin" "$home" "$dir"
}

test_swift_xcode() {
  local project="${SWIFT_XCODE_E2E_PROJECT:-}"
  local package="${SWIFT_XCODE_E2E_PACKAGE:-${SWIFT_XCODE_E2E_GENERATED_PACKAGE:-}}"
  local xcodebuild_bin=""
  xcodebuild_bin="$(command -v xcodebuild 2>/dev/null || true)"
  if [[ "$(uname -s)" != "Darwin" || -z "$xcodebuild_bin" || ! -x "$xcodebuild_bin" ]]; then
    if [[ "${SWIFT_E2E_REQUIRE_XCODE:-false}" == "true" ]]; then
      log "Xcode Swift registry flow is required but xcodebuild is unavailable"
      return 2
    fi
    log "Xcode Swift registry flow skipped: xcodebuild is only available on macOS runners"
    return 0
  fi
  if ! "$xcodebuild_bin" -version >/dev/null 2>&1; then
    if [[ "${SWIFT_E2E_REQUIRE_XCODE:-false}" == "true" ]]; then
      log "Xcode Swift registry flow is required but the active developer directory is not a full Xcode installation"
      return 2
    fi
    log "Xcode Swift registry flow skipped: the active developer directory is not a full Xcode installation"
    return 0
  fi
  if [[ -z "$project" && -z "$package" ]]; then
    if [[ "${SWIFT_E2E_REQUIRE_XCODE:-false}" == "true" ]]; then
      log "Xcode Swift registry flow is required but no generated package or project is available"
      return 2
    fi
    log "Xcode Swift registry flow skipped: no registry fixture package or project is available"
    return 0
  fi

  if [[ -n "$project" ]]; then
    local -a project_command=(xcodebuild -resolvePackageDependencies -project "$project")
    if [[ -n "${SWIFT_XCODE_E2E_SCHEME:-}" ]]; then
      project_command+=( -scheme "$SWIFT_XCODE_E2E_SCHEME" )
    fi
    run_logged swift-xcode-resolve "${project_command[@]}"
    return 0
  fi

  local scheme="${SWIFT_XCODE_E2E_SCHEME:-${SWIFT_XCODE_E2E_GENERATED_SCHEME:-Consumer}}"
  local xcode_home="${SWIFT_XCODE_E2E_HOME:-${SWIFT_XCODE_E2E_GENERATED_HOME:-$HOME}}"
  local xcode_source_packages="$WORK_DIR/xcode-source-packages"
  local xcode_derived_data="$WORK_DIR/xcode-derived-data"
  local xcode_config="$package/.swiftpm/configuration/registries.json"
  mkdir -p "$(dirname "$xcode_config")" "$xcode_source_packages" "$xcode_derived_data"
  run_logged swift-xcode-registry-config python3 - "$xcode_config" \
      "$SWIFT_KKREPO_URL/repository/swift-group/" <<'PY'
import json
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "registries": {"[default]": {"url": sys.argv[2]}},
    "version": 1,
}, separators=(",", ":")), encoding="utf-8")
PY
  rm -rf "$package/.build"
  rm -f "$package/Package.resolved"
  run_logged_in swift-xcode-resolve "$package" \
    env HOME="$xcode_home" XDG_CONFIG_HOME="$xcode_home/.config" \
    xcodebuild -resolvePackageDependencies \
    -scheme "$scheme" \
    -clonedSourcePackagesDirPath "$xcode_source_packages"
  run_logged_in swift-xcode-build "$package" \
    env HOME="$xcode_home" XDG_CONFIG_HOME="$xcode_home/.config" \
    xcodebuild \
    -scheme "$scheme" \
    -destination 'platform=macOS' \
    -derivedDataPath "$xcode_derived_data" \
    -clonedSourcePackagesDirPath "$xcode_source_packages" \
    CODE_SIGNING_ALLOWED=NO \
    build

  local resolved_file
  resolved_file="$(find "$package" -name Package.resolved -type f -print | head -n 1)"
  if [[ -z "$resolved_file" ]]; then
    log "Xcode did not produce Package.resolved for the registry fixture"
    return 1
  fi
  cp "$resolved_file" "$ARTIFACT_DIR/swift-xcode-Package.resolved"
  if [[ -n "${SWIFT_XCODE_E2E_GENERATED_NAME:-}" ]]; then
    assert_swift_registry_pin "$ARTIFACT_DIR/swift-xcode-Package.resolved" \
      "${SWIFT_XCODE_E2E_GENERATED_SCOPE:-kkrepo}" \
      "$SWIFT_XCODE_E2E_GENERATED_NAME" \
      "$SWIFT_XCODE_E2E_GENERATED_VERSION" \
      "Xcode hosted"
  else
    grep -Eqi '"kind"[[:space:]]*:[[:space:]]*"registry"' \
      "$ARTIFACT_DIR/swift-xcode-Package.resolved"
  fi
}

test_swift() {
  local configured_bins="${SWIFT_E2E_BINS:-}"
  local -a entries=()
  local entry label swift_bin actual_version
  if [[ -n "$configured_bins" ]]; then
    IFS=',' read -r -a entries <<<"$configured_bins"
  elif command -v swift >/dev/null 2>&1; then
    entries=("current=$(command -v swift)")
  else
    log "Swift client matrix skipped: install Swift or set SWIFT_E2E_BINS"
    return 0
  fi

  : >"$ARTIFACT_DIR/swift-client-matrix.tsv"
  local ordinal=0
  for entry in "${entries[@]}"; do
    entry="${entry#${entry%%[![:space:]]*}}"
    entry="${entry%${entry##*[![:space:]]}}"
    [[ -z "$entry" ]] && continue
    if [[ "$entry" != *=* ]]; then
      log "invalid SWIFT_E2E_BINS entry '$entry'; expected label=/path/to/swift"
      return 2
    fi
    label="${entry%%=*}"
    swift_bin="${entry#*=}"
    if [[ ! -x "$swift_bin" ]]; then
      log "Swift $label binary is not executable: $swift_bin"
      return 2
    fi
    actual_version="$(swift_version_line "$swift_bin")"
    printf '%s\t%s\t%s\n' "$label" "$swift_bin" "$actual_version" \
      >>"$ARTIFACT_DIR/swift-client-matrix.tsv"
    ordinal=$((ordinal + 1))
    test_swift_binary "$label" "$swift_bin" "$ordinal"
  done
  [[ "$ordinal" -gt 0 ]]

  if [[ "${SWIFT_E2E_REQUIRE_WINDOWS:-false}" == "true" ]] && ! is_windows_runner; then
    log "strict Windows Swift lane requires a native Windows runner"
    return 2
  fi

  if [[ "${SWIFT_E2E_REQUIRE_5_7_5_9_6:-false}" == "true" ]]; then
    python3 - "$ARTIFACT_DIR/swift-client-matrix.tsv" <<'PY'
import re
import sys

rows = []
for line in open(sys.argv[1], encoding="utf-8"):
    label, binary, reported = line.rstrip("\n").split("\t", 2)
    match = re.search(r"Swift version (\d+)\.(\d+)", reported)
    if not match:
        raise SystemExit(f"cannot parse Swift version for {label}: {reported}")
    rows.append((label, int(match.group(1)), int(match.group(2))))
if not any(label == "5.7" and (major, minor) == (5, 7) for label, major, minor in rows):
    raise SystemExit("strict Swift matrix requires a real 5.7 toolchain labeled 5.7")
if not any(label in {"5.9", "5.9+"} and major == 5 and minor >= 9
           for label, major, minor in rows):
    raise SystemExit("strict Swift matrix requires a real 5.9+ toolchain labeled 5.9 or 5.9+")
if not any(label in {"6", "6.x"} and major == 6 for label, major, minor in rows):
    raise SystemExit("strict Swift matrix requires a real Swift 6 toolchain labeled 6 or 6.x")
PY
  fi
  test_swift_xcode
}

run_terraform_fixture() {
  run_logged_in terraform-module-archive "$module_dir" zip -q -r "$module_zip" .

  cat >"$provider_dir/terraform-provider-fixture_v$fixture_version" <<'EOF'
#!/usr/bin/env sh
echo "kkrepo Terraform provider fixture is install-only" >&2
exit 1
EOF
  chmod +x "$provider_dir/terraform-provider-fixture_v$fixture_version"
  run_logged_in terraform-provider-archive "$provider_dir" zip -q "$provider_zip" "terraform-provider-fixture_v$fixture_version"

  run_logged terraform-module-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$module_zip" \
    "$KKREPO_URL/repository/terraform-hosted/v1/modules/kkrepo/client-e2e/aws/$fixture_version/$(basename "$module_zip")"
  run_logged terraform-provider-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H "Content-Disposition: attachment; filename=$(basename "$provider_zip")" \
    --upload-file "$provider_zip" \
    "$KKREPO_URL/repository/terraform-hosted/v1/providers/kkrepo/fixture/$fixture_version/download/$fixture_os/$fixture_arch"

  basic_token="$(printf '%s' "$KKREPO_AUTH" | base64 | tr -d '\r\n')"
  basic_token_encoded="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$basic_token")"
  add_redaction_value "$basic_token"
  add_redaction_value "$basic_token_encoded"
  module_headers="$dir/basic-module.headers"
  run_logged terraform-basic-module-metadata curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -D "$module_headers" -o /dev/null \
    "$KKREPO_URL/repository/terraform-hosted/v1/modules/kkrepo/client-e2e/aws/$fixture_version/download"
  module_download_url="$(awk 'BEGIN { IGNORECASE=1 } /^X-Terraform-Get:/ { sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit }' "$module_headers")"
  [[ "$module_download_url" == *"/$basic_token_encoded/"* ]]
  run_logged terraform-basic-module-followup curl -m 20 --fail-with-body -sS \
    -o "$dir/basic-module.zip" "$module_download_url"
  cmp "$module_zip" "$dir/basic-module.zip"

  run_logged_output terraform-basic-provider-metadata "$dir/basic-provider.json" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/terraform-hosted/v1/providers/kkrepo/fixture/$fixture_version/download/$fixture_os/$fixture_arch"
  while IFS= read -r provider_url; do
    [[ "$provider_url" == *"/$basic_token_encoded/"* ]]
    run_logged "terraform-basic-provider-followup-$(basename "$provider_url")" \
      curl -m 20 --fail-with-body -sS -o /dev/null "$provider_url"
  done < <(python3 - "$dir/basic-provider.json" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
for field in ("download_url", "shasums_url", "shasums_signature_url"):
    print(document[field])
PY
  )

  token="$(create_api_key GenericToken "client e2e terraform $STAMP")"
  add_redaction_value "$token"
  config="$dir/terraform.rc"
  cat >"$config" <<EOF
disable_checkpoint = true
host "registry.terraform.io" {
  services = {
    "modules.v1"   = "$KKREPO_URL/repository/terraform-group/v1/modules/$token/"
    "providers.v1" = "$KKREPO_URL/repository/terraform-group/v1/providers/$token/"
  }
}
EOF

  local version_label terraform_bin init_dir
  for version_label in 0.13 current; do
    terraform_bin="$terraform_current"
    [[ "$version_label" == "0.13" ]] && terraform_bin="$terraform_013"
    init_dir="$dir/init-$version_label"
    mkdir -p "$init_dir"
    cat >"$init_dir/main.tf" <<EOF
terraform {
  required_providers {
    fixture = {
      source  = "registry.terraform.io/kkrepo/fixture"
      version = "$fixture_version"
    }
    null = {
      source  = "registry.terraform.io/hashicorp/null"
      version = "3.2.4"
    }
  }
}

module "hosted" {
  source  = "registry.terraform.io/kkrepo/client-e2e/aws"
  version = "$fixture_version"
}
EOF
    run_logged_in "terraform-$version_label-version" "$init_dir" "$terraform_bin" version
    run_logged_in "terraform-$version_label-init" "$init_dir" env \
      TF_CLI_CONFIG_FILE="$config" CHECKPOINT_DISABLE=1 \
      "$terraform_bin" init -backend=false -input=false -no-color
    test -d "$init_dir/.terraform/modules/hosted"
    local provider_root="$init_dir/.terraform/providers"
    [[ "$version_label" == "0.13" ]] && provider_root="$init_dir/.terraform/plugins"
    test -d "$provider_root/registry.terraform.io/kkrepo/fixture/$fixture_version/${fixture_os}_${fixture_arch}"
    test -d "$provider_root/registry.terraform.io/hashicorp/null/3.2.4/${fixture_os}_${fixture_arch}"
  done

  run_logged_redacted_output terraform-provider-metadata "$ARTIFACT_DIR/terraform-provider-metadata.json" \
    curl -m 20 -fsS \
    "$KKREPO_URL/repository/terraform-group/v1/providers/$token/kkrepo/fixture/$fixture_version/download/$fixture_os/$fixture_arch"
  python3 - "$ARTIFACT_DIR/terraform-provider-metadata.json" "$fixture_version" "$fixture_os" "$fixture_arch" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
assert document["filename"].startswith(f"terraform-provider-fixture_{sys.argv[2]}_{sys.argv[3]}_{sys.argv[4]}")
assert document["shasum"]
assert document["signing_keys"]["gpg_public_keys"][0]["ascii_armor"].startswith("-----BEGIN PGP PUBLIC KEY BLOCK-----")
PY
}

run_selected_tests() {
  local selection="${CLIENT_E2E_TESTS:-all}"
  local -a tests=()
  local test

  if [[ -z "$selection" || "$selection" == "all" ]]; then
    tests=(maven npm pypi go helm cargo pub composer nuget rubygems yum terraform swift docker-oci)
  else
    IFS=',' read -r -a tests <<<"$selection"
  fi

  for test in "${tests[@]}"; do
    test="${test//[[:space:]]/}"
    case "$test" in
      maven)
        test_maven
        ;;
      npm)
        test_npm
        ;;
      pypi)
        test_pypi
        ;;
      go)
        test_go
        ;;
      helm)
        test_helm
        ;;
      cargo)
        test_cargo
        ;;
      pub|dart-pub|flutter-pub)
        test_pub
        ;;
      composer|php)
        test_composer
        ;;
      nuget)
        test_nuget
        ;;
      rubygems|ruby)
        test_rubygems
        ;;
      yum)
        test_yum
        ;;
      terraform)
        test_terraform
        ;;
      swift|swiftpm)
        test_swift
        ;;
      docker|docker-oci|oci)
        test_docker_oci
        ;;
      "")
        ;;
      *)
        log "unknown client E2E test: $test"
        exit 2
        ;;
    esac
  done
}

need curl
need python3

KKREPO_AUTH_URL="$(basic_auth_url)"
add_redaction_value "$KKREPO_AUTH_URL"
wait_for_http "kkrepo management health" "$KKREPO_MANAGEMENT_URL/actuator/health"

run_selected_tests

log "real client E2E matrix completed"
