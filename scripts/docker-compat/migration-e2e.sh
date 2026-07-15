#!/usr/bin/env bash
set -euo pipefail

NEXUS_URL="${NEXUS_COMPAT_BASE_URL:-http://localhost:28090}"
NEXUS_REPOSITORY="${DOCKER_MIGRATION_NEXUS_REPOSITORY:-docker-hosted}"
CARGO_NEXUS_REPOSITORY="${CARGO_MIGRATION_NEXUS_REPOSITORY:-cargo-hosted}"
PUB_NEXUS_REPOSITORY="${PUB_MIGRATION_NEXUS_REPOSITORY:-pub-hosted}"
COMPOSER_NEXUS_REPOSITORY="${COMPOSER_MIGRATION_NEXUS_REPOSITORY:-composer-proxy}"
TERRAFORM_NEXUS_REPOSITORY="${TERRAFORM_MIGRATION_NEXUS_REPOSITORY:-terraform-compat-hosted}"
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
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_BLOB_PATH="${KKREPO_COMPAT_BLOB_PATH:-/tmp/kkrepo-blobs/default}"
EXPECTED_ADAPTER="${MIGRATION_E2E_EXPECTED_ADAPTER:-}"
EXPECTED_CONNECTOR_PORT="${KKREPO_DOCKER_CONNECTOR_PORT:-18180}"

IMAGE="${DOCKER_MIGRATION_IMAGE:-kkrepo-migration/e2e}"
TAG="${DOCKER_MIGRATION_TAG:-$(date +%Y%m%d%H%M%S)}"
TAG_SAFE="${TAG//[^A-Za-z0-9_]/_}"
CARGO_CRATE="${CARGO_MIGRATION_CRATE:-kkrepo_migration_e2e_${TAG_SAFE}}"
CARGO_VERSION="${CARGO_MIGRATION_VERSION:-0.1.0}"
PUB_PACKAGE="${PUB_MIGRATION_PACKAGE:-kkrepo_migration_e2e_${TAG_SAFE,,}}"
PUB_VERSION="${PUB_MIGRATION_VERSION:-0.1.0}"
COMPOSER_MIGRATION_ENABLED="${COMPOSER_MIGRATION_ENABLED:-false}"
COMPOSER_PACKAGE="${COMPOSER_MIGRATION_PACKAGE:-psr/log}"
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
from urllib.parse import urljoin, urlparse
print(urljoin(sys.argv[1], sys.argv[2]))
PY
)"
  COMPOSER_DIST_TYPE="${fields[2]}"
  curl -m 120 -fsS \
    -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    "$dist_url" >"$dist_file"
  actual_sha1="$(file_sha1 "$dist_file")"
  if [[ -n "${fields[3]}" && "${fields[3],,}" != "${actual_sha1,,}" ]]; then
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
from urllib.parse import urljoin

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
  local path="$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    local body
    body="$(curl -m 20 -fsS -u "$(auth)" "$path")"
    log "job $job_id status: $(printf '%s' "$body" | job_status_summary)"
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
  local path="$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id"
  for ((i = 1; i <= WAIT_TIMEOUT_SECONDS; i++)); do
    local body
    body="$(curl -m 20 -fsS -u "$(auth)" "$path")"
    log "job $job_id discovery status: $(printf '%s' "$body" | job_status_summary)"
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
  python3 - "$preflight_file" "$expected_adapter" "$NEXUS_REPOSITORY" "$EXPECTED_CONNECTOR_PORT" <<'PY'
import json
import sys

path, expected_adapter, repository, expected_connector_port = sys.argv[1:5]
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
  python3 - "$run_file" "$expected_adapter" "$NEXUS_REPOSITORY" <<'PY'
import json
import sys

path, expected_adapter, repository = sys.argv[1:4]
with open(path, "r", encoding="utf-8") as source:
    payload = json.load(source)
status = payload.get("status")
if status not in {"finished", "finished_with_password_resets_required"}:
    raise SystemExit(f"metadata migration returned unexpected status: {status!r}")
validation = payload.get("validation") or {}
if validation.get("failed"):
    raise SystemExit(f"metadata migration validation failed: {validation}")
manual = validation.get("manualActions") or []
if manual:
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
if failed_checks or manual_checks:
    raise SystemExit(f"metadata migration had failed/manual checks: failed={failed_checks}, manual={manual_checks}")
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
fi
if composer_migration_enabled; then
  if ! source_composer_available; then
    log "required Composer proxy repository $COMPOSER_NEXUS_REPOSITORY is not available on the source Nexus"
    exit 1
  fi
  warm_composer_proxy_fixture
fi
run_config_metadata_migration
if composer_migration_enabled; then
  verify_composer_requires_explicit_proxy_selection
fi

kkrepo_ref="${KKREPO_DOCKER_REGISTRY}/${IMAGE}:${TAG}"

docker_login "$KKREPO_DOCKER_REGISTRY" "$KKREPO_USER" "$KKREPO_PASSWORD"

source_digest_value="$(push_fixture_to_source_nexus "$IMAGE" "$TAG")"
cargo_sha256_value=""
pub_sha256_value=""
migration_repositories_json="\"$(json_escape "$NEXUS_REPOSITORY")\""
backup_proxy_repositories_json=""
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
if terraform_migration_enabled; then
  migration_repositories_json="$migration_repositories_json,\"$(json_escape "$TERRAFORM_NEXUS_REPOSITORY")\""
fi
if composer_migration_enabled; then
  backup_proxy_repositories_json=",\"backupProxyRepositories\":[\"$(json_escape "$COMPOSER_NEXUS_REPOSITORY")\"]"
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

log "starting Docker package/blob migration for job $job_id"
curl -m 30 -fsS \
  -u "$(auth)" \
  -X POST \
  "$KKREPO_URL/internal/migration/nexus/repository-data/jobs/$job_id/packages/start" >/dev/null
wait_for_migration_idle "$job_id"

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
fi

if composer_migration_enabled; then
  verify_migrated_composer_fixture "$job_id"
fi

log "Docker/Cargo/Pub/Composer/Terraform migration E2E completed: job=$job_id source=${NEXUS_URL%/}/repository/${NEXUS_REPOSITORY}/v2/${IMAGE}:${TAG} target=$kkrepo_ref"
