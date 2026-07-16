#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.compat.yml}"
if [[ -z "${COMPOSE_PROJECT_NAME:-}" ]]; then
  case "$(basename "$COMPOSE_FILE")" in
    docker-compose.compat-postgres.yml) COMPOSE_PROJECT_NAME=kkrepo-postgres-compat ;;
    *) COMPOSE_PROJECT_NAME=kkrepo-compat ;;
  esac
fi
export COMPOSE_PROJECT_NAME

NEXUS_URL="${NEXUS_COMPAT_BASE_URL:-http://127.0.0.1:${NEXUS_COMPAT_PORT:-28090}}"
NEXUS_USER="${NEXUS_COMPAT_USERNAME:-admin}"
NEXUS_PASSWORD="${NEXUS_COMPAT_PASSWORD:-Admin1234}"
NEXUS_AUTH="$NEXUS_USER:$NEXUS_PASSWORD"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:${KKREPO_COMPAT_PORT:-18090}}"
KKREPO_MANAGEMENT_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:${KKREPO_MANAGEMENT_PORT:-18091}}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
KKREPO_BLOB_PATH="${KKREPO_COMPAT_BLOB_PATH:-/tmp/kkrepo-blobs/default}"
KKREPO_DOCKER_CONNECTOR_PORT="${KKREPO_DOCKER_CONNECTOR_PORT:-18180}"
NEXUS_DOCKER_HTTP_PORT="${NEXUS_DOCKER_HTTP_PORT:-$KKREPO_DOCKER_CONNECTOR_PORT}"
COMPOSER_NEXUS_REQUIRED="${COMPOSER_NEXUS_REQUIRED:-false}"

START_TIMEOUT_SECONDS="${LIVE_COMPAT_START_TIMEOUT_SECONDS:-240}"

wait_for_http() {
  local label="$1"
  local url="$2"
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    if curl -m 5 -fsS "$url" >/dev/null 2>&1; then
      echo "[compat] $label is ready"
      return 0
    fi
    sleep 1
  done
  echo "[compat] timed out waiting for $label at $url" >&2
  return 1
}

refresh_kkrepo_auth_if_needed() {
  if curl -m 10 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/security/session" >/dev/null 2>&1; then
    return 0
  fi
  if curl -m 10 -fsS -u "$KKREPO_USER:$NEXUS_PASSWORD" "$KKREPO_URL/internal/security/session" >/dev/null 2>&1; then
    KKREPO_PASSWORD="$NEXUS_PASSWORD"
    KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
    echo "[compat] kkrepo admin password now matches migrated source Nexus password"
    return 0
  fi
  echo "[compat] kkrepo admin password did not authenticate with the configured or source Nexus password" >&2
  return 1
}

create_kkrepo_api_key() {
  local domain="$1"
  local display_name="$2"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -H "Content-Type: application/json" \
    --data "{\"domain\":\"$domain\",\"displayName\":\"$display_name\"}" \
    "$KKREPO_URL/internal/security/api-keys/current" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

basic_auth_header() {
  python3 - "$1" <<'PY'
import base64
import sys

print("Basic " + base64.b64encode(sys.argv[1].encode("utf-8")).decode("ascii"))
PY
}

nexus_initial_password() {
  docker compose -f "$COMPOSE_FILE" exec -T nexus \
    sh -c 'cat /nexus-data/admin.password 2>/dev/null || true' | tr -d '\r\n'
}

initialize_nexus_admin() {
  if curl -m 5 -fsS -u "$NEXUS_AUTH" "$NEXUS_URL/service/rest/v1/status" >/dev/null 2>&1; then
    echo "[compat] Nexus admin password already matches requested credentials"
    return 0
  fi

  local initial_password=""
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    initial_password="$(nexus_initial_password)"
    if [[ -n "$initial_password" ]]; then
      break
    fi
    sleep 1
  done

  if [[ -z "$initial_password" ]]; then
    echo "[compat] timed out waiting for Nexus admin.password" >&2
    return 1
  fi

  echo "[compat] setting Nexus admin password"
  curl -m 30 -fsS \
    -u "$NEXUS_USER:$initial_password" \
    -X PUT \
    -H "Content-Type: text/plain" \
    --data-binary "$NEXUS_PASSWORD" \
    "$NEXUS_URL/service/rest/v1/security/users/$NEXUS_USER/change-password" >/dev/null

  curl -m 10 -fsS -u "$NEXUS_AUTH" "$NEXUS_URL/service/rest/v1/status" >/dev/null
}

accept_nexus_eula_if_required() {
  local eula_file accepted_file http_status
  eula_file="$(mktemp)"
  accepted_file="$(mktemp)"

  http_status="$(curl -m 20 -sS \
    -u "$NEXUS_AUTH" \
    -H "Accept: application/json" \
    -o "$eula_file" \
    -w "%{http_code}" \
    "$NEXUS_URL/service/rest/v1/system/eula" || true)"

  if [[ "$http_status" == "404" ]]; then
    echo "[compat] Nexus EULA endpoint is not available; skipping"
    rm -f "$eula_file" "$accepted_file"
    return 0
  fi
  if [[ "$http_status" != "200" ]]; then
    echo "[compat] Nexus EULA lookup failed with HTTP $http_status" >&2
    cat "$eula_file" >&2 || true
    rm -f "$eula_file" "$accepted_file"
    return 1
  fi
  if grep -q '"accepted"[[:space:]]*:[[:space:]]*true' "$eula_file"; then
    echo "[compat] Nexus EULA already accepted"
    rm -f "$eula_file" "$accepted_file"
    return 0
  fi

  python3 - "$eula_file" "$accepted_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    payload = json.load(source)
payload["accepted"] = True
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(payload, target, ensure_ascii=False, separators=(",", ":"))
PY

  echo "[compat] accepting Nexus EULA"
  curl -m 20 -fsS \
    -u "$NEXUS_AUTH" \
    -X POST \
    -H "Content-Type: application/json; charset=UTF-8" \
    --data-binary "@$accepted_file" \
    "$NEXUS_URL/service/rest/v1/system/eula" >/dev/null

  rm -f "$eula_file" "$accepted_file"
}

configure_nexus_anonymous_access() {
  local body_file http_status
  body_file="$(mktemp)"
  http_status="$(curl -m 20 -sS \
    -u "$NEXUS_AUTH" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data '{"enabled":true,"userId":"anonymous","realmName":"NexusAuthorizingRealm"}' \
    -o "$body_file" \
    -w "%{http_code}" \
    "$NEXUS_URL/service/rest/v1/security/anonymous" || true)"
  if [[ "$http_status" =~ ^2[0-9][0-9]$ ]]; then
    echo "[compat] Nexus anonymous access is enabled"
    rm -f "$body_file"
    return 0
  fi
  if [[ "$http_status" == "404" ]]; then
    echo "[compat] Nexus anonymous configuration endpoint is not available; skipping"
    rm -f "$body_file"
    return 0
  fi
  echo "[compat] Nexus anonymous configuration failed with HTTP $http_status" >&2
  cat "$body_file" >&2 || true
  rm -f "$body_file"
  return 1
}

nexus_repo_exists() {
  local name="$1"
  local repositories
  repositories="$(curl -m 20 -fsS -u "$NEXUS_AUTH" "$NEXUS_URL/service/rest/v1/repositories")"
  grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\"" <<<"$repositories"
}

nexus_create_repo() {
  local name="$1"
  local endpoint="$2"
  local payload="$3"
  if nexus_repo_exists "$name"; then
    echo "[compat] Nexus repository exists: $name"
    return 0
  fi
  echo "[compat] creating Nexus repository: $name"
  curl -m 30 -fsS \
    -u "$NEXUS_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$endpoint" >/dev/null
}

nexus_try_create_repo() {
  local name="$1"
  local endpoint="$2"
  local payload="$3"
  local body_file status
  if nexus_repo_exists "$name"; then
    echo "[compat] Nexus repository exists: $name"
    return 0
  fi
  body_file="$(mktemp)"
  echo "[compat] creating optional Nexus repository: $name"
  status="$(curl -m 30 -sS \
    -u "$NEXUS_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "$payload" \
    -o "$body_file" \
    -w "%{http_code}" \
    "$endpoint" || true)"
  if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
    rm -f "$body_file"
    return 0
  fi
  if [[ "$status" == "400" || "$status" == "404" ]]; then
    echo "[compat] optional Nexus repository is not available on this source: $name (HTTP $status)"
    rm -f "$body_file"
    return 0
  fi
  echo "[compat] optional Nexus repository create failed: $name HTTP $status" >&2
  cat "$body_file" >&2 || true
  rm -f "$body_file"
  return 1
}

ensure_nexus_repositories() {
  nexus_create_repo "maven-releases" "$NEXUS_URL/service/rest/v1/repositories/maven/hosted" '{
    "name":"maven-releases",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW_ONCE"},
    "maven":{"versionPolicy":"RELEASE","layoutPolicy":"STRICT"}
  }'

  nexus_create_repo "maven-snapshots" "$NEXUS_URL/service/rest/v1/repositories/maven/hosted" '{
    "name":"maven-snapshots",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW"},
    "maven":{"versionPolicy":"SNAPSHOT","layoutPolicy":"STRICT"}
  }'

  nexus_create_repo "maven-central" "$NEXUS_URL/service/rest/v1/repositories/maven/proxy" '{
    "name":"maven-central",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "proxy":{"remoteUrl":"https://repo1.maven.org/maven2/","contentMaxAge":1440,"metadataMaxAge":1440},
    "negativeCache":{"enabled":true,"timeToLive":1440},
    "httpClient":{"blocked":false,"autoBlock":true},
    "maven":{"versionPolicy":"RELEASE","layoutPolicy":"PERMISSIVE"}
  }'

  nexus_create_repo "maven-public" "$NEXUS_URL/service/rest/v1/repositories/maven/group" '{
    "name":"maven-public",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "group":{"memberNames":["maven-releases","maven-snapshots","maven-central"]}
  }'

  nexus_create_repo "npm-hosted" "$NEXUS_URL/service/rest/v1/repositories/npm/hosted" '{
    "name":"npm-hosted",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW"}
  }'

  nexus_create_repo "npm-proxy" "$NEXUS_URL/service/rest/v1/repositories/npm/proxy" '{
    "name":"npm-proxy",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "proxy":{"remoteUrl":"https://registry.npmjs.org","contentMaxAge":1440,"metadataMaxAge":1440},
    "negativeCache":{"enabled":true,"timeToLive":1440},
    "httpClient":{"blocked":false,"autoBlock":true}
  }'

  nexus_create_repo "npm-group" "$NEXUS_URL/service/rest/v1/repositories/npm/group" '{
    "name":"npm-group",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "group":{"memberNames":["npm-hosted","npm-proxy"]}
  }'

  nexus_create_repo "docker-hosted" "$NEXUS_URL/service/rest/v1/repositories/docker/hosted" "{
    \"name\":\"docker-hosted\",
    \"online\":true,
    \"storage\":{\"blobStoreName\":\"default\",\"strictContentTypeValidation\":true,\"writePolicy\":\"ALLOW\"},
    \"docker\":{\"v1Enabled\":false,\"forceBasicAuth\":true,\"httpPort\":$NEXUS_DOCKER_HTTP_PORT}
  }"

  nexus_try_create_repo "cargo-hosted" "$NEXUS_URL/service/rest/v1/repositories/cargo/hosted" '{
    "name":"cargo-hosted",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW"}
  }'

  nexus_try_create_repo "pub-hosted" "$NEXUS_URL/service/rest/v1/repositories/pub/hosted" '{
    "name":"pub-hosted",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW"}
  }'

  nexus_try_create_repo "pub-proxy" "$NEXUS_URL/service/rest/v1/repositories/pub/proxy" '{
    "name":"pub-proxy",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "proxy":{"remoteUrl":"https://pub.dev/","contentMaxAge":1440,"metadataMaxAge":1440},
    "negativeCache":{"enabled":true,"timeToLive":1440},
    "httpClient":{"blocked":false,"autoBlock":true}
  }'

  nexus_try_create_repo "pub-group" "$NEXUS_URL/service/rest/v1/repositories/pub/group" '{
    "name":"pub-group",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "group":{"memberNames":["pub-hosted","pub-proxy"]}
  }'

  nexus_try_create_repo "swift-hosted" "$NEXUS_URL/service/rest/v1/repositories/swift/hosted" '{
    "name":"swift-hosted",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW_ONCE"}
  }'

  nexus_try_create_repo "swift-proxy" "$NEXUS_URL/service/rest/v1/repositories/swift/proxy" '{
    "name":"swift-proxy",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "proxy":{"remoteUrl":"https://github.com/","contentMaxAge":1440,"metadataMaxAge":1440},
    "negativeCache":{"enabled":true,"timeToLive":60},
    "httpClient":{"blocked":false,"autoBlock":true}
  }'

  nexus_try_create_repo "swift-group" "$NEXUS_URL/service/rest/v1/repositories/swift/group" '{
    "name":"swift-group",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "group":{"memberNames":["swift-hosted","swift-proxy"]}
  }'

  local composer_payload='{
    "name":"composer-proxy",
    "online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
    "proxy":{"remoteUrl":"https://repo.packagist.org/","contentMaxAge":1440,"metadataMaxAge":1440},
    "negativeCache":{"enabled":true,"timeToLive":1440},
    "httpClient":{"blocked":false,"autoBlock":true}
  }'
  if [[ "$COMPOSER_NEXUS_REQUIRED" == "true" ]]; then
    nexus_create_repo "composer-proxy" "$NEXUS_URL/service/rest/v1/repositories/composer/proxy" "$composer_payload"
  else
    nexus_try_create_repo "composer-proxy" "$NEXUS_URL/service/rest/v1/repositories/composer/proxy" "$composer_payload"
  fi
}

initialize_kkrepo_admin() {
  wait_for_http "kkrepo bootstrap endpoint" "$KKREPO_URL/internal/security/bootstrap"

  local bootstrap_json
  bootstrap_json="$(curl -m 10 -fsS "$KKREPO_URL/internal/security/bootstrap")"
  if [[ "$bootstrap_json" != *'"required":true'* ]]; then
    echo "[compat] kkrepo admin bootstrap is already complete"
    refresh_kkrepo_auth_if_needed
    return 0
  fi

  local cookie_file headers_file token payload
  cookie_file="$(mktemp)"
  headers_file="$(mktemp)"

  curl -m 10 -sS -D "$headers_file" -c "$cookie_file" \
    "$KKREPO_URL/internal/security/session" >/dev/null || true
  token="$(awk 'BEGIN{IGNORECASE=1} /^X-Nexus-Plus-CSRF-Token:/ {gsub("\r","",$2); print $2}' "$headers_file" | tail -n 1)"
  if [[ -z "$token" ]]; then
    echo "[compat] kkrepo did not expose a CSRF token for bootstrap" >&2
    return 1
  fi

  payload="$(printf '{"password":"%s","passwordConfirm":"%s","anonymousAccessEnabled":true}' "$KKREPO_PASSWORD" "$KKREPO_PASSWORD")"
  echo "[compat] bootstrapping kkrepo admin"
  curl -m 20 -fsS \
    -b "$cookie_file" \
    -c "$cookie_file" \
    -H "X-Nexus-Plus-CSRF-Token: $token" \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KKREPO_URL/internal/security/bootstrap/admin" >/dev/null

  curl -m 10 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/security/session" >/dev/null
  rm -f "$cookie_file" "$headers_file"
}

kkrepo_blob_store_exists() {
  local name="$1"
  local stores
  stores="$(curl -m 20 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/blob-stores")"
  grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\"" <<<"$stores"
}

ensure_kkrepo_blob_store() {
  if kkrepo_blob_store_exists "default"; then
    echo "[compat] kkrepo blob store exists: default"
    return 0
  fi
  echo "[compat] creating kkrepo file blob store: default"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "{\"name\":\"default\",\"type\":\"file\",\"path\":\"$KKREPO_BLOB_PATH\"}" \
    "$KKREPO_URL/internal/blob-stores" >/dev/null
}

kkrepo_repo_exists() {
  local name="$1"
  local repositories
  repositories="$(curl -m 20 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/repositories?purpose=admin")"
  grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\"" <<<"$repositories"
}

kkrepo_create_repo() {
  local name="$1"
  local payload="$2"
  if kkrepo_repo_exists "$name"; then
    echo "[compat] kkrepo repository exists: $name"
    return 0
  fi
  echo "[compat] creating kkrepo repository: $name"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KKREPO_URL/internal/repositories" >/dev/null
}

ensure_kkrepo_repositories() {
  kkrepo_create_repo "maven-releases" '{
    "name":"maven-releases",
    "recipe":"maven2-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW_ONCE","versionPolicy":"RELEASE","layoutPolicy":"STRICT"}
  }'

  kkrepo_create_repo "maven-snapshots" '{
    "name":"maven-snapshots",
    "recipe":"maven2-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW","versionPolicy":"SNAPSHOT","layoutPolicy":"STRICT"}
  }'

  kkrepo_create_repo "maven-central" '{
    "name":"maven-central",
    "recipe":"maven2-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://repo1.maven.org/maven2/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "maven-public" '{
    "name":"maven-public",
    "recipe":"maven2-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["maven-releases","maven-snapshots","maven-central"]}
  }'

  kkrepo_create_repo "npm-hosted" '{
    "name":"npm-hosted",
    "recipe":"npm-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "npm-proxy" '{
    "name":"npm-proxy",
    "recipe":"npm-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://registry.npmjs.org","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "npm-group" '{
    "name":"npm-group",
    "recipe":"npm-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["npm-hosted","npm-proxy"]}
  }'

  kkrepo_create_repo "pypi-hosted" '{
    "name":"pypi-hosted",
    "recipe":"pypi-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "pypi-proxy" '{
    "name":"pypi-proxy",
    "recipe":"pypi-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://pypi.org/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "pypi-group" '{
    "name":"pypi-group",
    "recipe":"pypi-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["pypi-hosted","pypi-proxy"]}
  }'

  kkrepo_create_repo "go-proxy" '{
    "name":"go-proxy",
    "recipe":"go-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://proxy.golang.org/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "go-group" '{
    "name":"go-group",
    "recipe":"go-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["go-proxy"]}
  }'

  kkrepo_create_repo "helm-hosted" '{
    "name":"helm-hosted",
    "recipe":"helm-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "cargo-hosted" '{
    "name":"cargo-hosted",
    "recipe":"cargo-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "cargo-proxy" '{
    "name":"cargo-proxy",
    "recipe":"cargo-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://index.crates.io/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true},
    "cargo":{"requireAuthentication":false}
  }'

  kkrepo_create_repo "cargo-group" '{
    "name":"cargo-group",
    "recipe":"cargo-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["cargo-hosted","cargo-proxy"]},
    "cargo":{"requireAuthentication":true}
  }'

  kkrepo_create_repo "pub-hosted" '{
    "name":"pub-hosted",
    "recipe":"pub-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "pub-proxy" '{
    "name":"pub-proxy",
    "recipe":"pub-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://pub.dev/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "pub-group" '{
    "name":"pub-group",
    "recipe":"pub-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["pub-hosted","pub-proxy"]}
  }'

  kkrepo_create_repo "composer-hosted" '{
    "name":"composer-hosted",
    "recipe":"composer-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW_ONCE"}
  }'

  kkrepo_create_repo "composer-proxy" '{
    "name":"composer-proxy",
    "recipe":"composer-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://repo.packagist.org/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "composer-group" '{
    "name":"composer-group",
    "recipe":"composer-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["composer-hosted","composer-proxy"]}
  }'

  kkrepo_create_repo "nuget-hosted" '{
    "name":"nuget-hosted",
    "recipe":"nuget-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "nuget-proxy" '{
    "name":"nuget-proxy",
    "recipe":"nuget-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://api.nuget.org/v3/index.json","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":false}
  }'

  kkrepo_create_repo "nuget-group" '{
    "name":"nuget-group",
    "recipe":"nuget-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["nuget-hosted","nuget-proxy"]}
  }'

  kkrepo_create_repo "rubygems-hosted" '{
    "name":"rubygems-hosted",
    "recipe":"rubygems-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "rubygems-proxy" '{
    "name":"rubygems-proxy",
    "recipe":"rubygems-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://rubygems.org/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "rubygems-group" '{
    "name":"rubygems-group",
    "recipe":"rubygems-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["rubygems-hosted","rubygems-proxy"]}
  }'

  kkrepo_create_repo "yum-hosted" '{
    "name":"yum-hosted",
    "recipe":"yum-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  kkrepo_create_repo "yum-group" '{
    "name":"yum-group",
    "recipe":"yum-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["yum-hosted"]}
  }'

  kkrepo_create_repo "terraform-hosted" '{
    "name":"terraform-hosted",
    "recipe":"terraform-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW_ONCE"}
  }'

  kkrepo_create_repo "terraform-proxy" '{
    "name":"terraform-proxy",
    "recipe":"terraform-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://registry.terraform.io/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  kkrepo_create_repo "terraform-group" '{
    "name":"terraform-group",
    "recipe":"terraform-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["terraform-hosted","terraform-proxy"]}
  }'

  kkrepo_create_repo "swift-hosted" '{
    "name":"swift-hosted",
    "recipe":"swift-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW_ONCE"}
  }'

  kkrepo_create_repo "swift-proxy" '{
    "name":"swift-proxy",
    "recipe":"swift-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://github.com/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"negativeCacheEnabled":true,"negativeCacheTtlMinutes":1,"autoBlock":true}
  }'

  kkrepo_create_repo "swift-group" '{
    "name":"swift-group",
    "recipe":"swift-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["swift-hosted","swift-proxy"]}
  }'

  kkrepo_create_repo "docker-hosted" "{
    \"name\":\"docker-hosted\",
    \"recipe\":\"docker-hosted\",
    \"online\":true,
    \"blobStoreName\":\"default\",
    \"strictContentTypeValidation\":true,
    \"hosted\":{\"writePolicy\":\"ALLOW\"},
    \"docker\":{\"connectorEnabled\":true,\"connectorPort\":$KKREPO_DOCKER_CONNECTOR_PORT}
  }"
}

refresh_kkrepo_docker_connectors() {
  echo "[compat] refreshing kkrepo Docker connectors"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    "$KKREPO_URL/internal/docker/connectors/refresh" >/dev/null
}

seed_pub_hosted_fixture() {
  local package_name="${PUB_COMPAT_HOSTED_PACKAGE:-kkrepo_compat_hosted_fixture}"
  local package_version="${PUB_COMPAT_HOSTED_VERSION:-1.0.0}"
  if ! nexus_repo_exists "pub-hosted"; then
    echo "[compat] Nexus Pub hosted repository is not available; hosted Pub fixture skipped"
    return 0
  fi
  echo "[compat] publishing Pub hosted fixture $package_name $package_version"
  local work_dir archive nexus_auth_header kkrepo_token
  work_dir="$(mktemp -d)"
  archive="$work_dir/$package_name-$package_version.tar.gz"
  create_pub_fixture_archive "$package_name" "$package_version" "$archive"
  nexus_auth_header="$(basic_auth_header "$NEXUS_AUTH")"
  publish_pub_fixture "Nexus" "$NEXUS_URL/repository/pub-hosted" "$nexus_auth_header" \
    "$archive" "$package_name" "$package_version"
  kkrepo_token="$(create_kkrepo_api_key PubToken "live compat hosted pub fixture")"
  publish_pub_fixture "kkrepo" "$KKREPO_URL/repository/pub-hosted" "Bearer $kkrepo_token" \
    "$archive" "$package_name" "$package_version"
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    {
      echo "PUB_COMPAT_HOSTED_PACKAGE=$package_name"
      echo "PUB_COMPAT_HOSTED_VERSION=$package_version"
    } >>"$GITHUB_ENV"
  fi
  export PUB_COMPAT_HOSTED_PACKAGE="$package_name"
  export PUB_COMPAT_HOSTED_VERSION="$package_version"
  rm -rf "$work_dir"
}

create_pub_fixture_archive() {
  local package_name="$1"
  local package_version="$2"
  local archive="$3"
  python3 - "$package_name" "$package_version" "$archive" <<'PY'
import io
import sys
import tarfile

package_name = sys.argv[1]
package_version = sys.argv[2]
archive = sys.argv[3]

files = {
    "pubspec.yaml": f"""name: {package_name}
version: {package_version}
environment:
  sdk: ">=3.0.0 <4.0.0"
""",
    "README.md": f"# {package_name}\n\nGenerated by kkRepo live compatibility setup.\n",
    "CHANGELOG.md": f"## {package_version}\n\n- Initial compatibility package.\n",
    "LICENSE": "MIT License\n",
    f"lib/{package_name}.dart": "String message() => 'kkrepo pub live compatibility';\n",
}

with tarfile.open(archive, "w:gz") as tar:
    for name, text in files.items():
        data = text.encode("utf-8")
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mtime = 0
        tar.addfile(info, io.BytesIO(data))
PY
}

publish_pub_fixture() {
  local label="$1"
  local base_url="$2"
  local authorization="$3"
  local archive="$4"
  local package_name="$5"
  local package_version="$6"
  python3 - "$label" "$base_url" "$authorization" "$archive" "$package_name" "$package_version" <<'PY'
import hashlib
import json
import sys
import uuid
from urllib import error, parse, request

label, base_url, authorization, archive, package_name, package_version = sys.argv[1:]
base_url = base_url.rstrip("/")

class NoRedirectHandler(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = request.build_opener(NoRedirectHandler)

def http(method, url, body=None, headers=None, expected=None):
    req_headers = {
        "Authorization": authorization,
        "User-Agent": "kkrepo-live-compat-pub-fixture/1",
    }
    if headers:
        req_headers.update(headers)
    req = request.Request(url, data=body, headers=req_headers, method=method)
    try:
        with opener.open(req, timeout=60) as resp:
            data = resp.read()
            status = resp.getcode()
            resp_headers = dict(resp.headers.items())
    except error.HTTPError as exc:
        data = exc.read()
        status = exc.code
        resp_headers = dict(exc.headers.items())
    if expected is not None and status not in expected:
        raise SystemExit(f"{label} {method} {url} returned HTTP {status}: {data[:500]!r}")
    return status, data, resp_headers

def json_body(data, context):
    try:
        return json.loads(data.decode("utf-8"))
    except Exception as exc:
        raise SystemExit(f"{label} {context} did not return JSON: {data[:500]!r}") from exc

def multipart(fields, archive_bytes):
    boundary = "kkrepo-live-pub-" + uuid.uuid4().hex
    chunks = []
    for key, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode("ascii"))
        chunks.append(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode("utf-8"))
        chunks.append(str(value).encode("utf-8") + b"\r\n")
    chunks.append(f"--{boundary}\r\n".encode("ascii"))
    filename = f"{package_name}-{package_version}.tar.gz"
    chunks.append(
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode("utf-8"))
    chunks.append(b"Content-Type: application/gzip\r\n\r\n")
    chunks.append(archive_bytes)
    chunks.append(f"\r\n--{boundary}--\r\n".encode("ascii"))
    return boundary, b"".join(chunks)

metadata_status, metadata_data, _ = http(
    "GET",
    f"{base_url}/api/packages/{package_name}",
    headers={"Accept": "application/vnd.pub.v2+json"},
    expected={200, 404},
)
if metadata_status == 200:
    metadata = json_body(metadata_data, "existing metadata")
    versions = {entry.get("version") for entry in metadata.get("versions", [])}
    if package_version in versions:
        print(f"[compat] {label} Pub hosted fixture already exists")
        sys.exit(0)

_, init_data, _ = http(
    "GET",
    f"{base_url}/api/packages/versions/new",
    headers={"Accept": "application/vnd.pub.v2+json"},
    expected={200},
)
init = json_body(init_data, "publish init")
upload_url = parse.urljoin(base_url + "/", str(init.get("url", "")))
fields = init.get("fields")
if not isinstance(fields, dict) or not upload_url:
    raise SystemExit(f"{label} publish init did not return url and fields: {init!r}")

with open(archive, "rb") as source:
    archive_bytes = source.read()
boundary, upload_body = multipart(fields, archive_bytes)
_, _, upload_headers = http(
    "POST",
    upload_url,
    body=upload_body,
    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    expected={200, 201, 202, 204, 303},
)
location = upload_headers.get("Location") or upload_headers.get("location")
if not location:
    raise SystemExit(f"{label} Pub upload did not return Location header")
finalize_url = parse.urljoin(upload_url, location)
http("GET", finalize_url, headers={"Accept": "application/vnd.pub.v2+json"}, expected={200})

_, metadata_data, _ = http(
    "GET",
    f"{base_url}/api/packages/{package_name}",
    headers={"Accept": "application/vnd.pub.v2+json"},
    expected={200},
)
metadata = json_body(metadata_data, "metadata after publish")
versions = {entry.get("version"): entry for entry in metadata.get("versions", [])}
entry = versions.get(package_version)
if entry is None:
    raise SystemExit(f"{label} Pub metadata does not include {package_version}: {metadata!r}")
expected_sha = hashlib.sha256(archive_bytes).hexdigest()
if entry.get("archive_sha256") and entry.get("archive_sha256") != expected_sha:
    raise SystemExit(
        f"{label} Pub metadata archive_sha256 mismatch: {entry.get('archive_sha256')} != {expected_sha}")
print(f"[compat] {label} Pub hosted fixture published")
PY
}

wait_for_http "Nexus status endpoint" "$NEXUS_URL/service/rest/v1/status"
wait_for_http "kkrepo management health" "$KKREPO_MANAGEMENT_URL/actuator/health"

initialize_nexus_admin
accept_nexus_eula_if_required
configure_nexus_anonymous_access
ensure_nexus_repositories
initialize_kkrepo_admin
ensure_kkrepo_blob_store
ensure_kkrepo_repositories
refresh_kkrepo_docker_connectors
seed_pub_hosted_fixture

echo "[compat] live compatibility environment is ready"
