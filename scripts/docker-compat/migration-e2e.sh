#!/usr/bin/env bash
set -euo pipefail

NEXUS_URL="${NEXUS_COMPAT_BASE_URL:-http://localhost:28090}"
NEXUS_REPOSITORY="${DOCKER_MIGRATION_NEXUS_REPOSITORY:-docker-hosted}"
CARGO_NEXUS_REPOSITORY="${CARGO_MIGRATION_NEXUS_REPOSITORY:-cargo-hosted}"
NEXUS_USER="${NEXUS_COMPAT_USERNAME:-admin}"
NEXUS_PASSWORD="${NEXUS_COMPAT_PASSWORD:-123456}"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:18090}"
KKREPO_HEALTH_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:18091}/actuator/health"
KKREPO_DOCKER_REGISTRY="${DOCKER_MIGRATION_KKREPO_REGISTRY:-127.0.0.1:18183}"
KKREPO_REPOSITORY="${DOCKER_MIGRATION_KKREPO_REPOSITORY:-docker-hosted}"
CARGO_KKREPO_REPOSITORY="${CARGO_MIGRATION_KKREPO_REPOSITORY:-cargo-hosted}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_BLOB_PATH="${KKREPO_COMPAT_BLOB_PATH:-/tmp/kkrepo-blobs/default}"
EXPECTED_ADAPTER="${MIGRATION_E2E_EXPECTED_ADAPTER:-}"
EXPECTED_CONNECTOR_PORT="${KKREPO_DOCKER_CONNECTOR_PORT:-18180}"

IMAGE="${DOCKER_MIGRATION_IMAGE:-kkrepo-migration/e2e}"
TAG="${DOCKER_MIGRATION_TAG:-$(date +%Y%m%d%H%M%S)}"
CARGO_CRATE="${CARGO_MIGRATION_CRATE:-kkrepo_migration_e2e_${TAG//[^A-Za-z0-9_]/_}}"
CARGO_VERSION="${CARGO_MIGRATION_VERSION:-0.1.0}"
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
    repositories = body.get("repositoryDetails")
if not isinstance(repositories, list):
    repositories = []
repo_parts = []
for repo in repositories:
    name = repo.get("repositoryName") or repo.get("name")
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
run_config_metadata_migration

kkrepo_ref="${KKREPO_DOCKER_REGISTRY}/${IMAGE}:${TAG}"

docker_login "$KKREPO_DOCKER_REGISTRY" "$KKREPO_USER" "$KKREPO_PASSWORD"

source_digest_value="$(push_fixture_to_source_nexus "$IMAGE" "$TAG")"
cargo_sha256_value=""
cargo_repositories_json="\"$(json_escape "$NEXUS_REPOSITORY")\""
if cargo_migration_enabled; then
  if ! source_cargo_available; then
    log "expected Cargo repository $CARGO_NEXUS_REPOSITORY is not available on datastore source"
    exit 1
  fi
  log "publishing Cargo fixture to source Nexus: $CARGO_CRATE $CARGO_VERSION"
  cargo_sha256_value="$(publish_cargo_fixture_to_source_nexus "$CARGO_CRATE" "$CARGO_VERSION")"
  cargo_repositories_json="$cargo_repositories_json,\"$(json_escape "$CARGO_NEXUS_REPOSITORY")\""
fi

payload="{
  \"sourceBaseUrl\":\"$(json_escape "$NEXUS_URL")\",
  \"sourceUsername\":\"$(json_escape "$NEXUS_USER")\",
  \"sourcePassword\":\"$(json_escape "$NEXUS_PASSWORD")\",
  \"repositories\":[$cargo_repositories_json],
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

log "Docker/Cargo migration E2E completed: job=$job_id source=${NEXUS_URL%/}/repository/${NEXUS_REPOSITORY}/v2/${IMAGE}:${TAG} target=$kkrepo_ref"
