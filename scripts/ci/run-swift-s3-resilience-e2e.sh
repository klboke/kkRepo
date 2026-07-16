#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${SWIFT_S3_E2E_COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.swift-s3-e2e.yml}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-kkrepo-swift-s3-e2e}"
PRIMARY_URL="${SWIFT_S3_PRIMARY_URL:-http://127.0.0.1:38090}"
PRIMARY_MANAGEMENT_URL="${SWIFT_S3_PRIMARY_MANAGEMENT_URL:-http://127.0.0.1:38091}"
SECONDARY_URL="${SWIFT_S3_SECONDARY_URL:-http://127.0.0.1:38094}"
SECONDARY_MANAGEMENT_URL="${SWIFT_S3_SECONDARY_MANAGEMENT_URL:-http://127.0.0.1:38095}"
NEXUS_URL="${SWIFT_S3_NEXUS_URL:-http://127.0.0.1:48090}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
NEXUS_USER="${NEXUS_COMPAT_USERNAME:-admin}"
NEXUS_PASSWORD="${NEXUS_COMPAT_PASSWORD:-Admin1234}"
NEXUS_AUTH="$NEXUS_USER:$NEXUS_PASSWORD"
SWIFT_BIN="${SWIFT_S3_E2E_SWIFT_BIN:-}"
START_TIMEOUT_SECONDS="${LIVE_COMPAT_START_TIMEOUT_SECONDS:-300}"
ARTIFACT_DIR="${CLIENT_E2E_ARTIFACT_DIR:-$PROJECT_ROOT/artifacts/swift-s3-resilience-e2e}"
WORK_DIR="${CLIENT_E2E_WORK_DIR:-${RUNNER_TEMP:-$PROJECT_ROOT/target}/swift-s3-resilience-work}"
STAMP="${CLIENT_E2E_STAMP:-$(date +%Y%m%d%H%M%S)}"
SWIFT_PROXY_SCOPE="${SWIFT_COMPAT_PROXY_SCOPE:-apple}"
SWIFT_PROXY_NAME="${SWIFT_COMPAT_PROXY_NAME:-swift-log}"
SWIFT_PROXY_VERSION="${SWIFT_COMPAT_PROXY_VERSION:-1.6.3}"
SWIFT_PROXY_TAG_CASES="${SWIFT_COMPAT_PROXY_TAG_CASES:-MaestriHub/shared-modules/v0.3.8/0.3.8,MaestriHub/shared-modules/V0.3.7/0.3.7}"

export COMPOSE_PROJECT_NAME
mkdir -p "$ARTIFACT_DIR" "$WORK_DIR"

if [[ -z "$SWIFT_BIN" || ! -x "$SWIFT_BIN" ]]; then
  echo "SWIFT_S3_E2E_SWIFT_BIN must point to an executable Swift 6 client" >&2
  exit 2
fi

log() {
  printf '[swift-s3-resilience] %s\n' "$*"
}

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

wait_for_http() {
  local label="$1"
  local url="$2"
  local attempt
  for ((attempt = 1; attempt <= START_TIMEOUT_SECONDS; attempt++)); do
    if curl -m 5 -fsS "$url" >/dev/null 2>&1; then
      log "$label is ready"
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label at $url"
  return 1
}

nexus_initial_password() {
  compose exec -T nexus \
    sh -c 'cat /nexus-data/admin.password 2>/dev/null || true' | tr -d '\r\n'
}

initialize_nexus_admin() {
  if curl -m 10 -fsS -u "$NEXUS_AUTH" \
      "$NEXUS_URL/service/rest/v1/status" >/dev/null 2>&1; then
    return 0
  fi
  local initial_password=""
  local attempt
  for ((attempt = 1; attempt <= START_TIMEOUT_SECONDS; attempt++)); do
    initial_password="$(nexus_initial_password)"
    if [[ -n "$initial_password" ]]; then
      break
    fi
    sleep 1
  done
  if [[ -z "$initial_password" ]]; then
    log "timed out waiting for Nexus admin.password"
    return 1
  fi
  curl -m 30 -fsS \
    -u "$NEXUS_USER:$initial_password" \
    -X PUT \
    -H 'Content-Type: text/plain' \
    --data-binary "$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/security/users/$NEXUS_USER/change-password" >/dev/null
  curl -m 10 -fsS -u "$NEXUS_AUTH" \
    "$NEXUS_URL/service/rest/v1/status" >/dev/null
}

accept_nexus_eula_if_required() {
  local current accepted status
  current="$(mktemp)"
  accepted="$(mktemp)"
  status="$(curl -m 20 -sS \
    -u "$NEXUS_AUTH" \
    -H 'Accept: application/json' \
    -o "$current" \
    -w '%{http_code}' \
    "$NEXUS_URL/service/rest/v1/system/eula" || true)"
  if [[ "$status" == "404" ]] || grep -q '"accepted"[[:space:]]*:[[:space:]]*true' "$current"; then
    rm -f "$current" "$accepted"
    return 0
  fi
  if [[ "$status" != "200" ]]; then
    log "Nexus EULA lookup failed with HTTP $status"
    rm -f "$current" "$accepted"
    return 1
  fi
  python3 - "$current" "$accepted" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
payload["accepted"] = True
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(payload, separators=(",", ":")), encoding="utf-8")
PY
  curl -m 20 -fsS \
    -u "$NEXUS_AUTH" \
    -X POST \
    -H 'Content-Type: application/json; charset=UTF-8' \
    --data-binary "@$accepted" \
    "$NEXUS_URL/service/rest/v1/system/eula" >/dev/null
  rm -f "$current" "$accepted"
}

configure_nexus_anonymous_access() {
  log "enabling Nexus anonymous access to match the kkrepo fixture"
  curl -m 20 -fsS \
    -u "$NEXUS_AUTH" \
    -X PUT \
    -H 'Content-Type: application/json' \
    --data '{"enabled":true,"userId":"anonymous","realmName":"NexusAuthorizingRealm"}' \
    "$NEXUS_URL/service/rest/v1/security/anonymous" >/dev/null
}

nexus_repository_exists() {
  local name="$1"
  curl -m 20 -fsS -u "$NEXUS_AUTH" \
    "$NEXUS_URL/service/rest/v1/repositories" \
    | python3 -c 'import json,sys; name=sys.argv[1]; raise SystemExit(0 if any(item.get("name")==name for item in json.load(sys.stdin)) else 1)' \
      "$name"
}

ensure_nexus_repository() {
  local name="$1"
  local endpoint="$2"
  local payload="$3"
  if nexus_repository_exists "$name"; then
    return 0
  fi
  log "creating Nexus Swift repository: $name"
  curl -m 30 -fsS \
    -u "$NEXUS_AUTH" \
    -X POST \
    -H 'Content-Type: application/json' \
    --data "$payload" \
    "$NEXUS_URL/service/rest/v1/repositories/swift/$endpoint" >/dev/null
}

ensure_nexus_swift_repositories() {
  ensure_nexus_repository swift-compat-hosted hosted '{
    "name":"swift-compat-hosted","online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,
      "writePolicy":"ALLOW_ONCE"}
  }'
  ensure_nexus_repository swift-compat-hosted-secondary hosted '{
    "name":"swift-compat-hosted-secondary","online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,
      "writePolicy":"ALLOW_ONCE"}
  }'
  ensure_nexus_repository swift-compat-proxy proxy '{
    "name":"swift-compat-proxy","online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "proxy":{"remoteUrl":"https://github.com/","contentMaxAge":1440,
      "metadataMaxAge":1440},
    "negativeCache":{"enabled":true,"timeToLive":60},
    "httpClient":{"blocked":false,"autoBlock":true}
  }'
  ensure_nexus_repository swift-compat-group group '{
    "name":"swift-compat-group","online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "group":{"memberNames":["swift-compat-hosted",
      "swift-compat-hosted-secondary","swift-compat-proxy"]}
  }'
}

verify_s3_blob_store() {
  local stores_file probe_file store_id
  stores_file="$ARTIFACT_DIR/blob-stores.json"
  probe_file="$ARTIFACT_DIR/blob-store-probe.json"
  curl -m 30 -fsS -u "$KKREPO_AUTH" \
    "$PRIMARY_URL/internal/blob-stores" >"$stores_file"
  store_id="$(python3 - "$stores_file" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
store = next(item for item in payload.get("stores", []) if item.get("name") == "default")
assert store.get("type") == "s3", store
assert store.get("engine") == "aws-s3", store
assert store.get("endpoint") == "http://minio:9000", store
assert store.get("bucket") == "kkrepo-swift", store
assert store.get("prefix") == "swift-e2e", store
assert store.get("pathStyleAccess") is True, store
print(store["id"])
PY
)"
  curl -m 60 -fsS -u "$KKREPO_AUTH" \
    -X POST "$PRIMARY_URL/internal/blob-stores/$store_id/check" >"$probe_file"
  python3 - "$probe_file" <<'PY'
import json
import pathlib
import sys

probe = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert probe.get("ok") is True, probe
summary = probe.get("summary") or {}
assert summary.get("bucketExists") is True, probe
PY

  local attempt
  for ((attempt = 1; attempt <= 60; attempt++)); do
    if curl -m 10 -fsS -u "$KKREPO_AUTH" \
        "$SECONDARY_URL/internal/blob-stores" \
        | python3 -c 'import json,sys; stores=json.load(sys.stdin).get("stores", []); raise SystemExit(0 if any(s.get("name")=="default" and s.get("type")=="s3" for s in stores) else 1)'; then
      return 0
    fi
    sleep 1
  done
  log "secondary replica did not observe the S3 blob store"
  return 1
}

bootstrap_swift_blackbox_repositories() {
  log "bootstrapping the Nexus and kkrepo Swift compatibility repositories"
  SWIFT_COMPAT_ENABLED=true \
  SWIFT_COMPAT_PROXY_ENABLED=false \
  SWIFT_NEXUS_COMPAT_BASE_URL="$NEXUS_URL" \
  SWIFT_NEXUS_COMPAT_USERNAME="$NEXUS_USER" \
  SWIFT_NEXUS_COMPAT_PASSWORD="$NEXUS_PASSWORD" \
  SWIFT_KKREPO_COMPAT_BASE_URL="$PRIMARY_URL" \
  SWIFT_KKREPO_COMPAT_USERNAME="$KKREPO_USER" \
  SWIFT_KKREPO_COMPAT_PASSWORD="$KKREPO_PASSWORD" \
  mvn -B -ntp -pl compat-test -am \
    '-Dtest=SwiftRepositoryBlackBoxCompatibilityTest#mediaNegotiationIdentityValidationAndProblemDetailsMatchNexusWhenConfigured' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test 2>&1 | tee "$ARTIFACT_DIR/swift-blackbox-bootstrap.log"
}

run_swift_proxy_cold_read_blackbox() {
  log "running the 32-reader Swift proxy cold-read and orphaned-owner recovery contract"
  SWIFT_COMPAT_ENABLED=true \
  SWIFT_COMPAT_PROXY_ENABLED=true \
  SWIFT_COMPAT_PROXY_SCOPE="$SWIFT_PROXY_SCOPE" \
  SWIFT_COMPAT_PROXY_NAME="$SWIFT_PROXY_NAME" \
  SWIFT_COMPAT_PROXY_VERSION="$SWIFT_PROXY_VERSION" \
  SWIFT_NEXUS_COMPAT_BASE_URL="$NEXUS_URL" \
  SWIFT_NEXUS_COMPAT_USERNAME="$NEXUS_USER" \
  SWIFT_NEXUS_COMPAT_PASSWORD="$NEXUS_PASSWORD" \
  SWIFT_KKREPO_COMPAT_BASE_URL="$PRIMARY_URL" \
  SWIFT_KKREPO_COMPAT_USERNAME="$KKREPO_USER" \
  SWIFT_KKREPO_COMPAT_PASSWORD="$KKREPO_PASSWORD" \
  SWIFT_KKREPO_SECONDARY_BASE_URL="$SECONDARY_URL" \
  mvn -B -ntp -pl compat-test -am \
    '-Dtest=SwiftRepositoryBlackBoxCompatibilityTest#githubProxyAndConcurrentColdReadContractsMatchNexusWhenConfigured' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test 2>&1 | tee "$ARTIFACT_DIR/swift-proxy-cold-read-blackbox.log"
}

run_swift_blackbox() {
  log "running Swift compatibility blackbox against the S3-backed candidate"
  SWIFT_COMPAT_ENABLED=true \
  SWIFT_COMPAT_PROXY_ENABLED=true \
  SWIFT_COMPAT_PROXY_SCOPE="$SWIFT_PROXY_SCOPE" \
  SWIFT_COMPAT_PROXY_NAME="$SWIFT_PROXY_NAME" \
  SWIFT_COMPAT_PROXY_VERSION="$SWIFT_PROXY_VERSION" \
  SWIFT_COMPAT_REQUIRE_PROXY_TAG_CASES=true \
  SWIFT_COMPAT_PROXY_TAG_CASES="$SWIFT_PROXY_TAG_CASES" \
  SWIFT_COMPAT_RENAMED_REPOSITORY_CASE=apple/swift-docc-plugin/swiftlang/swift-docc-plugin/1.5.0 \
  SWIFT_COMPAT_OVERSIZE_BYTES=5242880 \
  SWIFT_NEXUS_COMPAT_BASE_URL="$NEXUS_URL" \
  SWIFT_NEXUS_COMPAT_USERNAME="$NEXUS_USER" \
  SWIFT_NEXUS_COMPAT_PASSWORD="$NEXUS_PASSWORD" \
  SWIFT_KKREPO_COMPAT_BASE_URL="$PRIMARY_URL" \
  SWIFT_KKREPO_COMPAT_USERNAME="$KKREPO_USER" \
  SWIFT_KKREPO_COMPAT_PASSWORD="$KKREPO_PASSWORD" \
  SWIFT_KKREPO_SECONDARY_BASE_URL="$SECONDARY_URL" \
  mvn -B -ntp -pl compat-test -am \
    -Dtest=SwiftRepositoryBlackBoxCompatibilityTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test 2>&1 | tee "$ARTIFACT_DIR/swift-blackbox.log"
}

repository_id() {
  local repository_name="$1"
  compose exec -T postgresql psql -U kkrepo -d kkrepo -At \
    -v ON_ERROR_STOP=1 -v repository_name="$repository_name" <<'SQL'
SELECT id FROM repository WHERE name = :'repository_name';
SQL
}

seed_expired_lease() {
  local lease_key="$1"
  compose exec -T postgresql psql -U kkrepo -d kkrepo \
    -v ON_ERROR_STOP=1 -v lease_key="$lease_key" <<'SQL' >/dev/null
INSERT INTO swift_coordinate_lease
  (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
VALUES
  (:'lease_key', 'lost-primary-replica', 41, 1, NOW() - INTERVAL '1 second',
   NOW() - INTERVAL '1 second')
ON CONFLICT (lease_key) DO UPDATE SET
  owner = EXCLUDED.owner,
  fencing_token = EXCLUDED.fencing_token,
  attempt_count = EXCLUDED.attempt_count,
  expires_at = EXCLUDED.expires_at,
  updated_at = EXCLUDED.updated_at;
SQL
}

assert_lease_takeover() {
  local lease_key="$1"
  local label="${2:-lease}"
  local row owner token attempts
  row="$(compose exec -T postgresql psql -U kkrepo -d kkrepo -At \
    -v ON_ERROR_STOP=1 -v lease_key="$lease_key" <<'SQL'
SELECT owner || '|' || fencing_token || '|' || attempt_count
FROM swift_coordinate_lease
WHERE lease_key = :'lease_key';
SQL
)"
  IFS='|' read -r owner token attempts <<<"$row"
  if [[ -z "$owner" || "$owner" == "lost-primary-replica" \
      || ! "$token" =~ ^[0-9]+$ || "$token" -le 41 \
      || ! "$attempts" =~ ^[0-9]+$ || "$attempts" -lt 2 ]]; then
    log "expired lease was not safely taken over: $row"
    return 1
  fi
  printf '%s\n' "$row" >"$ARTIFACT_DIR/fencing-takeover-$label.txt"
}

assert_proxy_release_ready() {
  local repository_id_value="$1"
  local row
  row="$(compose exec -T postgresql psql -U kkrepo -d kkrepo -At \
    -v ON_ERROR_STOP=1 \
    -v repository_id="$repository_id_value" \
    -v scope="$SWIFT_PROXY_SCOPE" \
    -v name="$SWIFT_PROXY_NAME" \
    -v version="$SWIFT_PROXY_VERSION" <<'SQL'
SELECT COUNT(*) || '|' || COUNT(DISTINCT source.release_id) || '|' ||
       COALESCE(MIN(source.cache_state), '') || '|' ||
       COALESCE(MIN(release.status), '')
FROM swift_proxy_source source
LEFT JOIN swift_release release ON release.id = source.release_id
WHERE source.repository_id = :'repository_id'::BIGINT
  AND source.scope_lc = LOWER(:'scope')
  AND source.name_lc = LOWER(:'name')
  AND source.version = :'version';
SQL
)"
  if [[ "$row" != "1|1|READY|READY" ]]; then
    log "proxy cold read did not leave one shared READY release: $row"
    return 1
  fi
  printf '%s\n' "$row" >"$ARTIFACT_DIR/proxy-ready-release.txt"
}

seed_proxy_negative_cache() {
  local repository_id_value="$1"
  local cache_key="$2"
  local status_code="$3"
  local cache_key_hash
  cache_key_hash="$(python3 - "$cache_key" <<'PY'
import hashlib
import sys

print(hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest())
PY
)"
  compose exec -T postgresql psql -U kkrepo -d kkrepo \
    -v ON_ERROR_STOP=1 \
    -v repository_id="$repository_id_value" \
    -v cache_key="$cache_key" \
    -v cache_key_hash="$cache_key_hash" \
    -v status_code="$status_code" <<'SQL' >/dev/null
INSERT INTO swift_proxy_negative_cache
  (repository_id, cache_key, cache_key_hash, status_code, retry_after, expires_at,
   updated_at)
VALUES
  (:'repository_id'::BIGINT, :'cache_key', decode(:'cache_key_hash', 'hex'),
   :'status_code'::INTEGER,
   CASE WHEN :'status_code'::INTEGER = 429 THEN NOW() + INTERVAL '10 minutes' END,
   NOW() + INTERVAL '10 minutes', NOW())
ON CONFLICT (repository_id, cache_key_hash) DO UPDATE SET
  cache_key = EXCLUDED.cache_key,
  status_code = EXCLUDED.status_code,
  retry_after = EXCLUDED.retry_after,
  expires_at = EXCLUDED.expires_at,
  updated_at = EXCLUDED.updated_at;
SQL
}

assert_proxy_problem() {
  local label="$1"
  local base_url="$2"
  local repository="$3"
  local scope="$4"
  local name="$5"
  local expected_status="$6"
  local headers="$ARTIFACT_DIR/$label.headers"
  local body="$ARTIFACT_DIR/$label.json"
  local status
  status="$(curl -m 30 -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+json' \
    -D "$headers" -o "$body" -w '%{http_code}' \
    "$base_url/repository/$repository/$scope/$name")"
  python3 - "$headers" "$body" "$status" "$expected_status" <<'PY'
import json
import pathlib
import sys

headers = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
body = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
status = int(sys.argv[3])
expected = int(sys.argv[4])
assert status == expected, (status, expected, body)
assert body.get("status") == expected, body
assert body.get("type") == "about:blank", body
header_map = {}
for line in headers:
    if ":" in line:
        key, value = line.split(":", 1)
        header_map.setdefault(key.strip().lower(), []).append(value.strip())
assert header_map.get("content-version") == ["1"], header_map
assert header_map.get("content-type", [""])[-1].startswith("application/problem+json"), header_map
retry_after = header_map.get("retry-after")
if expected == 429:
    assert retry_after and int(retry_after[-1]) >= 1, header_map
else:
    assert not retry_after, header_map
PY
}

assert_persisted_upstream_failure_contracts() {
  local repository_id_value="$1"
  local rate_scope="kkrepo"
  local rate_name="rate-limited-fixture"
  local bad_scope="kkrepo"
  local bad_name="bad-upstream-fixture"

  log "verifying shared 429/5xx waterlines, group propagation, and stale fallback"
  seed_proxy_negative_cache "$repository_id_value" \
    "tags:$rate_scope/$rate_name" 429
  assert_proxy_problem proxy-rate-limited-primary "$PRIMARY_URL" \
    swift-compat-proxy "$rate_scope" "$rate_name" 429

  seed_proxy_negative_cache "$repository_id_value" \
    "tags:$bad_scope/$bad_name" 502
  assert_proxy_problem group-bad-upstream-secondary "$SECONDARY_URL" \
    swift-compat-group "$bad_scope" "$bad_name" 502

  seed_proxy_negative_cache "$repository_id_value" \
    "tags:$(printf '%s' "$SWIFT_PROXY_SCOPE" | tr '[:upper:]' '[:lower:]')/$(printf '%s' "$SWIFT_PROXY_NAME" | tr '[:upper:]' '[:lower:]')" 502
  curl -m 30 -fsS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+json' \
    "$SECONDARY_URL/repository/swift-compat-group/$SWIFT_PROXY_SCOPE/$SWIFT_PROXY_NAME" \
    >"$ARTIFACT_DIR/group-stale-fallback-secondary.json"
  python3 - "$ARTIFACT_DIR/group-stale-fallback-secondary.json" \
    "$SWIFT_PROXY_VERSION" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert sys.argv[2] in (payload.get("releases") or {}), payload
PY
}

sha256_file() {
  python3 - "$1" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
}

download_archive() {
  local label="$1"
  local base_url="$2"
  local archive_path="$3"
  local output="$ARTIFACT_DIR/$label.zip"
  curl -m 60 -fsS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+zip' \
    "$base_url/$archive_path" >"$output"
  sha256_file "$output"
}

assert_registry_pin() {
  local resolved_file="$1"
  local coordinate="$2"
  local version="$3"
  python3 - "$resolved_file" "$coordinate" "$version" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
pins = payload.get("pins") or payload.get("object", {}).get("pins") or []
coordinate = sys.argv[2].lower()
package_name = coordinate.rsplit(".", 1)[-1]
version = sys.argv[3]
matches = [pin for pin in pins
           if str(pin.get("identity") or pin.get("package") or "").lower()
           in {coordinate, package_name}]
assert any(pin.get("kind") == "registry"
           and str((pin.get("state") or {}).get("version") or "") == version
           for pin in matches), matches
PY
}

clear_swift_client_cache() {
  local project="$1"
  local home="$2"
  rm -rf \
    "$project/.build" \
    "$project/.swiftpm/cache" \
    "$home/.cache/org.swift.swiftpm" \
    "$home/.swiftpm/cache" \
    "$home/Library/Caches/org.swift.swiftpm"
  rm -f "$project/Package.resolved"
}

resolve_after_restart() {
  local label="$1"
  local project="$2"
  local home="$3"
  local coordinate="$4"
  local version="$5"
  clear_swift_client_cache "$project" "$home"
  (
    cd "$project"
    env HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$SWIFT_BIN" package resolve
    env HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$SWIFT_BIN" build
  ) >"$ARTIFACT_DIR/$label.log" 2>&1
  cp "$project/Package.resolved" "$ARTIFACT_DIR/$label-Package.resolved"
  assert_registry_pin "$project/Package.resolved" "$coordinate" "$version"
}

resolve_proxy_after_restart() {
  local label="$1"
  local project="$2"
  local home="$3"
  clear_swift_client_cache "$project" "$home"
  (
    cd "$project"
    env HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$SWIFT_BIN" package resolve --replace-scm-with-registry
    env HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$SWIFT_BIN" build --replace-scm-with-registry
  ) >"$ARTIFACT_DIR/$label.log" 2>&1
  cp "$project/Package.resolved" "$ARTIFACT_DIR/$label-Package.resolved"
  assert_registry_pin "$project/Package.resolved" \
    "$SWIFT_PROXY_SCOPE.$SWIFT_PROXY_NAME" "$SWIFT_PROXY_VERSION"
}

verify_minio_objects() {
  compose run --rm -T --no-deps --entrypoint /bin/sh minio-init -ec '
    mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null
    mc ls --recursive local/kkrepo-swift/swift-e2e
  ' >"$ARTIFACT_DIR/minio-objects.txt"
  if [[ ! -s "$ARTIFACT_DIR/minio-objects.txt" ]]; then
    log "MinIO bucket contains no persisted Swift objects"
    return 1
  fi
}

start_minio_transfer_container() {
  local container_name="$1"
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  compose run -d --no-deps \
    --name "$container_name" \
    --entrypoint /bin/sh minio-init -ec 'sleep 600' >/dev/null
}

stop_minio_transfer_container() {
  docker rm -f "$1" >/dev/null 2>&1 || true
}

backup_and_restore_state() {
  local database_backup="$ARTIFACT_DIR/backup/kkrepo.dump"
  local object_backup="$ARTIFACT_DIR/backup/kkrepo-swift.tar"
  local object_stage="$WORK_DIR/object-backup-$STAMP"
  local transfer_container="${COMPOSE_PROJECT_NAME}-swift-object-transfer-$STAMP"
  mkdir -p "$ARTIFACT_DIR/backup"
  rm -rf "$object_stage"
  mkdir -p "$object_stage"

  log "backing up the shared PostgreSQL metadata and S3-compatible object state"
  compose exec -T postgresql \
    pg_dump -U kkrepo -d kkrepo --format=custom --no-owner --no-acl \
    >"$database_backup"
  start_minio_transfer_container "$transfer_container"
  docker exec "$transfer_container" /bin/sh -ec '
    rm -rf /tmp/kkrepo-swift-backup
    mkdir -p /tmp/kkrepo-swift-backup
    mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null
    mc mirror --quiet local/kkrepo-swift /tmp/kkrepo-swift-backup
  ' >/dev/null
  docker cp "$transfer_container:/tmp/kkrepo-swift-backup/." "$object_stage/"
  stop_minio_transfer_container "$transfer_container"
  tar -C "$object_stage" -cf "$object_backup" .
  if [[ ! -s "$database_backup" || ! -s "$object_backup" ]]; then
    log "backup artifacts are empty"
    return 1
  fi

  log "destroying the live metadata database and object bucket before restore"
  compose stop primary secondary
  compose exec -T postgresql psql -U kkrepo -d postgres -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'kkrepo' AND pid <> pg_backend_pid();
DROP DATABASE kkrepo;
CREATE DATABASE kkrepo OWNER kkrepo;
SQL
  compose run --rm -T --no-deps --entrypoint /bin/sh minio-init -ec '
    mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null
    mc rm --recursive --force local/kkrepo-swift >/dev/null 2>&1 || true
    mc mb --ignore-existing local/kkrepo-swift >/dev/null
    mc anonymous set private local/kkrepo-swift >/dev/null
  '

  log "restoring PostgreSQL and S3-compatible backups into empty stores"
  compose exec -T postgresql \
    pg_restore -U kkrepo -d kkrepo --exit-on-error --no-owner --no-acl \
    <"$database_backup"
  rm -rf "$object_stage"
  mkdir -p "$object_stage"
  tar -C "$object_stage" -xf "$object_backup"
  start_minio_transfer_container "$transfer_container"
  docker exec "$transfer_container" /bin/sh -ec '
    mkdir -p /tmp/kkrepo-swift-restore
  '
  docker cp "$object_stage/." "$transfer_container:/tmp/kkrepo-swift-restore/"
  docker exec "$transfer_container" /bin/sh -ec '
    mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null
    mc mirror --quiet --overwrite /tmp/kkrepo-swift-restore local/kkrepo-swift
  ' >/dev/null
  stop_minio_transfer_container "$transfer_container"
  rm -rf "$object_stage"

  compose start primary secondary
  wait_for_http "restored primary management health" "$PRIMARY_MANAGEMENT_URL/actuator/health"
  wait_for_http "restored secondary management health" "$SECONDARY_MANAGEMENT_URL/actuator/health"
  verify_s3_blob_store
  verify_minio_objects
  printf 'database=%s\nobjects=%s\n' \
    "$(sha256_file "$database_backup")" "$(sha256_file "$object_backup")" \
    >"$ARTIFACT_DIR/backup/backup.sha256"
}

swift_version="$($SWIFT_BIN --version | head -n 1)"
if [[ ! "$swift_version" =~ Swift[[:space:]]version[[:space:]]6\. ]]; then
  log "Swift 6 is required, got: $swift_version"
  exit 2
fi
printf '%s\n' "$swift_version" >"$ARTIFACT_DIR/swift-version.txt"

wait_for_http "primary management health" "$PRIMARY_MANAGEMENT_URL/actuator/health"
wait_for_http "secondary management health" "$SECONDARY_MANAGEMENT_URL/actuator/health"
wait_for_http "Nexus status" "$NEXUS_URL/service/rest/v1/status"
initialize_nexus_admin
accept_nexus_eula_if_required
configure_nexus_anonymous_access
ensure_nexus_swift_repositories

export KKREPO_COMPAT_BASE_URL="$PRIMARY_URL"
export KKREPO_MANAGEMENT_URL="$PRIMARY_MANAGEMENT_URL"
export KKREPO_COMPAT_USERNAME="$KKREPO_USER"
export KKREPO_COMPAT_PASSWORD="$KKREPO_PASSWORD"
export SWIFT_E2E_BLOB_STORE_PAYLOAD='{
  "name":"default","type":"s3","engine":"aws-s3",
  "endpoint":"http://minio:9000","region":"us-east-1",
  "bucket":"kkrepo-swift","prefix":"swift-e2e",
  "accessKey":"minioadmin","secretKey":"minioadmin",
  "pathStyleAccess":true,"multipartThresholdBytes":1,
  "multipartPartSizeBytes":5242880,"multipartConcurrency":2
}'
"$SCRIPT_DIR/setup-swift-platform-e2e.sh"
verify_s3_blob_store

bootstrap_swift_blackbox_repositories
proxy_repository_id_value="$(repository_id swift-compat-proxy)"
if [[ ! "$proxy_repository_id_value" =~ ^[0-9]+$ ]]; then
  log "cannot find swift-compat-proxy repository id: $proxy_repository_id_value"
  exit 1
fi
proxy_scope_normalized="$(printf '%s' "$SWIFT_PROXY_SCOPE" | tr '[:upper:]' '[:lower:]')"
proxy_name_normalized="$(printf '%s' "$SWIFT_PROXY_NAME" | tr '[:upper:]' '[:lower:]')"
proxy_lease_key="swift:$proxy_repository_id_value:$proxy_scope_normalized:$proxy_name_normalized:$SWIFT_PROXY_VERSION"
seed_expired_lease "$proxy_lease_key"
run_swift_proxy_cold_read_blackbox
assert_lease_takeover "$proxy_lease_key" proxy
assert_proxy_release_ready "$proxy_repository_id_value"

run_swift_blackbox
assert_persisted_upstream_failure_contracts "$proxy_repository_id_value"

repository_id_value="$(repository_id swift-hosted)"
if [[ ! "$repository_id_value" =~ ^[0-9]+$ ]]; then
  log "cannot find swift-hosted repository id: $repository_id_value"
  exit 1
fi
package_name="client-e2e-$STAMP-s3"
package_version="1.0.1"
lease_key="swift:$repository_id_value:kkrepo:$package_name:$package_version"
seed_expired_lease "$lease_key"

export CLIENT_E2E_ARTIFACT_DIR="$ARTIFACT_DIR/client"
export CLIENT_E2E_WORK_DIR="$WORK_DIR/client"
export CLIENT_E2E_STAMP="$STAMP"
export CLIENT_E2E_TESTS=swift
export SWIFT_E2E_BINS="s3=$SWIFT_BIN"
export SWIFT_E2E_PROXY_ENABLED=true
export SWIFT_E2E_PROXY_SCOPE="$SWIFT_PROXY_SCOPE"
export SWIFT_E2E_PROXY_NAME="$SWIFT_PROXY_NAME"
export SWIFT_E2E_PROXY_VERSION="$SWIFT_PROXY_VERSION"
export SWIFT_E2E_LARGE_FIXTURE_BYTES="${SWIFT_E2E_LARGE_FIXTURE_BYTES:-2097152}"
export SWIFT_KKREPO_BASE_URL="$PRIMARY_URL"
export SWIFT_KKREPO_SECONDARY_BASE_URL="$SECONDARY_URL"
"$SCRIPT_DIR/run-client-e2e.sh"

assert_lease_takeover "$lease_key" hosted
verify_minio_objects

archive_path="repository/swift-group/kkrepo/$package_name/$package_version.zip"
proxy_archive_path="repository/swift-group/$SWIFT_PROXY_SCOPE/$SWIFT_PROXY_NAME/$SWIFT_PROXY_VERSION.zip"
primary_sha="$(download_archive initial-primary "$PRIMARY_URL" "$archive_path")"
secondary_sha="$(download_archive initial-secondary "$SECONDARY_URL" "$archive_path")"
proxy_primary_sha="$(download_archive proxy-initial-primary "$PRIMARY_URL" "$proxy_archive_path")"
proxy_secondary_sha="$(download_archive proxy-initial-secondary "$SECONDARY_URL" "$proxy_archive_path")"
if [[ "$primary_sha" != "$secondary_sha" ]]; then
  log "cross-replica archive SHA mismatch: $primary_sha != $secondary_sha"
  exit 1
fi
printf '%s\n' "$primary_sha" >"$ARTIFACT_DIR/archive.sha256"
if [[ "$proxy_primary_sha" != "$proxy_secondary_sha" ]]; then
  log "cross-replica proxy archive SHA mismatch: $proxy_primary_sha != $proxy_secondary_sha"
  exit 1
fi
printf '%s\n' "$proxy_primary_sha" >"$ARTIFACT_DIR/proxy-archive.sha256"

log "stopping primary and verifying the secondary replica remains readable"
compose stop primary
[[ "$(download_archive primary-down-secondary "$SECONDARY_URL" "$archive_path")" == "$primary_sha" ]]
[[ "$(download_archive proxy-primary-down-secondary "$SECONDARY_URL" "$proxy_archive_path")" == "$proxy_primary_sha" ]]
compose start primary
wait_for_http "restarted primary management health" "$PRIMARY_MANAGEMENT_URL/actuator/health"
resolve_after_restart primary-restart-resolve \
  "$WORK_DIR/client/swift-s3/consumer" \
  "$WORK_DIR/client/swift-s3/home" \
  "kkrepo.$package_name" "$package_version"
resolve_proxy_after_restart primary-restart-proxy-resolve \
  "$WORK_DIR/client/swift-s3/proxy-consumer" \
  "$WORK_DIR/client/swift-s3/home"

log "stopping secondary and verifying the primary replica remains readable"
compose stop secondary
[[ "$(download_archive secondary-down-primary "$PRIMARY_URL" "$archive_path")" == "$primary_sha" ]]
[[ "$(download_archive proxy-secondary-down-primary "$PRIMARY_URL" "$proxy_archive_path")" == "$proxy_primary_sha" ]]
compose start secondary
wait_for_http "restarted secondary management health" "$SECONDARY_MANAGEMENT_URL/actuator/health"
resolve_after_restart secondary-restart-resolve \
  "$WORK_DIR/client/swift-s3/secondary-consumer" \
  "$WORK_DIR/client/swift-s3/secondary-home" \
  "kkrepo.$package_name" "$package_version"

[[ "$(download_archive final-primary "$PRIMARY_URL" "$archive_path")" == "$primary_sha" ]]
[[ "$(download_archive final-secondary "$SECONDARY_URL" "$archive_path")" == "$primary_sha" ]]
[[ "$(download_archive proxy-final-primary "$PRIMARY_URL" "$proxy_archive_path")" == "$proxy_primary_sha" ]]
[[ "$(download_archive proxy-final-secondary "$SECONDARY_URL" "$proxy_archive_path")" == "$proxy_primary_sha" ]]

backup_and_restore_state
[[ "$(download_archive restored-primary "$PRIMARY_URL" "$archive_path")" == "$primary_sha" ]]
[[ "$(download_archive restored-secondary "$SECONDARY_URL" "$archive_path")" == "$primary_sha" ]]
[[ "$(download_archive proxy-restored-primary "$PRIMARY_URL" "$proxy_archive_path")" == "$proxy_primary_sha" ]]
[[ "$(download_archive proxy-restored-secondary "$SECONDARY_URL" "$proxy_archive_path")" == "$proxy_primary_sha" ]]
resolve_after_restart backup-restore-hosted-resolve \
  "$WORK_DIR/client/swift-s3/consumer" \
  "$WORK_DIR/client/swift-s3/home" \
  "kkrepo.$package_name" "$package_version"
resolve_proxy_after_restart backup-restore-proxy-resolve \
  "$WORK_DIR/client/swift-s3/proxy-consumer" \
  "$WORK_DIR/client/swift-s3/home"
log "Swift S3-compatible dual-replica resilience E2E completed"
