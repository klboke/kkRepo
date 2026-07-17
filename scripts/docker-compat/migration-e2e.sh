#!/usr/bin/env bash
set -euo pipefail

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
    "$SWIFT_PROXY_NEXUS_REPOSITORY" <<'PY'
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
) = sys.argv[1:8]
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
    "$SWIFT_MIGRATION_ENABLED" "$SWIFT_PROXY_NEXUS_REPOSITORY" <<'PY'
import json
import sys

path, expected_adapter, repository, swift_enabled, swift_proxy_repository = sys.argv[1:6]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
status = payload.get("status")
if swift_enabled == "true":
    if status != "finished_with_manual_actions":
        raise SystemExit(
            "metadata migration with an unavailable Swift proxy credential returned "
            f"unexpected status: {status!r}"
        )
elif status not in {"finished", "finished_with_password_resets_required"}:
    raise SystemExit(f"metadata migration returned unexpected status: {status!r}")
validation = payload.get("validation") or {}
if validation.get("failed"):
    raise SystemExit(f"metadata migration validation failed: {validation}")
manual = validation.get("manualActions") or []
if swift_enabled == "true":
    if "repository/proxy credentials" not in manual:
        raise SystemExit(
            "metadata migration did not require manual Swift proxy credential completion: "
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
if swift_enabled == "true":
    proxy_checks = [
        check
        for check in manual_checks
        if check.get("scope") == "repository" and check.get("name") == "proxy credentials"
    ]
    other_manual_checks = [check for check in manual_checks if check not in proxy_checks]
    if len(proxy_checks) != 1 or other_manual_checks:
        raise SystemExit(
            "metadata migration manual checks differ from the expected fail-closed Swift proxy "
            f"credential check: proxy={proxy_checks} other={other_manual_checks}"
        )
    expected_action = "repository:" + swift_proxy_repository
    plan_manual = plan.get("manualActions") or []
    if expected_action not in plan_manual:
        raise SystemExit(
            f"metadata migration run omitted preflight action {expected_action}: {plan_manual}"
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
run_config_metadata_migration
if composer_migration_enabled; then
  verify_composer_requires_explicit_proxy_selection
fi
if swift_migration_enabled; then
  verify_swift_repository_definitions "$KKREPO_URL" "primary fail-closed migration" false missing
  verify_swift_proxy_secret_storage missing "primary fail-closed migration"
  configure_swift_target_proxy_credentials "$KKREPO_URL" "primary"
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
if terraform_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$TERRAFORM_NEXUS_REPOSITORY")\""
  backup_proxy_repository_values="\"$(json_escape "$TERRAFORM_PROXY_NEXUS_REPOSITORY")\""
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

log "Docker/Cargo/Pub/Composer/Terraform/Swift migration E2E completed: job=$job_id source=${NEXUS_URL%/}/repository/${NEXUS_REPOSITORY}/v2/${IMAGE}:${TAG} target=$kkrepo_ref"
