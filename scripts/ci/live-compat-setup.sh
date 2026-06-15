#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.compat.yml}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-nexus-plus-compat}"

NEXUS_URL="${NEXUS_COMPAT_BASE_URL:-http://127.0.0.1:${NEXUS_COMPAT_PORT:-28090}}"
NEXUS_USER="${NEXUS_COMPAT_USERNAME:-admin}"
NEXUS_PASSWORD="${NEXUS_COMPAT_PASSWORD:-123456}"
NEXUS_AUTH="$NEXUS_USER:$NEXUS_PASSWORD"

NEXUS_PLUS_URL="${NEXUS_PLUS_COMPAT_BASE_URL:-http://127.0.0.1:${NEXUS_PLUS_COMPAT_PORT:-18090}}"
NEXUS_PLUS_MANAGEMENT_URL="${NEXUS_PLUS_MANAGEMENT_URL:-http://127.0.0.1:${NEXUS_PLUS_MANAGEMENT_PORT:-18091}}"
NEXUS_PLUS_USER="${NEXUS_PLUS_COMPAT_USERNAME:-admin}"
NEXUS_PLUS_PASSWORD="${NEXUS_PLUS_COMPAT_PASSWORD:-12345678}"
NEXUS_PLUS_AUTH="$NEXUS_PLUS_USER:$NEXUS_PLUS_PASSWORD"
NEXUS_PLUS_BLOB_PATH="${NEXUS_PLUS_COMPAT_BLOB_PATH:-/tmp/nexus-plus-blobs/default}"

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
}

initialize_nexus_plus_admin() {
  wait_for_http "nexus-plus bootstrap endpoint" "$NEXUS_PLUS_URL/internal/security/bootstrap"

  local bootstrap_json
  bootstrap_json="$(curl -m 10 -fsS "$NEXUS_PLUS_URL/internal/security/bootstrap")"
  if [[ "$bootstrap_json" != *'"required":true'* ]]; then
    echo "[compat] nexus-plus admin bootstrap is already complete"
    curl -m 10 -fsS -u "$NEXUS_PLUS_AUTH" "$NEXUS_PLUS_URL/internal/security/session" >/dev/null
    return 0
  fi

  local cookie_file headers_file token payload
  cookie_file="$(mktemp)"
  headers_file="$(mktemp)"

  curl -m 10 -sS -D "$headers_file" -c "$cookie_file" \
    "$NEXUS_PLUS_URL/internal/security/session" >/dev/null || true
  token="$(awk 'BEGIN{IGNORECASE=1} /^X-Nexus-Plus-CSRF-Token:/ {gsub("\r","",$2); print $2}' "$headers_file" | tail -n 1)"
  if [[ -z "$token" ]]; then
    echo "[compat] nexus-plus did not expose a CSRF token for bootstrap" >&2
    return 1
  fi

  payload="$(printf '{"password":"%s","passwordConfirm":"%s"}' "$NEXUS_PLUS_PASSWORD" "$NEXUS_PLUS_PASSWORD")"
  echo "[compat] bootstrapping nexus-plus admin"
  curl -m 20 -fsS \
    -b "$cookie_file" \
    -c "$cookie_file" \
    -H "X-Nexus-Plus-CSRF-Token: $token" \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$NEXUS_PLUS_URL/internal/security/bootstrap/admin" >/dev/null

  curl -m 10 -fsS -u "$NEXUS_PLUS_AUTH" "$NEXUS_PLUS_URL/internal/security/session" >/dev/null
  rm -f "$cookie_file" "$headers_file"
}

nexus_plus_blob_store_exists() {
  local name="$1"
  local stores
  stores="$(curl -m 20 -fsS -u "$NEXUS_PLUS_AUTH" "$NEXUS_PLUS_URL/internal/blob-stores")"
  grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\"" <<<"$stores"
}

ensure_nexus_plus_blob_store() {
  if nexus_plus_blob_store_exists "default"; then
    echo "[compat] nexus-plus blob store exists: default"
    return 0
  fi
  echo "[compat] creating nexus-plus file blob store: default"
  curl -m 30 -fsS \
    -u "$NEXUS_PLUS_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "{\"name\":\"default\",\"type\":\"file\",\"path\":\"$NEXUS_PLUS_BLOB_PATH\"}" \
    "$NEXUS_PLUS_URL/internal/blob-stores" >/dev/null
}

nexus_plus_repo_exists() {
  local name="$1"
  local repositories
  repositories="$(curl -m 20 -fsS -u "$NEXUS_PLUS_AUTH" "$NEXUS_PLUS_URL/internal/repositories?purpose=admin")"
  grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\"" <<<"$repositories"
}

nexus_plus_create_repo() {
  local name="$1"
  local payload="$2"
  if nexus_plus_repo_exists "$name"; then
    echo "[compat] nexus-plus repository exists: $name"
    return 0
  fi
  echo "[compat] creating nexus-plus repository: $name"
  curl -m 30 -fsS \
    -u "$NEXUS_PLUS_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$NEXUS_PLUS_URL/internal/repositories" >/dev/null
}

ensure_nexus_plus_repositories() {
  nexus_plus_create_repo "maven-releases" '{
    "name":"maven-releases",
    "recipe":"maven2-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW_ONCE","versionPolicy":"RELEASE","layoutPolicy":"STRICT"}
  }'

  nexus_plus_create_repo "maven-snapshots" '{
    "name":"maven-snapshots",
    "recipe":"maven2-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW","versionPolicy":"SNAPSHOT","layoutPolicy":"STRICT"}
  }'

  nexus_plus_create_repo "maven-central" '{
    "name":"maven-central",
    "recipe":"maven2-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://repo1.maven.org/maven2/","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  nexus_plus_create_repo "maven-public" '{
    "name":"maven-public",
    "recipe":"maven2-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["maven-releases","maven-snapshots","maven-central"]}
  }'

  nexus_plus_create_repo "npm-hosted" '{
    "name":"npm-hosted",
    "recipe":"npm-hosted",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "hosted":{"writePolicy":"ALLOW"}
  }'

  nexus_plus_create_repo "npm-proxy" '{
    "name":"npm-proxy",
    "recipe":"npm-proxy",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "proxy":{"remoteUrl":"https://registry.npmjs.org","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true}
  }'

  nexus_plus_create_repo "npm-group" '{
    "name":"npm-group",
    "recipe":"npm-group",
    "online":true,
    "blobStoreName":"default",
    "strictContentTypeValidation":true,
    "group":{"memberNames":["npm-hosted","npm-proxy"]}
  }'
}

wait_for_http "Nexus status endpoint" "$NEXUS_URL/service/rest/v1/status"
wait_for_http "nexus-plus management health" "$NEXUS_PLUS_MANAGEMENT_URL/actuator/health"

initialize_nexus_admin
ensure_nexus_repositories
initialize_nexus_plus_admin
ensure_nexus_plus_blob_store
ensure_nexus_plus_repositories

echo "[compat] live compatibility environment is ready"
