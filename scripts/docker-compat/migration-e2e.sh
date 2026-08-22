#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NEXUS_URL="${NEXUS_COMPAT_BASE_URL:-http://localhost:28090}"
NEXUS_REPOSITORY="${DOCKER_MIGRATION_NEXUS_REPOSITORY:-docker-hosted}"
CARGO_NEXUS_REPOSITORY="${CARGO_MIGRATION_NEXUS_REPOSITORY:-cargo-hosted}"
PUB_NEXUS_REPOSITORY="${PUB_MIGRATION_NEXUS_REPOSITORY:-pub-hosted}"
COMPOSER_NEXUS_REPOSITORY="${COMPOSER_MIGRATION_NEXUS_REPOSITORY:-composer-proxy}"
TERRAFORM_NEXUS_REPOSITORY="${TERRAFORM_MIGRATION_NEXUS_REPOSITORY:-terraform-compat-hosted}"
TERRAFORM_PROXY_NEXUS_REPOSITORY="${TERRAFORM_PROXY_MIGRATION_NEXUS_REPOSITORY:-terraform-compat-proxy}"
SWIFT_NEXUS_REPOSITORY="${SWIFT_MIGRATION_NEXUS_REPOSITORY:-swift-hosted}"
SWIFT_PROXY_NEXUS_REPOSITORY="${SWIFT_PROXY_MIGRATION_NEXUS_REPOSITORY:-swift-proxy}"
SWIFT_GROUP_NEXUS_REPOSITORY="${SWIFT_GROUP_MIGRATION_NEXUS_REPOSITORY:-swift-group}"
ANSIBLE_NEXUS_REPOSITORY="${ANSIBLE_MIGRATION_NEXUS_REPOSITORY:-ansible-hosted}"
ANSIBLE_PROXY_NEXUS_REPOSITORY="${ANSIBLE_PROXY_MIGRATION_NEXUS_REPOSITORY:-ansible-proxy}"
ANSIBLE_GROUP_NEXUS_REPOSITORY="${ANSIBLE_GROUP_MIGRATION_NEXUS_REPOSITORY:-ansible-group}"
ANSIBLE_SECRET_PROXY_NEXUS_REPOSITORY="${ANSIBLE_SECRET_PROXY_MIGRATION_NEXUS_REPOSITORY:-ansible-secret-proxy}"
CONDA_NEXUS_REPOSITORY="${CONDA_MIGRATION_NEXUS_REPOSITORY:-conda-hosted}"
CONDA_PROXY_NEXUS_REPOSITORY="${CONDA_PROXY_MIGRATION_NEXUS_REPOSITORY:-conda-proxy}"
CONDA_GROUP_NEXUS_REPOSITORY="${CONDA_GROUP_MIGRATION_NEXUS_REPOSITORY:-conda-group}"
APT_NEXUS_REPOSITORY="${APT_MIGRATION_NEXUS_REPOSITORY:-apt-hosted}"
ALPINE_NEXUS_REPOSITORY="${ALPINE_MIGRATION_NEXUS_REPOSITORY:-alpine-migration-hosted}"
ALPINE_PROXY_NEXUS_REPOSITORY="${ALPINE_PROXY_MIGRATION_NEXUS_REPOSITORY:-alpine-migration-proxy}"
ALPINE_GROUP_NEXUS_REPOSITORY="${ALPINE_GROUP_MIGRATION_NEXUS_REPOSITORY:-alpine-migration-group}"
R_NEXUS_REPOSITORY="${R_MIGRATION_NEXUS_REPOSITORY:-r-migration-hosted}"
R_PROXY_NEXUS_REPOSITORY="${R_PROXY_MIGRATION_NEXUS_REPOSITORY:-r-migration-proxy}"
R_GROUP_NEXUS_REPOSITORY="${R_GROUP_MIGRATION_NEXUS_REPOSITORY:-r-migration-group}"
NEXUS_USER="${NEXUS_COMPAT_USERNAME:-admin}"
NEXUS_PASSWORD="${NEXUS_COMPAT_PASSWORD:-Admin1234}"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:18090}"
KKREPO_HEALTH_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:18091}/actuator/health"
KKREPO_DOCKER_REGISTRY="${DOCKER_MIGRATION_KKREPO_REGISTRY:-127.0.0.1:18183}"
KKREPO_REPOSITORY="${DOCKER_MIGRATION_KKREPO_REPOSITORY:-docker-hosted}"
CARGO_KKREPO_REPOSITORY="${CARGO_MIGRATION_KKREPO_REPOSITORY:-cargo-hosted}"
PUB_KKREPO_REPOSITORY="${PUB_MIGRATION_KKREPO_REPOSITORY:-pub-hosted}"
COMPOSER_KKREPO_REPOSITORY="${COMPOSER_MIGRATION_KKREPO_REPOSITORY:-composer-proxy}"
TERRAFORM_KKREPO_REPOSITORY="${TERRAFORM_MIGRATION_KKREPO_REPOSITORY:-terraform-compat-hosted}"
TERRAFORM_PROXY_KKREPO_REPOSITORY="${TERRAFORM_PROXY_MIGRATION_KKREPO_REPOSITORY:-terraform-compat-proxy}"
SWIFT_KKREPO_REPOSITORY="${SWIFT_MIGRATION_KKREPO_REPOSITORY:-swift-hosted}"
SWIFT_PROXY_KKREPO_REPOSITORY="${SWIFT_PROXY_MIGRATION_KKREPO_REPOSITORY:-swift-proxy}"
SWIFT_GROUP_KKREPO_REPOSITORY="${SWIFT_GROUP_MIGRATION_KKREPO_REPOSITORY:-swift-group}"
ANSIBLE_KKREPO_REPOSITORY="${ANSIBLE_MIGRATION_KKREPO_REPOSITORY:-ansible-hosted}"
ANSIBLE_PROXY_KKREPO_REPOSITORY="${ANSIBLE_PROXY_MIGRATION_KKREPO_REPOSITORY:-ansible-proxy}"
ANSIBLE_GROUP_KKREPO_REPOSITORY="${ANSIBLE_GROUP_MIGRATION_KKREPO_REPOSITORY:-ansible-group}"
ANSIBLE_SECRET_PROXY_KKREPO_REPOSITORY="${ANSIBLE_SECRET_PROXY_MIGRATION_KKREPO_REPOSITORY:-ansible-secret-proxy}"
CONDA_KKREPO_REPOSITORY="${CONDA_MIGRATION_KKREPO_REPOSITORY:-conda-hosted}"
CONDA_PROXY_KKREPO_REPOSITORY="${CONDA_PROXY_MIGRATION_KKREPO_REPOSITORY:-conda-proxy}"
CONDA_GROUP_KKREPO_REPOSITORY="${CONDA_GROUP_MIGRATION_KKREPO_REPOSITORY:-conda-group}"
APT_KKREPO_REPOSITORY="${APT_MIGRATION_KKREPO_REPOSITORY:-apt-hosted}"
ALPINE_KKREPO_REPOSITORY="${ALPINE_MIGRATION_KKREPO_REPOSITORY:-alpine-migration-hosted}"
ALPINE_PROXY_KKREPO_REPOSITORY="${ALPINE_PROXY_MIGRATION_KKREPO_REPOSITORY:-alpine-migration-proxy}"
ALPINE_GROUP_KKREPO_REPOSITORY="${ALPINE_GROUP_MIGRATION_KKREPO_REPOSITORY:-alpine-migration-group}"
R_KKREPO_REPOSITORY="${R_MIGRATION_KKREPO_REPOSITORY:-r-migration-hosted}"
R_PROXY_KKREPO_REPOSITORY="${R_PROXY_MIGRATION_KKREPO_REPOSITORY:-r-migration-proxy}"
R_GROUP_KKREPO_REPOSITORY="${R_GROUP_MIGRATION_KKREPO_REPOSITORY:-r-migration-group}"
KKREPO_SECONDARY_URL="${KKREPO_MIGRATION_SECONDARY_URL:-}"
KKREPO_TARGET_DATABASE="${KKREPO_MIGRATION_TARGET_DATABASE:-mysql}"
KKREPO_TARGET_DATABASE_SERVICE="${KKREPO_MIGRATION_TARGET_DATABASE_SERVICE:-mysql}"
if [[ "$KKREPO_TARGET_DATABASE" == "postgresql" ]]; then
  KKREPO_PRIMARY_SERVICE="${KKREPO_MIGRATION_PRIMARY_SERVICE:-kkrepo-postgresql}"
else
  KKREPO_PRIMARY_SERVICE="${KKREPO_MIGRATION_PRIMARY_SERVICE:-kkrepo}"
fi
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_BLOB_PATH="${KKREPO_COMPAT_BLOB_PATH:-/tmp/kkrepo-blobs/default}"
EXPECTED_ADAPTER="${MIGRATION_E2E_EXPECTED_ADAPTER:-}"
EXPECTED_CONNECTOR_PORT="${KKREPO_DOCKER_CONNECTOR_PORT:-18180}"

IMAGE="${DOCKER_MIGRATION_IMAGE:-kkrepo-migration/e2e}"
TAG="${DOCKER_MIGRATION_TAG:-$(date +%Y%m%d%H%M%S)}"
TAG_SAFE="${TAG//[^A-Za-z0-9_]/_}"
TAG_SAFE_LC="$(printf '%s' "$TAG_SAFE" | tr '[:upper:]' '[:lower:]')"
CARGO_CRATE="${CARGO_MIGRATION_CRATE:-kkrepo_migration_e2e_${TAG_SAFE}}"
CARGO_VERSION="${CARGO_MIGRATION_VERSION:-0.1.0}"
PUB_PACKAGE="${PUB_MIGRATION_PACKAGE:-kkrepo_migration_e2e_${TAG_SAFE_LC}}"
PUB_VERSION="${PUB_MIGRATION_VERSION:-0.1.0}"
COMPOSER_MIGRATION_ENABLED="${COMPOSER_MIGRATION_ENABLED:-false}"
COMPOSER_PACKAGE="${COMPOSER_MIGRATION_PACKAGE:-psr/log}"
SWIFT_MIGRATION_ENABLED="${SWIFT_MIGRATION_ENABLED:-false}"
SWIFT_SCOPE="${SWIFT_MIGRATION_SCOPE:-kkrepo}"
SWIFT_PACKAGE="${SWIFT_MIGRATION_PACKAGE:-migration-${TAG_SAFE_LC}}"
SWIFT_PACKAGE="${SWIFT_PACKAGE//_/-}"
SWIFT_PACKAGE="${SWIFT_PACKAGE:0:90}"
SWIFT_VERSION="${SWIFT_MIGRATION_VERSION:-1.2.3}"
SWIFT_PROXY_USERNAME="${SWIFT_MIGRATION_PROXY_USERNAME:-swift-migration-user}"
SWIFT_PROXY_SECRET="${SWIFT_MIGRATION_PROXY_SECRET:-swift-migration-password-not-for-production}"
SWIFT_METADATA_DESCRIPTION="kkrepo Swift migration e2e fixture"
SWIFT_METADATA_PUBLICATION_TIME="2025-02-03T04:05:06Z"
SWIFT_FIXTURE_WORKDIR=""
SWIFT_FIXTURE_ARCHIVE=""
SWIFT_FIXTURE_SIGNATURE=""
SWIFT_FIXTURE_MANIFEST=""
SWIFT_FIXTURE_VERSIONED_MANIFEST=""
SWIFT_FIXTURE_SHA256=""
SWIFT_FIXTURE_SIGNATURE_BASE64=""
SWIFT_MIGRATED_PUBLISHED_AT=""
ANSIBLE_MIGRATION_ENABLED="${ANSIBLE_MIGRATION_ENABLED:-false}"
ANSIBLE_NAMESPACE="${ANSIBLE_MIGRATION_NAMESPACE:-kkrepo}"
ANSIBLE_COLLECTION="${ANSIBLE_MIGRATION_COLLECTION:-migration_${TAG_SAFE_LC}}"
ANSIBLE_COLLECTION="${ANSIBLE_COLLECTION:0:64}"
ANSIBLE_VERSION="${ANSIBLE_MIGRATION_VERSION:-1.2.3}"
ANSIBLE_PROXY_NAMESPACE="${ANSIBLE_PROXY_MIGRATION_NAMESPACE:-community}"
ANSIBLE_PROXY_COLLECTION="${ANSIBLE_PROXY_MIGRATION_COLLECTION:-general}"
ANSIBLE_PROXY_VERSION="${ANSIBLE_PROXY_MIGRATION_VERSION:-10.4.0}"
ANSIBLE_SECRET_PROXY_USERNAME="${ANSIBLE_SECRET_PROXY_MIGRATION_USERNAME:-ansible-migration-user}"
ANSIBLE_SECRET_PROXY_SECRET="${ANSIBLE_SECRET_PROXY_MIGRATION_SECRET:-ansible-migration-password-not-for-production}"
ANSIBLE_FIXTURE_WORKDIR=""
ANSIBLE_FIXTURE_ARCHIVE=""
ANSIBLE_FIXTURE_SHA256=""
ANSIBLE_FIXTURE_FILES_JSON_SIZE=""
ANSIBLE_PROXY_FIXTURE_ARCHIVE=""
ANSIBLE_PROXY_FIXTURE_SHA256=""
CONDA_MIGRATION_ENABLED="${CONDA_MIGRATION_ENABLED:-false}"
CONDA_BIN="${CONDA_E2E_BIN:-${CONDA_BIN:-conda}}"
CONDA_PACKAGE="${CONDA_MIGRATION_PACKAGE:-kkrepo_migration_e2e_${TAG_SAFE_LC}}"
CONDA_PACKAGE="${CONDA_PACKAGE:0:64}"
CONDA_VERSION="${CONDA_MIGRATION_VERSION:-1.2.3}"
CONDA_BUILD="${CONDA_MIGRATION_BUILD:-0}"
CONDA_SUBDIR="${CONDA_MIGRATION_SUBDIR:-linux-64}"
CONDA_FIXTURE_WORKDIR=""
CONDA_FIXTURE_ARCHIVE=""
CONDA_FIXTURE_SHA256=""
CONDA_FIXTURE_MARKER="kkrepo Conda Nexus migration E2E $TAG_SAFE_LC"
APT_MIGRATION_ENABLED="${APT_MIGRATION_ENABLED:-false}"
APT_PACKAGE="${APT_MIGRATION_PACKAGE:-kkrepo-apt-migration}"
APT_VERSION="${APT_MIGRATION_VERSION:-1:1.2.3~rc1-2}"
APT_ARCHITECTURE="${APT_MIGRATION_ARCHITECTURE:-amd64}"
APT_FIXTURE_MARKER="${APT_MIGRATION_MARKER:-kkRepo Nexus APT migration E2E}"
APT_FIXTURE_WORKDIR="${APT_MIGRATION_FIXTURE_DIR:-${RUNNER_TEMP:-/tmp}/kkrepo-apt-migration-fixture}"
APT_FIXTURE_MANIFEST="$APT_FIXTURE_WORKDIR/fixture.json"
APT_FIXTURE_ARCHIVE=""
APT_FIXTURE_PRIVATE_KEY="$APT_FIXTURE_WORKDIR/private.asc"
APT_FIXTURE_PUBLIC_KEY="$APT_FIXTURE_WORKDIR/public.asc"
APT_FIXTURE_SHA256=""
APT_FIXTURE_PACKAGE_PATH=""
ALPINE_MIGRATION_ENABLED="${ALPINE_MIGRATION_ENABLED:-false}"
ALPINE_BASE_PACKAGE="${ALPINE_MIGRATION_BASE_PACKAGE:-kkrepo-alpine-migration-base-${TAG_SAFE_LC}}"
ALPINE_APP_PACKAGE="${ALPINE_MIGRATION_APP_PACKAGE:-kkrepo-alpine-migration-app-${TAG_SAFE_LC}}"
ALPINE_VERSION="${ALPINE_MIGRATION_VERSION:-1.2.3-r0}"
ALPINE_DISTRIBUTION="${ALPINE_MIGRATION_DISTRIBUTION:-v3.23}"
ALPINE_CHANNEL="${ALPINE_MIGRATION_CHANNEL:-main}"
ALPINE_ARCHITECTURE="${ALPINE_MIGRATION_ARCHITECTURE:-x86_64}"
ALPINE_CLIENT_IMAGE="${ALPINE_MIGRATION_CLIENT_IMAGE:-alpine:3.23}"
ALPINE_FIXTURE_WORKDIR=""
ALPINE_FIXTURE_PRIVATE_KEY=""
ALPINE_FIXTURE_PUBLIC_KEY=""
ALPINE_FIXTURE_KEY_FILENAME=""
ALPINE_PACKAGE_KEY_FILENAME="kkrepo-alpine-migration.rsa.pub"
ALPINE_BASE_ARCHIVE=""
ALPINE_APP_ARCHIVE=""
ALPINE_BASE_SHA256=""
ALPINE_APP_SHA256=""
ALPINE_BASE_MESSAGE="kkRepo Alpine migration dependency $TAG_SAFE_LC"
ALPINE_APP_MESSAGE="kkRepo Alpine migration application $TAG_SAFE_LC"
R_MIGRATION_ENABLED="${R_MIGRATION_ENABLED:-false}"
R_MIGRATION_UPSTREAM_CONTAINER="${R_MIGRATION_UPSTREAM_CONTAINER:-${COMPOSE_PROJECT_NAME:-kkrepo-compat}-r-upstream}"
R_MIGRATION_UPSTREAM_URL="${R_MIGRATION_UPSTREAM_URL:-http://${R_MIGRATION_UPSTREAM_CONTAINER}:8080/}"
R_TAG_ALNUM="$(printf '%s' "$TAG_SAFE" | tr -cd 'A-Za-z0-9' | cut -c1-24)"
R_BASE_PACKAGE="${R_MIGRATION_BASE_PACKAGE:-kkrepoRmigrationbase${R_TAG_ALNUM}}"
R_APP_PACKAGE="${R_MIGRATION_APP_PACKAGE:-kkrepoRmigrationapp${R_TAG_ALNUM}}"
R_BASE_VERSION="${R_MIGRATION_BASE_VERSION:-1.0.0}"
R_APP_OLD_VERSION="${R_MIGRATION_APP_OLD_VERSION:-1.0.0}"
R_APP_VERSION="${R_MIGRATION_APP_VERSION:-1.1.0}"
R_CLIENT_IMAGES="${R_MIGRATION_CLIENT_IMAGES:-4.5.3=r-base:4.5.3,4.6.1=r-base:4.6.1}"
R_FIXTURE_WORKDIR=""
R_BASE_ARCHIVE=""
R_APP_OLD_ARCHIVE=""
R_APP_ARCHIVE=""
R_APP_SHA256=""
R_APP_MESSAGE="kkRepo R migration application $TAG_SAFE_LC"
TERRAFORM_PROXY_PROVIDER_NAMESPACE="${TERRAFORM_PROXY_PROVIDER_NAMESPACE:-hashicorp}"
TERRAFORM_PROXY_PROVIDER_NAME="${TERRAFORM_PROXY_PROVIDER_NAME:-null}"
TERRAFORM_PROXY_PROVIDER_VERSION="${TERRAFORM_PROXY_PROVIDER_VERSION:-3.2.4}"
TERRAFORM_PROXY_PROVIDER_PATH=""
TERRAFORM_PROXY_PROVIDER_FILENAME=""
TERRAFORM_PROXY_PROVIDER_SHA256=""
PAGE_SIZE="${DOCKER_MIGRATION_PAGE_SIZE:-500}"
CONCURRENCY="${DOCKER_MIGRATION_CONCURRENCY:-2}"
WAIT_TIMEOUT_SECONDS="${DOCKER_MIGRATION_WAIT_TIMEOUT_SECONDS:-300}"

log() {
  printf '[docker-migration-e2e] %s\n' "$*" >&2
}

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "missing required command: $1"
    exit 2
  fi
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "$value"
}

sql_literal() {
  local value="$1"
  value=${value//\'/\'\'}
  printf "'%s'" "$value"
}

target_db_query() {
  local query="$1"
  case "$KKREPO_TARGET_DATABASE" in
    mysql)
      docker compose -f "${COMPOSE_FILE:-docker-compose.compat.yml}" exec -T \
        -e MYSQL_PWD=kkrepo "$KKREPO_TARGET_DATABASE_SERVICE" \
        mysql -ukkrepo -Dkkrepo -N -B -e "$query"
      ;;
    postgresql)
      docker compose -f "${COMPOSE_FILE:-docker-compose.compat.yml}" exec -T \
        -e PGPASSWORD=kkrepo "$KKREPO_TARGET_DATABASE_SERVICE" \
        psql -U kkrepo -d kkrepo -A -t -F $'\t' -c "$query"
      ;;
    *)
      log "unsupported kkrepo migration target database: $KKREPO_TARGET_DATABASE"
      exit 1
      ;;
  esac
}

wait_for_http() {
  local label="$1"
  local url="$2"
  local auth="${3:-}"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    if [[ -n "$auth" ]]; then
      if curl -m 5 -fsS -u "$auth" "$url" >/dev/null 2>&1; then
        log "$label is ready"
        return 0
      fi
    elif curl -m 5 -fsS "$url" >/dev/null 2>&1; then
      log "$label is ready"
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label at $url"
  exit 1
}

docker_login() {
  local registry="$1"
  local username="$2"
  local password="$3"
  log "docker login $registry"
  printf '%s\n' "$password" | docker login "$registry" --username "$username" --password-stdin >/dev/null
}

file_size() {
  wc -c <"$1" | tr -d '[:space:]'
}

file_sha256() {
  shasum -a 256 "$1" | awk '{print $1}'
}

file_sha1() {
  shasum -a 1 "$1" | awk '{print $1}'
}

append_query() {
  local url="$1"
  local key_value="$2"
  if [[ "$url" == *"?"* ]]; then
    printf '%s&%s' "$url" "$key_value"
  else
    printf '%s?%s' "$url" "$key_value"
  fi
}

absolute_location() {
  local location="$1"
  if [[ "$location" == http://* || "$location" == https://* ]]; then
    printf '%s' "$location"
  elif [[ "$location" == /* ]]; then
    printf '%s%s' "${NEXUS_URL%/}" "$location"
  else
    printf '%s/%s' "${NEXUS_URL%/}" "$location"
  fi
}

header_location() {
  awk 'BEGIN{IGNORECASE=1} /^Location:/ {
    sub(/\r$/, "")
    sub(/^[^:]+:[[:space:]]*/, "")
    print
  }' "$1" | tail -n 1
}

header_value() {
  local name="$1"
  local headers="$2"
  awk -v wanted="$name" 'BEGIN{IGNORECASE=1} {
    line=$0
    sub(/\r$/, "", line)
    separator=index(line, ":")
    if (separator > 0 && tolower(substr(line, 1, separator - 1)) == tolower(wanted)) {
      value=substr(line, separator + 1)
      sub(/^[[:space:]]*/, "", value)
      found=value
    }
  } END { print found }' "$headers"
}

cleanup() {
  if [[ -n "$SWIFT_FIXTURE_WORKDIR" ]]; then
    rm -rf "$SWIFT_FIXTURE_WORKDIR"
  fi
  if [[ -n "$ANSIBLE_FIXTURE_WORKDIR" ]]; then
    rm -rf "$ANSIBLE_FIXTURE_WORKDIR"
  fi
  if [[ -n "$CONDA_FIXTURE_WORKDIR" ]]; then
    rm -rf "$CONDA_FIXTURE_WORKDIR"
  fi
  if [[ "$APT_MIGRATION_ENABLED" == "true" ]]; then
    rm -f \
      "$APT_FIXTURE_WORKDIR/private.asc" \
      "$APT_FIXTURE_WORKDIR/public.asc" \
      "$APT_FIXTURE_WORKDIR/fixture.json" \
      "$APT_FIXTURE_WORKDIR/key-import.json" \
      "$APT_FIXTURE_WORKDIR/target-repository.json" \
      "$APT_FIXTURE_WORKDIR/target-job.json" \
      "$APT_FIXTURE_WORKDIR/target-Packages" \
      "$APT_FIXTURE_WORKDIR/target-package.deb" \
      "$APT_FIXTURE_WORKDIR/source-Packages" \
      "$APT_FIXTURE_WORKDIR/source-package.deb"
    if [[ -n "$APT_FIXTURE_ARCHIVE" ]]; then
      rm -f "$APT_FIXTURE_ARCHIVE"
    fi
    rmdir "$APT_FIXTURE_WORKDIR" >/dev/null 2>&1 || true
  fi
  if [[ -n "$ALPINE_FIXTURE_WORKDIR" ]]; then
    rm -rf "$ALPINE_FIXTURE_WORKDIR"
  fi
  if [[ -n "$R_FIXTURE_WORKDIR" ]]; then
    rm -rf "$R_FIXTURE_WORKDIR"
  fi
  if [[ "$R_MIGRATION_ENABLED" == "true" ]]; then
    docker rm -f "$R_MIGRATION_UPSTREAM_CONTAINER" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT

expect_status() {
  local status="$1"
  local expected="$2"
  local action="$3"
  if [[ "$status" != "$expected" ]]; then
    log "$action returned HTTP $status, expected $expected"
    exit 1
  fi
}

upload_source_blob() {
  local image="$1"
  local file="$2"
  local digest="$3"
  local upload_url="${NEXUS_URL%/}/repository/${NEXUS_REPOSITORY}/v2/${image}/blobs/uploads/"
  local complete_url status

  complete_url="$(append_query "$upload_url" "digest=$digest")"
  status="$(curl -m 60 -sS -o /dev/null -w '%{http_code}' \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X POST \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"$file" \
    "$complete_url")"
  expect_status "$status" "201" "complete source blob upload"
}

put_source_manifest() {
  local image="$1"
  local tag="$2"
  local manifest_file="$3"
  local manifest_url="${NEXUS_URL%/}/repository/${NEXUS_REPOSITORY}/v2/${image}/manifests/${tag}"
  local status
  status="$(curl -m 60 -sS -o /dev/null -w '%{http_code}' \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X PUT \
    -H "Content-Type: application/vnd.docker.distribution.manifest.v2+json" \
    --data-binary @"$manifest_file" \
    "$manifest_url")"
  expect_status "$status" "201" "put source manifest"
}

push_fixture_to_source_nexus() {
  local image="$1"
  local tag="$2"
  local workdir layer_tar layer_gz config manifest
  local layer_diff_id layer_digest layer_size config_digest config_size manifest_digest

  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-docker-migration.XXXXXX")"
  layer_tar="$workdir/layer.tar"
  layer_gz="$workdir/layer.tar.gz"
  config="$workdir/config.json"
  manifest="$workdir/manifest.json"

  dd if=/dev/zero of="$layer_tar" bs=1024 count=10 >/dev/null 2>&1
  gzip -n -c "$layer_tar" >"$layer_gz"
  layer_diff_id="sha256:$(file_sha256 "$layer_tar")"
  layer_digest="sha256:$(file_sha256 "$layer_gz")"
  layer_size="$(file_size "$layer_gz")"

  printf '{"created":"2026-06-23T00:00:00Z","architecture":"amd64","os":"linux","config":{},"rootfs":{"type":"layers","diff_ids":["%s"]},"history":[{"created":"2026-06-23T00:00:00Z","created_by":"kkrepo docker migration e2e"}]}' \
    "$layer_diff_id" >"$config"
  config_digest="sha256:$(file_sha256 "$config")"
  config_size="$(file_size "$config")"

  cat >"$manifest" <<EOF
{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json","config":{"mediaType":"application/vnd.docker.container.image.v1+json","size":${config_size},"digest":"${config_digest}"},"layers":[{"mediaType":"application/vnd.docker.image.rootfs.diff.tar.gzip","size":${layer_size},"digest":"${layer_digest}"}]}
EOF
  manifest_digest="sha256:$(file_sha256 "$manifest")"

  log "uploading fixture config blob to source Nexus: $config_digest"
  upload_source_blob "$image" "$config" "$config_digest"
  log "uploading fixture layer blob to source Nexus: $layer_digest"
  upload_source_blob "$image" "$layer_gz" "$layer_digest"
  log "putting fixture manifest to source Nexus: $image:$tag $manifest_digest"
  put_source_manifest "$image" "$tag" "$manifest"

  rm -rf "$workdir"
  printf '%s' "$manifest_digest"
}

cargo_migration_enabled() {
  [[ "$EXPECTED_ADAPTER" == "DatastoreH2NexusAdapter"
    || "$EXPECTED_ADAPTER" == "DatastorePostgresqlNexusAdapter"
    || "${NEXUS_COMPAT_IMAGE:-}" == *3.92*
    || "${NEXUS_COMPAT_IMAGE:-}" == *3.77* ]]
}

cargo_index_path() {
  local crate="$1"
  python3 - "$crate" <<'PY'
import sys
name = sys.argv[1]
lower = name.lower()
if len(lower) == 1:
    print("1/" + lower)
elif len(lower) == 2:
    print("2/" + lower)
elif len(lower) == 3:
    print("3/" + lower[0] + "/" + lower)
else:
    print(lower[0:2] + "/" + lower[2:4] + "/" + lower)
PY
}

source_cargo_available() {
  curl -m 20 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/repositories/cargo/hosted/$CARGO_NEXUS_REPOSITORY" >/dev/null 2>&1
}

pub_migration_enabled() {
  [[ "${NEXUS_COMPAT_IMAGE:-}" == *3.92* ]]
}

source_pub_available() {
  curl -m 20 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/repositories/pub/hosted/$PUB_NEXUS_REPOSITORY" >/dev/null 2>&1
}

composer_migration_enabled() {
  [[ "$COMPOSER_MIGRATION_ENABLED" == "true" ]]
}

source_composer_available() {
  curl -m 20 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/repositories/composer/proxy/$COMPOSER_NEXUS_REPOSITORY" >/dev/null 2>&1
}

terraform_migration_enabled() {
  [[ "${NEXUS_COMPAT_IMAGE:-}" == *3.92* && -n "${TERRAFORM_CURRENT_BIN:-}" ]]
}

source_terraform_available() {
  curl -m 20 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/repositories/terraform/hosted/$TERRAFORM_NEXUS_REPOSITORY" >/dev/null 2>&1
}

source_terraform_proxy_available() {
  curl -m 20 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/repositories/terraform/proxy/$TERRAFORM_PROXY_NEXUS_REPOSITORY" >/dev/null 2>&1
}

swift_migration_enabled() {
  [[ "$SWIFT_MIGRATION_ENABLED" == "true" ]]
}

ansible_migration_enabled() {
  [[ "$ANSIBLE_MIGRATION_ENABLED" == "true" ]]
}

conda_migration_enabled() {
  [[ "$CONDA_MIGRATION_ENABLED" == "true" ]]
}

apt_migration_enabled() {
  [[ "$APT_MIGRATION_ENABLED" == "true" ]]
}

alpine_migration_enabled() {
  [[ "$ALPINE_MIGRATION_ENABLED" == "true" ]]
}

r_migration_enabled() {
  [[ "$R_MIGRATION_ENABLED" == "true" ]]
}

source_apt_available() {
  curl -m 20 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/repositories/apt/hosted/$APT_NEXUS_REPOSITORY" \
    >/dev/null 2>&1
}

load_apt_fixture() {
  if [[ ! -s "$APT_FIXTURE_MANIFEST" \
      || ! -s "$APT_FIXTURE_PRIVATE_KEY" \
      || ! -s "$APT_FIXTURE_PUBLIC_KEY" ]]; then
    log "APT migration fixture is incomplete in $APT_FIXTURE_WORKDIR"
    exit 1
  fi
  local fixture_repository fixture_package fixture_version fixture_architecture
  local fixture_filename fixture_path fixture_marker
  IFS=$'\t' read -r \
    fixture_repository fixture_package fixture_version fixture_architecture \
    fixture_filename fixture_path APT_FIXTURE_SHA256 APT_FIXTURE_SIZE fixture_marker \
    < <(python3 - "$APT_FIXTURE_MANIFEST" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
fields = [
    "repository", "package", "version", "architecture", "filename",
    "packagePath", "sha256", "size", "marker",
]
values = [str(payload.get(field, "")) for field in fields]
if any(not value for value in values):
    raise SystemExit(f"APT fixture manifest is incomplete: {payload}")
print("\t".join(values))
PY
)
  if [[ "$fixture_repository" != "$APT_NEXUS_REPOSITORY" \
      || "$fixture_package" != "$APT_PACKAGE" \
      || "$fixture_version" != "$APT_VERSION" \
      || "$fixture_architecture" != "$APT_ARCHITECTURE" \
      || "$fixture_marker" != "$APT_FIXTURE_MARKER" ]]; then
    log "APT fixture manifest does not match the configured migration coordinates"
    exit 1
  fi
  APT_FIXTURE_ARCHIVE="$APT_FIXTURE_WORKDIR/$fixture_filename"
  APT_FIXTURE_PACKAGE_PATH="$fixture_path"
  if [[ ! -s "$APT_FIXTURE_ARCHIVE" ]]; then
    log "APT fixture archive is missing: $APT_FIXTURE_ARCHIVE"
    exit 1
  fi
  if [[ "$(file_size "$APT_FIXTURE_ARCHIVE")" != "$APT_FIXTURE_SIZE" \
      || "$(file_sha256 "$APT_FIXTURE_ARCHIVE")" != "$APT_FIXTURE_SHA256" ]]; then
    log "APT fixture archive size or SHA-256 does not match its manifest"
    exit 1
  fi
}

assert_apt_packages_file() {
  local packages_file="$1"
  local expected_path="$2"
  python3 - \
    "$packages_file" "$APT_PACKAGE" "$APT_VERSION" "$APT_ARCHITECTURE" \
    "$APT_FIXTURE_SHA256" "$APT_FIXTURE_SIZE" "$expected_path" <<'PY'
import pathlib
import sys

path, package, version, architecture, sha256, size, expected_path = sys.argv[1:8]
paragraphs = pathlib.Path(path).read_text(encoding="utf-8").strip().split("\n\n")
records = []
for paragraph in paragraphs:
    fields = {}
    current = None
    for line in paragraph.splitlines():
        if line.startswith((" ", "\t")) and current:
            fields[current] += "\n" + line
            continue
        if ":" not in line:
            continue
        current, value = line.split(":", 1)
        fields[current] = value.strip()
    if fields:
        records.append(fields)
matches = [record for record in records if record.get("Package") == package]
if len(matches) != 1:
    raise SystemExit(f"APT Packages contains {len(matches)} records for {package}: {records}")
record = matches[0]
expected = {
    "Version": version,
    "Architecture": architecture,
    "SHA256": sha256,
    "Size": size,
    "Filename": expected_path,
}
wrong = {key: (record.get(key), value) for key, value in expected.items()
         if record.get(key) != value}
if wrong:
    raise SystemExit(f"APT Packages metadata changed during migration: {wrong}")
PY
}

verify_source_apt_fixture() {
  local packages_file="$APT_FIXTURE_WORKDIR/source-Packages"
  local downloaded="$APT_FIXTURE_WORKDIR/source-package.deb"
  local packages_url="$NEXUS_URL/repository/$APT_NEXUS_REPOSITORY/dists/stable/main/binary-$APT_ARCHITECTURE/Packages"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    if curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
        "$packages_url" -o "$packages_file" 2>/dev/null \
        && grep -Fq "Package: $APT_PACKAGE" "$packages_file"; then
      break
    fi
    sleep 1
  done
  assert_apt_packages_file "$packages_file" "$APT_FIXTURE_PACKAGE_PATH"
  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$APT_NEXUS_REPOSITORY/$APT_FIXTURE_PACKAGE_PATH" \
    -o "$downloaded"
  cmp "$APT_FIXTURE_ARCHIVE" "$downloaded"
  curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$APT_NEXUS_REPOSITORY/dists/stable/InRelease" \
    >/dev/null
  rm -f "$packages_file" "$downloaded"
  log "source Nexus APT package, metadata, and signed Release verified"
}

verify_apt_repository_definition() {
  local target_url="$1"
  local label="$2"
  local expected_online="$3"
  local output="$APT_FIXTURE_WORKDIR/target-repository.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$APT_KKREPO_REPOSITORY" >"$output"
  python3 - "$output" "$expected_online" <<'PY'
import json
import pathlib
import sys

repository = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_online = sys.argv[2] == "true"
if repository.get("recipe") != "apt-hosted":
    raise SystemExit(f"migrated APT recipe is invalid: {repository}")
if repository.get("online") is not expected_online:
    raise SystemExit(
        f"migrated APT online state is {repository.get('online')!r}, expected {expected_online}"
    )
apt = repository.get("apt") or {}
if apt.get("distribution") != "stable" or apt.get("metadataMode") != "RESIGN":
    raise SystemExit(f"migrated APT settings are invalid: {apt}")
PY
  log "APT hosted definition verified through $label (online=$expected_online)"
}

apt_signing_key_count() {
  local repository_name
  repository_name="$(sql_literal "$APT_KKREPO_REPOSITORY")"
  target_db_query "
    SELECT COUNT(*)
      FROM apt_signing_key key_row
      JOIN repository r ON r.id = key_row.repository_id
     WHERE r.name = $repository_name"
}

activate_migrated_apt_repository() {
  local payload="$APT_FIXTURE_WORKDIR/key-import.json"
  python3 - "$APT_FIXTURE_PRIVATE_KEY" "$payload" <<'PY'
import json
import pathlib
import sys

private_key = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
pathlib.Path(sys.argv[2]).write_text(
    json.dumps({"privateKey": private_key, "passphrase": ""}),
    encoding="utf-8",
)
PY
  curl -m 90 -fsS -u "$(auth)" \
    -X PUT -H "Content-Type: application/json" --data-binary "@$payload" \
    "$KKREPO_URL/internal/repositories/$APT_KKREPO_REPOSITORY/apt/signing-key" \
    >/dev/null
  rm -f "$payload"
  curl -m 30 -fsS -u "$(auth)" \
    -X PUT -H "Content-Type: application/json" --data '{"online":true}' \
    "$KKREPO_URL/internal/repositories/$APT_KKREPO_REPOSITORY" >/dev/null
  curl -m 30 -fsS -u "$(auth)" \
    -X POST -H "Content-Type: application/json" --data '{}' \
    "$KKREPO_URL/internal/repositories/$APT_KKREPO_REPOSITORY/apt/rebuild" >/dev/null
  log "APT signing key was explicitly imported and the migrated repository was brought online"
}

wait_for_migrated_apt_packages() {
  local target_url="$1"
  local output="$2"
  local packages_url="$target_url/repository/$APT_KKREPO_REPOSITORY/dists/stable/main/binary-$APT_ARCHITECTURE/Packages"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    if curl -m 30 -fsS -u "$(auth)" "$packages_url" -o "$output" 2>/dev/null \
        && grep -Fq "Package: $APT_PACKAGE" "$output"; then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for migrated APT Packages through $target_url"
  exit 1
}

apt_client_url() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlsplit, urlunsplit

parts = urlsplit(sys.argv[1])
host = parts.hostname or ""
if host in {"127.0.0.1", "localhost", "::1"}:
    host = "host.docker.internal"
port = f":{parts.port}" if parts.port else ""
print(urlunsplit((parts.scheme, host + port, parts.path.rstrip("/"), "", "")))
PY
}

run_apt_migration_client_acceptance() {
  local target_url="$1"
  local label="$2"
  local safe_label workdir client_url auth_machine
  safe_label="$(printf '%s' "$label" | tr -c 'A-Za-z0-9._-' '-')"
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-apt-migration-client-${safe_label}.XXXXXX")"
  client_url="$(apt_client_url "$target_url")"
  auth_machine="$(python3 - "$client_url" "$APT_KKREPO_REPOSITORY" <<'PY'
import sys
from urllib.parse import urlsplit

parts = urlsplit(sys.argv[1])
print(f"{parts.scheme}://{parts.netloc}/repository/{sys.argv[2]}/")
PY
)"
  printf 'deb [signed-by=/etc/apt/keyrings/kkrepo.asc] %s/repository/%s stable main\n' \
    "$client_url" "$APT_KKREPO_REPOSITORY" >"$workdir/kkrepo.list"
  printf 'machine %s\nlogin %s\npassword %s\n' \
    "$auth_machine" "$KKREPO_USER" "$KKREPO_PASSWORD" >"$workdir/kkrepo.conf"
  chmod 0600 "$workdir/kkrepo.conf"
  cp "$APT_FIXTURE_PUBLIC_KEY" "$workdir/kkrepo.asc"
  log "running real apt update/download/install acceptance through $label"
  docker run --rm --pull=missing \
    --add-host host.docker.internal:host-gateway \
    --volume "$workdir/kkrepo.list:/etc/apt/sources.list.d/kkrepo.list:ro" \
    --volume "$workdir/kkrepo.conf:/etc/apt/auth.conf.d/kkrepo.conf:ro" \
    --volume "$workdir/kkrepo.asc:/etc/apt/keyrings/kkrepo.asc:ro" \
    -e APT_PACKAGE="$APT_PACKAGE" \
    -e APT_VERSION="$APT_VERSION" \
    -e APT_SHA256="$APT_FIXTURE_SHA256" \
    -e APT_MARKER="$APT_FIXTURE_MARKER" \
    debian:12-slim sh -euxc '
      find /etc/apt/sources.list.d -maxdepth 1 -type f ! -name kkrepo.list -delete
      rm -f /etc/apt/sources.list
      rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb
      apt-get update
      cd /tmp
      apt-get download "$APT_PACKAGE=$APT_VERSION"
      archive="$(find /tmp -maxdepth 1 -type f -name "${APT_PACKAGE}_*.deb" -print -quit)"
      test -n "$archive"
      test "$(sha256sum "$archive" | cut -d " " -f 1)" = "$APT_SHA256"
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        "$APT_PACKAGE=$APT_VERSION"
      test "$(dpkg-query -W -f="\${Version}" "$APT_PACKAGE")" = "$APT_VERSION"
      test "$(cat "/usr/share/kkrepo-apt/$APT_PACKAGE.txt")" = "$APT_MARKER"
    '
  rm -rf "$workdir"
}

verify_migrated_apt_fixture() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local activate="${4:-false}"
  local job="$APT_FIXTURE_WORKDIR/target-job.json"
  local packages="$APT_FIXTURE_WORKDIR/target-Packages"
  local downloaded="$APT_FIXTURE_WORKDIR/target-package.deb"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id" >"$job"
  python3 - "$job" "$APT_NEXUS_REPOSITORY" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
repository = sys.argv[2]
rows = (payload.get("repositoryJobs") or payload.get("repositoryStatuses")
        or payload.get("repositoryDetails") or [])
matches = [row for row in rows
           if (row.get("sourceRepositoryName") or row.get("repositoryName")
               or row.get("name")) == repository]
if not matches:
    raise SystemExit(f"APT migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 1 or int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"APT migration package result is invalid: {row}")
PY
  if [[ "$activate" == "true" ]]; then
    if [[ "$(apt_signing_key_count | tr -d '[:space:]')" != "0" ]]; then
      log "APT signing key was imported before explicit migration acceptance"
      exit 1
    fi
    activate_migrated_apt_repository
  fi
  verify_apt_repository_definition "$target_url" "$label" true
  wait_for_migrated_apt_packages "$target_url" "$packages"
  assert_apt_packages_file "$packages" "$APT_FIXTURE_PACKAGE_PATH"
  curl -m 60 -fsS -u "$(auth)" \
    "$target_url/repository/$APT_KKREPO_REPOSITORY/$APT_FIXTURE_PACKAGE_PATH" \
    -o "$downloaded"
  cmp "$APT_FIXTURE_ARCHIVE" "$downloaded"
  run_apt_migration_client_acceptance "$target_url" "$label"
  rm -f "$job" "$packages" "$downloaded"
  log "migrated APT package and real-client install verified through $label"
}

apt_fixture_row_counts() {
  local repository_name package_name version
  repository_name="$(sql_literal "$APT_KKREPO_REPOSITORY")"
  package_name="$(sql_literal "$APT_PACKAGE")"
  version="$(sql_literal "$APT_VERSION")"
  target_db_query "
    SELECT
      (SELECT COUNT(*) FROM apt_package_record p JOIN repository r ON r.id = p.repository_id
        WHERE r.name = $repository_name AND p.package_name = $package_name
          AND p.package_version = $version),
      (SELECT COUNT(*) FROM component c JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'apt'
          AND c.name = $package_name AND c.version = $version),
      (SELECT COUNT(*) FROM asset a JOIN component c ON c.id = a.component_id
        JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'apt'
          AND c.name = $package_name AND c.version = $version),
      (SELECT COUNT(DISTINCT ab.id) FROM asset_blob ab JOIN asset a ON a.asset_blob_id = ab.id
        JOIN component c ON c.id = a.component_id JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'apt'
          AND c.name = $package_name AND c.version = $version),
      (SELECT COUNT(*) FROM apt_suite_state s JOIN repository r ON r.id = s.repository_id
        WHERE r.name = $repository_name AND s.distribution_name = 'stable'),
      (SELECT COUNT(*) FROM apt_snapshot s JOIN repository r ON r.id = s.repository_id
        WHERE r.name = $repository_name AND s.distribution_name = 'stable'
          AND s.published_at IS NOT NULL),
      (SELECT COUNT(*) FROM apt_signing_key k JOIN repository r ON r.id = k.repository_id
        WHERE r.name = $repository_name AND k.active = TRUE)"
}

assert_apt_fixture_counts() {
  local counts="$1"
  python3 - "$counts" <<'PY'
import sys

raw = sys.argv[1]
values = [int(value) for value in raw.split()]
names = ["package", "component", "asset", "blob", "suite", "snapshots", "active_key"]
if len(values) != len(names):
    raise SystemExit(f"unexpected APT row-count snapshot: {raw!r}")
wrong = [name for name, value in zip(names[:5], values[:5]) if value != 1]
if values[5] < 1:
    wrong.append("snapshots")
if values[6] != 1:
    wrong.append("active_key")
if wrong:
    raise SystemExit(f"APT migration row counts are invalid for {wrong}: {raw!r}")
print(" ".join(f"{name}={value}" for name, value in zip(names, values)))
PY
}

source_alpine_available() {
  local endpoint
  for endpoint in \
      "hosted/$ALPINE_NEXUS_REPOSITORY" \
      "proxy/$ALPINE_PROXY_NEXUS_REPOSITORY" \
      "group/$ALPINE_GROUP_NEXUS_REPOSITORY"; do
    if ! curl -m 20 -fsS \
        -u "$NEXUS_USER:$NEXUS_PASSWORD" \
        "$NEXUS_URL/service/rest/v1/repositories/alpine/$endpoint" \
        >/dev/null 2>&1; then
      return 1
    fi
  done
}

create_nexus_alpine_repository() {
  local type="$1"
  local payload="$2"
  local status
  status="$(curl -m 60 -sS -o /dev/null -w '%{http_code}' \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -H 'Content-Type: application/json' \
    --data-binary "@$payload" \
    "$NEXUS_URL/service/rest/v1/repositories/alpine/$type")"
  expect_status "$status" "201" "create Nexus Alpine $type repository"
}

prepare_alpine_fixture() {
  ALPINE_FIXTURE_WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-alpine-migration.XXXXXX")"
  ALPINE_FIXTURE_PRIVATE_KEY="$ALPINE_FIXTURE_WORKDIR/private.pem"
  ALPINE_FIXTURE_KEY_FILENAME="$ALPINE_PACKAGE_KEY_FILENAME"
  ALPINE_FIXTURE_PUBLIC_KEY="$ALPINE_FIXTURE_WORKDIR/$ALPINE_PACKAGE_KEY_FILENAME"
  ALPINE_BASE_ARCHIVE="$ALPINE_FIXTURE_WORKDIR/$ALPINE_BASE_PACKAGE-$ALPINE_VERSION.apk"
  ALPINE_APP_ARCHIVE="$ALPINE_FIXTURE_WORKDIR/$ALPINE_APP_PACKAGE-$ALPINE_VERSION.apk"

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$ALPINE_FIXTURE_PRIVATE_KEY" >/dev/null 2>&1
  openssl pkey -in "$ALPINE_FIXTURE_PRIVATE_KEY" -pubout \
    -out "$ALPINE_FIXTURE_PUBLIC_KEY" >/dev/null 2>&1

  python3 "$PROJECT_ROOT/scripts/ci/create-alpine-e2e-fixture.py" \
    --output "$ALPINE_BASE_ARCHIVE" \
    --package "$ALPINE_BASE_PACKAGE" \
    --version "$ALPINE_VERSION" \
    --architecture noarch \
    --message "$ALPINE_BASE_MESSAGE" \
    --signing-key "$ALPINE_FIXTURE_PRIVATE_KEY" \
    --signature-key-name "$ALPINE_PACKAGE_KEY_FILENAME" \
    >"$ALPINE_FIXTURE_WORKDIR/base.json"
  python3 "$PROJECT_ROOT/scripts/ci/create-alpine-e2e-fixture.py" \
    --output "$ALPINE_APP_ARCHIVE" \
    --package "$ALPINE_APP_PACKAGE" \
    --version "$ALPINE_VERSION" \
    --architecture noarch \
    --message "$ALPINE_APP_MESSAGE" \
    --depends "$ALPINE_BASE_PACKAGE=$ALPINE_VERSION" \
    --signing-key "$ALPINE_FIXTURE_PRIVATE_KEY" \
    --signature-key-name "$ALPINE_PACKAGE_KEY_FILENAME" \
    >"$ALPINE_FIXTURE_WORKDIR/app.json"
  ALPINE_BASE_SHA256="$(file_sha256 "$ALPINE_BASE_ARCHIVE")"
  ALPINE_APP_SHA256="$(file_sha256 "$ALPINE_APP_ARCHIVE")"

  python3 - \
    "$ALPINE_FIXTURE_PRIVATE_KEY" \
    "$ALPINE_FIXTURE_WORKDIR/hosted.json" \
    "$ALPINE_FIXTURE_WORKDIR/proxy.json" \
    "$ALPINE_FIXTURE_WORKDIR/group.json" \
    "$ALPINE_NEXUS_REPOSITORY" \
    "$ALPINE_PROXY_NEXUS_REPOSITORY" \
    "$ALPINE_GROUP_NEXUS_REPOSITORY" <<'PY'
import json
import pathlib
import sys

private_path, hosted_path, proxy_path, group_path, hosted, proxy, group = sys.argv[1:8]
keypair = pathlib.Path(private_path).read_text(encoding="utf-8")
storage = {"blobStoreName": "default", "strictContentTypeValidation": True}
signing = {"keypair": keypair, "passphrase": ""}
pathlib.Path(hosted_path).write_text(json.dumps({
    "name": hosted,
    "online": True,
    "storage": {**storage, "writePolicy": "ALLOW"},
    "alpineSigning": signing,
    "component": {"proprietaryComponents": False},
}), encoding="utf-8")
pathlib.Path(proxy_path).write_text(json.dumps({
    "name": proxy,
    "online": True,
    "storage": storage,
    "proxy": {
        "remoteUrl": "https://dl-cdn.alpinelinux.org/alpine/",
        "contentMaxAge": 1440,
        "metadataMaxAge": 60,
    },
    "negativeCache": {"enabled": True, "timeToLive": 5},
    "httpClient": {"blocked": False, "autoBlock": True},
    "alpineSigning": signing,
}), encoding="utf-8")
pathlib.Path(group_path).write_text(json.dumps({
    "name": group,
    "online": True,
    "storage": storage,
    "group": {"memberNames": [hosted, proxy]},
    "alpineSigning": signing,
}), encoding="utf-8")
PY

  # Migration E2E runs in a disposable Nexus. Remove only the exact fixture names so a retried
  # local run cannot retain a previous signing identity or package set.
  for repository in \
      "$ALPINE_GROUP_NEXUS_REPOSITORY" \
      "$ALPINE_PROXY_NEXUS_REPOSITORY" \
      "$ALPINE_NEXUS_REPOSITORY"; do
    curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" -X DELETE \
      "$NEXUS_URL/service/rest/v1/repositories/$repository" >/dev/null 2>&1 || true
  done
  create_nexus_alpine_repository hosted "$ALPINE_FIXTURE_WORKDIR/hosted.json"
  create_nexus_alpine_repository proxy "$ALPINE_FIXTURE_WORKDIR/proxy.json"
  create_nexus_alpine_repository group "$ALPINE_FIXTURE_WORKDIR/group.json"

  local archive status
  for archive in "$ALPINE_BASE_ARCHIVE" "$ALPINE_APP_ARCHIVE"; do
    status="$(curl -m 90 -sS -o /dev/null -w '%{http_code}' \
      -u "$NEXUS_USER:$NEXUS_PASSWORD" \
      -X PUT -H 'Content-Type: application/vnd.alpine.apk' \
      --data-binary "@$archive" \
      "$NEXUS_URL/repository/$ALPINE_NEXUS_REPOSITORY/$ALPINE_DISTRIBUTION/$ALPINE_CHANNEL/$ALPINE_ARCHITECTURE/$(basename "$archive")")"
    expect_status "$status" "200" "upload Nexus Alpine migration package"
  done
  log "prepared Nexus Alpine hosted/proxy/group definitions and signed dependency fixtures"
}

wait_for_alpine_index() {
  local target_url="$1"
  local repository="$2"
  local username="$3"
  local password="$4"
  local output="$5"
  local index_url="$target_url/repository/$repository/$ALPINE_DISTRIBUTION/$ALPINE_CHANNEL/$ALPINE_ARCHITECTURE/APKINDEX.tar.gz"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    if curl -m 30 -fsS -u "$username:$password" "$index_url" -o "$output" 2>/dev/null \
        && python3 - "$output" "$ALPINE_BASE_PACKAGE" "$ALPINE_APP_PACKAGE" <<'PY'
import gzip
import io
import pathlib
import sys
import tarfile

raw = gzip.decompress(pathlib.Path(sys.argv[1]).read_bytes())
with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
    member = archive.extractfile("APKINDEX")
    if member is None:
        raise SystemExit(1)
    text = member.read().decode("utf-8")
names = {line[2:] for line in text.splitlines() if line.startswith("P:")}
raise SystemExit(0 if set(sys.argv[2:4]) <= names else 1)
PY
    then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for Alpine migration index through $target_url/$repository"
  exit 1
}

alpine_index_key_filename() {
  python3 - "$1" <<'PY'
import gzip
import io
import pathlib
import tarfile
import sys

raw = gzip.decompress(pathlib.Path(sys.argv[1]).read_bytes())
with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
    names = [item.name for item in archive if item.name.startswith(".SIGN.")]
if len(names) != 1:
    raise SystemExit(f"expected one Alpine index signature entry, got {names}")
print(names[0].split(".", 3)[-1])
PY
}

alpine_client_base_url() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys
from urllib.parse import quote, urlsplit, urlunsplit

parts = urlsplit(sys.argv[1])
host = parts.hostname or ""
if host in {"127.0.0.1", "localhost", "::1"}:
    host = "host.docker.internal"
port = f":{parts.port}" if parts.port else ""
auth = quote(sys.argv[2], safe="") + ":" + quote(sys.argv[3], safe="") + "@"
print(urlunsplit((parts.scheme, auth + host + port, parts.path.rstrip("/"), "", "")))
PY
}

run_alpine_migration_client_acceptance() {
  local target_url="$1"
  local repository="$2"
  local username="$3"
  local password="$4"
  local label="$5"
  local workdir client_url
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-alpine-migration-client.XXXXXX")"
  client_url="$(alpine_client_base_url "$target_url" "$username" "$password")"
  mkdir -p "$workdir/keys"
  cp "$ALPINE_FIXTURE_PUBLIC_KEY" "$workdir/keys/$ALPINE_FIXTURE_KEY_FILENAME"
  if [[ "$ALPINE_FIXTURE_KEY_FILENAME" != "$ALPINE_PACKAGE_KEY_FILENAME" ]]; then
    cp "$ALPINE_FIXTURE_PUBLIC_KEY" "$workdir/keys/$ALPINE_PACKAGE_KEY_FILENAME"
  fi
  printf '%s/repository/%s/%s/%s\n' \
    "$client_url" "$repository" "$ALPINE_DISTRIBUTION" "$ALPINE_CHANNEL" \
    >"$workdir/repositories"
  log "running real apk-tools dependency install through $label"
  docker run --rm --pull=missing \
    --add-host host.docker.internal:host-gateway \
    --volume "$workdir/repositories:/etc/apk/repositories:ro" \
    --volume "$workdir/keys:/etc/apk/keys:ro" \
    -e ALPINE_BASE_PACKAGE="$ALPINE_BASE_PACKAGE" \
    -e ALPINE_APP_PACKAGE="$ALPINE_APP_PACKAGE" \
    -e ALPINE_VERSION="$ALPINE_VERSION" \
    -e ALPINE_APP_SHA256="$ALPINE_APP_SHA256" \
    -e ALPINE_BASE_MESSAGE="$ALPINE_BASE_MESSAGE" \
    -e ALPINE_APP_MESSAGE="$ALPINE_APP_MESSAGE" \
    "$ALPINE_CLIENT_IMAGE" sh -euxc '
      rm -rf /var/cache/apk/* /tmp/*.apk
      apk update
      apk search -x "$ALPINE_APP_PACKAGE" | grep -Fx "$ALPINE_APP_PACKAGE-$ALPINE_VERSION"
      apk policy "$ALPINE_APP_PACKAGE" | grep -F "$ALPINE_VERSION"
      cd /tmp
      apk fetch "$ALPINE_APP_PACKAGE"
      archive="$(find /tmp -maxdepth 1 -type f -name "${ALPINE_APP_PACKAGE}-${ALPINE_VERSION}.apk" -print -quit)"
      test -n "$archive"
      test "$(sha256sum "$archive" | cut -d " " -f 1)" = "$ALPINE_APP_SHA256"
      apk add "$ALPINE_APP_PACKAGE=$ALPINE_VERSION"
      apk info -e "$ALPINE_BASE_PACKAGE=$ALPINE_VERSION"
      apk info -e "$ALPINE_APP_PACKAGE=$ALPINE_VERSION"
      test "$(cat "/usr/share/kkrepo-alpine-e2e/$ALPINE_BASE_PACKAGE.txt")" = "$ALPINE_BASE_MESSAGE"
      test "$(cat "/usr/share/kkrepo-alpine-e2e/$ALPINE_APP_PACKAGE.txt")" = "$ALPINE_APP_MESSAGE"
    '
  rm -rf "$workdir"
}

verify_source_alpine_fixture() {
  local index="$ALPINE_FIXTURE_WORKDIR/source-APKINDEX.tar.gz"
  local downloaded="$ALPINE_FIXTURE_WORKDIR/source-app.apk"
  wait_for_alpine_index \
    "$NEXUS_URL" "$ALPINE_NEXUS_REPOSITORY" "$NEXUS_USER" "$NEXUS_PASSWORD" "$index"
  local index_key_filename
  index_key_filename="$(alpine_index_key_filename "$index")"
  if [[ "$index_key_filename" != "$ALPINE_FIXTURE_KEY_FILENAME" ]]; then
    cp "$ALPINE_FIXTURE_PUBLIC_KEY" "$ALPINE_FIXTURE_WORKDIR/$index_key_filename"
    ALPINE_FIXTURE_KEY_FILENAME="$index_key_filename"
  fi
  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$ALPINE_NEXUS_REPOSITORY/$ALPINE_DISTRIBUTION/$ALPINE_CHANNEL/$ALPINE_ARCHITECTURE/$(basename "$ALPINE_APP_ARCHIVE")" \
    -o "$downloaded"
  cmp "$ALPINE_APP_ARCHIVE" "$downloaded"
  run_alpine_migration_client_acceptance \
    "$NEXUS_URL" "$ALPINE_GROUP_NEXUS_REPOSITORY" \
    "$NEXUS_USER" "$NEXUS_PASSWORD" "source Nexus group"
  rm -f "$downloaded"
  log "source Nexus Alpine signed index, package bytes, and dependency install verified"
}

verify_alpine_repository_definitions() {
  local target_url="$1"
  local label="$2"
  local expected_online="$3"
  local workdir hosted proxy group
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-alpine-definitions.XXXXXX")"
  hosted="$workdir/hosted.json"
  proxy="$workdir/proxy.json"
  group="$workdir/group.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ALPINE_KKREPO_REPOSITORY" >"$hosted"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ALPINE_PROXY_KKREPO_REPOSITORY" >"$proxy"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ALPINE_GROUP_KKREPO_REPOSITORY" >"$group"
  python3 - \
    "$hosted" "$proxy" "$group" "$expected_online" \
    "$ALPINE_KKREPO_REPOSITORY" "$ALPINE_PROXY_KKREPO_REPOSITORY" \
    "$ALPINE_GROUP_KKREPO_REPOSITORY" <<'PY'
import json
import pathlib
import sys

hosted_path, proxy_path, group_path, online_text, hosted_name, proxy_name, group_name = sys.argv[1:8]
hosted = json.loads(pathlib.Path(hosted_path).read_text(encoding="utf-8"))
proxy = json.loads(pathlib.Path(proxy_path).read_text(encoding="utf-8"))
group = json.loads(pathlib.Path(group_path).read_text(encoding="utf-8"))
expected_online = online_text == "true"
for repository, recipe in (
    (hosted, "alpine-hosted"),
    (proxy, "alpine-proxy"),
    (group, "alpine-group"),
):
    if repository.get("recipe") != recipe:
        raise SystemExit(f"migrated Alpine definition is invalid: {repository}")
    if repository.get("online") is not expected_online:
        raise SystemExit(
            f"migrated Alpine online state is {repository.get('online')!r}, "
            f"expected {expected_online}: {repository}"
        )
    if (repository.get("alpine") or {}).get("metadataMode") != "RESIGN":
        raise SystemExit(f"migrated Alpine signing mode is invalid: {repository}")
if (proxy.get("proxy") or {}).get("remoteUrl", "").rstrip("/") != \
        "https://dl-cdn.alpinelinux.org/alpine":
    raise SystemExit(f"migrated Alpine proxy remote URL changed: {proxy}")
upstream_keys = (proxy.get("alpine") or {}).get("upstreamPublicKeys") or []
if expected_online and not upstream_keys:
    raise SystemExit(f"online migrated Alpine re-sign proxy has no upstream trust key: {proxy}")
if not expected_online and upstream_keys:
    raise SystemExit(f"fail-closed migrated Alpine proxy unexpectedly has trust keys: {proxy}")
members = (group.get("group") or {}).get("memberNames") or []
if members != [hosted_name, proxy_name]:
    raise SystemExit(f"migrated Alpine group members changed for {group_name}: {members!r}")
PY
  rm -rf "$workdir"
  log "Alpine hosted/proxy/group definitions verified through $label (online=$expected_online)"
}

alpine_signing_key_count() {
  local hosted proxy group
  hosted="$(sql_literal "$ALPINE_KKREPO_REPOSITORY")"
  proxy="$(sql_literal "$ALPINE_PROXY_KKREPO_REPOSITORY")"
  group="$(sql_literal "$ALPINE_GROUP_KKREPO_REPOSITORY")"
  target_db_query "
    SELECT COUNT(*) FROM alpine_signing_key k
    JOIN repository r ON r.id = k.repository_id
    WHERE r.name IN ($hosted, $proxy, $group)"
}

import_alpine_signing_key() {
  local repository="$1"
  local payload="$ALPINE_FIXTURE_WORKDIR/key-$repository.json"
  python3 - \
    "$ALPINE_FIXTURE_PRIVATE_KEY" "$payload" "$ALPINE_FIXTURE_KEY_FILENAME" <<'PY'
import json
import pathlib
import sys

private_key = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
pathlib.Path(sys.argv[2]).write_text(json.dumps({
    "privateKey": private_key,
    "keyFilename": sys.argv[3],
    "signatureType": "RSA",
}), encoding="utf-8")
PY
  curl -m 90 -fsS -u "$(auth)" \
    -X PUT -H 'Content-Type: application/json' --data-binary "@$payload" \
    "$KKREPO_URL/internal/repositories/$repository/alpine/signing-key" >/dev/null
  if [[ "$repository" == "$ALPINE_PROXY_KKREPO_REPOSITORY" ]]; then
    python3 - "$ALPINE_FIXTURE_PUBLIC_KEY" "$payload" <<'PY'
import json
import pathlib
import sys

public_key = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
pathlib.Path(sys.argv[2]).write_text(json.dumps({
    "alpine": {"upstreamPublicKeys": [public_key]},
    "online": True,
}), encoding="utf-8")
PY
  else
    printf '%s\n' '{"online":true}' >"$payload"
  fi
  curl -m 30 -fsS -u "$(auth)" \
    -X PUT -H 'Content-Type: application/json' --data-binary "@$payload" \
    "$KKREPO_URL/internal/repositories/$repository" >/dev/null
}

activate_migrated_alpine_repositories() {
  import_alpine_signing_key "$ALPINE_KKREPO_REPOSITORY"
  import_alpine_signing_key "$ALPINE_PROXY_KKREPO_REPOSITORY"
  import_alpine_signing_key "$ALPINE_GROUP_KKREPO_REPOSITORY"
  local namespace="$ALPINE_DISTRIBUTION/$ALPINE_CHANNEL/$ALPINE_ARCHITECTURE"
  local repository
  for repository in "$ALPINE_KKREPO_REPOSITORY" "$ALPINE_GROUP_KKREPO_REPOSITORY"; do
    curl -m 60 -fsS -u "$(auth)" \
      -X POST -H 'Content-Type: application/json' \
      --data "{\"namespace\":\"$(json_escape "$namespace")\"}" \
      "$KKREPO_URL/internal/repositories/$repository/alpine/rebuild" >/dev/null
  done
  log "Alpine signing keys were explicitly imported and migrated repositories enabled"
}

verify_migrated_alpine_fixture() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local activate="${4:-false}"
  local workdir job index downloaded
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-alpine-migrated.XXXXXX")"
  job="$workdir/job.json"
  index="$workdir/APKINDEX.tar.gz"
  downloaded="$workdir/app.apk"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id" >"$job"
  python3 - "$job" "$ALPINE_NEXUS_REPOSITORY" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
repository = sys.argv[2]
rows = (payload.get("repositoryJobs") or payload.get("repositoryStatuses")
        or payload.get("repositoryDetails") or [])
matches = [row for row in rows
           if (row.get("sourceRepositoryName") or row.get("repositoryName")
               or row.get("name")) == repository]
if not matches:
    raise SystemExit(f"Alpine migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 2 or int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"Alpine migration package result is invalid: {row}")
PY
  if [[ "$activate" == "true" ]]; then
    if [[ "$(alpine_signing_key_count | tr -d '[:space:]')" != "0" ]]; then
      log "Alpine signing key was imported before explicit migration acceptance"
      exit 1
    fi
    activate_migrated_alpine_repositories
  fi
  wait_for_alpine_index \
    "$target_url" "$ALPINE_GROUP_KKREPO_REPOSITORY" "$KKREPO_USER" "$KKREPO_PASSWORD" "$index"
  curl -m 60 -fsS -u "$(auth)" \
    "$target_url/repository/$ALPINE_KKREPO_REPOSITORY/$ALPINE_DISTRIBUTION/$ALPINE_CHANNEL/$ALPINE_ARCHITECTURE/$(basename "$ALPINE_APP_ARCHIVE")" \
    -o "$downloaded"
  cmp "$ALPINE_APP_ARCHIVE" "$downloaded"
  run_alpine_migration_client_acceptance \
    "$target_url" "$ALPINE_GROUP_KKREPO_REPOSITORY" \
    "$KKREPO_USER" "$KKREPO_PASSWORD" "$label target group"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/search/components?format=alpine&q=$ALPINE_APP_PACKAGE" \
    | python3 -c 'import json,sys; p=json.load(sys.stdin); assert any(i.get("name") == sys.argv[1] for i in p.get("items", []))' \
      "$ALPINE_APP_PACKAGE"
  rm -rf "$workdir"
  log "migrated Alpine package, index, Search, and real-client install verified through $label"
}

alpine_fixture_row_counts() {
  local repository_name group_name base_name app_name version
  repository_name="$(sql_literal "$ALPINE_KKREPO_REPOSITORY")"
  group_name="$(sql_literal "$ALPINE_GROUP_KKREPO_REPOSITORY")"
  base_name="$(sql_literal "$ALPINE_BASE_PACKAGE")"
  app_name="$(sql_literal "$ALPINE_APP_PACKAGE")"
  version="$(sql_literal "$ALPINE_VERSION")"
  target_db_query "
    SELECT
      (SELECT COUNT(*) FROM alpine_package_record p JOIN repository r ON r.id = p.repository_id
        WHERE r.name = $repository_name AND p.package_name IN ($base_name, $app_name)
          AND p.package_version = $version),
      (SELECT COUNT(*) FROM component c JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'alpine'
          AND c.name IN ($base_name, $app_name) AND c.version = $version),
      (SELECT COUNT(*) FROM asset a JOIN component c ON c.id = a.component_id
        JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'alpine'
          AND c.name IN ($base_name, $app_name) AND c.version = $version),
      (SELECT COUNT(DISTINCT ab.id) FROM asset_blob ab JOIN asset a ON a.asset_blob_id = ab.id
        JOIN component c ON c.id = a.component_id JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'alpine'
          AND c.name IN ($base_name, $app_name) AND c.version = $version),
      (SELECT COUNT(*) FROM alpine_package_relation relation_row
        JOIN alpine_package_record p ON p.id = relation_row.package_id
        JOIN repository r ON r.id = p.repository_id
        WHERE r.name = $repository_name AND p.package_name = $app_name
          AND relation_row.relation_kind = 'DEPEND'),
      (SELECT COUNT(*) FROM alpine_suite_state s JOIN repository r ON r.id = s.repository_id
        WHERE r.name = $repository_name),
      (SELECT COUNT(*) FROM alpine_snapshot s JOIN repository r ON r.id = s.repository_id
        WHERE r.name = $repository_name AND s.published_at IS NOT NULL),
      (SELECT COUNT(*) FROM alpine_signing_key k JOIN repository r ON r.id = k.repository_id
        WHERE r.name IN ($repository_name, $group_name) AND k.active = TRUE),
      (SELECT COUNT(*) FROM alpine_group_binding binding_row
        JOIN repository r ON r.id = binding_row.group_repository_id
        WHERE r.name = $group_name)"
}

wait_for_alpine_publication_idle() {
  local repository_name pending
  repository_name="$(sql_literal "$ALPINE_KKREPO_REPOSITORY")"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    pending="$(target_db_query "
      SELECT COUNT(*)
      FROM alpine_suite_state state_row
      JOIN repository r ON r.id = state_row.repository_id
      WHERE r.name = $repository_name
        AND (
          state_row.desired_revision <> state_row.published_revision
          OR state_row.published_revision = 0
          OR NOT EXISTS (
            SELECT 1
            FROM alpine_snapshot snapshot_row
            WHERE snapshot_row.repository_id = state_row.repository_id
              AND snapshot_row.distribution_name = state_row.distribution_name
              AND snapshot_row.revision = state_row.published_revision
              AND snapshot_row.published_at IS NOT NULL))")"
    pending="$(printf '%s' "$pending" | tr -d '[:space:]')"
    if [[ "$pending" == "0" ]]; then
      log "Alpine hosted suite publication is idle"
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for Alpine hosted suite publication; pending=$pending"
  exit 1
}

assert_alpine_fixture_counts() {
  local counts="$1"
  python3 - "$counts" <<'PY'
import sys

raw = sys.argv[1]
values = [int(value) for value in raw.split()]
names = [
    "packages", "components", "assets", "blobs", "dependencies",
    "suite", "snapshots", "active_keys", "group_bindings",
]
if len(values) != len(names):
    raise SystemExit(f"unexpected Alpine row-count snapshot: {raw!r}")
wrong = []
for name, value, expected in zip(names[:6], values[:6], [2, 2, 2, 2, 1, 2]):
    if value != expected:
        wrong.append(name)
if values[6] < 1:
    wrong.append("snapshots")
if values[7] != 2:
    wrong.append("active_keys")
if values[8] < 2:
    wrong.append("group_bindings")
if wrong:
    raise SystemExit(f"Alpine migration row counts are invalid for {wrong}: {raw!r}")
print(" ".join(f"{name}={value}" for name, value in zip(names, values)))
PY
}

run_alpine_idempotency_migration() {
  local payload start_body job_id
  payload="{
    \"sourceBaseUrl\":\"$(json_escape "$NEXUS_URL")\",
    \"sourceUsername\":\"$(json_escape "$NEXUS_USER")\",
    \"sourcePassword\":\"$(json_escape "$NEXUS_PASSWORD")\",
    \"repositories\":[\"$(json_escape "$ALPINE_NEXUS_REPOSITORY")\"],
    \"pageSize\":$PAGE_SIZE,
    \"concurrency\":$CONCURRENCY,
    \"checksumValidation\":true
  }"
  start_body="$(curl -m 60 -fsS \
    -u "$(auth)" -H 'Content-Type: application/json' --data "$payload" \
    "$KKREPO_URL/internal/migration/nexus/repository-data/start")"
  job_id="$(printf '%s' "$start_body" | json_field jobId)"
  if [[ -z "$job_id" ]]; then
    log "could not parse Alpine idempotency migration job id from: $start_body"
    exit 1
  fi
  wait_for_discovery_ready "$job_id"
  curl -m 30 -fsS -u "$(auth)" -X POST \
    "$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id/packages/start" \
    >/dev/null
  wait_for_migration_idle "$job_id"
  log "Alpine idempotency migration completed: job=$job_id"
}

source_r_available() {
  local endpoint
  for endpoint in \
      "hosted/$R_NEXUS_REPOSITORY" \
      "proxy/$R_PROXY_NEXUS_REPOSITORY" \
      "group/$R_GROUP_NEXUS_REPOSITORY"; do
    curl -m 20 -fsS \
      -u "$NEXUS_USER:$NEXUS_PASSWORD" \
      "$NEXUS_URL/service/rest/v1/repositories/r/$endpoint" >/dev/null 2>&1 \
      || return 1
  done
}

prepare_r_fixture() {
  R_FIXTURE_WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-r-migration.XXXXXX")"
  R_BASE_ARCHIVE="$R_FIXTURE_WORKDIR/${R_BASE_PACKAGE}_${R_BASE_VERSION}.tar.gz"
  R_APP_OLD_ARCHIVE="$R_FIXTURE_WORKDIR/${R_APP_PACKAGE}_${R_APP_OLD_VERSION}.tar.gz"
  R_APP_ARCHIVE="$R_FIXTURE_WORKDIR/${R_APP_PACKAGE}_${R_APP_VERSION}.tar.gz"
  python3 "$PROJECT_ROOT/scripts/ci/create-r-e2e-fixture.py" \
    --output "$R_BASE_ARCHIVE" \
    --package "$R_BASE_PACKAGE" \
    --version "$R_BASE_VERSION" \
    --message "kkRepo R migration dependency $TAG_SAFE_LC" >/dev/null
  python3 "$PROJECT_ROOT/scripts/ci/create-r-e2e-fixture.py" \
    --output "$R_APP_OLD_ARCHIVE" \
    --package "$R_APP_PACKAGE" \
    --version "$R_APP_OLD_VERSION" \
    --imports "$R_BASE_PACKAGE" \
    --message "kkRepo R migration old application $TAG_SAFE_LC" >/dev/null
  python3 "$PROJECT_ROOT/scripts/ci/create-r-e2e-fixture.py" \
    --output "$R_APP_ARCHIVE" \
    --package "$R_APP_PACKAGE" \
    --version "$R_APP_VERSION" \
    --imports "$R_BASE_PACKAGE" \
    --message "$R_APP_MESSAGE" >/dev/null
  R_APP_SHA256="$(file_sha256 "$R_APP_ARCHIVE")"
}

publish_r_fixture_to_source_nexus() {
  local archive status
  for archive in "$R_BASE_ARCHIVE" "$R_APP_OLD_ARCHIVE" "$R_APP_ARCHIVE"; do
    status="$(curl -m 90 -sS -o /dev/null -w '%{http_code}' \
      -u "$NEXUS_USER:$NEXUS_PASSWORD" \
      -X PUT -H 'Content-Type: application/gzip' --data-binary "@$archive" \
      "$NEXUS_URL/repository/$R_NEXUS_REPOSITORY/src/contrib/$(basename "$archive")")"
    expect_status "$status" "200" "publish source Nexus R package $(basename "$archive")"
  done
  log "published Nexus R dependency and two application versions"
}

assert_r_packages_index() {
  local index="$1"
  local require_md5="$2"
  python3 - \
    "$index" "$R_BASE_PACKAGE" "$R_BASE_VERSION" "$R_BASE_ARCHIVE" \
    "$R_APP_PACKAGE" "$R_APP_VERSION" "$R_APP_ARCHIVE" "$require_md5" <<'PY'
import gzip
import hashlib
import pathlib
import sys

(
    index_path,
    base_name,
    base_version,
    base_archive,
    app_name,
    app_version,
    app_archive,
    require_md5,
) = sys.argv[1:9]

def records(payload):
    parsed = []
    current = {}
    last = None
    for raw in payload.decode("utf-8").splitlines():
        if not raw.strip():
            if current:
                parsed.append(current)
                current = {}
                last = None
            continue
        if raw[:1].isspace() and last is not None:
            current[last] += "\n" + raw[1:]
            continue
        if ":" not in raw:
            raise SystemExit(f"invalid R DCF line: {raw!r}")
        last, value = raw.split(":", 1)
        current[last] = value.lstrip()
    if current:
        parsed.append(current)
    return parsed

with gzip.open(index_path, "rb") as source:
    rows = records(source.read())
base = [row for row in rows if row.get("Package") == base_name]
app = [row for row in rows if row.get("Package") == app_name]
if len(base) != 1 or base[0].get("Version") != base_version:
    raise SystemExit(f"R base record is invalid: {base!r}")
if len(app) != 1 or app[0].get("Version") != app_version:
    raise SystemExit(f"R application latest record is invalid: {app!r}")
imports = app[0].get("Imports", "")
if base_name not in imports:
    raise SystemExit(f"R application dependency was lost: {imports!r}")
if require_md5 == "true":
    for row, archive in ((base[0], base_archive), (app[0], app_archive)):
        expected = hashlib.md5(pathlib.Path(archive).read_bytes()).hexdigest()
        if row.get("MD5sum", "").lower() != expected:
            raise SystemExit(
                f"R MD5sum changed for {row.get('Package')}: {row.get('MD5sum')} != {expected}"
            )
PY
}

wait_for_r_index() {
  local target_url="$1"
  local repository="$2"
  local username="$3"
  local password="$4"
  local output="$5"
  local require_md5="$6"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    if curl -m 30 -fsS -u "$username:$password" \
        "$target_url/repository/$repository/src/contrib/PACKAGES.gz" \
        -o "$output" 2>/dev/null \
        && assert_r_packages_index "$output" "$require_md5" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for R PACKAGES.gz through $target_url/$repository"
  exit 1
}

verify_source_r_fixture() {
  local index="$R_FIXTURE_WORKDIR/source-PACKAGES.gz"
  local archive downloaded expected
  wait_for_r_index \
    "$NEXUS_URL" "$R_NEXUS_REPOSITORY" "$NEXUS_USER" "$NEXUS_PASSWORD" \
    "$index" false
  for archive in "$R_BASE_ARCHIVE" "$R_APP_OLD_ARCHIVE" "$R_APP_ARCHIVE"; do
    downloaded="$R_FIXTURE_WORKDIR/source-$(basename "$archive")"
    curl -m 90 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
      "$NEXUS_URL/repository/$R_NEXUS_REPOSITORY/src/contrib/$(basename "$archive")" \
      -o "$downloaded"
    expected="$(file_sha256 "$archive")"
    if [[ "$(file_sha256 "$downloaded")" != "$expected" ]]; then
      log "source Nexus R package checksum changed: $(basename "$archive")"
      exit 1
    fi
  done
  rm -f "$index" "$R_FIXTURE_WORKDIR"/source-*.tar.gz
  log "source Nexus R index, dependency graph, versions, and package bytes verified"
}

verify_r_repository_definitions() {
  local target_url="$1"
  local label="$2"
  local workdir hosted proxy group proxy_attributes
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-r-definitions.XXXXXX")"
  hosted="$workdir/hosted.json"
  proxy="$workdir/proxy.json"
  group="$workdir/group.json"
  proxy_attributes="$workdir/proxy-attributes.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$R_KKREPO_REPOSITORY" >"$hosted"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$R_PROXY_KKREPO_REPOSITORY" >"$proxy"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$R_GROUP_KKREPO_REPOSITORY" >"$group"
  target_db_query \
    "SELECT attributes_json FROM repository WHERE name = $(sql_literal "$R_PROXY_KKREPO_REPOSITORY")" \
    >"$proxy_attributes"
  python3 - \
    "$hosted" "$proxy" "$group" "$proxy_attributes" \
    "$R_KKREPO_REPOSITORY" "$R_PROXY_KKREPO_REPOSITORY" \
    "$R_GROUP_KKREPO_REPOSITORY" "$R_MIGRATION_UPSTREAM_URL" <<'PY'
import json
import pathlib
import sys

(
    hosted_path,
    proxy_path,
    group_path,
    attributes_path,
    hosted_name,
    proxy_name,
    group_name,
    remote_url,
) = sys.argv[1:9]
hosted = json.loads(pathlib.Path(hosted_path).read_text(encoding="utf-8"))
proxy = json.loads(pathlib.Path(proxy_path).read_text(encoding="utf-8"))
group = json.loads(pathlib.Path(group_path).read_text(encoding="utf-8"))
attributes = json.loads(pathlib.Path(attributes_path).read_text(encoding="utf-8").strip())
if hosted.get("recipe") != "r-hosted" or hosted.get("type") != "HOSTED":
    raise SystemExit(f"migrated R hosted definition is invalid: {hosted}")
if (hosted.get("hosted") or {}).get("writePolicy") != "ALLOW":
    raise SystemExit(f"migrated R hosted write policy changed: {hosted}")
if proxy.get("recipe") != "r-proxy" or proxy.get("type") != "PROXY":
    raise SystemExit(f"migrated R proxy definition is invalid: {proxy}")
settings = proxy.get("proxy") or {}
if settings.get("remoteUrl", "").rstrip("/") != remote_url.rstrip("/"):
    raise SystemExit(f"migrated R proxy remote changed: {settings}")
if settings.get("contentMaxAgeMinutes") != 37 or settings.get("metadataMaxAgeMinutes") != 19:
    raise SystemExit(f"migrated R proxy TTLs changed: {settings}")
if settings.get("autoBlock") is not False:
    raise SystemExit(f"migrated R proxy autoBlock changed: {settings}")
negative = ((attributes.get("proxy") or {}).get("negativeCache") or {})
if negative.get("enabled") is not True or negative.get("timeToLive") != 11:
    raise SystemExit(f"migrated R negative-cache settings changed: {attributes}")
if group.get("recipe") != "r-group" or group.get("type") != "GROUP":
    raise SystemExit(f"migrated R group definition is invalid: {group}")
members = (group.get("group") or {}).get("memberNames") or []
if members != [hosted_name, proxy_name]:
    raise SystemExit(f"migrated R group member order changed for {group_name}: {members!r}")
PY
  rm -rf "$workdir"
  log "R hosted/proxy/group definitions verified through $label"
}

wait_for_r_publication_idle() {
  local repository_name pending
  repository_name="$(sql_literal "$R_KKREPO_REPOSITORY")"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    pending="$(target_db_query "
      SELECT COUNT(*)
      FROM r_suite_state state_row
      JOIN repository r ON r.id = state_row.repository_id
      WHERE r.name = $repository_name
        AND (
          state_row.desired_revision <> state_row.published_revision
          OR state_row.published_revision = 0
          OR NOT EXISTS (
            SELECT 1 FROM r_snapshot snapshot_row
            WHERE snapshot_row.repository_id = state_row.repository_id
              AND snapshot_row.distribution_name = state_row.distribution_name
              AND snapshot_row.revision = state_row.published_revision
              AND snapshot_row.published_at IS NOT NULL))")"
    pending="$(printf '%s' "$pending" | tr -d '[:space:]')"
    if [[ "$pending" == "0" ]]; then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for R hosted publication; pending=$pending"
  exit 1
}

r_client_repository_url() {
  python3 - "$1" "$2" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import sys
from urllib.parse import quote, urlsplit, urlunsplit

base, repository, username, password = sys.argv[1:5]
parts = urlsplit(base)
host = parts.hostname or ""
if host in {"127.0.0.1", "localhost", "::1"}:
    host = "host.docker.internal"
port = f":{parts.port}" if parts.port else ""
authority = f"{quote(username, safe='')}:{quote(password, safe='')}@{host}{port}"
path = f"{parts.path.rstrip('/')}/repository/{repository}"
print(urlunsplit((parts.scheme, authority, path, "", "")))
PY
}

run_r_migration_client_acceptance() {
  local target_url="$1"
  local repository="$2"
  local label="$3"
  local repository_url pair version image
  repository_url="$(r_client_repository_url "$target_url" "$repository")"
  IFS=',' read -r -a r_images <<<"$R_CLIENT_IMAGES"
  for pair in "${r_images[@]}"; do
    version="${pair%%=*}"
    image="${pair#*=}"
    if [[ -z "$version" || -z "$image" || "$version" == "$image" ]]; then
      log "invalid R migration client image entry: $pair"
      exit 1
    fi
    log "running R $version migration install through $label"
    docker run --rm --pull=missing \
      --add-host host.docker.internal:host-gateway \
      -e R_REPOSITORY_URL="$repository_url" \
      -e R_BASE_PACKAGE="$R_BASE_PACKAGE" \
      -e R_BASE_VERSION="$R_BASE_VERSION" \
      -e R_APP_PACKAGE="$R_APP_PACKAGE" \
      -e R_APP_VERSION="$R_APP_VERSION" \
      -e R_APP_MESSAGE="$R_APP_MESSAGE" \
      "$image" Rscript --vanilla -e '
        repository <- Sys.getenv("R_REPOSITORY_URL")
        base <- Sys.getenv("R_BASE_PACKAGE")
        app <- Sys.getenv("R_APP_PACKAGE")
        available <- available.packages(repos = repository, type = "source", filters = list())
        stopifnot(base %in% rownames(available), app %in% rownames(available))
        stopifnot(available[base, "Version"] == Sys.getenv("R_BASE_VERSION"))
        stopifnot(available[app, "Version"] == Sys.getenv("R_APP_VERSION"))
        library_path <- "/tmp/kkrepo-r-migration-library"
        dir.create(library_path, recursive = TRUE, showWarnings = FALSE)
        .libPaths(c(library_path, .libPaths()))
        install.packages(app, repos = repository, lib = library_path,
                         dependencies = TRUE, type = "source", quiet = FALSE)
        stopifnot(as.character(packageVersion(app, lib.loc = library_path)) ==
                  Sys.getenv("R_APP_VERSION"))
        loadNamespace(app, lib.loc = library_path)
        stopifnot(identical(getExportedValue(app, "kkrepo_marker")(),
                            Sys.getenv("R_APP_MESSAGE")))
      '
  done
}

verify_migrated_r_fixture() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local run_clients="${4:-false}"
  local workdir job hosted_index group_index browse archive expected
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-r-migrated.XXXXXX")"
  job="$workdir/job.json"
  hosted_index="$workdir/hosted-PACKAGES.gz"
  group_index="$workdir/group-PACKAGES.gz"
  browse="$workdir/browse.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id" >"$job"
  python3 - "$job" "$R_NEXUS_REPOSITORY" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
repository = sys.argv[2]
rows = (payload.get("repositoryJobs") or payload.get("repositoryStatuses")
        or payload.get("repositoryDetails") or [])
matches = [row for row in rows
           if (row.get("sourceRepositoryName") or row.get("repositoryName")
               or row.get("name")) == repository]
if not matches:
    raise SystemExit(f"R migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 3 or int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"R migration package result is invalid: {row}")
PY
  wait_for_r_publication_idle
  wait_for_r_index \
    "$target_url" "$R_KKREPO_REPOSITORY" "$KKREPO_USER" "$KKREPO_PASSWORD" \
    "$hosted_index" true
  wait_for_r_index \
    "$target_url" "$R_GROUP_KKREPO_REPOSITORY" "$KKREPO_USER" "$KKREPO_PASSWORD" \
    "$group_index" true
  for archive in "$R_BASE_ARCHIVE" "$R_APP_OLD_ARCHIVE" "$R_APP_ARCHIVE"; do
    expected="$(file_sha256 "$archive")"
    curl -m 90 -fsS -u "$(auth)" \
      "$target_url/repository/$R_KKREPO_REPOSITORY/src/contrib/$(basename "$archive")" \
      -o "$workdir/$(basename "$archive")"
    if [[ "$(file_sha256 "$workdir/$(basename "$archive")")" != "$expected" ]]; then
      log "migrated R package checksum changed through $label: $(basename "$archive")"
      rm -rf "$workdir"
      exit 1
    fi
  done
  curl -m 90 -fsS -u "$(auth)" \
    "$target_url/repository/$R_GROUP_KKREPO_REPOSITORY/src/contrib/$(basename "$R_APP_ARCHIVE")" \
    -o "$workdir/group-$(basename "$R_APP_ARCHIVE")"
  if [[ "$(file_sha256 "$workdir/group-$(basename "$R_APP_ARCHIVE")")" != "$R_APP_SHA256" ]]; then
    log "migrated R group binding returned different application bytes through $label"
    rm -rf "$workdir"
    exit 1
  fi
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/search/components?format=r&q=$R_APP_PACKAGE" \
    | python3 -c 'import json,sys; p=json.load(sys.stdin); assert any(i.get("name") == sys.argv[1] and i.get("version") == sys.argv[2] for i in p.get("items", []))' \
      "$R_APP_PACKAGE" "$R_APP_VERSION"
  curl -m 30 -fsS -u "$(auth)" --get \
    --data-urlencode "path=src/contrib/$R_APP_PACKAGE/$R_APP_VERSION" \
    "$target_url/internal/browse/$R_KKREPO_REPOSITORY" >"$browse"
  python3 - \
    "$browse" \
    "$(basename "$R_APP_ARCHIVE")" \
    "src/contrib/$R_APP_PACKAGE/$R_APP_VERSION/$(basename "$R_APP_ARCHIVE")" \
    "/repository/$R_KKREPO_REPOSITORY/src/contrib/$(basename "$R_APP_ARCHIVE")" <<'PY'
import json
import pathlib
import sys

path, filename, expected_path, expected_download_url = sys.argv[1:5]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
matches = [entry for entry in payload.get("entries", [])
           if entry.get("name") == filename and entry.get("leaf") is True]
if len(matches) != 1:
    raise SystemExit(f"migrated R Browse leaf is missing: {filename}; payload={payload}")
entry = matches[0]
if entry.get("path") != expected_path:
    raise SystemExit(
        f"migrated R Browse projection changed: {entry.get('path')!r} != {expected_path!r}"
    )
if entry.get("downloadUrl") != expected_download_url:
    raise SystemExit(
        "migrated R Browse download URL changed: "
        f"{entry.get('downloadUrl')!r} != {expected_download_url!r}"
    )
PY
  if [[ "$run_clients" == "true" ]]; then
    run_r_migration_client_acceptance \
      "$target_url" "$R_GROUP_KKREPO_REPOSITORY" "$label group"
  fi
  rm -rf "$workdir"
  log "migrated R packages, index, Browse/Search, group binding, and client result verified through $label"
}

r_fixture_row_counts() {
  local repository_name group_name base_name app_name
  repository_name="$(sql_literal "$R_KKREPO_REPOSITORY")"
  group_name="$(sql_literal "$R_GROUP_KKREPO_REPOSITORY")"
  base_name="$(sql_literal "$R_BASE_PACKAGE")"
  app_name="$(sql_literal "$R_APP_PACKAGE")"
  target_db_query "
    SELECT
      (SELECT COUNT(*) FROM r_package_record p JOIN repository r ON r.id = p.repository_id
        WHERE r.name = $repository_name AND p.package_name IN ($base_name, $app_name)),
      (SELECT COUNT(*) FROM component c JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'r'
          AND c.name IN ($base_name, $app_name)),
      (SELECT COUNT(*) FROM asset a JOIN component c ON c.id = a.component_id
        JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'r'
          AND c.name IN ($base_name, $app_name)),
      (SELECT COUNT(DISTINCT ab.id) FROM asset_blob ab JOIN asset a ON a.asset_blob_id = ab.id
        JOIN component c ON c.id = a.component_id JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'r'
          AND c.name IN ($base_name, $app_name)),
      (SELECT COUNT(*) FROM r_package_relation relation_row
        JOIN r_package_record p ON p.id = relation_row.package_id
        JOIN repository r ON r.id = p.repository_id
        WHERE r.name = $repository_name AND p.package_name = $app_name
          AND relation_row.relation_kind = 'IMPORTS'),
      (SELECT COUNT(*) FROM r_suite_state s JOIN repository r ON r.id = s.repository_id
        WHERE r.name = $repository_name),
      (SELECT COUNT(*) FROM r_snapshot s JOIN repository r ON r.id = s.repository_id
        WHERE r.name = $repository_name AND s.published_at IS NOT NULL),
      (SELECT COUNT(*) FROM r_group_binding binding_row
        JOIN repository r ON r.id = binding_row.group_repository_id
        WHERE r.name = $group_name)"
}

assert_r_fixture_counts() {
  local counts="$1"
  python3 - "$counts" <<'PY'
import sys

raw = sys.argv[1]
values = [int(value) for value in raw.split()]
names = ["packages", "components", "assets", "blobs", "imports", "suites", "snapshots", "bindings"]
if len(values) != len(names):
    raise SystemExit(f"unexpected R row-count snapshot: {raw!r}")
expected = [3, 3, 3, 3, 2, 1]
wrong = [name for name, value, wanted in zip(names[:6], values[:6], expected) if value != wanted]
if values[6] < 1:
    wrong.append("snapshots")
if values[7] < 2:
    wrong.append("bindings")
if wrong:
    raise SystemExit(f"R migration row counts are invalid for {wrong}: {raw!r}")
print(" ".join(f"{name}={value}" for name, value in zip(names, values)))
PY
}

run_r_idempotency_migration() {
  local payload start_body job_id
  payload="{
    \"sourceBaseUrl\":\"$(json_escape "$NEXUS_URL")\",
    \"sourceUsername\":\"$(json_escape "$NEXUS_USER")\",
    \"sourcePassword\":\"$(json_escape "$NEXUS_PASSWORD")\",
    \"repositories\":[\"$(json_escape "$R_NEXUS_REPOSITORY")\"],
    \"pageSize\":$PAGE_SIZE,
    \"concurrency\":$CONCURRENCY,
    \"checksumValidation\":true
  }"
  start_body="$(curl -m 60 -fsS \
    -u "$(auth)" -H 'Content-Type: application/json' --data "$payload" \
    "$KKREPO_URL/internal/migration/nexus/repository-data/start")"
  job_id="$(printf '%s' "$start_body" | json_field jobId)"
  if [[ -z "$job_id" ]]; then
    log "could not parse R idempotency migration job id from: $start_body"
    exit 1
  fi
  wait_for_discovery_ready "$job_id"
  curl -m 30 -fsS -u "$(auth)" -X POST \
    "$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id/packages/start" \
    >/dev/null
  wait_for_migration_idle "$job_id"
  log "R idempotency migration completed: job=$job_id"
}

source_conda_available() {
  local endpoint
  for endpoint in \
      "hosted/$CONDA_NEXUS_REPOSITORY" \
      "proxy/$CONDA_PROXY_NEXUS_REPOSITORY" \
      "group/$CONDA_GROUP_NEXUS_REPOSITORY"; do
    if ! curl -m 20 -fsS \
        -u "$NEXUS_USER:$NEXUS_PASSWORD" \
        "$NEXUS_URL/service/rest/v1/repositories/conda/$endpoint" \
        >/dev/null 2>&1; then
      return 1
    fi
  done
}

prepare_conda_fixture() {
  CONDA_FIXTURE_WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-conda-migration.XXXXXX")"
  CONDA_FIXTURE_ARCHIVE="$CONDA_FIXTURE_WORKDIR/$CONDA_PACKAGE-$CONDA_VERSION-$CONDA_BUILD.tar.bz2"
  python3 "$PROJECT_ROOT/scripts/ci/create-conda-e2e-fixture.py" \
    --name "$CONDA_PACKAGE" \
    --version "$CONDA_VERSION" \
    --build "$CONDA_BUILD" \
    --subdir "$CONDA_SUBDIR" \
    --marker "$CONDA_FIXTURE_MARKER" \
    --output "$CONDA_FIXTURE_ARCHIVE" \
    >"$CONDA_FIXTURE_WORKDIR/fixture.json"
  CONDA_FIXTURE_SHA256="$(file_sha256 "$CONDA_FIXTURE_ARCHIVE")"
}

publish_conda_fixture_to_source_nexus() {
  local status
  status="$(curl -m 60 -sS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X PUT \
    -H "Content-Type: application/x-tar" \
    --data-binary "@$CONDA_FIXTURE_ARCHIVE" \
    -o "$CONDA_FIXTURE_WORKDIR/source-upload.json" \
    -w '%{http_code}' \
    "$NEXUS_URL/repository/$CONDA_NEXUS_REPOSITORY/$CONDA_SUBDIR/$(basename "$CONDA_FIXTURE_ARCHIVE")")"
  expect_status "$status" "201" "publish source Conda fixture"
}

wait_for_conda_repodata() {
  local label="$1"
  local base_url="$2"
  local repository="$3"
  local credentials="$4"
  local output="$5"
  local filename
  filename="$(basename "$CONDA_FIXTURE_ARCHIVE")"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    if curl -m 30 -fsS -u "$credentials" \
        "$base_url/repository/$repository/$CONDA_SUBDIR/repodata.json" \
        -o "$output" 2>/dev/null \
        && grep -Fq "\"$filename\"" "$output"; then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label Conda repodata to contain $filename"
  exit 1
}

run_conda_client_acceptance() {
  local label="$1"
  local base_url="$2"
  local repository="$3"
  local safe_label workdir condarc prefix search list channel marker_path
  safe_label="$(printf '%s' "$label" | tr -c 'A-Za-z0-9._-' '-')"
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-conda-client-${safe_label}.XXXXXX")"
  condarc="$workdir/condarc"
  prefix="$workdir/prefix"
  search="$workdir/search.json"
  list="$workdir/list.json"
  channel="${base_url%/}/repository/$repository"
  marker_path="share/kkrepo-conda-e2e/$CONDA_PACKAGE.txt"
  mkdir -p "$workdir/pkgs" "$workdir/envs"
  cat >"$condarc" <<EOF
channels: []
default_channels: []
channel_priority: strict
show_channel_urls: true
auto_activate_base: false
pkgs_dirs:
  - $workdir/pkgs
envs_dirs:
  - $workdir/envs
EOF
  log "running Conda client search/create acceptance through $label"
  if ! env \
      CONDARC="$condarc" \
      CONDA_PKGS_DIRS="$workdir/pkgs" \
      CONDA_ENVS_PATH="$workdir/envs" \
      CONDA_SUBDIR="$CONDA_SUBDIR" \
      "$CONDA_BIN" search --json --override-channels \
        --channel "$channel" "$CONDA_PACKAGE=$CONDA_VERSION=$CONDA_BUILD" \
        >"$search"; then
    log "Conda client search failed through $label"
    cat "$search" >&2 || true
    rm -rf "$workdir"
    return 1
  fi
  python3 - "$search" "$CONDA_PACKAGE" "$CONDA_VERSION" "$CONDA_BUILD" <<'PY'
import json
import pathlib
import sys

path, name, version, build = sys.argv[1:5]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
records = payload.get(name) or []
if not any(
    record.get("version") == version and record.get("build") == build
    for record in records
):
    raise SystemExit(f"Conda client search did not find migrated fixture: {payload}")
PY
  if ! env \
      CONDARC="$condarc" \
      CONDA_PKGS_DIRS="$workdir/pkgs" \
      CONDA_ENVS_PATH="$workdir/envs" \
      CONDA_SUBDIR="$CONDA_SUBDIR" \
      "$CONDA_BIN" create --json --yes --no-deps \
        --prefix "$prefix" --override-channels \
        --channel "$channel" "$CONDA_PACKAGE=$CONDA_VERSION=$CONDA_BUILD" \
        >"$workdir/create.json"; then
    log "Conda client create failed through $label"
    cat "$workdir/create.json" >&2 || true
    rm -rf "$workdir"
    return 1
  fi
  grep -Fxq "$CONDA_FIXTURE_MARKER" "$prefix/$marker_path"
  env \
    CONDARC="$condarc" \
    CONDA_PKGS_DIRS="$workdir/pkgs" \
    CONDA_ENVS_PATH="$workdir/envs" \
    CONDA_SUBDIR="$CONDA_SUBDIR" \
    "$CONDA_BIN" list --json --prefix "$prefix" >"$list"
  python3 - "$list" "$CONDA_PACKAGE" "$CONDA_VERSION" "$CONDA_BUILD" <<'PY'
import json
import pathlib
import sys

path, name, version, build = sys.argv[1:5]
records = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
if not any(
    record.get("name") == name
    and record.get("version") == version
    and record.get("build_string") == build
    for record in records
):
    raise SystemExit(f"Conda client did not install migrated fixture: {records}")
PY
  rm -rf "$workdir"
}

verify_source_conda_fixture() {
  local repodata downloaded
  repodata="$CONDA_FIXTURE_WORKDIR/source-repodata.json"
  downloaded="$CONDA_FIXTURE_WORKDIR/source-package.tar.bz2"
  wait_for_conda_repodata \
    "source Nexus" "$NEXUS_URL" "$CONDA_NEXUS_REPOSITORY" \
    "$NEXUS_USER:$NEXUS_PASSWORD" "$repodata"
  python3 - "$repodata" "$(basename "$CONDA_FIXTURE_ARCHIVE")" "$CONDA_FIXTURE_SHA256" <<'PY'
import json
import pathlib
import sys

path, filename, checksum = sys.argv[1:4]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
record = (payload.get("packages") or {}).get(filename)
if not record or str(record.get("sha256") or "").lower() != checksum:
    raise SystemExit(f"Nexus Conda fixture checksum was not indexed: {record}")
PY
  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$CONDA_NEXUS_REPOSITORY/$CONDA_SUBDIR/$(basename "$CONDA_FIXTURE_ARCHIVE")" \
    -o "$downloaded"
  cmp "$CONDA_FIXTURE_ARCHIVE" "$downloaded"
  # A valid Conda channel must expose both the requested platform and noarch metadata.
  # The source hosted repository only contains the platform fixture, while the group combines
  # it with the proxy's noarch index, matching the client-facing repository used after migration.
  run_conda_client_acceptance \
    "source Nexus group" "$NEXUS_URL" "$CONDA_GROUP_NEXUS_REPOSITORY"
}

verify_conda_repository_definitions() {
  local target_url="$1"
  local label="$2"
  local workdir hosted proxy group
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-conda-definitions.XXXXXX")"
  hosted="$workdir/hosted.json"
  proxy="$workdir/proxy.json"
  group="$workdir/group.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$CONDA_KKREPO_REPOSITORY" >"$hosted"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$CONDA_PROXY_KKREPO_REPOSITORY" >"$proxy"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$CONDA_GROUP_KKREPO_REPOSITORY" >"$group"
  python3 - \
    "$hosted" "$proxy" "$group" \
    "$CONDA_KKREPO_REPOSITORY" "$CONDA_PROXY_KKREPO_REPOSITORY" \
    "$CONDA_GROUP_KKREPO_REPOSITORY" <<'PY'
import json
import pathlib
import sys

hosted_path, proxy_path, group_path, hosted_name, proxy_name, group_name = sys.argv[1:7]
hosted = json.loads(pathlib.Path(hosted_path).read_text(encoding="utf-8"))
proxy = json.loads(pathlib.Path(proxy_path).read_text(encoding="utf-8"))
group = json.loads(pathlib.Path(group_path).read_text(encoding="utf-8"))
if hosted.get("recipe") != "conda-hosted":
    raise SystemExit(f"migrated Conda hosted definition is invalid: {hosted}")
if proxy.get("recipe") != "conda-proxy":
    raise SystemExit(f"migrated Conda proxy definition is invalid: {proxy}")
if (proxy.get("proxy") or {}).get("remoteUrl", "").rstrip("/") != "https://repo.anaconda.com/pkgs/main":
    raise SystemExit(f"migrated Conda proxy remote URL changed: {proxy}")
if group.get("recipe") != "conda-group":
    raise SystemExit(f"migrated Conda group definition is invalid: {group}")
members = (group.get("group") or {}).get("memberNames") or []
if members != [hosted_name, proxy_name]:
    raise SystemExit(f"migrated Conda group members changed: {members!r} for {group_name}")
PY
  rm -rf "$workdir"
  log "Conda hosted/proxy/group definitions verified through $label"
}

verify_migrated_conda_fixture() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local workdir job repodata downloaded
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-conda-migrated.XXXXXX")"
  job="$workdir/job.json"
  repodata="$workdir/repodata.json"
  downloaded="$workdir/package.tar.bz2"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id" >"$job"
  python3 - "$job" "$CONDA_NEXUS_REPOSITORY" <<'PY'
import json
import pathlib
import sys

path, repository = sys.argv[1:3]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
rows = payload.get("repositoryJobs") or payload.get("repositoryStatuses") or payload.get("repositoryDetails") or []
matches = [
    row for row in rows
    if (row.get("sourceRepositoryName") or row.get("repositoryName") or row.get("name")) == repository
]
if not matches:
    raise SystemExit(f"Conda migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 1:
    raise SystemExit(f"Conda migration did not restore a package: {row}")
if int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"Conda migration has failed assets: {row}")
PY
  wait_for_conda_repodata \
    "migrated $label" "$target_url" "$CONDA_KKREPO_REPOSITORY" \
    "$(auth)" "$repodata"
  python3 - "$repodata" "$(basename "$CONDA_FIXTURE_ARCHIVE")" "$CONDA_FIXTURE_SHA256" <<'PY'
import json
import pathlib
import sys

path, filename, checksum = sys.argv[1:4]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
record = (payload.get("packages") or {}).get(filename)
if not record or str(record.get("sha256") or "").lower() != checksum:
    raise SystemExit(f"migrated Conda repodata checksum changed: {record}")
PY
  curl -m 60 -fsS -u "$(auth)" \
    "$target_url/repository/$CONDA_KKREPO_REPOSITORY/$CONDA_SUBDIR/$(basename "$CONDA_FIXTURE_ARCHIVE")" \
    -o "$downloaded"
  cmp "$CONDA_FIXTURE_ARCHIVE" "$downloaded"
  run_conda_client_acceptance "$label group" "$target_url" "$CONDA_GROUP_KKREPO_REPOSITORY"
  rm -rf "$workdir"
  log "Conda hosted package and group client install verified through $label"
}

conda_fixture_row_counts() {
  local repository_name group_name package_name version filename subdir
  repository_name="$(sql_literal "$CONDA_KKREPO_REPOSITORY")"
  group_name="$(sql_literal "$CONDA_GROUP_KKREPO_REPOSITORY")"
  package_name="$(sql_literal "$CONDA_PACKAGE")"
  version="$(sql_literal "$CONDA_VERSION")"
  filename="$(sql_literal "$(basename "$CONDA_FIXTURE_ARCHIVE")")"
  subdir="$(sql_literal "$CONDA_SUBDIR")"
  target_db_query "
    SELECT
      (SELECT COUNT(*)
         FROM conda_package_record cp JOIN repository r ON r.id = cp.repository_id
        WHERE r.name = $repository_name AND cp.name = $package_name
          AND cp.version = $version AND cp.filename = $filename),
      (SELECT COUNT(*)
         FROM component c JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'conda'
          AND c.name = $package_name AND c.version = $version),
      (SELECT COUNT(*)
         FROM asset a JOIN component c ON c.id = a.component_id
         JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'conda'
          AND c.name = $package_name AND c.version = $version),
      (SELECT COUNT(DISTINCT ab.id)
         FROM asset_blob ab JOIN asset a ON a.asset_blob_id = ab.id
         JOIN component c ON c.id = a.component_id
         JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'conda'
          AND c.name = $package_name AND c.version = $version),
      (SELECT COUNT(*)
         FROM conda_channel_state cs JOIN repository r ON r.id = cs.repository_id
        WHERE r.name = $repository_name AND cs.subdir = $subdir),
      (SELECT COUNT(*)
         FROM conda_group_source_binding gb JOIN repository r ON r.id = gb.group_repository_id
        WHERE r.name = $group_name AND gb.filename = $filename)"
}

assert_conda_fixture_counts() {
  local counts="$1"
  python3 - "$counts" <<'PY'
import sys

raw = sys.argv[1]
values = [int(value) for value in raw.split()]
names = ["package", "component", "asset", "blob", "channel_state", "group_binding"]
if len(values) != len(names):
    raise SystemExit(f"unexpected Conda row-count snapshot: {raw!r}")
missing = [name for name, value in zip(names, values) if value != 1]
if missing:
    raise SystemExit(f"Conda migration row counts are not exactly one for {missing}: {raw!r}")
print(" ".join(f"{name}={value}" for name, value in zip(names, values)))
PY
}

source_ansible_available() {
  local endpoint
  for endpoint in \
      "hosted/$ANSIBLE_NEXUS_REPOSITORY" \
      "proxy/$ANSIBLE_PROXY_NEXUS_REPOSITORY" \
      "group/$ANSIBLE_GROUP_NEXUS_REPOSITORY"; do
    if ! curl -m 20 -fsS \
        -u "$NEXUS_USER:$NEXUS_PASSWORD" \
        "$NEXUS_URL/service/rest/v1/repositories/ansiblegalaxy/$endpoint" \
        >/dev/null 2>&1; then
      return 1
    fi
  done
}

source_swift_available() {
  local endpoint
  for endpoint in \
      "hosted/$SWIFT_NEXUS_REPOSITORY" \
      "proxy/$SWIFT_PROXY_NEXUS_REPOSITORY" \
      "group/$SWIFT_GROUP_NEXUS_REPOSITORY"; do
    if ! curl -m 20 -fsS \
        -u "$NEXUS_USER:$NEXUS_PASSWORD" \
        "$NEXUS_URL/service/rest/v1/repositories/swift/$endpoint" \
        >/dev/null 2>&1; then
      return 1
    fi
  done
}

ensure_ansible_source_secret_proxy() {
  local endpoint payload response status method
  endpoint="$NEXUS_URL/service/rest/v1/repositories/ansiblegalaxy/proxy/$ANSIBLE_SECRET_PROXY_NEXUS_REPOSITORY"
  method=POST
  if curl -m 20 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" "$endpoint" >/dev/null 2>&1; then
    method=PUT
  fi
  payload="{
    \"name\":\"$(json_escape "$ANSIBLE_SECRET_PROXY_NEXUS_REPOSITORY")\",
    \"online\":true,
    \"storage\":{\"blobStoreName\":\"default\",\"strictContentTypeValidation\":true},
    \"proxy\":{\"remoteUrl\":\"https://galaxy.ansible.com/\",\"contentMaxAge\":17,\"metadataMaxAge\":23},
    \"negativeCache\":{\"enabled\":true,\"timeToLive\":60},
    \"httpClient\":{
      \"blocked\":false,
      \"autoBlock\":false,
      \"authentication\":{
        \"type\":\"username\",
        \"username\":\"$(json_escape "$ANSIBLE_SECRET_PROXY_USERNAME")\",
        \"password\":\"$(json_escape "$ANSIBLE_SECRET_PROXY_SECRET")\"
      }
    }
  }"
  response="$(mktemp)"
  if [[ "$method" == "POST" ]]; then
    endpoint="$NEXUS_URL/service/rest/v1/repositories/ansiblegalaxy/proxy"
  fi
  status="$(curl -m 30 -sS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X "$method" \
    -H "Content-Type: application/json" \
    --data "$payload" \
    -o "$response" \
    -w '%{http_code}' \
    "$endpoint")"
  if [[ "$status" != "200" && "$status" != "201" && "$status" != "204" ]]; then
    log "configuring authenticated Nexus Ansible proxy returned HTTP $status"
    cat "$response" >&2 || true
    rm -f "$response"
    exit 1
  fi
  rm -f "$response"
  log "Nexus Ansible proxy authentication fixture configured (secret omitted)"
}

prepare_ansible_fixture() {
  ANSIBLE_FIXTURE_WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-ansible-migration.XXXXXX")"
  ANSIBLE_FIXTURE_ARCHIVE="$ANSIBLE_FIXTURE_WORKDIR/$ANSIBLE_NAMESPACE-$ANSIBLE_COLLECTION-$ANSIBLE_VERSION.tar.gz"
  ANSIBLE_PROXY_FIXTURE_ARCHIVE="$ANSIBLE_FIXTURE_WORKDIR/$ANSIBLE_PROXY_NAMESPACE-$ANSIBLE_PROXY_COLLECTION-$ANSIBLE_PROXY_VERSION.tar.gz"
  ANSIBLE_FIXTURE_FILES_JSON_SIZE="$(python3 - \
    "$ANSIBLE_FIXTURE_ARCHIVE" "$ANSIBLE_FIXTURE_WORKDIR" \
    "$ANSIBLE_NAMESPACE" "$ANSIBLE_COLLECTION" "$ANSIBLE_VERSION" <<'PY'
import hashlib
import io
import json
import pathlib
import sys
import tarfile

archive_path, workdir, namespace, name, version = sys.argv[1:6]
workdir_path = pathlib.Path(workdir)
files = {
    "README.md": f"# {namespace}.{name}\nNexus Ansible migration E2E fixture\n".encode(),
    "meta/runtime.yml": b"requires_ansible: '>=2.15'\n",
}
for index in range(600):
    path = f"payload/data-{index:04d}.json"
    files[path] = json.dumps(
        {"index": index, "marker": f"ansible-migration-files-json-{index:04d}"},
        separators=(",", ":"),
    ).encode()

inventory = [
    {"name": ".", "ftype": "dir"},
    {"name": "meta", "ftype": "dir"},
    {"name": "payload", "ftype": "dir"},
]
for path, content in files.items():
    inventory.append({
        "name": path,
        "ftype": "file",
        "chksum_type": "sha256",
        "chksum_sha256": hashlib.sha256(content).hexdigest(),
    })
files_json = json.dumps(
    {"files": inventory, "format": 1}, separators=(",", ":"), sort_keys=True
).encode()
manifest_json = json.dumps({
    "collection_info": {
        "namespace": namespace,
        "name": name,
        "version": version,
        "authors": ["kkRepo Migration E2E"],
        "description": "Ansible migration fixture with a large FILES.json kept in blob storage",
        "license": ["Apache-2.0"],
        "tags": ["migration", "e2e"],
        "dependencies": {},
    },
    "file_manifest_file": {
        "name": "FILES.json",
        "ftype": "file",
        "chksum_type": "sha256",
        "chksum_sha256": hashlib.sha256(files_json).hexdigest(),
    },
    "format": 1,
}, separators=(",", ":"), sort_keys=True).encode()

workdir_path.joinpath("MANIFEST.json").write_bytes(manifest_json)
workdir_path.joinpath("FILES.json").write_bytes(files_json)

def add_file(output, path, content):
    entry = tarfile.TarInfo(path)
    entry.mode = 0o644
    entry.mtime = 1738555506
    entry.size = len(content)
    output.addfile(entry, io.BytesIO(content))

with tarfile.open(archive_path, "w:gz", format=tarfile.GNU_FORMAT) as output:
    add_file(output, "MANIFEST.json", manifest_json)
    add_file(output, "FILES.json", files_json)
    for directory in ("meta", "payload"):
        entry = tarfile.TarInfo(directory + "/")
        entry.type = tarfile.DIRTYPE
        entry.mode = 0o755
        entry.mtime = 1738555506
        output.addfile(entry)
    for path, content in files.items():
        add_file(output, path, content)

print(len(files_json))
PY
)"
  ANSIBLE_FIXTURE_SHA256="$(file_sha256 "$ANSIBLE_FIXTURE_ARCHIVE")"
  if [[ "$ANSIBLE_FIXTURE_FILES_JSON_SIZE" -lt 65536 ]]; then
    log "Ansible migration fixture FILES.json is unexpectedly small: $ANSIBLE_FIXTURE_FILES_JSON_SIZE"
    exit 1
  fi
  log "prepared Ansible collection fixture: $ANSIBLE_NAMESPACE.$ANSIBLE_COLLECTION $ANSIBLE_VERSION archiveSha256=$ANSIBLE_FIXTURE_SHA256 filesJsonBytes=$ANSIBLE_FIXTURE_FILES_JSON_SIZE"
}

publish_ansible_fixture_to_source_nexus() {
  local repository_base response status task_url task_file state finished
  repository_base="$NEXUS_URL/repository/$ANSIBLE_NEXUS_REPOSITORY"
  response="$(mktemp)"
  status="$(curl -m 300 -sS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -F "sha256=$ANSIBLE_FIXTURE_SHA256" \
    -F "file=@$ANSIBLE_FIXTURE_ARCHIVE;type=application/octet-stream" \
    -o "$response" \
    -w '%{http_code}' \
    "$repository_base/api/v3/artifacts/collections/")"
  if [[ "$status" != "202" ]]; then
    log "publishing Ansible fixture to source Nexus returned HTTP $status"
    cat "$response" >&2 || true
    rm -f "$response"
    exit 1
  fi
  task_url="$(python3 - "$response" "$repository_base/" <<'PY'
import json
import pathlib
import sys
from urllib.parse import urljoin

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
task = payload.get("task")
if not isinstance(task, str) or not task:
    raise SystemExit(f"Nexus Ansible publish response omitted task: {payload}")
print(urljoin(sys.argv[2], task))
PY
)"
  task_file="$(mktemp)"
  for ((attempt = 1; attempt <= WAIT_TIMEOUT_SECONDS; attempt++)); do
    curl -m 20 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" "$task_url" >"$task_file"
    read -r state finished < <(python3 - "$task_file" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload.get("state") or "", "yes" if payload.get("finished_at") else "no")
PY
)
    if [[ "$finished" == "yes" ]]; then
      if [[ "$state" != "completed" ]]; then
        log "source Nexus Ansible import finished in state $state"
        cat "$task_file" >&2 || true
        rm -f "$response" "$task_file"
        exit 1
      fi
      rm -f "$response" "$task_file"
      log "source Nexus Ansible import completed: $task_url"
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for source Nexus Ansible import task: $task_url"
  cat "$task_file" >&2 || true
  rm -f "$response" "$task_file"
  exit 1
}

verify_source_ansible_fixture() {
  local repository_base detail artifact actual_sha
  repository_base="$NEXUS_URL/repository/$ANSIBLE_NEXUS_REPOSITORY"
  detail="$(mktemp)"
  artifact="$(mktemp)"
  curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$repository_base/api/v3/collections/$ANSIBLE_NAMESPACE/$ANSIBLE_COLLECTION/versions/$ANSIBLE_VERSION/" \
    >"$detail"
  python3 - "$detail" "$ANSIBLE_FIXTURE_SHA256" "$ANSIBLE_NAMESPACE" "$ANSIBLE_COLLECTION" "$ANSIBLE_VERSION" <<'PY'
import json
import pathlib
import sys

path, expected_sha, namespace, name, version = sys.argv[1:6]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
if (payload.get("namespace") or {}).get("name") != namespace:
    raise SystemExit(f"source Nexus Ansible namespace changed: {payload}")
if (payload.get("collection") or {}).get("name") != name or payload.get("version") != version:
    raise SystemExit(f"source Nexus Ansible coordinate changed: {payload}")
if (payload.get("artifact") or {}).get("sha256") != expected_sha:
    raise SystemExit(f"source Nexus Ansible artifact SHA-256 changed: {payload}")
PY
  curl -m 300 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$repository_base/api/v3/plugin/ansible/content/published/collections/artifacts/$ANSIBLE_NAMESPACE-$ANSIBLE_COLLECTION-$ANSIBLE_VERSION.tar.gz" \
    >"$artifact"
  actual_sha="$(file_sha256 "$artifact")"
  if [[ "$actual_sha" != "$ANSIBLE_FIXTURE_SHA256" ]]; then
    log "source Nexus Ansible artifact SHA-256 mismatch: expected=$ANSIBLE_FIXTURE_SHA256 actual=$actual_sha"
    rm -f "$detail" "$artifact"
    exit 1
  fi
  cmp "$ANSIBLE_FIXTURE_WORKDIR/MANIFEST.json" <(tar -xOzf "$artifact" MANIFEST.json)
  cmp "$ANSIBLE_FIXTURE_WORKDIR/FILES.json" <(tar -xOzf "$artifact" FILES.json)
  rm -f "$detail" "$artifact"
  log "source Nexus Ansible fixture verified: $ANSIBLE_NAMESPACE.$ANSIBLE_COLLECTION $ANSIBLE_VERSION"
}

warm_ansible_proxy_fixture() {
  local repository_base detail expected_sha expected_filename actual_sha
  repository_base="$NEXUS_URL/repository/$ANSIBLE_PROXY_NEXUS_REPOSITORY"
  detail="$(mktemp)"
  curl -m 180 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$repository_base/api/v3/collections/$ANSIBLE_PROXY_NAMESPACE/$ANSIBLE_PROXY_COLLECTION/versions/$ANSIBLE_PROXY_VERSION/" \
    >"$detail"
  read -r expected_sha expected_filename < <(python3 - "$detail" \
    "$ANSIBLE_PROXY_NAMESPACE" "$ANSIBLE_PROXY_COLLECTION" "$ANSIBLE_PROXY_VERSION" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
namespace, name, version = sys.argv[2:5]
artifact = payload.get("artifact") or {}
expected_filename = f"{namespace}-{name}-{version}.tar.gz"
if artifact.get("filename") not in (None, expected_filename):
    raise SystemExit(f"Nexus Ansible proxy returned a noncanonical filename: {payload}")
sha = artifact.get("sha256")
if not isinstance(sha, str) or len(sha) != 64:
    raise SystemExit(f"Nexus Ansible proxy omitted the artifact SHA-256: {payload}")
print(sha, expected_filename)
PY
)
  curl -m 300 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$repository_base/api/v3/plugin/ansible/content/published/collections/artifacts/$expected_filename" \
    >"$ANSIBLE_PROXY_FIXTURE_ARCHIVE"
  actual_sha="$(file_sha256 "$ANSIBLE_PROXY_FIXTURE_ARCHIVE")"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    log "warmed Nexus Ansible proxy SHA-256 mismatch: metadata=$expected_sha archive=$actual_sha"
    rm -f "$detail"
    exit 1
  fi
  tar -tzf "$ANSIBLE_PROXY_FIXTURE_ARCHIVE" MANIFEST.json >/dev/null
  tar -tzf "$ANSIBLE_PROXY_FIXTURE_ARCHIVE" FILES.json >/dev/null
  ANSIBLE_PROXY_FIXTURE_SHA256="$actual_sha"
  rm -f "$detail"
  log "warmed Nexus Ansible proxy fixture: $ANSIBLE_PROXY_NAMESPACE.$ANSIBLE_PROXY_COLLECTION $ANSIBLE_PROXY_VERSION sha256=$actual_sha"
}

configure_swift_source_proxy_authentication() {
  local response status
  response="$(mktemp)"
  status="$(curl -m 30 -sS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "{
      \"name\":\"$(json_escape "$SWIFT_PROXY_NEXUS_REPOSITORY")\",
      \"online\":true,
      \"storage\":{\"blobStoreName\":\"default\",\"strictContentTypeValidation\":true},
      \"proxy\":{\"remoteUrl\":\"https://github.com/\",\"contentMaxAge\":17,\"metadataMaxAge\":23},
      \"negativeCache\":{\"enabled\":true,\"timeToLive\":60},
      \"httpClient\":{
        \"blocked\":false,
        \"autoBlock\":false,
        \"authentication\":{
          \"type\":\"username\",
          \"username\":\"$(json_escape "$SWIFT_PROXY_USERNAME")\",
          \"password\":\"$(json_escape "$SWIFT_PROXY_SECRET")\"
        }
      }
    }" \
    -o "$response" \
    -w '%{http_code}' \
    "$NEXUS_URL/service/rest/v1/repositories/swift/proxy/$SWIFT_PROXY_NEXUS_REPOSITORY")"
  if [[ "$status" != "200" && "$status" != "204" ]]; then
    log "configuring authenticated Nexus Swift proxy returned HTTP $status"
    cat "$response" >&2 || true
    rm -f "$response"
    exit 1
  fi
  rm -f "$response"
  log "Nexus Swift proxy authentication fixture configured (secret omitted)"
}

prepare_swift_fixture() {
  local module metadata key certificate
  SWIFT_FIXTURE_WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-swift-migration.XXXXXX")"
  SWIFT_FIXTURE_ARCHIVE="$SWIFT_FIXTURE_WORKDIR/$SWIFT_PACKAGE-$SWIFT_VERSION.zip"
  SWIFT_FIXTURE_SIGNATURE="$SWIFT_FIXTURE_WORKDIR/source-archive.cms"
  SWIFT_FIXTURE_MANIFEST="$SWIFT_FIXTURE_WORKDIR/Package.swift"
  SWIFT_FIXTURE_VERSIONED_MANIFEST="$SWIFT_FIXTURE_WORKDIR/Package@swift-5.9.swift"
  metadata="$SWIFT_FIXTURE_WORKDIR/metadata.json"
  key="$SWIFT_FIXTURE_WORKDIR/signing-key.pem"
  certificate="$SWIFT_FIXTURE_WORKDIR/signing-certificate.pem"
  module="MigrationFixture"

  cat >"$SWIFT_FIXTURE_MANIFEST" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "$module",
    products: [.library(name: "$module", targets: ["$module"])],
    targets: [.target(name: "$module")]
)
// kkrepo Swift Nexus migration fixture
EOF
  cat >"$SWIFT_FIXTURE_VERSIONED_MANIFEST" <<EOF
// swift-tools-version:5.9
import PackageDescription
let package = Package(
    name: "$module",
    products: [.library(name: "$module", targets: ["$module"])],
    targets: [.target(name: "$module")]
)
// kkrepo Swift Nexus migration fixture swift-5.9
EOF
  cat >"$metadata" <<EOF
{
  "description":"$SWIFT_METADATA_DESCRIPTION",
  "repositoryURLs":["https://github.com/kkrepo-fixtures/$SWIFT_PACKAGE.git"],
  "author":{"name":"kkrepo migration e2e"},
  "originalPublicationTime":"$SWIFT_METADATA_PUBLICATION_TIME"
}
EOF

  python3 - \
    "$SWIFT_FIXTURE_ARCHIVE" \
    "$SWIFT_PACKAGE" \
    "$SWIFT_VERSION" \
    "$SWIFT_FIXTURE_MANIFEST" \
    "$SWIFT_FIXTURE_VERSIONED_MANIFEST" <<'PY'
import pathlib
import sys
import zipfile

archive, package, version, manifest, versioned = sys.argv[1:6]
root = f"{package}-{version}/"
entries = {
    root + "Package.swift": pathlib.Path(manifest).read_bytes(),
    root + "Package@swift-5.9.swift": pathlib.Path(versioned).read_bytes(),
    root + "Sources/MigrationFixture/MigrationFixture.swift": (
        b'public enum MigrationFixture { public static let answer = 42 }\n'
    ),
    root + "README.md": b"# Swift migration fixture\n",
}
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
    for name, body in entries.items():
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        target.writestr(info, body)
PY

  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$key" \
    -out "$certificate" \
    -days 1 \
    -subj '/CN=kkrepo Swift migration e2e' >/dev/null 2>&1
  openssl cms -sign -binary \
    -in "$SWIFT_FIXTURE_ARCHIVE" \
    -signer "$certificate" \
    -inkey "$key" \
    -outform DER \
    -out "$SWIFT_FIXTURE_SIGNATURE" \
    -md sha256 >/dev/null 2>&1
  openssl cms -verify -binary -inform DER \
    -in "$SWIFT_FIXTURE_SIGNATURE" \
    -content "$SWIFT_FIXTURE_ARCHIVE" \
    -noverify -out /dev/null >/dev/null 2>&1

  SWIFT_FIXTURE_SHA256="$(file_sha256 "$SWIFT_FIXTURE_ARCHIVE")"
  SWIFT_FIXTURE_SIGNATURE_BASE64="$(python3 - "$SWIFT_FIXTURE_SIGNATURE" <<'PY'
import base64
import pathlib
import sys
print(base64.b64encode(pathlib.Path(sys.argv[1]).read_bytes()).decode("ascii"))
PY
)"
}

publish_swift_fixture_to_source_nexus() {
  local metadata response status
  metadata="$SWIFT_FIXTURE_WORKDIR/metadata.json"
  response="$SWIFT_FIXTURE_WORKDIR/publish-response.txt"
  status="$(curl -m 120 -sS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X PUT \
    -H "Accept: application/vnd.swift.registry.v1+json" \
    -H "X-Swift-Package-Signature-Format: cms-1.0.0" \
    -H "Expect:" \
    -F "source-archive=@$SWIFT_FIXTURE_ARCHIVE;type=application/zip" \
    -F "source-archive-signature=@$SWIFT_FIXTURE_SIGNATURE;type=application/octet-stream" \
    -F "metadata=<$metadata;type=application/json" \
    -o "$response" \
    -w '%{http_code}' \
    "$NEXUS_URL/repository/$SWIFT_NEXUS_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION")"
  if [[ "$status" != "201" ]]; then
    log "publishing signed Swift fixture to Nexus returned HTTP $status"
    cat "$response" >&2 || true
    exit 1
  fi
  log "published signed Swift fixture to Nexus: $SWIFT_SCOPE.$SWIFT_PACKAGE $SWIFT_VERSION sha256=$SWIFT_FIXTURE_SHA256"
}

verify_source_swift_fixture() {
  local workdir metadata archive headers manifest versioned
  workdir="$SWIFT_FIXTURE_WORKDIR/source-verification"
  mkdir -p "$workdir"
  metadata="$workdir/metadata.json"
  archive="$workdir/archive.zip"
  headers="$workdir/archive.headers"
  manifest="$workdir/Package.swift"
  versioned="$workdir/Package@swift-5.9.swift"

  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -H "Accept: application/vnd.swift.registry.v1+json" \
    "$NEXUS_URL/repository/$SWIFT_NEXUS_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION" \
    >"$metadata"
  python3 - \
    "$metadata" \
    "$SWIFT_SCOPE.$SWIFT_PACKAGE" \
    "$SWIFT_VERSION" \
    "$SWIFT_FIXTURE_SHA256" <<'PY'
import json
import sys

path, identity, version, checksum = sys.argv[1:5]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
if str(payload.get("id") or "").lower() != identity.lower():
    raise SystemExit(f"Nexus Swift metadata identity changed: {payload.get('id')!r}")
if payload.get("version") != version:
    raise SystemExit(f"Nexus Swift metadata version changed: {payload.get('version')!r}")
resources = [
    resource for resource in payload.get("resources") or []
    if resource.get("name") == "source-archive" and resource.get("type") == "application/zip"
]
if len(resources) != 1 or str(resources[0].get("checksum") or "").lower() != checksum:
    raise SystemExit(f"Nexus Swift source-archive resource is incomplete: {resources}")
if resources[0].get("signing"):
    raise SystemExit(f"Nexus 3.94 unexpectedly re-exposed the uploaded signature: {resources[0]}")
if payload.get("metadata"):
    raise SystemExit(f"Nexus 3.94 unexpectedly re-exposed the uploaded metadata: {payload['metadata']}")
PY

  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -D "$headers" \
    -H "Accept: application/vnd.swift.registry.v1+zip" \
    "$NEXUS_URL/repository/$SWIFT_NEXUS_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION.zip" \
    >"$archive"
  if [[ "$(file_sha256 "$archive")" != "$SWIFT_FIXTURE_SHA256" ]]; then
    log "Nexus Swift source archive checksum changed after publish"
    exit 1
  fi
  if [[ -n "$(header_value 'X-Swift-Package-Signature-Format' "$headers")" \
      || -n "$(header_value 'X-Swift-Package-Signature' "$headers")" ]]; then
    log "Nexus 3.94 unexpectedly re-exposed Swift signature headers"
    exit 1
  fi
  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -H "Accept: application/vnd.swift.registry.v1+swift" \
    "$NEXUS_URL/repository/$SWIFT_NEXUS_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION/Package.swift" \
    >"$manifest"
  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -H "Accept: application/vnd.swift.registry.v1+swift" \
    "$NEXUS_URL/repository/$SWIFT_NEXUS_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION/Package.swift?swift-version=5.9" \
    >"$versioned"
  cmp -s "$manifest" "$SWIFT_FIXTURE_MANIFEST" || {
    log "Nexus Swift default manifest changed after publish"
    exit 1
  }
  if ! cmp -s "$versioned" "$SWIFT_FIXTURE_VERSIONED_MANIFEST" \
      && ! cmp -s "$versioned" "$SWIFT_FIXTURE_MANIFEST"; then
    log "Nexus Swift versioned-manifest response is neither the uploaded version nor its known default-manifest fallback"
    exit 1
  fi
  log "verified Nexus Swift fixture metadata, archive and manifests"
}

warm_terraform_proxy_fixture() {
  local workdir versions metadata archive fields metadata_url download_url downloaded_sha
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-terraform-proxy-warm.XXXXXX")"
  versions="$workdir/versions.json"
  metadata="$workdir/provider.json"
  archive="$workdir/provider.zip"
  fields="$workdir/provider-fields.txt"

  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$TERRAFORM_PROXY_NEXUS_REPOSITORY/v1/providers/$TERRAFORM_PROXY_PROVIDER_NAMESPACE/$TERRAFORM_PROXY_PROVIDER_NAME/versions" \
    >"$versions"
  python3 - "$versions" "$TERRAFORM_PROXY_PROVIDER_VERSION" <<'PY'
import json
import sys

path, expected = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
for row in payload.get("versions") or []:
    if row.get("version") == expected:
        platforms = row.get("platforms") or []
        if any(item.get("os") == "linux" and item.get("arch") == "amd64" for item in platforms):
            break
else:
    raise SystemExit(f"Nexus Terraform proxy does not expose {expected} for linux/amd64")
PY

  metadata_url="$NEXUS_URL/repository/$TERRAFORM_PROXY_NEXUS_REPOSITORY/v1/providers/$TERRAFORM_PROXY_PROVIDER_NAMESPACE/$TERRAFORM_PROXY_PROVIDER_NAME/$TERRAFORM_PROXY_PROVIDER_VERSION/download/linux/amd64"
  curl -m 60 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" "$metadata_url" >"$metadata"
  python3 - "$metadata" "$metadata_url" "$fields" <<'PY'
import json
import sys
from urllib.parse import urljoin, urlparse

metadata_path, metadata_url, output_path = sys.argv[1:4]
with open(metadata_path, "r", encoding="utf-8") as source:
    payload = json.load(source)
download_url = urljoin(metadata_url, str(payload.get("download_url") or ""))
filename = str(payload.get("filename") or "")
shasum = str(payload.get("shasum") or "").lower()
if not filename or len(shasum) != 64:
    raise SystemExit(f"Nexus Terraform proxy metadata is incomplete: {payload}")
segments = urlparse(download_url).path.strip("/").split("/")
try:
    marker = segments.index("v1")
except ValueError as error:
    raise SystemExit(f"Nexus Terraform proxy archive URL has no v1 path: {download_url}") from error
provider = segments[marker:]
if len(provider) < 9 or provider[1] != "providers" or provider[-4] != "download":
    raise SystemExit(f"Nexus Terraform proxy archive URL has an unexpected shape: {download_url}")
canonical = "v1/providers/" + "/".join(provider[-7:])
with open(output_path, "w", encoding="utf-8") as output:
    output.write(filename + "\n")
    output.write(shasum + "\n")
    output.write(download_url + "\n")
    output.write(canonical + "\n")
PY
  TERRAFORM_PROXY_PROVIDER_FILENAME="$(sed -n '1p' "$fields")"
  TERRAFORM_PROXY_PROVIDER_SHA256="$(sed -n '2p' "$fields")"
  download_url="$(sed -n '3p' "$fields")"
  TERRAFORM_PROXY_PROVIDER_PATH="$(sed -n '4p' "$fields")"
  curl -m 120 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" "$download_url" >"$archive"
  downloaded_sha="$(file_sha256 "$archive")"
  if [[ "$downloaded_sha" != "$TERRAFORM_PROXY_PROVIDER_SHA256" ]]; then
    log "Nexus Terraform proxy fixture checksum mismatch: metadata=$TERRAFORM_PROXY_PROVIDER_SHA256 downloaded=$downloaded_sha"
    exit 1
  fi
  rm -rf "$workdir"
  log "Nexus Terraform proxy cache warmed: $TERRAFORM_PROXY_PROVIDER_PATH sha256=$downloaded_sha"
}

warm_composer_proxy_fixture() {
  local metadata_file dist_file metadata_url dist_url actual_sha1 prefix
  local -a fields
  metadata_file="$(mktemp)"
  dist_file="$(mktemp)"
  metadata_url="${NEXUS_URL%/}/repository/$COMPOSER_NEXUS_REPOSITORY/p2/$COMPOSER_PACKAGE.json"

  curl -m 60 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "${NEXUS_URL%/}/repository/$COMPOSER_NEXUS_REPOSITORY/packages.json" >/dev/null
  curl -m 60 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$metadata_url" >"$metadata_file"
  mapfile -t fields < <(python3 - "$metadata_file" "$COMPOSER_PACKAGE" <<'PY'
import json
import sys

path, package = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
versions = (payload.get("packages") or {}).get(package) or []
for version in versions:
    dist = version.get("dist") or {}
    if version.get("version") and dist.get("url") and dist.get("type"):
        print(version["version"])
        print(dist["url"])
        print(dist["type"])
        print(dist.get("shasum") or "")
        break
else:
    raise SystemExit(f"Nexus Composer metadata has no dist version for {package}")
PY
)
  if [[ "${#fields[@]}" -ne 4 ]]; then
    log "could not parse Nexus Composer fixture metadata for $COMPOSER_PACKAGE"
    exit 1
  fi
  COMPOSER_VERSION="${fields[0]}"
  dist_url="$(python3 - "$metadata_url" "${fields[1]}" <<'PY'
import sys
from urllib.parse import urljoin
print(urljoin(sys.argv[1], sys.argv[2]))
PY
)"
  COMPOSER_DIST_TYPE="${fields[2]}"
  curl -m 120 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$dist_url" >"$dist_file"
  actual_sha1="$(file_sha1 "$dist_file")"
  expected_sha1="$(printf '%s' "${fields[3]}" | tr '[:upper:]' '[:lower:]')"
  actual_sha1_normalized="$(printf '%s' "$actual_sha1" | tr '[:upper:]' '[:lower:]')"
  if [[ -n "$expected_sha1" && "$expected_sha1" != "$actual_sha1_normalized" ]]; then
    log "Nexus Composer fixture SHA-1 mismatch: metadata=${fields[3]} downloaded=$actual_sha1"
    exit 1
  fi
  prefix="/repository/$COMPOSER_NEXUS_REPOSITORY/"
  COMPOSER_DIST_PATH="$(python3 - "$dist_url" "$prefix" <<'PY'
import sys
from urllib.parse import urlsplit
path = urlsplit(sys.argv[1]).path
prefix = sys.argv[2]
if not path.startswith(prefix):
    raise SystemExit(f"Nexus Composer dist URL is outside the repository: {path}")
print(path[len(prefix):])
PY
)"
  COMPOSER_DIST_SHA1="$actual_sha1"
  rm -f "$metadata_file" "$dist_file"
  log "Composer proxy fixture warmed: $COMPOSER_PACKAGE $COMPOSER_VERSION path=$COMPOSER_DIST_PATH"
}

publish_cargo_fixture_to_source_nexus() {
  local crate="$1"
  local version="$2"
  local workdir body crate_file sha256 status
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-cargo-migration.XXXXXX")"
  body="$workdir/publish.bin"
  crate_file="$workdir/${crate}-${version}.crate"
  python3 - "$crate" "$version" "$body" "$crate_file" <<'PY'
import gzip
import io
import json
import struct
import sys
import tarfile

name, version, body_path, crate_path = sys.argv[1:5]
manifest = (
    "[package]\n"
    f"name = \"{name}\"\n"
    f"version = \"{version}\"\n"
    "edition = \"2021\"\n"
    "description = \"kkrepo Cargo migration e2e fixture\"\n"
).encode()
lib = b"pub fn answer() -> u32 { 42 }\n"
crate_bytes = io.BytesIO()
with gzip.GzipFile(fileobj=crate_bytes, mode="wb", mtime=0) as gz:
    with tarfile.open(fileobj=gz, mode="w") as tar:
        for path, payload in [
            (f"{name}-{version}/Cargo.toml", manifest),
            (f"{name}-{version}/src/lib.rs", lib),
        ]:
            info = tarfile.TarInfo(path)
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
crate = crate_bytes.getvalue()
metadata = {
    "name": name,
    "vers": version,
    "deps": [],
    "features": {},
    "description": "kkrepo Cargo migration e2e fixture",
}
encoded = json.dumps(metadata, separators=(",", ":")).encode()
with open(body_path, "wb") as out:
    out.write(struct.pack("<I", len(encoded)))
    out.write(encoded)
    out.write(struct.pack("<I", len(crate)))
    out.write(crate)
with open(crate_path, "wb") as out:
    out.write(crate)
PY
  sha256="$(file_sha256 "$crate_file")"
  status="$(curl -m 60 -sS -o "$workdir/response.txt" -w '%{http_code}' \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X PUT \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"$body" \
    "$NEXUS_URL/repository/$CARGO_NEXUS_REPOSITORY/api/v1/crates/new")"
  if [[ "$status" != "200" ]]; then
    log "publish Cargo fixture returned HTTP $status"
    cat "$workdir/response.txt" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi
  rm -rf "$workdir"
  printf '%s' "$sha256"
}

verify_migrated_cargo_fixture() {
  local crate="$1"
  local version="$2"
  local expected_sha256="$3"
  local index_path index_file crate_file downloaded_sha
  index_path="$(cargo_index_path "$crate")"
  index_file="$(mktemp)"
  crate_file="$(mktemp)"
  curl -m 30 -fsS \
    -u "$(auth)" \
    "$KKREPO_URL/repository/$CARGO_KKREPO_REPOSITORY/config.json" >/dev/null
  curl -m 30 -fsS \
    -u "$(auth)" \
    "$KKREPO_URL/repository/$CARGO_KKREPO_REPOSITORY/$index_path" >"$index_file"
  python3 - "$index_file" "$crate" "$version" "$expected_sha256" <<'PY'
import json
import sys

path, crate, version, expected_sha256 = sys.argv[1:5]
with open(path, "r", encoding="utf-8") as source:
    entries = [json.loads(line) for line in source if line.strip()]
matches = [entry for entry in entries if entry.get("name") == crate and entry.get("vers") == version]
if not matches:
    raise SystemExit(f"Cargo sparse index did not expose {crate} {version}: {entries}")
entry = matches[0]
if entry.get("cksum") != expected_sha256:
    raise SystemExit(f"Cargo checksum mismatch in sparse index: {entry.get('cksum')} != {expected_sha256}")
if entry.get("yanked") is not False:
    raise SystemExit(f"Cargo yanked flag should be false: {entry}")
PY
  curl -m 30 -fsS \
    -u "$(auth)" \
    "$KKREPO_URL/repository/$CARGO_KKREPO_REPOSITORY/crates/$crate/$version/download" >"$crate_file"
  downloaded_sha="$(file_sha256 "$crate_file")"
  if [[ "$downloaded_sha" != "$expected_sha256" ]]; then
    log "Cargo crate sha256 mismatch: source=$expected_sha256 target=$downloaded_sha"
    rm -f "$index_file" "$crate_file"
    exit 1
  fi
  rm -f "$index_file" "$crate_file"
  log "Cargo fixture verified: $crate $version sha256=$expected_sha256"
}

publish_pub_fixture_to_source_nexus() {
  local package_name="$1"
  local version="$2"
  local workdir archive init_file upload_url_file fields_file session_file headers_file response_file
  local sha256 status upload_url finalize_location finalize_url session_id
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-pub-migration.XXXXXX")"
  archive="$workdir/${package_name}-${version}.tar.gz"
  init_file="$workdir/init.json"
  upload_url_file="$workdir/upload_url.txt"
  fields_file="$workdir/fields.txt"
  session_file="$workdir/session.txt"
  headers_file="$workdir/headers.txt"
  response_file="$workdir/response.txt"

  python3 - "$package_name" "$version" "$archive" <<'PY'
import gzip
import io
import sys
import tarfile

package_name, version, archive_path = sys.argv[1:4]
files = {
    "pubspec.yaml": (
        f"name: {package_name}\n"
        f"version: {version}\n"
        "description: kkRepo Pub migration e2e fixture\n"
        "environment:\n"
        "  sdk: '>=3.0.0 <4.0.0'\n"
    ).encode(),
    "lib/main.dart": b"int answer() => 42;\n",
}
with open(archive_path, "wb") as target:
    with gzip.GzipFile(fileobj=target, mode="wb", mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w") as tar:
            for path, payload in files.items():
                info = tarfile.TarInfo(path)
                info.size = len(payload)
                info.mtime = 0
                tar.addfile(info, io.BytesIO(payload))
PY
  sha256="$(file_sha256 "$archive")"

  status="$(curl -m 60 -sS -o "$init_file" -w '%{http_code}' \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$PUB_NEXUS_REPOSITORY/api/packages/versions/new")"
  if [[ "$status" != "200" ]]; then
    log "initialize Pub publish returned HTTP $status"
    cat "$init_file" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi

  python3 - "$init_file" "$upload_url_file" "$fields_file" "$session_file" <<'PY'
import json
import sys

init_path, upload_url_path, fields_path, session_path = sys.argv[1:5]
with open(init_path, "r", encoding="utf-8") as source:
    payload = json.load(source)
url = payload.get("url")
if not url:
    raise SystemExit(f"Pub publish init did not return upload url: {payload}")
fields = payload.get("fields") or {}
if not isinstance(fields, dict):
    raise SystemExit(f"Pub publish init fields is not an object: {fields!r}")
with open(upload_url_path, "w", encoding="utf-8") as target:
    target.write(str(url))
with open(fields_path, "w", encoding="utf-8") as target:
    for key, value in fields.items():
        target.write(f"{key}={value}\n")
with open(session_path, "w", encoding="utf-8") as target:
    target.write(str(fields.get("session") or ""))
PY
  upload_url="$(absolute_location "$(cat "$upload_url_file")")"
  local curl_args=(-m 60 -sS -D "$headers_file" -o "$response_file" -w '%{http_code}' -X POST)
  if [[ "$upload_url" == "${NEXUS_URL%/}/"* ]]; then
    curl_args+=(-u "$NEXUS_USER:$NEXUS_PASSWORD")
  fi
  while IFS= read -r field; do
    if [[ -n "$field" ]]; then
      curl_args+=(-F "$field")
    fi
  done <"$fields_file"
  curl_args+=(-F "file=@$archive;filename=${package_name}-${version}.tar.gz;type=application/octet-stream")
  status="$(curl "${curl_args[@]}" "$upload_url")"
  if [[ "$status" != "204" && "$status" != "303" ]]; then
    log "upload Pub fixture returned HTTP $status"
    cat "$response_file" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi

  finalize_location="$(header_location "$headers_file")"
  session_id="$(cat "$session_file")"
  if [[ -z "$finalize_location" && -n "$session_id" ]]; then
    finalize_location="/repository/$PUB_NEXUS_REPOSITORY/api/packages/versions/finalize/$session_id"
  fi
  if [[ -z "$finalize_location" ]]; then
    log "Pub upload response did not include a finalize Location"
    rm -rf "$workdir"
    exit 1
  fi
  finalize_url="$(absolute_location "$finalize_location")"
  status="$(curl -m 60 -sS -o "$response_file" -w '%{http_code}' \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$finalize_url")"
  if [[ "$status" != "200" ]]; then
    log "finalize Pub fixture returned HTTP $status"
    cat "$response_file" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi

  rm -rf "$workdir"
  printf '%s' "$sha256"
}

verify_migrated_pub_fixture() {
  local package_name="$1"
  local version="$2"
  local expected_sha256="$3"
  local metadata_file archive_url archive_file downloaded_sha
  metadata_file="$(mktemp)"
  archive_file="$(mktemp)"
  curl -m 30 -fsS \
    -u "$(auth)" \
    "$KKREPO_URL/repository/$PUB_KKREPO_REPOSITORY/api/packages/$package_name" >"$metadata_file"
  archive_url="$(python3 - "$metadata_file" "$package_name" "$version" "$expected_sha256" <<'PY'
import json
import sys

path, package_name, version, expected_sha256 = sys.argv[1:5]
with open(path, "r", encoding="utf-8") as source:
    body = json.load(source)
if body.get("name") != package_name:
    raise SystemExit(f"Pub metadata name mismatch: {body.get('name')!r} != {package_name!r}")
versions = body.get("versions") or []
matches = [entry for entry in versions if isinstance(entry, dict) and entry.get("version") == version]
if not matches:
    raise SystemExit(f"Pub metadata did not expose {package_name} {version}: {versions}")
entry = matches[0]
actual_sha256 = str(entry.get("archive_sha256") or "").lower()
if actual_sha256 != expected_sha256:
    raise SystemExit(f"Pub archive_sha256 mismatch: {actual_sha256} != {expected_sha256}")
archive_url = entry.get("archive_url")
if not archive_url:
    raise SystemExit(f"Pub metadata entry did not include archive_url: {entry}")
print(archive_url)
PY
)"
  if [[ "$archive_url" == http://* || "$archive_url" == https://* ]]; then
    :
  elif [[ "$archive_url" == /* ]]; then
    archive_url="${KKREPO_URL%/}$archive_url"
  else
    archive_url="${KKREPO_URL%/}/$archive_url"
  fi
  curl -m 30 -fsS \
    -u "$(auth)" \
    "$archive_url" >"$archive_file"
  downloaded_sha="$(file_sha256 "$archive_file")"
  if [[ "$downloaded_sha" != "$expected_sha256" ]]; then
    log "Pub archive sha256 mismatch: source=$expected_sha256 target=$downloaded_sha"
    rm -f "$metadata_file" "$archive_file"
    exit 1
  fi
  rm -f "$metadata_file" "$archive_file"
  log "Pub fixture verified: $package_name $version sha256=$expected_sha256"
}

verify_migrated_terraform_fixture() {
  local workdir source_modules target_modules source_providers target_providers
  local source_metadata target_metadata fields archive module_version provider_version
  local expected_sha downloaded_sha token
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-terraform-migration.XXXXXX")"
  source_modules="$workdir/source-modules.json"
  target_modules="$workdir/target-modules.json"
  source_providers="$workdir/source-providers.json"
  target_providers="$workdir/target-providers.json"
  source_metadata="$workdir/source-provider.json"
  target_metadata="$workdir/target-provider.json"
  fields="$workdir/provider-fields.txt"
  archive="$workdir/provider.zip"

  curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$TERRAFORM_NEXUS_REPOSITORY/v1/modules/kkrepo/fixture/aws/versions" \
    >"$source_modules"
  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/repository/$TERRAFORM_KKREPO_REPOSITORY/v1/modules/kkrepo/fixture/aws/versions" \
    >"$target_modules"
  module_version="$(python3 - "$source_modules" "$target_modules" <<'PY'
import json
import sys

def versions(path):
    with open(path, "r", encoding="utf-8") as source:
        body = json.load(source)
    return [row["version"] for row in body["modules"][0]["versions"]]

source = versions(sys.argv[1])
target = versions(sys.argv[2])
if not source or not set(source).issubset(set(target)):
    raise SystemExit(f"migrated Terraform module versions are incomplete: source={source} target={target}")
print(source[0])
PY
)"

  curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$TERRAFORM_NEXUS_REPOSITORY/v1/providers/kkrepo/fixture/versions" \
    >"$source_providers"
  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/repository/$TERRAFORM_KKREPO_REPOSITORY/v1/providers/kkrepo/fixture/versions" \
    >"$target_providers"
  provider_version="$(python3 - "$source_providers" "$target_providers" <<'PY'
import json
import sys

def versions(path):
    with open(path, "r", encoding="utf-8") as source:
        body = json.load(source)
    return [row["version"] for row in body["versions"]]

source = versions(sys.argv[1])
target = versions(sys.argv[2])
if not source or not set(source).issubset(set(target)):
    raise SystemExit(f"migrated Terraform provider versions are incomplete: source={source} target={target}")
print(source[0])
PY
)"

  curl -m 30 -fsS -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$NEXUS_URL/repository/$TERRAFORM_NEXUS_REPOSITORY/v1/providers/kkrepo/fixture/$provider_version/download/linux/amd64" \
    >"$source_metadata"
  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/repository/$TERRAFORM_KKREPO_REPOSITORY/v1/providers/kkrepo/fixture/$provider_version/download/linux/amd64" \
    >"$target_metadata"
  python3 - "$source_metadata" "$target_metadata" "$KKREPO_URL/repository/$TERRAFORM_KKREPO_REPOSITORY/" "$fields" <<'PY'
import json
import sys
from urllib.parse import urljoin, urlparse

source_path, target_path, repository_url, output_path = sys.argv[1:5]
with open(source_path, "r", encoding="utf-8") as handle:
    source = json.load(handle)
with open(target_path, "r", encoding="utf-8") as handle:
    target = json.load(handle)
for field in ("filename", "shasum"):
    if source.get(field) != target.get(field):
        raise SystemExit(f"migrated Terraform provider {field} changed: {source.get(field)!r} != {target.get(field)!r}")
source_keys = ((source.get("signing_keys") or {}).get("gpg_public_keys") or [])
target_keys = ((target.get("signing_keys") or {}).get("gpg_public_keys") or [])
if not source_keys or not target_keys or source_keys[0].get("key_id") != target_keys[0].get("key_id"):
    raise SystemExit("migrated Terraform signing key id changed")
with open(output_path, "w", encoding="utf-8") as output:
    output.write(str(target["shasum"]) + "\n")
    output.write(urljoin(repository_url, str(target["download_url"])) + "\n")
    source_path = urlparse(str(source["download_url"])).path
    marker = "/v1/providers/"
    marker_index = source_path.find(marker)
    if marker_index < 0:
        raise SystemExit(f"Nexus Terraform provider download URL has no registry path: {source_path!r}")
    provider_segments = source_path[marker_index + len(marker):].strip("/").split("/")
    if len(provider_segments) < 7 or provider_segments[-4] != "download":
        raise SystemExit(f"Nexus Terraform provider archive URL has an unexpected shape: {source_path!r}")
    # A private Nexus URL may include its credential segment before the namespace. Exercise the
    # path-compatible alias with target authentication instead of replaying a source credential.
    output.write(urljoin(repository_url, "v1/providers/" + "/".join(provider_segments[-7:])) + "\n")
PY
  expected_sha="$(sed -n '1p' "$fields")"
  curl -m 30 -fsS -u "$(auth)" "$(sed -n '2p' "$fields")" >"$archive"
  downloaded_sha="$(file_sha256 "$archive")"
  if [[ "$downloaded_sha" != "$expected_sha" ]]; then
    log "Terraform provider checksum mismatch after migration: $downloaded_sha != $expected_sha"
    exit 1
  fi
  curl -m 30 -fsS -u "$(auth)" "$(sed -n '3p' "$fields")" >"$archive"
  downloaded_sha="$(file_sha256 "$archive")"
  if [[ "$downloaded_sha" != "$expected_sha" ]]; then
    log "Terraform provider Nexus archive alias checksum mismatch after migration: $downloaded_sha != $expected_sha"
    exit 1
  fi

  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/repositories/$TERRAFORM_KKREPO_REPOSITORY" >"$workdir/repository.json"
  if grep -q 'BEGIN PGP PRIVATE KEY BLOCK\|source-key-passphrase' "$workdir/repository.json"; then
    log "Terraform signing secret leaked through migrated repository JSON"
    exit 1
  fi

  token="$(printf '%s' "$(auth)" | base64 | tr -d '\r\n')"
  mkdir -p "$workdir/module"
  cat >"$workdir/terraform.rc" <<EOF
host "registry.terraform.io" {
  services = {
    "modules.v1" = "$KKREPO_URL/repository/$TERRAFORM_KKREPO_REPOSITORY/v1/modules/$token/"
  }
}
EOF
  cat >"$workdir/module/main.tf" <<EOF
module "fixture" {
  source  = "registry.terraform.io/kkrepo/fixture/aws"
  version = "$module_version"
}
EOF
  TF_CLI_CONFIG_FILE="$workdir/terraform.rc" \
    "$TERRAFORM_CURRENT_BIN" -chdir="$workdir/module" init -backend=false -input=false
  test -f "$workdir/module/.terraform/modules/fixture/main.tf"
  rm -rf "$workdir"
  log "Terraform fixture verified after migration: module=$module_version provider=$provider_version sha256=$expected_sha"
}

verify_migrated_terraform_proxy_fixture() {
  local job_id="$1"
  local workdir job_file detail_file metadata_file archive_file encoded_path token downloaded_sha download_url
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-terraform-proxy-migration.XXXXXX")"
  job_file="$workdir/job.json"
  detail_file="$workdir/detail.json"
  metadata_file="$workdir/provider.json"
  archive_file="$workdir/provider.zip"

  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id" >"$job_file"
  python3 - "$job_file" "$TERRAFORM_PROXY_NEXUS_REPOSITORY" <<'PY'
import json
import sys

path, repository = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
rows = payload.get("repositoryJobs") or payload.get("repositoryStatuses") or payload.get("repositoryDetails") or []
matches = [row for row in rows if (
    row.get("sourceRepositoryName") or row.get("repositoryName") or row.get("name")
) == repository]
if not matches:
    raise SystemExit(f"Terraform proxy migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 1:
    raise SystemExit(f"Terraform proxy migration did not migrate any assets: {row}")
if int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"Terraform proxy migration has failed assets: {row}")
PY

  encoded_path="$(python3 - "$TERRAFORM_PROXY_PROVIDER_PATH" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
)"
  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/browse/$TERRAFORM_PROXY_KKREPO_REPOSITORY/attributes?path=$encoded_path" \
    >"$detail_file"
  python3 - "$detail_file" "$TERRAFORM_PROXY_PROVIDER_PATH" "$TERRAFORM_PROXY_PROVIDER_SHA256" <<'PY'
import json
import sys

path, expected_path, expected_sha256 = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as source:
    detail = json.load(source)
if detail.get("path") != expected_path:
    raise SystemExit(f"migrated Terraform proxy path changed: {detail.get('path')!r}")
actual_sha256 = str((detail.get("checksum") or {}).get("sha256") or "").lower()
if actual_sha256 != expected_sha256:
    raise SystemExit(f"migrated Terraform proxy SHA-256 changed: {actual_sha256!r} != {expected_sha256!r}")
PY

  curl -m 60 -fsS -u "$(auth)" \
    "$KKREPO_URL/repository/$TERRAFORM_PROXY_KKREPO_REPOSITORY/v1/providers/$TERRAFORM_PROXY_PROVIDER_NAMESPACE/$TERRAFORM_PROXY_PROVIDER_NAME/$TERRAFORM_PROXY_PROVIDER_VERSION/download/linux/amd64" \
    >"$metadata_file"
  python3 - "$metadata_file" "$TERRAFORM_PROXY_PROVIDER_FILENAME" "$TERRAFORM_PROXY_PROVIDER_SHA256" <<'PY'
import json
import sys

path, expected_filename, expected_sha256 = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
if payload.get("filename") != expected_filename:
    raise SystemExit(f"migrated Terraform proxy filename changed: {payload.get('filename')!r}")
if str(payload.get("shasum") or "").lower() != expected_sha256:
    raise SystemExit(f"migrated Terraform proxy metadata SHA-256 changed: {payload.get('shasum')!r}")
PY

  token="$(printf '%s' "$(auth)" | base64 | tr -d '\r\n')"
  mkdir -p "$workdir/client"
  cat >"$workdir/terraform.rc" <<EOF
host "registry.terraform.io" {
  services = {
    "providers.v1" = "$KKREPO_URL/repository/$TERRAFORM_PROXY_KKREPO_REPOSITORY/v1/providers/$token/"
  }
}
EOF
  cat >"$workdir/client/main.tf" <<EOF
terraform {
  required_providers {
    null = {
      source  = "$TERRAFORM_PROXY_PROVIDER_NAMESPACE/$TERRAFORM_PROXY_PROVIDER_NAME"
      version = "$TERRAFORM_PROXY_PROVIDER_VERSION"
    }
  }
}
EOF
  TF_CLI_CONFIG_FILE="$workdir/terraform.rc" \
    "$TERRAFORM_CURRENT_BIN" -chdir="$workdir/client" init -backend=false -input=false -no-color
  test -d "$workdir/client/.terraform/providers/registry.terraform.io/$TERRAFORM_PROXY_PROVIDER_NAMESPACE/$TERRAFORM_PROXY_PROVIDER_NAME/$TERRAFORM_PROXY_PROVIDER_VERSION/linux_amd64"

  download_url="$(python3 - "$metadata_file" "$KKREPO_URL/repository/$TERRAFORM_PROXY_KKREPO_REPOSITORY/" <<'PY'
import json
import sys
from urllib.parse import urljoin
with open(sys.argv[1], "r", encoding="utf-8") as source:
    payload = json.load(source)
print(urljoin(sys.argv[2], str(payload["download_url"])))
PY
)"
  curl -m 60 -fsS -u "$(auth)" "$download_url" >"$archive_file"
  downloaded_sha="$(file_sha256 "$archive_file")"
  if [[ "$downloaded_sha" != "$TERRAFORM_PROXY_PROVIDER_SHA256" ]]; then
    log "migrated Terraform proxy archive checksum mismatch: source=$TERRAFORM_PROXY_PROVIDER_SHA256 target=$downloaded_sha"
    exit 1
  fi
  rm -rf "$workdir"
  log "Terraform proxy cache migration verified with terraform init: $TERRAFORM_PROXY_PROVIDER_PATH sha256=$downloaded_sha"
}

verify_composer_requires_explicit_proxy_selection() {
  local response status
  response="$(mktemp)"
  status="$(curl -m 60 -sS \
    -u "$(auth)" \
    -H "Content-Type: application/json" \
    --data "{
      \"sourceBaseUrl\":\"$(json_escape "$NEXUS_URL")\",
      \"sourceUsername\":\"$(json_escape "$NEXUS_USER")\",
      \"sourcePassword\":\"$(json_escape "$NEXUS_PASSWORD")\",
      \"repositories\":[\"$(json_escape "$COMPOSER_NEXUS_REPOSITORY")\"],
      \"checksumValidation\":true
    }" \
    -o "$response" \
    -w '%{http_code}' \
    "$KKREPO_URL/internal/migration/nexus/repository-data/start")"
  if [[ "$status" != "400" ]]; then
    log "Composer proxy migration without backupProxyRepositories returned HTTP $status, expected 400"
    cat "$response" >&2 || true
    exit 1
  fi
  if ! grep -qi 'proxy' "$response"; then
    log "Composer proxy migration rejection did not explain the proxy selection error"
    cat "$response" >&2 || true
    exit 1
  fi
  rm -f "$response"
  log "Composer proxy requires explicit backupProxyRepositories selection"
}

verify_migrated_composer_fixture() {
  local job_id="$1"
  local job_file repo_file update_file detail_file root_file metadata_file dist_file
  local encoded_path target_dist_url target_sha1
  job_file="$(mktemp)"
  repo_file="$(mktemp)"
  update_file="$(mktemp)"
  detail_file="$(mktemp)"
  root_file="$(mktemp)"
  metadata_file="$(mktemp)"
  dist_file="$(mktemp)"

  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id" >"$job_file"
  python3 - "$job_file" "$COMPOSER_NEXUS_REPOSITORY" <<'PY'
import json
import sys

path, repository = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
rows = payload.get("repositoryJobs") or payload.get("repositoryStatuses") or payload.get("repositoryDetails") or []
matches = [row for row in rows if (
    row.get("sourceRepositoryName") or row.get("repositoryName") or row.get("name")
) == repository]
if not matches:
    raise SystemExit(f"Composer migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 1:
    raise SystemExit(f"Composer migration did not migrate any assets: {row}")
if int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"Composer migration has failed assets: {row}")
PY

  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/repositories/$COMPOSER_KKREPO_REPOSITORY" >"$repo_file"
  python3 - "$repo_file" "$update_file" <<'PY'
import json
import sys

source_path, update_path = sys.argv[1:3]
with open(source_path, "r", encoding="utf-8") as source:
    repository = json.load(source)
if repository.get("recipe") != "composer-proxy" or repository.get("type") != "PROXY":
    raise SystemExit(f"migrated Composer repository did not remain proxy: {repository}")
proxy = repository.get("proxy") or {}
if proxy.get("remoteUrl") != "https://repo.packagist.org/":
    raise SystemExit(f"migrated Composer remote URL changed: {proxy.get('remoteUrl')!r}")
update = {
    "online": True,
    "blobStoreName": repository.get("blobStoreName"),
    "strictContentTypeValidation": repository.get("strictContentTypeValidation"),
    "proxy": {
        # Use a resolvable public host so repository URL validation accepts the update. Port 1 is
        # intentionally unusable; successful reads below therefore prove the migrated cache is
        # sufficient without waiting on or depending on the Composer upstream.
        "remoteUrl": "https://example.com:1/composer-migration/",
        "contentMaxAgeMinutes": proxy.get("contentMaxAgeMinutes"),
        "metadataMaxAgeMinutes": proxy.get("metadataMaxAgeMinutes"),
        "autoBlock": proxy.get("autoBlock"),
    },
}
with open(update_path, "w", encoding="utf-8") as target:
    json.dump(update, target, separators=(",", ":"))
PY

  encoded_path="$(python3 - "$COMPOSER_DIST_PATH" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
)"
  curl -m 30 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/browse/$COMPOSER_KKREPO_REPOSITORY/attributes?path=$encoded_path" >"$detail_file"
  python3 - "$detail_file" "$COMPOSER_DIST_PATH" "$COMPOSER_DIST_SHA1" <<'PY'
import json
import sys

path, expected_path, expected_sha1 = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as source:
    detail = json.load(source)
if detail.get("path") != expected_path:
    raise SystemExit(f"migrated Composer dist path changed: {detail.get('path')!r}")
actual_sha1 = (detail.get("checksum") or {}).get("sha1")
if actual_sha1 != expected_sha1:
    raise SystemExit(f"migrated Composer dist SHA-1 changed: {actual_sha1!r}")
PY

  curl -m 30 -fsS -u "$(auth)" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data-binary "@$update_file" \
    "$KKREPO_URL/internal/repositories/$COMPOSER_KKREPO_REPOSITORY" >/dev/null
  curl -m 60 -fsS -u "$(auth)" \
    "$KKREPO_URL/repository/$COMPOSER_KKREPO_REPOSITORY/packages.json" >"$root_file"
  python3 - "$root_file" "$KKREPO_URL/repository/$COMPOSER_KKREPO_REPOSITORY" <<'PY'
import json
import sys

path, repository_url = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
expected = repository_url.rstrip("/") + "/p2/%package%.json"
if payload.get("metadata-url") != expected:
    raise SystemExit(
        f"migrated Composer packages.json metadata-url changed: {payload.get('metadata-url')!r}"
    )
PY
  curl -m 60 -fsS -u "$(auth)" \
    "$KKREPO_URL/repository/$COMPOSER_KKREPO_REPOSITORY/p2/$COMPOSER_PACKAGE.json" >"$metadata_file"
  target_dist_url="$(python3 - "$metadata_file" "$COMPOSER_PACKAGE" "$COMPOSER_VERSION" <<'PY'
import json
import sys

path, package, expected_version = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
versions = (payload.get("packages") or {}).get(package) or []
for version in versions:
    if version.get("version") == expected_version and (version.get("dist") or {}).get("url"):
        print(version["dist"]["url"])
        break
else:
    raise SystemExit(f"migrated Composer metadata lacks {package} {expected_version}")
PY
)"
  target_dist_url="$(python3 - "$KKREPO_URL/repository/$COMPOSER_KKREPO_REPOSITORY/" "$target_dist_url" <<'PY'
import sys
from urllib.parse import urljoin
print(urljoin(sys.argv[1], sys.argv[2]))
PY
)"
  curl -m 60 -fsS -u "$(auth)" "$target_dist_url" >"$dist_file"
  target_sha1="$(file_sha1 "$dist_file")"
  if [[ "$target_sha1" != "$COMPOSER_DIST_SHA1" ]]; then
    log "migrated Composer dist SHA-1 mismatch: source=$COMPOSER_DIST_SHA1 target=$target_sha1"
    exit 1
  fi
  rm -f "$job_file" "$repo_file" "$update_file" "$detail_file" "$root_file" "$metadata_file" "$dist_file"
  log "Composer proxy migration verified offline: $COMPOSER_PACKAGE $COMPOSER_VERSION sha1=$target_sha1"
}

verify_swift_repository_definitions() {
  local target_url="${1:-$KKREPO_URL}"
  local label="${2:-primary}"
  local expected_proxy_online="${3:-false}"
  local expected_proxy_credential="${4:-missing}"
  local workdir hosted proxy group
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-swift-definition.XXXXXX")"
  hosted="$workdir/hosted.json"
  proxy="$workdir/proxy.json"
  group="$workdir/group.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$SWIFT_KKREPO_REPOSITORY" >"$hosted"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$SWIFT_PROXY_KKREPO_REPOSITORY" >"$proxy"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$SWIFT_GROUP_KKREPO_REPOSITORY" >"$group"
  python3 - \
    "$hosted" "$proxy" "$group" \
    "$SWIFT_PROXY_USERNAME" "$SWIFT_PROXY_SECRET" \
    "$SWIFT_KKREPO_REPOSITORY" "$SWIFT_PROXY_KKREPO_REPOSITORY" \
    "$expected_proxy_online" "$expected_proxy_credential" <<'PY'
import json
import pathlib
import sys

(
    hosted_path,
    proxy_path,
    group_path,
    expected_username,
    forbidden_secret,
    expected_hosted,
    expected_proxy,
    expected_proxy_online,
    expected_proxy_credential,
) = sys.argv[1:10]
hosted = json.loads(pathlib.Path(hosted_path).read_text(encoding="utf-8"))
proxy = json.loads(pathlib.Path(proxy_path).read_text(encoding="utf-8"))
group = json.loads(pathlib.Path(group_path).read_text(encoding="utf-8"))
if hosted.get("recipe") != "swift-hosted" or hosted.get("type") != "HOSTED":
    raise SystemExit(f"migrated Swift hosted definition is invalid: {hosted}")
if (hosted.get("hosted") or {}).get("writePolicy") != "ALLOW_ONCE":
    raise SystemExit(f"migrated Swift hosted write policy changed: {hosted.get('hosted')}")
if proxy.get("recipe") != "swift-proxy" or proxy.get("type") != "PROXY":
    raise SystemExit(f"migrated Swift proxy definition is invalid: {proxy}")
if proxy.get("online") != (expected_proxy_online == "true"):
    raise SystemExit(
        f"migrated Swift proxy online state is {proxy.get('online')!r}, "
        f"expected {expected_proxy_online}"
    )
settings = proxy.get("proxy") or {}
if settings.get("remoteUrl") != "https://github.com/":
    raise SystemExit(f"migrated Swift proxy remote changed: {settings}")
if settings.get("contentMaxAgeMinutes") != 17 or settings.get("metadataMaxAgeMinutes") != 23:
    raise SystemExit(f"migrated Swift proxy TTLs changed: {settings}")
if settings.get("autoBlock") is not False:
    raise SystemExit(f"migrated Swift proxy autoBlock changed: {settings}")
if expected_proxy_credential == "configured" and settings.get("remoteUsername") != expected_username:
    raise SystemExit(f"manually configured Swift proxy username changed: {settings}")
if expected_proxy_credential == "missing" and settings.get("remoteUsername") not in (None, expected_username):
    raise SystemExit(f"migrated Swift proxy username changed: {settings}")
if settings.get("remotePassword") is not None or settings.get("remoteBearerToken") is not None:
    raise SystemExit("migrated Swift proxy API exposed an upstream secret")
if bool(settings.get("remotePasswordConfigured")) != (expected_proxy_credential == "configured"):
    raise SystemExit(
        "migrated Swift proxy API credential marker changed: "
        f"expected={expected_proxy_credential} proxy={settings}"
    )
if forbidden_secret in pathlib.Path(proxy_path).read_text(encoding="utf-8"):
    raise SystemExit("migrated Swift proxy API leaked the source password")
if group.get("recipe") != "swift-group" or group.get("type") != "GROUP":
    raise SystemExit(f"migrated Swift group definition is invalid: {group}")
if (group.get("group") or {}).get("memberNames") != [expected_hosted, expected_proxy]:
    raise SystemExit(f"migrated Swift group member order changed: {group.get('group')}")
PY
  rm -rf "$workdir"
  log "Swift repository definitions verified through $label replica (proxyOnline=$expected_proxy_online credential=$expected_proxy_credential)"
}

verify_swift_proxy_secret_storage() {
  local expected_credential="${1:-missing}"
  local label="${2:-target database}"
  local attributes repository_name
  attributes="$(mktemp)"
  repository_name="$(sql_literal "$SWIFT_PROXY_KKREPO_REPOSITORY")"
  target_db_query \
    "SELECT attributes_json FROM repository WHERE name = $repository_name" \
    >"$attributes"
  python3 - \
    "$attributes" "$SWIFT_PROXY_SECRET" "$SWIFT_PROXY_USERNAME" \
    "$expected_credential" <<'PY'
import json
import pathlib
import sys

path, forbidden, expected_username, expected_credential = sys.argv[1:5]
raw = pathlib.Path(path).read_text(encoding="utf-8").strip()
if not raw:
    raise SystemExit("migrated Swift proxy database row is missing")
if forbidden in raw:
    raise SystemExit("migrated Swift proxy source password is plaintext in the database")
payload = json.loads(raw)
proxy = payload.get("proxy") or {}
stored_password = proxy.get("remotePassword")
if expected_credential == "missing":
    if stored_password is not None or proxy.get("remoteBearerToken") is not None:
        raise SystemExit(
            "fail-closed Swift proxy retained an unavailable credential: "
            f"password={stored_password!r} bearer={proxy.get('remoteBearerToken')!r}"
        )
elif expected_credential == "configured":
    if not isinstance(stored_password, str) or not stored_password.startswith("{aes-gcm-v1}"):
        raise SystemExit("manually supplied Swift proxy password is not AES-GCM ciphertext")
    if proxy.get("remoteBearerToken") is not None:
        raise SystemExit("manually configured basic-auth proxy unexpectedly stores a bearer token")
else:
    raise SystemExit(f"unknown expected credential state: {expected_credential}")

source_repository = payload.get("sourceRepository")
if not isinstance(source_repository, dict):
    raise SystemExit("migrated Swift proxy source snapshot is missing")

def authentication(value):
    if isinstance(value, dict):
        candidate = value.get("authentication")
        if isinstance(candidate, dict):
            return candidate
        for child in value.values():
            found = authentication(child)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = authentication(child)
            if found is not None:
                return found
    return None

source_authentication = authentication(source_repository)
if source_authentication is None:
    raise SystemExit("migrated Swift proxy source authentication snapshot is missing")
if source_authentication.get("username") != expected_username:
    raise SystemExit(f"migrated Swift proxy source username changed: {source_authentication}")

redacted_fields = []
def visit(value, inside_source=False):
    if isinstance(value, dict):
        for key, child in value.items():
            child_inside = inside_source or key == "sourceRepository"
            normalized = key.lower()
            if child_inside and any(part in normalized for part in (
                "password", "passphrase", "secret", "credential", "bearer", "token"
            )):
                redacted_fields.append(child)
            visit(child, child_inside)
    elif isinstance(value, list):
        for child in value:
            visit(child, inside_source)
visit(payload)
if any(value != "<redacted>" for value in redacted_fields):
    raise SystemExit(f"migrated Swift source authentication was not recursively redacted: {redacted_fields}")
PY
  rm -f "$attributes"
  log "Swift proxy credentials verified through $label (database=$KKREPO_TARGET_DATABASE credential=$expected_credential)"
}

configure_swift_target_proxy_credentials() {
  local target_url="${1:-$KKREPO_URL}"
  local label="${2:-primary}"
  local response status
  response="$(mktemp)"
  status="$(curl -m 30 -sS \
    -u "$(auth)" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "{
      \"online\":true,
      \"proxy\":{
        \"remoteUrl\":\"https://github.com/\",
        \"contentMaxAgeMinutes\":17,
        \"metadataMaxAgeMinutes\":23,
        \"autoBlock\":false,
        \"remoteUsername\":\"$(json_escape "$SWIFT_PROXY_USERNAME")\",
        \"remotePassword\":\"$(json_escape "$SWIFT_PROXY_SECRET")\",
        \"remotePasswordConfigured\":true,
        \"remoteBearerTokenConfigured\":false
      }
    }" \
    -o "$response" \
    -w '%{http_code}' \
    "$target_url/internal/repositories/$SWIFT_PROXY_KKREPO_REPOSITORY")"
  if [[ "$status" != "200" ]]; then
    log "manual Swift proxy credential completion through $label returned HTTP $status"
    rm -f "$response"
    exit 1
  fi
  if grep -Fq -- "$SWIFT_PROXY_SECRET" "$response"; then
    log "manual Swift proxy update response exposed the supplied secret"
    rm -f "$response"
    exit 1
  fi
  rm -f "$response"
  verify_swift_repository_definitions "$target_url" "$label after manual credential completion" true configured
  verify_swift_proxy_secret_storage configured "$label after manual credential completion"
  log "Swift proxy credential was explicitly completed through the admin API on $label"
}

verify_ansible_repository_definitions() {
  local target_url="${1:-$KKREPO_URL}"
  local label="${2:-primary}"
  local workdir hosted proxy group secret_proxy
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-ansible-definition.XXXXXX")"
  hosted="$workdir/hosted.json"
  proxy="$workdir/proxy.json"
  group="$workdir/group.json"
  secret_proxy="$workdir/secret-proxy.json"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ANSIBLE_KKREPO_REPOSITORY" >"$hosted"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ANSIBLE_PROXY_KKREPO_REPOSITORY" >"$proxy"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ANSIBLE_GROUP_KKREPO_REPOSITORY" >"$group"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/repositories/$ANSIBLE_SECRET_PROXY_KKREPO_REPOSITORY" >"$secret_proxy"
  python3 - \
    "$hosted" "$proxy" "$group" "$secret_proxy" \
    "$ANSIBLE_KKREPO_REPOSITORY" "$ANSIBLE_PROXY_KKREPO_REPOSITORY" \
    "$ANSIBLE_SECRET_PROXY_USERNAME" "$ANSIBLE_SECRET_PROXY_SECRET" <<'PY'
import json
import pathlib
import sys

(
    hosted_path,
    proxy_path,
    group_path,
    secret_proxy_path,
    expected_hosted,
    expected_proxy,
    expected_secret_username,
    forbidden_secret,
) = sys.argv[1:9]
hosted = json.loads(pathlib.Path(hosted_path).read_text(encoding="utf-8"))
proxy = json.loads(pathlib.Path(proxy_path).read_text(encoding="utf-8"))
group = json.loads(pathlib.Path(group_path).read_text(encoding="utf-8"))
secret_proxy = json.loads(pathlib.Path(secret_proxy_path).read_text(encoding="utf-8"))

if hosted.get("recipe") != "ansiblegalaxy-hosted" or hosted.get("type") != "HOSTED":
    raise SystemExit(f"migrated Ansible hosted definition is invalid: {hosted}")
if (hosted.get("hosted") or {}).get("writePolicy") != "ALLOW_ONCE":
    raise SystemExit(f"migrated Ansible hosted write policy changed: {hosted.get('hosted')}")
if proxy.get("recipe") != "ansiblegalaxy-proxy" or proxy.get("type") != "PROXY":
    raise SystemExit(f"migrated Ansible proxy definition is invalid: {proxy}")
if proxy.get("online") is not True:
    raise SystemExit(f"migrated unauthenticated Ansible proxy is offline: {proxy}")
proxy_settings = proxy.get("proxy") or {}
if proxy_settings.get("remoteUrl") != "https://galaxy.ansible.com/":
    raise SystemExit(f"migrated Ansible proxy remote changed: {proxy_settings}")
if group.get("recipe") != "ansiblegalaxy-group" or group.get("type") != "GROUP":
    raise SystemExit(f"migrated Ansible group definition is invalid: {group}")
if (group.get("group") or {}).get("memberNames") != [expected_hosted, expected_proxy]:
    raise SystemExit(f"migrated Ansible group member order changed: {group.get('group')}")

if secret_proxy.get("recipe") != "ansiblegalaxy-proxy" or secret_proxy.get("type") != "PROXY":
    raise SystemExit(f"migrated credentialed Ansible proxy definition is invalid: {secret_proxy}")
if secret_proxy.get("online") is not False:
    raise SystemExit(f"Ansible proxy with an unavailable source secret did not migrate offline: {secret_proxy}")
secret_settings = secret_proxy.get("proxy") or {}
if secret_settings.get("remoteUrl") != "https://galaxy.ansible.com/":
    raise SystemExit(f"migrated credentialed Ansible proxy remote changed: {secret_settings}")
if secret_settings.get("contentMaxAgeMinutes") != 17 or secret_settings.get("metadataMaxAgeMinutes") != 23:
    raise SystemExit(f"migrated credentialed Ansible proxy TTLs changed: {secret_settings}")
if secret_settings.get("autoBlock") is not False:
    raise SystemExit(f"migrated credentialed Ansible proxy autoBlock changed: {secret_settings}")
if secret_settings.get("remoteUsername") not in (None, expected_secret_username):
    raise SystemExit(f"migrated Ansible proxy username changed: {secret_settings}")
if secret_settings.get("remotePassword") is not None or secret_settings.get("remoteBearerToken") is not None:
    raise SystemExit("migrated Ansible proxy API exposed an upstream secret")
if secret_settings.get("remotePasswordConfigured") is True:
    raise SystemExit("migrated Ansible proxy wrote a placeholder credential")
if forbidden_secret in pathlib.Path(secret_proxy_path).read_text(encoding="utf-8"):
    raise SystemExit("migrated Ansible proxy API leaked the source password")
PY
  rm -rf "$workdir"
  log "Ansible repository definitions verified through $label replica (credentialed proxy is fail-closed offline)"
}

verify_ansible_secret_proxy_storage() {
  local attributes repository_name
  attributes="$(mktemp)"
  repository_name="$(sql_literal "$ANSIBLE_SECRET_PROXY_KKREPO_REPOSITORY")"
  target_db_query \
    "SELECT attributes_json FROM repository WHERE name = $repository_name" \
    >"$attributes"
  python3 - \
    "$attributes" "$ANSIBLE_SECRET_PROXY_SECRET" "$ANSIBLE_SECRET_PROXY_USERNAME" <<'PY'
import json
import pathlib
import sys

path, forbidden, expected_username = sys.argv[1:4]
raw = pathlib.Path(path).read_text(encoding="utf-8").strip()
if not raw:
    raise SystemExit("migrated Ansible credentialed proxy database row is missing")
if forbidden in raw:
    raise SystemExit("migrated Ansible proxy source password is plaintext in the database")
payload = json.loads(raw)
proxy = payload.get("proxy") or {}
if proxy.get("remotePassword") is not None or proxy.get("remoteBearerToken") is not None:
    raise SystemExit("fail-closed Ansible proxy retained an unavailable credential")

source_repository = payload.get("sourceRepository")
if not isinstance(source_repository, dict):
    raise SystemExit("migrated Ansible proxy source snapshot is missing")

def authentication(value):
    if isinstance(value, dict):
        candidate = value.get("authentication")
        if isinstance(candidate, dict):
            return candidate
        for child in value.values():
            found = authentication(child)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = authentication(child)
            if found is not None:
                return found
    return None

source_authentication = authentication(source_repository)
if source_authentication is None:
    raise SystemExit("migrated Ansible proxy source authentication snapshot is missing")
if source_authentication.get("username") != expected_username:
    raise SystemExit(f"migrated Ansible proxy source username changed: {source_authentication}")
source_password = source_authentication.get("password")
if source_password not in (None, "<redacted>"):
    raise SystemExit(f"migrated Ansible proxy source password was not omitted or redacted: {source_authentication}")
PY
  rm -f "$attributes"
  log "Ansible fail-closed proxy secret storage verified in $KKREPO_TARGET_DATABASE"
}

ansible_collection_database_snapshot() {
  local repository namespace collection version
  repository="$(sql_literal "$1")"
  namespace="$(sql_literal "$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')")"
  collection="$(sql_literal "$(printf '%s' "$3" | tr '[:upper:]' '[:lower:]')")"
  version="$(sql_literal "$4")"
  target_db_query "
    SELECT acv.artifact_sha256, acv.artifact_size, acv.metadata_json,
           acv.dependencies_json, ab.object_key, ab.size, ab.content_type, a.path
      FROM ansible_collection_version acv
      JOIN repository r ON r.id = acv.repository_id
      JOIN asset a ON a.id = acv.artifact_asset_id
      JOIN asset_blob ab ON ab.id = a.asset_blob_id
     WHERE r.name = $repository AND acv.namespace_lc = $namespace
       AND acv.name_lc = $collection AND acv.version_normalized = $version"
}

verify_ansible_database_and_blob() {
  local repository="$1"
  local namespace="$2"
  local collection="$3"
  local version="$4"
  local expected_archive="$5"
  local expected_sha="$6"
  local label="$7"
  local snapshot object_key blob_dir blob_copy actual_sha
  snapshot="$(mktemp)"
  ansible_collection_database_snapshot "$repository" "$namespace" "$collection" "$version" >"$snapshot"
  object_key="$(python3 - \
    "$snapshot" "$expected_sha" "$(file_size "$expected_archive")" "$label" <<'PY'
import json
import pathlib
import sys

path, expected_sha, expected_size, label = sys.argv[1:5]
raw = pathlib.Path(path).read_text(encoding="utf-8").strip()
rows = [line for line in raw.splitlines() if line.strip()]
if len(rows) != 1:
    raise SystemExit(f"expected one migrated Ansible database row for {label}, found {len(rows)}")
parts = rows[0].split("\t", 7)
if len(parts) != 8:
    raise SystemExit(f"unexpected migrated Ansible database snapshot for {label}: {rows[0]!r}")
sha, artifact_size, metadata_raw, dependencies_raw, object_key, blob_size, content_type, asset_path = parts
if sha != expected_sha or artifact_size != expected_size or blob_size != expected_size:
    raise SystemExit(
        f"migrated Ansible checksum/size changed for {label}: "
        f"sha={sha} artifact={artifact_size} blob={blob_size}"
    )
metadata = json.loads(metadata_raw)
dependencies = json.loads(dependencies_raw)
projected = json.dumps({"metadata": metadata, "dependencies": dependencies}, separators=(",", ":"))
if len(projected.encode()) > 65536:
    raise SystemExit(f"migrated Ansible database projection is unbounded for {label}: {len(projected.encode())}")
allowed_metadata = {
    "authors", "license", "tags", "description", "repository",
    "documentation", "homepage", "issues",
}
unexpected_metadata = set(metadata) - allowed_metadata
if unexpected_metadata:
    raise SystemExit(
        f"migrated Ansible database projection retained non-protocol metadata for {label}: "
        f"{sorted(unexpected_metadata)}"
    )
for forbidden in ("file_manifest_file", "payload/data-0599.json", "ansible-migration-files-json-0599"):
    if forbidden in projected:
        raise SystemExit(f"migrated Ansible database projection retained blob-only content {forbidden!r}")
if not object_key or not asset_path.endswith(".tar.gz"):
    raise SystemExit(f"migrated Ansible blob reference is incomplete for {label}: {rows[0]!r}")
if content_type not in {"application/octet-stream", "application/gzip"}:
    raise SystemExit(f"migrated Ansible content type is unexpected for {label}: {content_type!r}")
print(object_key)
PY
)"
  blob_dir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-ansible-blob.XXXXXX")"
  blob_copy="$blob_dir/collection.tar.gz"
  docker compose -f "${COMPOSE_FILE:-docker-compose.compat.yml}" exec -T \
    "$KKREPO_PRIMARY_SERVICE" test -f "$KKREPO_BLOB_PATH/$object_key"
  docker compose -f "${COMPOSE_FILE:-docker-compose.compat.yml}" cp \
    "$KKREPO_PRIMARY_SERVICE:$KKREPO_BLOB_PATH/$object_key" "$blob_copy" >/dev/null
  actual_sha="$(file_sha256 "$blob_copy")"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    log "migrated Ansible physical blob SHA-256 mismatch for $label: expected=$expected_sha actual=$actual_sha"
    rm -f "$snapshot"
    rm -rf "$blob_dir"
    exit 1
  fi
  cmp "$expected_archive" "$blob_copy"
  tar -tzf "$blob_copy" MANIFEST.json >/dev/null
  tar -tzf "$blob_copy" FILES.json >/dev/null
  rm -f "$snapshot"
  rm -rf "$blob_dir"
  log "Ansible $label database projection and physical blob boundary verified: objectKey=$object_key"
}

set_ansible_proxy_remote() {
  local remote_url="$1"
  local response status
  response="$(mktemp)"
  status="$(curl -m 30 -sS \
    -u "$(auth)" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "{
      \"online\":true,
      \"proxy\":{
        \"remoteUrl\":\"$(json_escape "$remote_url")\",
        \"contentMaxAgeMinutes\":1440,
        \"metadataMaxAgeMinutes\":1440,
        \"negativeCacheEnabled\":true,
        \"negativeCacheTtlMinutes\":1,
        \"autoBlock\":true
      }
    }" \
    -o "$response" \
    -w '%{http_code}' \
    "$KKREPO_URL/internal/repositories/$ANSIBLE_PROXY_KKREPO_REPOSITORY")"
  if [[ "$status" != "200" ]]; then
    log "updating migrated Ansible proxy remote returned HTTP $status"
    cat "$response" >&2 || true
    rm -f "$response"
    exit 1
  fi
  rm -f "$response"
}

install_migrated_ansible_collections() {
  local workdir token
  if [[ -z "${ANSIBLE_GALAXY_BIN:-}" || ! -x "$ANSIBLE_GALAXY_BIN" ]]; then
    log "ANSIBLE_GALAXY_BIN must point to an executable client when Ansible migration E2E is enabled"
    exit 1
  fi
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-ansible-install.XXXXXX")"
  token="$(python3 - "$(auth)" <<'PY'
import base64
import sys
print(base64.b64encode(sys.argv[1].encode()).decode())
PY
)"
  ANSIBLE_NOCOLOR=1 "$ANSIBLE_GALAXY_BIN" collection install \
    "$ANSIBLE_NAMESPACE.$ANSIBLE_COLLECTION:$ANSIBLE_VERSION" \
    --server "$KKREPO_URL/repository/$ANSIBLE_GROUP_KKREPO_REPOSITORY/" \
    --token "$token" \
    --collections-path "$workdir/hosted" --force --no-deps >/dev/null
  test -f "$workdir/hosted/ansible_collections/$ANSIBLE_NAMESPACE/$ANSIBLE_COLLECTION/MANIFEST.json"

  # Point the proxy at a closed local port so the client can only succeed from the
  # explicitly migrated proxy-cache collection and its blob.
  set_ansible_proxy_remote "http://127.0.0.1:9/"
  ANSIBLE_NOCOLOR=1 "$ANSIBLE_GALAXY_BIN" collection install \
    "$ANSIBLE_PROXY_NAMESPACE.$ANSIBLE_PROXY_COLLECTION:$ANSIBLE_PROXY_VERSION" \
    --server "$KKREPO_URL/repository/$ANSIBLE_PROXY_KKREPO_REPOSITORY/" \
    --token "$token" \
    --collections-path "$workdir/proxy" --force --no-deps >/dev/null
  test -f "$workdir/proxy/ansible_collections/$ANSIBLE_PROXY_NAMESPACE/$ANSIBLE_PROXY_COLLECTION/MANIFEST.json"
  set_ansible_proxy_remote "https://galaxy.ansible.com/"
  rm -rf "$workdir"
  log "ansible-galaxy installed migrated hosted and explicit proxy-cache collections without proxy upstream access"
}

verify_migrated_ansible_fixture() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local workdir job hosted_detail hosted_artifact group_detail proxy_detail proxy_artifact
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-ansible-migrated.XXXXXX")"
  job="$workdir/job.json"
  hosted_detail="$workdir/hosted-detail.json"
  hosted_artifact="$workdir/hosted.tar.gz"
  group_detail="$workdir/group-detail.json"
  proxy_detail="$workdir/proxy-detail.json"
  proxy_artifact="$workdir/proxy.tar.gz"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id" >"$job"
  python3 - \
    "$job" "$ANSIBLE_NEXUS_REPOSITORY" "$ANSIBLE_PROXY_NEXUS_REPOSITORY" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = set(sys.argv[2:4])
capability = (((payload.get("sourceProfile") or {}).get("formatCapabilities") or {}).get("ansiblegalaxy") or {})
if capability.get("contentMigration") is not True:
    raise SystemExit(f"Ansible datastore content model was not proven for data migration: {capability}")
items = {
    item.get("name"): item
    for item in ((payload.get("migrationPlan") or {}).get("items") or [])
    if item.get("name") in expected
}
if set(items) != expected:
    raise SystemExit(f"Ansible data migration plan omitted repositories: expected={expected} items={items}")
for name, item in items.items():
    if item.get("status") != "FULL" or item.get("readMode") != "script-datastore":
        raise SystemExit(f"Ansible data migration plan is not FULL for {name}: {item}")
jobs = {
    item.get("sourceRepositoryName"): item
    for item in payload.get("repositoryJobs") or []
    if item.get("sourceRepositoryName") in expected
}
if set(jobs) != expected:
    raise SystemExit(f"Ansible repository-data jobs are missing: expected={expected} jobs={jobs}")
for name, item in jobs.items():
    if item.get("status") != "finished" or item.get("failedAssets") != 0:
        raise SystemExit(f"Ansible repository-data migration failed for {name}: {item}")
    if item.get("migratedAssets", 0) < 1:
        raise SystemExit(f"Ansible repository-data migration moved no collection archive for {name}: {item}")
PY

  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/repository/$ANSIBLE_KKREPO_REPOSITORY/api/v3/collections/$ANSIBLE_NAMESPACE/$ANSIBLE_COLLECTION/versions/$ANSIBLE_VERSION/" \
    >"$hosted_detail"
  curl -m 300 -fsS -u "$(auth)" \
    "$target_url/repository/$ANSIBLE_KKREPO_REPOSITORY/api/v3/plugin/ansible/content/published/collections/artifacts/$ANSIBLE_NAMESPACE-$ANSIBLE_COLLECTION-$ANSIBLE_VERSION.tar.gz" \
    >"$hosted_artifact"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/repository/$ANSIBLE_GROUP_KKREPO_REPOSITORY/api/v3/collections/$ANSIBLE_NAMESPACE/$ANSIBLE_COLLECTION/versions/$ANSIBLE_VERSION/" \
    >"$group_detail"
  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/repository/$ANSIBLE_PROXY_KKREPO_REPOSITORY/api/v3/collections/$ANSIBLE_PROXY_NAMESPACE/$ANSIBLE_PROXY_COLLECTION/versions/$ANSIBLE_PROXY_VERSION/" \
    >"$proxy_detail"
  curl -m 300 -fsS -u "$(auth)" \
    "$target_url/repository/$ANSIBLE_PROXY_KKREPO_REPOSITORY/api/v3/plugin/ansible/content/published/collections/artifacts/$ANSIBLE_PROXY_NAMESPACE-$ANSIBLE_PROXY_COLLECTION-$ANSIBLE_PROXY_VERSION.tar.gz" \
    >"$proxy_artifact"
  python3 - \
    "$hosted_detail" "$group_detail" "$proxy_detail" \
    "$ANSIBLE_FIXTURE_SHA256" "$ANSIBLE_PROXY_FIXTURE_SHA256" <<'PY'
import json
import pathlib
import sys

hosted = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
group = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
proxy = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
hosted_sha, proxy_sha = sys.argv[4:6]
if (hosted.get("artifact") or {}).get("sha256") != hosted_sha:
    raise SystemExit(f"migrated Ansible hosted metadata SHA-256 changed: {hosted}")
if (group.get("artifact") or {}).get("sha256") != hosted_sha:
    raise SystemExit(f"migrated Ansible group did not resolve the hosted collection: {group}")
if (proxy.get("artifact") or {}).get("sha256") != proxy_sha:
    raise SystemExit(f"migrated Ansible proxy metadata SHA-256 changed: {proxy}")
PY
  cmp "$ANSIBLE_FIXTURE_ARCHIVE" "$hosted_artifact"
  cmp "$ANSIBLE_PROXY_FIXTURE_ARCHIVE" "$proxy_artifact"
  cmp "$ANSIBLE_FIXTURE_WORKDIR/MANIFEST.json" <(tar -xOzf "$hosted_artifact" MANIFEST.json)
  cmp "$ANSIBLE_FIXTURE_WORKDIR/FILES.json" <(tar -xOzf "$hosted_artifact" FILES.json)
  rm -rf "$workdir"
  log "Ansible hosted/group/proxy-cache migration verified through $label replica"
}

swift_fixture_row_counts() {
  local repository_name scope package version
  repository_name="$(sql_literal "$SWIFT_KKREPO_REPOSITORY")"
  scope="$(sql_literal "$(printf '%s' "$SWIFT_SCOPE" | tr '[:upper:]' '[:lower:]')")"
  package="$(sql_literal "$(printf '%s' "$SWIFT_PACKAGE" | tr '[:upper:]' '[:lower:]')")"
  version="$(sql_literal "$SWIFT_VERSION")"
  target_db_query "
    SELECT
      (SELECT COUNT(*)
         FROM swift_release sr JOIN repository r ON r.id = sr.repository_id
        WHERE r.name = $repository_name AND sr.scope_lc = $scope
          AND sr.name_lc = $package AND sr.version = $version),
      (SELECT COUNT(*)
         FROM component c JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'swift'
          AND LOWER(c.namespace) = $scope AND LOWER(c.name) = $package
          AND c.version = $version),
      (SELECT COUNT(*)
         FROM asset a JOIN component c ON c.id = a.component_id
         JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'swift'
          AND LOWER(c.namespace) = $scope AND LOWER(c.name) = $package
          AND c.version = $version),
      (SELECT COUNT(DISTINCT ab.id)
         FROM asset_blob ab JOIN asset a ON a.asset_blob_id = ab.id
         JOIN component c ON c.id = a.component_id
         JOIN repository r ON r.id = c.repository_id
        WHERE r.name = $repository_name AND c.format = 'swift'
          AND LOWER(c.namespace) = $scope AND LOWER(c.name) = $package
          AND c.version = $version),
      (SELECT COUNT(*)
         FROM swift_manifest sm JOIN swift_release sr ON sr.id = sm.release_id
         JOIN repository r ON r.id = sr.repository_id
        WHERE r.name = $repository_name AND sr.scope_lc = $scope
          AND sr.name_lc = $package AND sr.version = $version),
      (SELECT COUNT(*)
         FROM swift_repository_url su JOIN swift_release sr ON sr.id = su.release_id
         JOIN repository r ON r.id = sr.repository_id
        WHERE r.name = $repository_name AND sr.scope_lc = $scope
          AND sr.name_lc = $package AND sr.version = $version)"
}

assert_swift_fixture_counts() {
  local counts="$1"
  local label="$2"
  python3 - "$counts" "$label" <<'PY'
import sys

raw, label = sys.argv[1:3]
values = [int(value) for value in raw.split()]
names = ["release", "component", "asset", "blob", "manifest", "repository_url"]
if len(values) != len(names):
    raise SystemExit(f"unexpected Swift row-count snapshot for {label}: {raw!r}")
missing = [name for name, value in zip(names[:5], values[:5]) if value <= 0]
if missing:
    raise SystemExit(
        f"Swift row-count snapshot for {label} has empty core tables {missing}: {raw!r}"
    )
if values[5] != 0:
    raise SystemExit(
        "Nexus 3.94 did not persist the uploaded repository URL, but migration "
        f"created {values[5]} URL mapping row(s): {raw!r}"
    )
print(" ".join(f"{name}={value}" for name, value in zip(names, values)))
PY
}

verify_migrated_swift_fixture() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local workdir job_file releases metadata archive headers manifest versioned identifiers
  local actual_sha actual_signature_format actual_signature actual_published_at repository_url status
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-swift-migrated.XXXXXX")"
  job_file="$workdir/job.json"
  releases="$workdir/releases.json"
  metadata="$workdir/metadata.json"
  archive="$workdir/archive.zip"
  headers="$workdir/archive.headers"
  manifest="$workdir/Package.swift"
  versioned="$workdir/Package@swift-5.9.swift"
  identifiers="$workdir/identifiers.json"

  curl -m 30 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id" >"$job_file"
  python3 - "$job_file" "$SWIFT_NEXUS_REPOSITORY" <<'PY'
import json
import sys

path, repository = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
rows = payload.get("repositoryJobs") or payload.get("repositoryStatuses") or payload.get("repositoryDetails") or []
matches = [row for row in rows if (
    row.get("sourceRepositoryName") or row.get("repositoryName") or row.get("name")
) == repository]
if not matches:
    raise SystemExit(f"Swift migration repository status not found: {repository}")
row = matches[0]
if int(row.get("migratedAssets") or 0) < 1:
    raise SystemExit(f"Swift migration did not restore an archive: {row}")
if int(row.get("failedAssets") or 0) != 0:
    raise SystemExit(f"Swift migration has failed assets: {row}")
PY

  curl -m 30 -fsS -u "$(auth)" \
    -H "Accept: application/vnd.swift.registry.v1+json" \
    "$target_url/repository/$SWIFT_KKREPO_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE" \
    >"$releases"
  python3 - "$releases" "$SWIFT_VERSION" <<'PY'
import json
import sys

path, version = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
releases = payload.get("releases") or {}
if version not in releases or not isinstance(releases.get(version), dict):
    raise SystemExit(f"migrated Swift release list does not contain {version}: {payload}")
PY

  curl -m 30 -fsS -u "$(auth)" \
    -H "Accept: application/vnd.swift.registry.v1+json" \
    "$target_url/repository/$SWIFT_KKREPO_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION" \
    >"$metadata"
  python3 - \
    "$metadata" \
    "$SWIFT_SCOPE.$SWIFT_PACKAGE" \
    "$SWIFT_VERSION" \
    "$SWIFT_FIXTURE_SHA256" <<'PY'
import json
import sys
from datetime import datetime

path, identity, version, checksum = sys.argv[1:5]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
if str(payload.get("id") or "").lower() != identity.lower():
    raise SystemExit(f"migrated Swift identity changed: {payload.get('id')!r}")
if payload.get("version") != version:
    raise SystemExit(f"migrated Swift version changed: {payload.get('version')!r}")
resources = [
    resource for resource in payload.get("resources") or []
    if resource.get("name") == "source-archive" and resource.get("type") == "application/zip"
]
if len(resources) != 1 or str(resources[0].get("checksum") or "").lower() != checksum:
    raise SystemExit(f"migrated Swift checksum changed: {resources}")
if resources[0].get("signing"):
    raise SystemExit(f"migration fabricated a Swift signature absent from Nexus 3.94: {resources[0]}")
metadata = payload.get("metadata") or {}
if metadata:
    raise SystemExit(f"migration fabricated Swift metadata absent from Nexus 3.94: {metadata}")
published_at = str(payload.get("publishedAt") or "")
try:
    datetime.fromisoformat(published_at.replace("Z", "+00:00"))
except ValueError as exc:
    raise SystemExit(f"migrated Swift publishedAt is invalid: {published_at!r}") from exc
PY
  actual_published_at="$(python3 - "$metadata" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as source:
    print(json.load(source).get("publishedAt") or "")
PY
)"
  if [[ -z "$SWIFT_MIGRATED_PUBLISHED_AT" ]]; then
    SWIFT_MIGRATED_PUBLISHED_AT="$actual_published_at"
  elif [[ "$actual_published_at" != "$SWIFT_MIGRATED_PUBLISHED_AT" ]]; then
    log "migrated Swift publishedAt changed through $label: $actual_published_at != $SWIFT_MIGRATED_PUBLISHED_AT"
    rm -rf "$workdir"
    exit 1
  fi

  curl -m 60 -fsS -u "$(auth)" \
    -D "$headers" \
    -H "Accept: application/vnd.swift.registry.v1+zip" \
    "$target_url/repository/$SWIFT_KKREPO_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION.zip" \
    >"$archive"
  actual_sha="$(file_sha256 "$archive")"
  if [[ "$actual_sha" != "$SWIFT_FIXTURE_SHA256" ]]; then
    log "migrated Swift archive checksum mismatch through $label: $actual_sha != $SWIFT_FIXTURE_SHA256"
    rm -rf "$workdir"
    exit 1
  fi
  actual_signature_format="$(header_value 'X-Swift-Package-Signature-Format' "$headers")"
  actual_signature="$(header_value 'X-Swift-Package-Signature' "$headers")"
  if [[ -n "$actual_signature_format" || -n "$actual_signature" ]]; then
    log "migration fabricated Swift archive signature headers through $label"
    rm -rf "$workdir"
    exit 1
  fi

  curl -m 30 -fsS -u "$(auth)" \
    -H "Accept: application/vnd.swift.registry.v1+swift" \
    "$target_url/repository/$SWIFT_KKREPO_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION/Package.swift" \
    >"$manifest"
  curl -m 30 -fsS -u "$(auth)" \
    -H "Accept: application/vnd.swift.registry.v1+swift" \
    "$target_url/repository/$SWIFT_KKREPO_REPOSITORY/$SWIFT_SCOPE/$SWIFT_PACKAGE/$SWIFT_VERSION/Package.swift?swift-version=5.9" \
    >"$versioned"
  cmp -s "$manifest" "$SWIFT_FIXTURE_MANIFEST" || {
    log "migrated Swift default manifest changed through $label"
    rm -rf "$workdir"
    exit 1
  }
  cmp -s "$versioned" "$SWIFT_FIXTURE_VERSIONED_MANIFEST" || {
    log "migrated Swift versioned manifest changed through $label"
    rm -rf "$workdir"
    exit 1
  }

  repository_url="https://github.com/kkrepo-fixtures/$SWIFT_PACKAGE.git"
  status="$(curl -m 30 -sS -u "$(auth)" \
    -H "Accept: application/vnd.swift.registry.v1+json" \
    --get \
    --data-urlencode "url=$repository_url" \
    "$target_url/repository/$SWIFT_KKREPO_REPOSITORY/identifiers" \
    -o "$identifiers" \
    -w '%{http_code}')"
  if [[ "$status" != "404" ]]; then
    log "migration fabricated a repository URL mapping absent from Nexus 3.94 through $label: HTTP $status"
    rm -rf "$workdir"
    exit 1
  fi
  python3 - "$identifiers" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    payload = json.load(source)
if payload.get("status") != 404:
    raise SystemExit(f"unexpected Swift identifier response for an unpersisted Nexus URL: {payload}")
PY
  rm -rf "$workdir"
  log "Swift fixture verified through $label replica: $SWIFT_SCOPE.$SWIFT_PACKAGE $SWIFT_VERSION sha256=$actual_sha"
}

run_swift_idempotency_migration() {
  local payload start_body
  payload="{
    \"sourceBaseUrl\":\"$(json_escape "$NEXUS_URL")\",
    \"sourceUsername\":\"$(json_escape "$NEXUS_USER")\",
    \"sourcePassword\":\"$(json_escape "$NEXUS_PASSWORD")\",
    \"repositories\":[\"$(json_escape "$SWIFT_NEXUS_REPOSITORY")\"],
    \"pageSize\":$PAGE_SIZE,
    \"concurrency\":$CONCURRENCY,
    \"checksumValidation\":true
  }"
  start_body="$(curl -m 60 -fsS \
    -u "$(auth)" \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KKREPO_URL/internal/migration/nexus/repository-data/start")"
  SWIFT_IDEMPOTENCY_JOB_ID="$(printf '%s' "$start_body" | json_field jobId)"
  if [[ -z "$SWIFT_IDEMPOTENCY_JOB_ID" ]]; then
    log "could not parse Swift idempotency migration job id from: $start_body"
    exit 1
  fi
  wait_for_discovery_ready "$SWIFT_IDEMPOTENCY_JOB_ID"
  curl -m 30 -fsS \
    -u "$(auth)" \
    -X POST \
    "$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$SWIFT_IDEMPOTENCY_JOB_ID/packages/start" \
    >/dev/null
  wait_for_migration_idle "$SWIFT_IDEMPOTENCY_JOB_ID"
  log "Swift idempotency migration completed: job=$SWIFT_IDEMPOTENCY_JOB_ID"
}

kkrepo_repo_exists() {
  local name="$1"
  curl -m 20 -fsS -u "$(auth)" \
    "$KKREPO_URL/internal/repositories?purpose=admin" \
    | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\""
}

ensure_kkrepo_blob_store() {
  if curl -m 20 -fsS -u "$(auth)" "$KKREPO_URL/internal/blob-stores" \
      | grep -q '"name"[[:space:]]*:[[:space:]]*"default"'; then
    log "kkrepo blob store exists: default"
    return 0
  fi
  log "creating kkrepo file blob store: default"
  curl -m 30 -fsS \
    -u "$(auth)" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "{\"name\":\"default\",\"type\":\"file\",\"path\":\"$(json_escape "$KKREPO_BLOB_PATH")\"}" \
    "$KKREPO_URL/internal/blob-stores" >/dev/null
}

ensure_kkrepo_docker_repository() {
  if kkrepo_repo_exists "$KKREPO_REPOSITORY"; then
    log "kkrepo repository exists: $KKREPO_REPOSITORY"
    return 0
  fi
  log "creating kkrepo Docker hosted repository: $KKREPO_REPOSITORY"
  curl -m 30 -fsS \
    -u "$(auth)" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "{
      \"name\":\"$(json_escape "$KKREPO_REPOSITORY")\",
      \"recipe\":\"docker-hosted\",
      \"online\":true,
      \"blobStoreName\":\"default\",
      \"strictContentTypeValidation\":true,
      \"hosted\":{\"writePolicy\":\"ALLOW\"},
      \"docker\":{\"connectorEnabled\":true,\"connectorPort\":$EXPECTED_CONNECTOR_PORT}
    }" \
    "$KKREPO_URL/internal/repositories" >/dev/null
  curl -m 30 -fsS \
    -u "$(auth)" \
    -X POST \
    "$KKREPO_URL/internal/docker/connectors/refresh" >/dev/null || true
}

job_status_summary() {
  python3 -c '
import json
import sys

try:
    body = json.load(sys.stdin)
except Exception as exc:
    print(f"unparseable job payload: {exc}")
    sys.exit(0)

fields = [
    "status",
    "active",
    "discoveredRepositories",
    "finishedRepositories",
    "failedRepositories",
    "discoveredAssets",
    "migratedAssets",
    "pendingAssets",
    "failedAssets",
]
parts = [f"{field}={body.get(field)}" for field in fields if field in body]
repositories = body.get("repositoryStatuses")
if not isinstance(repositories, list):
    repositories = body.get("repositoryJobs") or body.get("repositoryDetails")
if not isinstance(repositories, list):
    repositories = []
repo_parts = []
for repo in repositories:
    name = repo.get("sourceRepositoryName") or repo.get("repositoryName") or repo.get("name")
    if not name:
        continue
    repo_fields = []
    for field in ["status", "discoveredAssets", "migratedAssets", "pendingAssets", "failedAssets"]:
        if field in repo:
            repo_fields.append(f"{field}={repo.get(field)}")
    repo_parts.append(name + "(" + ",".join(repo_fields) + ")")
if repo_parts:
    parts.append("repos=" + ";".join(repo_parts))
print(" ".join(parts))
'
}

wait_for_migration_idle() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local path="$target_url/internal/migration/nexus/repository-data/jobs/$job_id"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    local body
    body="$(curl -m 20 -fsS -u "$(auth)" "$path")"
    log "job $job_id status through $label: $(printf '%s' "$body" | job_status_summary)"
    if printf '%s' "$body" | grep -q '"active"[[:space:]]*:[[:space:]]*false'; then
      if printf '%s' "$body" | grep -q '"failedAssets"[[:space:]]*:[[:space:]]*[1-9]'; then
        log "migration job has failed assets"
        exit 1
      fi
      return 0
    fi
    sleep 2
  done
  log "timed out waiting for migration job $job_id"
  exit 1
}

wait_for_discovery_ready() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local path="$target_url/internal/migration/nexus/repository-data/jobs/$job_id"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    local body
    body="$(curl -m 20 -fsS -u "$(auth)" "$path")"
    log "job $job_id discovery status through $label: $(printf '%s' "$body" | job_status_summary)"
    if printf '%s' "$body" | grep -q '"failedRepositories"[[:space:]]*:[[:space:]]*true'; then
      log "migration discovery failed"
      exit 1
    fi
    if ! printf '%s' "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"discovering"'; then
      if printf '%s' "$body" | grep -q '"pendingAssets"[[:space:]]*:[[:space:]]*[1-9]'; then
        return 0
      fi
      if printf '%s' "$body" | grep -q '"discoveredAssets"[[:space:]]*:[[:space:]]*[1-9]'; then
        return 0
      fi
    fi
    sleep 2
  done
  log "timed out waiting for migration discovery on job $job_id"
  exit 1
}

wait_for_pre_package_stage_boundary() {
  local job_id="$1"
  local target_url="${2:-$KKREPO_URL}"
  local label="${3:-primary}"
  local path="$target_url/internal/migration/nexus/repository-data/jobs/$job_id"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    local body
    body="$(curl -m 20 -fsS -u "$(auth)" "$path")"
    if printf '%s' "$body" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
repositories = payload.get("repositoryJobs") or []
statuses = {str(row.get("status") or "") for row in repositories}
stable = (
    bool(repositories)
    and payload.get("packageMigrationEnabled") is False
    and int(payload.get("pendingAssets") or 0) > 0
    and payload.get("failedRepositories") is False
    and statuses <= {"ready", "finished"}
)
raise SystemExit(0 if stable else 1)
'; then
      log "job $job_id reached deterministic pre-package boundary through $label: "\
"$(printf '%s' "$body" | job_status_summary)"
      return 0
    fi
    log "job $job_id has not reached the pre-package boundary through $label: "\
"$(printf '%s' "$body" | job_status_summary)"
    sleep 2
  done
  log "timed out waiting for deterministic pre-package boundary on job $job_id through $label"
  exit 1
}

verify_migration_job_visible() {
  local job_id="$1"
  local target_url="$2"
  local label="$3"
  local body
  body="$(curl -m 20 -fsS -u "$(auth)" \
    "$target_url/internal/migration/nexus/repository-data/jobs/$job_id")"
  if [[ -z "$body" ]] || ! printf '%s' "$body" | grep -q "\"jobId\"[[:space:]]*:[[:space:]]*$job_id"; then
    log "migration job $job_id is not visible through $label"
    exit 1
  fi
  log "migration job $job_id is durable through $label: "\
"$(printf '%s' "$body" | job_status_summary)"
}

restart_primary_at_swift_migration_stage_boundary() {
  local job_id="$1"
  if [[ -z "$KKREPO_SECONDARY_URL" ]]; then
    log "Swift migration restart/resume acceptance requires KKREPO_MIGRATION_SECONDARY_URL"
    exit 1
  fi

  wait_for_pre_package_stage_boundary "$job_id" "$KKREPO_URL" "primary"
  wait_for_http "kkrepo migration read replica" \
    "$KKREPO_SECONDARY_URL/internal/repositories?purpose=admin" "$(auth)"
  wait_for_pre_package_stage_boundary "$job_id" "$KKREPO_SECONDARY_URL" "secondary"

  log "restarting $KKREPO_PRIMARY_SERVICE at the deterministic post-discovery/pre-package boundary"
  docker compose -f "${COMPOSE_FILE:-docker-compose.compat.yml}" restart "$KKREPO_PRIMARY_SERVICE" >/dev/null
  wait_for_http "kkrepo health endpoint after migration worker restart" "$KKREPO_HEALTH_URL"
  wait_for_http "kkrepo repositories endpoint after migration worker restart" \
    "$KKREPO_URL/internal/repositories?purpose=admin" "$(auth)"

  wait_for_pre_package_stage_boundary \
    "$job_id" "$KKREPO_SECONDARY_URL" "secondary after primary restart"
  wait_for_pre_package_stage_boundary "$job_id" "$KKREPO_URL" "restarted primary"
  log "persisted migration job survived the primary worker restart; package work will resume through secondary"
}

json_field() {
  local field="$1"
  sed -n "s/.*\"$field\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" | head -n 1
}

auth() {
  printf '%s:%s' "$KKREPO_USER" "$KKREPO_PASSWORD"
}

curl_kkrepo_json() {
  local path="$1"
  local payload="$2"
  curl -m 90 -fsS \
    -u "$(auth)" \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KKREPO_URL$path"
}

migration_request_payload() {
  printf '{"sourceBaseUrl":"%s","sourceUsername":"%s","sourcePassword":"%s"}' \
    "$(json_escape "$NEXUS_URL")" \
    "$(json_escape "$NEXUS_USER")" \
    "$(json_escape "$NEXUS_PASSWORD")"
}

run_config_metadata_migration() {
  local payload expected_adapter preflight_file run_file repo_file
  payload="$(migration_request_payload)"
  expected_adapter="$EXPECTED_ADAPTER"
  if [[ -z "$expected_adapter" ]]; then
    case "${NEXUS_COMPAT_IMAGE:-}" in
      *3.29*) expected_adapter="OrientDbNexusAdapter" ;;
      *3.92*|*3.77*|*3.73*) expected_adapter="DatastoreH2NexusAdapter" ;;
    esac
  fi
  preflight_file="$(mktemp)"
  run_file="$(mktemp)"
  repo_file="$(mktemp)"

  log "running Nexus config/security metadata preflight"
  curl_kkrepo_json "/internal/migration/nexus/preflight" "$payload" >"$preflight_file"
  python3 - \
    "$preflight_file" \
    "$expected_adapter" \
    "$NEXUS_REPOSITORY" \
    "$EXPECTED_CONNECTOR_PORT" \
    "$SWIFT_MIGRATION_ENABLED" \
    "$SWIFT_NEXUS_REPOSITORY" \
    "$SWIFT_PROXY_NEXUS_REPOSITORY" \
    "$ANSIBLE_MIGRATION_ENABLED" \
    "$ANSIBLE_NEXUS_REPOSITORY" \
    "$ANSIBLE_PROXY_NEXUS_REPOSITORY" \
    "$ANSIBLE_SECRET_PROXY_NEXUS_REPOSITORY" \
    "$CONDA_MIGRATION_ENABLED" \
    "$CONDA_NEXUS_REPOSITORY" \
    "$APT_MIGRATION_ENABLED" \
    "$APT_NEXUS_REPOSITORY" \
    "$ALPINE_MIGRATION_ENABLED" \
    "$ALPINE_NEXUS_REPOSITORY" \
    "$ALPINE_PROXY_NEXUS_REPOSITORY" \
    "$ALPINE_GROUP_NEXUS_REPOSITORY" \
    "$R_MIGRATION_ENABLED" \
    "$R_NEXUS_REPOSITORY" \
    "$R_PROXY_NEXUS_REPOSITORY" \
    "$R_GROUP_NEXUS_REPOSITORY" <<'PY'
import json
import sys

(
    path,
    expected_adapter,
    repository,
    expected_connector_port,
    swift_enabled,
    swift_repository,
    swift_proxy_repository,
    ansible_enabled,
    ansible_repository,
    ansible_proxy_repository,
    ansible_secret_proxy_repository,
    conda_enabled,
    conda_repository,
    apt_enabled,
    apt_repository,
    alpine_enabled,
    alpine_repository,
    alpine_proxy_repository,
    alpine_group_repository,
    r_enabled,
    r_repository,
    r_proxy_repository,
    r_group_repository,
) = sys.argv[1:24]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
plan = payload.get("migrationPlan") or {}
profile = payload.get("sourceProfile") or {}
adapter = plan.get("adapter")
if expected_adapter and adapter != expected_adapter:
    raise SystemExit(f"unexpected migration adapter: {adapter!r}, expected {expected_adapter!r}")
engine = profile.get("metadataEngine")
if expected_adapter == "OrientDbNexusAdapter" and engine != "ORIENTDB":
    raise SystemExit(f"unexpected metadata engine: {engine!r}, expected ORIENTDB")
if expected_adapter == "DatastoreH2NexusAdapter" and engine != "DATASTORE_H2":
    raise SystemExit(f"unexpected metadata engine: {engine!r}, expected DATASTORE_H2")
if expected_adapter == "DatastorePostgresqlNexusAdapter" and engine != "DATASTORE_POSTGRESQL":
    raise SystemExit(f"unexpected metadata engine: {engine!r}, expected DATASTORE_POSTGRESQL")
if len(plan.get("profileHash") or "") != 64 or len(plan.get("planHash") or "") != 64:
    raise SystemExit("profileHash/planHash were not recorded as SHA-256 hashes")
items = plan.get("items") or []
matches = [
    item for item in items
    if item.get("area") == "repository" and item.get("name") == repository
]
if not matches:
    raise SystemExit(f"repository plan item not found: {repository}")
item = matches[0]
if item.get("status") != "FULL":
    raise SystemExit(f"repository {repository} plan status is {item.get('status')!r}, expected FULL")
if item.get("readMode") not in ("script-orientdb", "script-datastore"):
    raise SystemExit(f"repository {repository} readMode is {item.get('readMode')!r}")
security = [item for item in items if item.get("area") == "security"]
if not security or security[0].get("status") != "FULL":
    raise SystemExit("security migration plan is not FULL")
warnings = "\n".join(payload.get("warnings") or []) + "\n" + "\n".join(plan.get("warnings") or [])
blocked = [
    "version probe skipped",
    "did not expose API keys",
    "Datastore-era Nexus sources are probed and planned fail-closed",
    "Cargo migration remains configuration-only",
    "Cargo repository content migration is intentionally disabled",
]
for text in blocked:
    if text in warnings:
        raise SystemExit(f"unexpected warning remained visible: {text}")
if expected_adapter in {"DatastoreH2NexusAdapter", "DatastorePostgresqlNexusAdapter"}:
    cargo = [
        item for item in items
        if item.get("area") == "repository" and item.get("name") == "cargo-hosted"
    ]
    if not cargo:
        raise SystemExit("cargo-hosted plan item not found for datastore migration")
    if cargo[0].get("status") != "FULL":
        raise SystemExit(f"cargo-hosted plan status is {cargo[0].get('status')!r}, expected FULL")
    if cargo[0].get("readMode") != "script-datastore":
        raise SystemExit(f"cargo-hosted readMode is {cargo[0].get('readMode')!r}")
if swift_enabled == "true":
    capability = ((profile.get("formatCapabilities") or {}).get("swift") or {})
    if capability.get("contentMigration") is not True:
        raise SystemExit(f"Swift datastore content model was not proven: {capability}")
    swift = [
        item for item in items
        if item.get("area") == "repository" and item.get("name") == swift_repository
    ]
    if not swift:
        raise SystemExit(f"Swift hosted plan item not found: {swift_repository}")
    if swift[0].get("status") != "FULL" or swift[0].get("readMode") != "script-datastore":
        raise SystemExit(f"Swift hosted migration is not fail-closed FULL: {swift[0]}")
    swift_proxy = [
        item
        for item in items
        if item.get("area") == "repository" and item.get("name") == swift_proxy_repository
    ]
    if not swift_proxy:
        raise SystemExit(f"Swift proxy plan item not found: {swift_proxy_repository}")
    if swift_proxy[0].get("status") != "NEEDS_MANUAL_ACTION":
        raise SystemExit(
            "Swift proxy with an unrecoverable source credential did not fail closed: "
            f"{swift_proxy[0]}"
        )
    expected_action = "repository:" + swift_proxy_repository
    if expected_action not in (plan.get("manualActions") or []):
        raise SystemExit(
            f"Swift proxy preflight omitted manual action {expected_action}: "
            f"{plan.get('manualActions')}"
        )
    proxy_risks = [
        risk
        for risk in payload.get("proxyRemoteRisks") or []
        if risk.get("repository") == swift_proxy_repository
    ]
    if len(proxy_risks) != 1 or proxy_risks[0].get("status") not in {
        "masked_proxy_credential_secret",
        "missing_proxy_credential_secret",
    }:
        raise SystemExit(
            f"Swift proxy preflight did not report an unavailable credential: {proxy_risks}"
        )
if ansible_enabled == "true":
    capability = ((profile.get("formatCapabilities") or {}).get("ansiblegalaxy") or {})
    if capability.get("contentMigration") is not True:
        raise SystemExit(f"Ansible datastore content model was not proven: {capability}")
    ansible = [
        item
        for item in items
        if item.get("area") == "repository" and item.get("name") == ansible_repository
    ]
    if not ansible:
        raise SystemExit(f"Ansible hosted plan item not found: {ansible_repository}")
    if ansible[0].get("status") != "FULL" or ansible[0].get("readMode") != "script-datastore":
        raise SystemExit(f"Ansible hosted migration is not fail-closed FULL: {ansible[0]}")
    ansible_proxy = [
        item
        for item in items
        if item.get("area") == "repository" and item.get("name") == ansible_proxy_repository
    ]
    if not ansible_proxy:
        raise SystemExit(f"Ansible proxy plan item not found: {ansible_proxy_repository}")
    ansible_secret_proxy = [
        item
        for item in items
        if item.get("area") == "repository"
        and item.get("name") == ansible_secret_proxy_repository
    ]
    if not ansible_secret_proxy or ansible_secret_proxy[0].get("status") != "NEEDS_MANUAL_ACTION":
        raise SystemExit(
            "Ansible proxy with an unrecoverable source credential did not fail closed: "
            f"{ansible_secret_proxy}"
        )
    expected_action = "repository:" + ansible_secret_proxy_repository
    if expected_action not in (plan.get("manualActions") or []):
        raise SystemExit(
            f"Ansible proxy preflight omitted manual action {expected_action}: "
            f"{plan.get('manualActions')}"
        )
    proxy_risks = [
        risk
        for risk in payload.get("proxyRemoteRisks") or []
        if risk.get("repository") == ansible_secret_proxy_repository
    ]
    if len(proxy_risks) != 1 or proxy_risks[0].get("status") not in {
        "masked_proxy_credential_secret",
        "missing_proxy_credential_secret",
    }:
        raise SystemExit(
            f"Ansible proxy preflight did not report an unavailable credential: {proxy_risks}"
        )
if conda_enabled == "true":
    capability = ((profile.get("formatCapabilities") or {}).get("conda") or {})
    if capability.get("contentMigration") is not True:
        raise SystemExit(f"Conda datastore content model was not proven: {capability}")
    conda = [
        item
        for item in items
        if item.get("area") == "repository" and item.get("name") == conda_repository
    ]
    if not conda:
        raise SystemExit(f"Conda hosted plan item not found: {conda_repository}")
    if conda[0].get("status") != "FULL" or conda[0].get("readMode") != "script-datastore":
        raise SystemExit(f"Conda hosted migration is not fail-closed FULL: {conda[0]}")
if apt_enabled == "true":
    capability = ((profile.get("formatCapabilities") or {}).get("apt") or {})
    if capability.get("contentMigration") is not True:
        raise SystemExit(f"APT datastore content model was not proven: {capability}")
    apt = [
        item
        for item in items
        if item.get("area") == "repository" and item.get("name") == apt_repository
    ]
    if not apt:
        raise SystemExit(f"APT hosted plan item not found: {apt_repository}")
    if (apt[0].get("status") != "NEEDS_MANUAL_ACTION"
            or apt[0].get("readMode") != "script-datastore"):
        raise SystemExit(f"APT hosted migration did not fail closed for key import: {apt[0]}")
    expected_action = "repository:" + apt_repository
    if expected_action not in (plan.get("manualActions") or []):
        raise SystemExit(
            f"APT hosted preflight omitted manual action {expected_action}: "
            f"{plan.get('manualActions')}"
        )
    if not any(
        f"APT repository {apt_repository} requires manual signing-key import" in warning
        for warning in (payload.get("warnings") or [])
    ):
        raise SystemExit("APT preflight omitted the explicit signing-key warning")
if alpine_enabled == "true":
    capability = ((profile.get("formatCapabilities") or {}).get("alpine") or {})
    if capability.get("contentMigration") is not True:
        raise SystemExit(f"Alpine datastore content model was not proven: {capability}")
    repositories = [alpine_repository, alpine_proxy_repository, alpine_group_repository]
    for name in repositories:
        matches = [
            item for item in items
            if item.get("area") == "repository" and item.get("name") == name
        ]
        if not matches:
            raise SystemExit(f"Alpine repository plan item not found: {name}")
        if matches[0].get("status") != "NEEDS_MANUAL_ACTION":
            raise SystemExit(
                f"Alpine repository did not fail closed for signing-key import: {matches[0]}"
            )
        expected_action = "repository:" + name
        if expected_action not in (plan.get("manualActions") or []):
            raise SystemExit(
                f"Alpine preflight omitted manual action {expected_action}: "
                f"{plan.get('manualActions')}"
            )
    hosted = next(item for item in items if item.get("name") == alpine_repository)
    if hosted.get("readMode") != "script-datastore":
        raise SystemExit(f"Alpine hosted read mode is not datastore-backed: {hosted}")
    if not any(
        f"Alpine repository {alpine_repository} requires manual signing-key import" in warning
        for warning in (payload.get("warnings") or [])
    ):
        raise SystemExit("Alpine preflight omitted the explicit signing-key warning")
if r_enabled == "true":
    capability = ((profile.get("formatCapabilities") or {}).get("r") or {})
    if capability.get("contentMigration") is not True:
        raise SystemExit(f"R datastore content model was not proven: {capability}")
    repositories = {
        r_repository: ("FULL", "script-datastore"),
        r_proxy_repository: ("CONFIG_ONLY", "repository-config-rest"),
        r_group_repository: ("CONFIG_ONLY", "repository-config-rest"),
    }
    for name, expected in repositories.items():
        matches = [
            item for item in items
            if item.get("area") == "repository" and item.get("name") == name
        ]
        if not matches:
            raise SystemExit(f"R repository plan item not found: {name}")
        actual = (matches[0].get("status"), matches[0].get("readMode"))
        if actual != expected:
            raise SystemExit(
                f"R repository plan is not fail-closed for {name}: {actual!r} != {expected!r}"
            )
print(
    "preflight adapter="
    + str(adapter)
    + " engine="
    + str(engine)
    + " profileHash="
    + plan.get("profileHash", "")[:12]
    + " planHash="
    + plan.get("planHash", "")[:12]
    + " connectorPort="
    + expected_connector_port
)
PY

  log "running Nexus config/security metadata migration"
  curl_kkrepo_json "/internal/migration/nexus/run" "$payload" >"$run_file"
  python3 - \
    "$run_file" "$expected_adapter" "$NEXUS_REPOSITORY" \
    "$SWIFT_MIGRATION_ENABLED" "$SWIFT_PROXY_NEXUS_REPOSITORY" \
    "$ANSIBLE_MIGRATION_ENABLED" "$ANSIBLE_SECRET_PROXY_NEXUS_REPOSITORY" \
    "$APT_MIGRATION_ENABLED" "$APT_NEXUS_REPOSITORY" \
    "$ALPINE_MIGRATION_ENABLED" "$ALPINE_NEXUS_REPOSITORY" \
    "$ALPINE_PROXY_NEXUS_REPOSITORY" "$ALPINE_GROUP_NEXUS_REPOSITORY" <<'PY'
import json
import sys

(
    path,
    expected_adapter,
    repository,
    swift_enabled,
    swift_proxy_repository,
    ansible_enabled,
    ansible_secret_proxy_repository,
    apt_enabled,
    apt_repository,
    alpine_enabled,
    alpine_repository,
    alpine_proxy_repository,
    alpine_group_repository,
) = sys.argv[1:14]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
status = payload.get("status")
has_unavailable_proxy = swift_enabled == "true" or ansible_enabled == "true"
has_apt_signing = apt_enabled == "true"
has_alpine_signing = alpine_enabled == "true"
has_manual_repository = has_unavailable_proxy or has_apt_signing or has_alpine_signing
if has_manual_repository:
    if status != "finished_with_manual_actions":
        raise SystemExit(
            "metadata migration with a fail-closed repository returned "
            f"unexpected status: {status!r}"
        )
elif status not in {"finished", "finished_with_password_resets_required"}:
    raise SystemExit(f"metadata migration returned unexpected status: {status!r}")
validation = payload.get("validation") or {}
if validation.get("failed"):
    raise SystemExit(f"metadata migration validation failed: {validation}")
manual = validation.get("manualActions") or []
if has_manual_repository:
    if has_unavailable_proxy and "repository/proxy credentials" not in manual:
        raise SystemExit(
            "metadata migration did not require manual proxy credential completion: "
            f"{manual}"
        )
    if has_apt_signing and "repository/APT signing keys" not in manual:
        raise SystemExit(
            "metadata migration did not require explicit APT signing-key import: "
            f"{manual}"
        )
    if has_alpine_signing and "repository/Alpine signing keys" not in manual:
        raise SystemExit(
            "metadata migration did not require explicit Alpine signing-key import: "
            f"{manual}"
        )
elif manual:
    raise SystemExit(f"metadata migration requires manual actions: {manual}")
plan = ((payload.get("preflight") or {}).get("migrationPlan") or {})
if expected_adapter and plan.get("adapter") != expected_adapter:
    raise SystemExit(f"run adapter changed to {plan.get('adapter')!r}, expected {expected_adapter!r}")
config = payload.get("config") or {}
if config.get("repositories", 0) < 1:
    raise SystemExit(f"metadata migration did not report migrated repositories: {config}")
security = payload.get("apiSecurity") or {}
if security.get("users", 0) < 1:
    raise SystemExit(f"metadata migration did not migrate local users: {security}")
checks = validation.get("checks") or []
failed_checks = [check for check in checks if check.get("status") == "FAIL"]
manual_checks = [check for check in checks if check.get("status") == "MANUAL"]
if failed_checks:
    raise SystemExit(f"metadata migration had failed checks: {failed_checks}")
if has_manual_repository:
    proxy_checks = [
        check
        for check in manual_checks
        if check.get("scope") == "repository" and check.get("name") == "proxy credentials"
    ]
    apt_checks = [
        check
        for check in manual_checks
        if check.get("scope") == "repository" and check.get("name") == "APT signing keys"
    ]
    alpine_checks = [
        check
        for check in manual_checks
        if check.get("scope") == "repository" and check.get("name") == "Alpine signing keys"
    ]
    expected_proxy_checks = 1 if has_unavailable_proxy else 0
    expected_apt_checks = 1 if has_apt_signing else 0
    expected_alpine_checks = 1 if has_alpine_signing else 0
    other_manual_checks = [
        check for check in manual_checks
        if check not in proxy_checks and check not in apt_checks and check not in alpine_checks
    ]
    if (len(proxy_checks) != expected_proxy_checks
            or len(apt_checks) != expected_apt_checks
            or len(alpine_checks) != expected_alpine_checks
            or other_manual_checks):
        raise SystemExit(
            "metadata migration manual checks differ from the expected fail-closed "
            f"repositories: proxy={proxy_checks} apt={apt_checks} "
            f"alpine={alpine_checks} other={other_manual_checks}"
        )
    plan_manual = plan.get("manualActions") or []
    expected_actions = []
    if swift_enabled == "true":
        expected_actions.append("repository:" + swift_proxy_repository)
    if ansible_enabled == "true":
        expected_actions.append("repository:" + ansible_secret_proxy_repository)
    if apt_enabled == "true":
        expected_actions.append("repository:" + apt_repository)
    if alpine_enabled == "true":
        expected_actions.extend([
            "repository:" + alpine_repository,
            "repository:" + alpine_proxy_repository,
            "repository:" + alpine_group_repository,
        ])
    missing_actions = [action for action in expected_actions if action not in plan_manual]
    if missing_actions:
        raise SystemExit(
            f"metadata migration run omitted preflight actions {missing_actions}: {plan_manual}"
        )
elif manual_checks:
    raise SystemExit(f"metadata migration had manual checks: {manual_checks}")
print(f"metadata migration status={status} repositories={config.get('repositories')} users={security.get('users')}")
PY

  refresh_kkrepo_password_after_metadata_migration

  log "verifying migrated repository configuration"
  curl -m 30 -fsS -u "$(auth)" "$KKREPO_URL/internal/repositories/$KKREPO_REPOSITORY" >"$repo_file"
  python3 - "$repo_file" "$EXPECTED_CONNECTOR_PORT" <<'PY'
import json
import sys

path, expected_port = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as source:
    repository = json.load(source)
if repository.get("recipe") != "docker-hosted":
    raise SystemExit(f"unexpected migrated repository recipe: {repository.get('recipe')!r}")
docker = repository.get("docker") or {}
if docker.get("connectorEnabled") is not True:
    raise SystemExit(f"Docker connector is not enabled after metadata migration: {docker}")
if str(docker.get("connectorPort")) != str(expected_port):
    raise SystemExit(f"Docker connector port is {docker.get('connectorPort')!r}, expected {expected_port!r}")
print(f"repository config verified: docker connector {expected_port}")
PY
  rm -f "$preflight_file" "$run_file" "$repo_file"
}

refresh_kkrepo_password_after_metadata_migration() {
  if curl -m 10 -fsS -u "$KKREPO_USER:$NEXUS_PASSWORD" \
      "$KKREPO_URL/internal/security/session" >/dev/null 2>&1; then
    if [[ "$KKREPO_PASSWORD" != "$NEXUS_PASSWORD" ]]; then
      log "kkrepo admin password now matches migrated source Nexus password"
    fi
    KKREPO_PASSWORD="$NEXUS_PASSWORD"
    return 0
  fi
  if curl -m 10 -fsS -u "$KKREPO_USER:$KKREPO_PASSWORD" \
      "$KKREPO_URL/internal/security/session" >/dev/null 2>&1; then
    log "kkrepo admin password remains the pre-migration password"
    return 0
  fi
  log "kkrepo admin password did not authenticate with the pre-migration or migrated source password"
  exit 1
}

need curl
need docker
need shasum
need gzip
need dd
need tar

wait_for_http "Nexus status endpoint" "$NEXUS_URL/service/rest/v1/status" "$NEXUS_USER:$NEXUS_PASSWORD"
wait_for_http "kkrepo health endpoint" "$KKREPO_HEALTH_URL"
wait_for_http "kkrepo repositories endpoint" "$KKREPO_URL/internal/repositories?purpose=admin" "$(auth)"

ensure_kkrepo_blob_store
ensure_kkrepo_docker_repository
if terraform_migration_enabled; then
  if ! source_terraform_available; then
    log "required Terraform repository $TERRAFORM_NEXUS_REPOSITORY is not available on the Nexus 3.92 source"
    exit 1
  fi
  if ! source_terraform_proxy_available; then
    log "required Terraform proxy repository $TERRAFORM_PROXY_NEXUS_REPOSITORY is not available on the Nexus 3.92 source"
    exit 1
  fi
  warm_terraform_proxy_fixture
fi
if composer_migration_enabled; then
  if ! source_composer_available; then
    log "required Composer proxy repository $COMPOSER_NEXUS_REPOSITORY is not available on the source Nexus"
    exit 1
  fi
  warm_composer_proxy_fixture
fi
if swift_migration_enabled; then
  need python3
  need openssl
  need cmp
  if ! source_swift_available; then
    log "required Swift hosted repository $SWIFT_NEXUS_REPOSITORY is not available on the Nexus source"
    exit 1
  fi
  configure_swift_source_proxy_authentication
  # The fail-closed source-profile probe only marks Swift content as FULL after it has
  # fingerprinted a real archive, manifest, checksum, and optional CMS signature.
  # Seed that evidence before the first configuration preflight.
  prepare_swift_fixture
  publish_swift_fixture_to_source_nexus
  verify_source_swift_fixture
fi
if ansible_migration_enabled; then
  need python3
  need cmp
  if ! source_ansible_available; then
    log "required Ansible hosted/proxy/group repositories are not available on the Nexus 3.93/3.94 source"
    exit 1
  fi
  # Seed both the hosted content fingerprint and a real, explicitly selected
  # proxy-cache archive before the fail-closed source-profile probe.
  prepare_ansible_fixture
  publish_ansible_fixture_to_source_nexus
  verify_source_ansible_fixture
  warm_ansible_proxy_fixture
  ensure_ansible_source_secret_proxy
fi
if conda_migration_enabled; then
  need python3
  need cmp
  need "$CONDA_BIN"
  if ! source_conda_available; then
    log "required Conda hosted/proxy/group repositories are not available on the Nexus 3.92/3.94 source"
    exit 1
  fi
  # Seed an installable package before preflight so the fail-closed source-profile probe can
  # fingerprint the real Nexus Conda datastore tables and package asset shape.
  prepare_conda_fixture
  publish_conda_fixture_to_source_nexus
  verify_source_conda_fixture
fi
if apt_migration_enabled; then
  need python3
  need cmp
  if ! source_apt_available; then
    log "required APT hosted repository $APT_NEXUS_REPOSITORY is not available on the Nexus 3.92/3.94 source"
    exit 1
  fi
  # The preparation test creates a real signed Nexus repository and leaves its private key only
  # in the runner's temporary directory. Preflight can therefore prove the datastore shape while
  # target activation still requires an explicit key-import step below.
  load_apt_fixture
  verify_source_apt_fixture
fi
if alpine_migration_enabled; then
  need python3
  need openssl
  need cmp
  prepare_alpine_fixture
  if ! source_alpine_available; then
    log "required Alpine hosted/proxy/group repositories are not available on the Nexus 3.94 source"
    exit 1
  fi
  verify_source_alpine_fixture
fi
if r_migration_enabled; then
  need python3
  need cmp
  prepare_r_fixture
  if ! source_r_available; then
    log "required R hosted/proxy/group repositories are not available on the Nexus 3.94 source"
    exit 1
  fi
  publish_r_fixture_to_source_nexus
  verify_source_r_fixture
fi
run_config_metadata_migration
if composer_migration_enabled; then
  verify_composer_requires_explicit_proxy_selection
fi
if swift_migration_enabled; then
  verify_swift_repository_definitions "$KKREPO_URL" "primary fail-closed migration" false missing
  verify_swift_proxy_secret_storage missing "primary fail-closed migration"
  configure_swift_target_proxy_credentials "$KKREPO_URL" "primary"
fi
if ansible_migration_enabled; then
  verify_ansible_repository_definitions "$KKREPO_URL" "primary"
  verify_ansible_secret_proxy_storage
fi
if conda_migration_enabled; then
  verify_conda_repository_definitions "$KKREPO_URL" "primary"
fi
if apt_migration_enabled; then
  verify_apt_repository_definition "$KKREPO_URL" "primary fail-closed migration" false
  if [[ "$(apt_signing_key_count | tr -d '[:space:]')" != "0" ]]; then
    log "APT private signing material was migrated without explicit approval"
    exit 1
  fi
fi
if alpine_migration_enabled; then
  verify_alpine_repository_definitions \
    "$KKREPO_URL" "primary fail-closed migration" false
  if [[ "$(alpine_signing_key_count | tr -d '[:space:]')" != "0" ]]; then
    log "Alpine private signing material was migrated without explicit approval"
    exit 1
  fi
fi
if r_migration_enabled; then
  verify_r_repository_definitions "$KKREPO_URL" "primary"
fi

kkrepo_ref="${KKREPO_DOCKER_REGISTRY}/${IMAGE}:${TAG}"

docker_login "$KKREPO_DOCKER_REGISTRY" "$KKREPO_USER" "$KKREPO_PASSWORD"

source_digest_value="$(push_fixture_to_source_nexus "$IMAGE" "$TAG")"
cargo_sha256_value=""
pub_sha256_value=""
migration_repositories_json="\"$(json_escape "$NEXUS_REPOSITORY")\""
backup_proxy_repositories_json=""
backup_proxy_repository_values=""
if cargo_migration_enabled; then
  if ! source_cargo_available; then
    log "expected Cargo repository $CARGO_NEXUS_REPOSITORY is not available on datastore source"
    exit 1
  fi
  log "publishing Cargo fixture to source Nexus: $CARGO_CRATE $CARGO_VERSION"
  cargo_sha256_value="$(publish_cargo_fixture_to_source_nexus "$CARGO_CRATE" "$CARGO_VERSION")"
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$CARGO_NEXUS_REPOSITORY")\""
fi
if pub_migration_enabled; then
  if ! source_pub_available; then
    log "expected Pub repository $PUB_NEXUS_REPOSITORY is not available on Nexus 3.92 source"
    exit 1
  fi
  log "publishing Pub fixture to source Nexus: $PUB_PACKAGE $PUB_VERSION"
  pub_sha256_value="$(publish_pub_fixture_to_source_nexus "$PUB_PACKAGE" "$PUB_VERSION")"
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$PUB_NEXUS_REPOSITORY")\""
fi
if swift_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$SWIFT_NEXUS_REPOSITORY")\""
fi
if ansible_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$ANSIBLE_NEXUS_REPOSITORY")\""
  if [[ -n "$backup_proxy_repository_values" ]]; then
    backup_proxy_repository_values="$backup_proxy_repository_values,"
  fi
  backup_proxy_repository_values="$backup_proxy_repository_values\"$(json_escape "$ANSIBLE_PROXY_NEXUS_REPOSITORY")\""
fi
if conda_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$CONDA_NEXUS_REPOSITORY")\""
fi
if apt_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$APT_NEXUS_REPOSITORY")\""
fi
if alpine_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$ALPINE_NEXUS_REPOSITORY")\""
fi
if r_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$R_NEXUS_REPOSITORY")\""
fi
if terraform_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$TERRAFORM_NEXUS_REPOSITORY")\""
  if [[ -n "$backup_proxy_repository_values" ]]; then
    backup_proxy_repository_values="$backup_proxy_repository_values,"
  fi
  backup_proxy_repository_values="$backup_proxy_repository_values\"$(json_escape "$TERRAFORM_PROXY_NEXUS_REPOSITORY")\""
fi
if composer_migration_enabled; then
  if [[ -n "$backup_proxy_repository_values" ]]; then
    backup_proxy_repository_values="$backup_proxy_repository_values,"
  fi
  backup_proxy_repository_values="$backup_proxy_repository_values\"$(json_escape "$COMPOSER_NEXUS_REPOSITORY")\""
fi
if [[ -n "$backup_proxy_repository_values" ]]; then
  backup_proxy_repositories_json=",\"backupProxyRepositories\":[$backup_proxy_repository_values]"
fi

payload="{
  \"sourceBaseUrl\":\"$(json_escape "$NEXUS_URL")\",
  \"sourceUsername\":\"$(json_escape "$NEXUS_USER")\",
  \"sourcePassword\":\"$(json_escape "$NEXUS_PASSWORD")\",
  \"repositories\":[$migration_repositories_json]$backup_proxy_repositories_json,
  \"pageSize\":$PAGE_SIZE,
  \"concurrency\":$CONCURRENCY,
  \"checksumValidation\":true
}"

log "starting Docker repository-data metadata migration from $NEXUS_REPOSITORY"
start_body="$(curl -m 60 -fsS \
  -u "$(auth)" \
  -H "Content-Type: application/json" \
  --data "$payload" \
  "$KKREPO_URL/internal/migration/nexus/repository-data/start")"
job_id="$(printf '%s' "$start_body" | json_field jobId)"
if [[ -z "$job_id" ]]; then
  log "could not parse migration job id from: $start_body"
  exit 1
fi

wait_for_discovery_ready "$job_id"

package_migration_url="$KKREPO_URL"
package_migration_label="primary"
if swift_migration_enabled; then
  restart_primary_at_swift_migration_stage_boundary "$job_id"
  package_migration_url="$KKREPO_SECONDARY_URL"
  package_migration_label="secondary after primary restart"
fi

log "starting package/blob migration for job $job_id through $package_migration_label"
curl -m 30 -fsS \
  -u "$(auth)" \
  -X POST \
  "$package_migration_url/internal/migration/nexus/repository-data/jobs/$job_id/packages/start" >/dev/null
wait_for_migration_idle "$job_id" "$package_migration_url" "$package_migration_label"
if swift_migration_enabled; then
  verify_migration_job_visible "$job_id" "$KKREPO_URL" "primary after secondary completion"
  verify_migration_job_visible "$job_id" "$KKREPO_SECONDARY_URL" "secondary after completion"
fi

log "pulling migrated image from kkrepo: $kkrepo_ref"
docker pull "$kkrepo_ref" >/dev/null

target_digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$kkrepo_ref" 2>/dev/null || true)"
if [[ -n "$source_digest_value" && -n "$target_digest" ]]; then
  target_digest_value="${target_digest#*@}"
  if [[ "$source_digest_value" != "$target_digest_value" ]]; then
    log "digest mismatch: source=$source_digest_value target=$target_digest"
    exit 1
  fi
  log "digest verified: $target_digest_value"
fi

if [[ -n "$cargo_sha256_value" ]]; then
  verify_migrated_cargo_fixture "$CARGO_CRATE" "$CARGO_VERSION" "$cargo_sha256_value"
fi

if [[ -n "$pub_sha256_value" ]]; then
  verify_migrated_pub_fixture "$PUB_PACKAGE" "$PUB_VERSION" "$pub_sha256_value"
fi

if terraform_migration_enabled; then
  verify_migrated_terraform_fixture
  verify_migrated_terraform_proxy_fixture "$job_id"
fi

if composer_migration_enabled; then
  verify_migrated_composer_fixture "$job_id"
fi

if ansible_migration_enabled; then
  verify_migrated_ansible_fixture "$job_id" "$KKREPO_URL" "primary"
  verify_ansible_database_and_blob \
    "$ANSIBLE_KKREPO_REPOSITORY" "$ANSIBLE_NAMESPACE" "$ANSIBLE_COLLECTION" \
    "$ANSIBLE_VERSION" "$ANSIBLE_FIXTURE_ARCHIVE" "$ANSIBLE_FIXTURE_SHA256" "hosted collection"
  verify_ansible_database_and_blob \
    "$ANSIBLE_PROXY_KKREPO_REPOSITORY" "$ANSIBLE_PROXY_NAMESPACE" "$ANSIBLE_PROXY_COLLECTION" \
    "$ANSIBLE_PROXY_VERSION" "$ANSIBLE_PROXY_FIXTURE_ARCHIVE" "$ANSIBLE_PROXY_FIXTURE_SHA256" \
    "explicit proxy-cache collection"
  install_migrated_ansible_collections
  if swift_migration_enabled && [[ -n "$KKREPO_SECONDARY_URL" ]]; then
    verify_ansible_repository_definitions "$KKREPO_SECONDARY_URL" "secondary"
    verify_migrated_ansible_fixture "$job_id" "$KKREPO_SECONDARY_URL" "secondary"
  fi
fi

if conda_migration_enabled; then
  verify_migrated_conda_fixture "$job_id" "$KKREPO_URL" "primary"
  conda_counts="$(conda_fixture_row_counts)"
  log "Conda migration row counts: $(assert_conda_fixture_counts "$conda_counts")"
  if swift_migration_enabled && [[ -n "$KKREPO_SECONDARY_URL" ]]; then
    verify_conda_repository_definitions "$KKREPO_SECONDARY_URL" "secondary"
    verify_migrated_conda_fixture "$job_id" "$KKREPO_SECONDARY_URL" "secondary"
  fi
fi

if swift_migration_enabled; then
  swift_counts_before=""
  swift_counts_after=""
  verify_migrated_swift_fixture "$job_id" "$KKREPO_URL" "primary"
  swift_counts_before="$(swift_fixture_row_counts)"
  log "Swift idempotency baseline: "\
"$(assert_swift_fixture_counts "$swift_counts_before" "before rerun")"

  log "rerunning Nexus definition migration before the Swift idempotency pass"
  run_config_metadata_migration
  verify_swift_repository_definitions \
    "$KKREPO_URL" "primary fail-closed definition rerun" false missing
  verify_swift_proxy_secret_storage missing "primary fail-closed definition rerun"
  configure_swift_target_proxy_credentials "$KKREPO_URL" "primary after definition rerun"
  run_swift_idempotency_migration
  verify_migrated_swift_fixture "$SWIFT_IDEMPOTENCY_JOB_ID" "$KKREPO_URL" "primary after idempotency rerun"
  swift_counts_after="$(swift_fixture_row_counts)"
  log "Swift idempotency rerun counts: "\
"$(assert_swift_fixture_counts "$swift_counts_after" "after rerun")"
  if [[ "$swift_counts_before" != "$swift_counts_after" ]]; then
    log "Swift idempotency row counts changed: before=$swift_counts_before after=$swift_counts_after"
    exit 1
  fi
  log "Swift release/component/asset/blob/manifest counts and absent URL mapping are exactly stable across rerun"

  if [[ -n "$KKREPO_SECONDARY_URL" ]]; then
    wait_for_http "kkrepo migration read replica" \
      "$KKREPO_SECONDARY_URL/internal/repositories?purpose=admin" "$(auth)"
    verify_swift_repository_definitions "$KKREPO_SECONDARY_URL" "secondary" true configured
    verify_migrated_swift_fixture \
      "$SWIFT_IDEMPOTENCY_JOB_ID" "$KKREPO_SECONDARY_URL" "secondary"
  else
    log "SWIFT_MIGRATION_ENABLED requires KKREPO_MIGRATION_SECONDARY_URL for the cross-replica read acceptance"
    exit 1
  fi
fi

if apt_migration_enabled; then
  verify_migrated_apt_fixture "$job_id" "$KKREPO_URL" "primary" true
  apt_counts="$(apt_fixture_row_counts)"
  log "APT migration row counts: $(assert_apt_fixture_counts "$apt_counts")"
  if swift_migration_enabled && [[ -n "$KKREPO_SECONDARY_URL" ]]; then
    verify_migrated_apt_fixture "$job_id" "$KKREPO_SECONDARY_URL" "secondary" false
  fi
fi

if alpine_migration_enabled; then
  verify_migrated_alpine_fixture "$job_id" "$KKREPO_URL" "primary" true
  wait_for_alpine_publication_idle
  alpine_counts_before="$(alpine_fixture_row_counts)"
  alpine_counts_summary="$(assert_alpine_fixture_counts "$alpine_counts_before")"
  log "Alpine migration row counts: $alpine_counts_summary"
  run_alpine_idempotency_migration
  wait_for_alpine_publication_idle
  alpine_counts_after="$(alpine_fixture_row_counts)"
  alpine_counts_summary="$(assert_alpine_fixture_counts "$alpine_counts_after")"
  log "Alpine idempotency row counts: $alpine_counts_summary"
  if [[ "$alpine_counts_before" != "$alpine_counts_after" ]]; then
    log "Alpine idempotency row counts changed: before=$alpine_counts_before after=$alpine_counts_after"
    exit 1
  fi
  if [[ -n "$KKREPO_SECONDARY_URL" ]]; then
    verify_alpine_repository_definitions "$KKREPO_SECONDARY_URL" "secondary" true
    verify_migrated_alpine_fixture "$job_id" "$KKREPO_SECONDARY_URL" "secondary" false
    primary_index="$ALPINE_FIXTURE_WORKDIR/primary-APKINDEX.tar.gz"
    secondary_index="$ALPINE_FIXTURE_WORKDIR/secondary-APKINDEX.tar.gz"
    wait_for_alpine_index \
      "$KKREPO_URL" "$ALPINE_GROUP_KKREPO_REPOSITORY" \
      "$KKREPO_USER" "$KKREPO_PASSWORD" "$primary_index"
    wait_for_alpine_index \
      "$KKREPO_SECONDARY_URL" "$ALPINE_GROUP_KKREPO_REPOSITORY" \
      "$KKREPO_USER" "$KKREPO_PASSWORD" "$secondary_index"
    cmp "$primary_index" "$secondary_index"
    log "Alpine signed group index is byte-identical across migration replicas"
  else
    log "ALPINE_MIGRATION_ENABLED requires KKREPO_MIGRATION_SECONDARY_URL"
    exit 1
  fi
fi

if r_migration_enabled; then
  verify_migrated_r_fixture "$job_id" "$KKREPO_URL" "primary" true
  wait_for_r_publication_idle
  r_counts_before="$(r_fixture_row_counts)"
  log "R migration row counts: $(assert_r_fixture_counts "$r_counts_before")"
  run_r_idempotency_migration
  wait_for_r_publication_idle
  r_counts_after="$(r_fixture_row_counts)"
  log "R idempotency row counts: $(assert_r_fixture_counts "$r_counts_after")"
  if [[ "$r_counts_before" != "$r_counts_after" ]]; then
    log "R idempotency row counts changed: before=$r_counts_before after=$r_counts_after"
    exit 1
  fi
  if [[ -n "$KKREPO_SECONDARY_URL" ]]; then
    verify_r_repository_definitions "$KKREPO_SECONDARY_URL" "secondary"
    verify_migrated_r_fixture "$job_id" "$KKREPO_SECONDARY_URL" "secondary" false
    primary_r_index="$R_FIXTURE_WORKDIR/primary-PACKAGES.gz"
    secondary_r_index="$R_FIXTURE_WORKDIR/secondary-PACKAGES.gz"
    wait_for_r_index \
      "$KKREPO_URL" "$R_GROUP_KKREPO_REPOSITORY" "$KKREPO_USER" "$KKREPO_PASSWORD" \
      "$primary_r_index" true
    wait_for_r_index \
      "$KKREPO_SECONDARY_URL" "$R_GROUP_KKREPO_REPOSITORY" \
      "$KKREPO_USER" "$KKREPO_PASSWORD" "$secondary_r_index" true
    cmp "$primary_r_index" "$secondary_r_index"
    log "R group PACKAGES.gz is byte-identical across migration replicas"
  else
    log "R_MIGRATION_ENABLED requires KKREPO_MIGRATION_SECONDARY_URL"
    exit 1
  fi
fi

log "Docker/Cargo/Pub/Composer/Terraform/Swift/Ansible/Conda/APT/Alpine/R migration E2E completed: job=$job_id source=${NEXUS_URL%/}/repository/${NEXUS_REPOSITORY}/v2/${IMAGE}:${TAG} target=$kkrepo_ref"
