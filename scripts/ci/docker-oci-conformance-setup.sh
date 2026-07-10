#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.compat.yml}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-kkrepo-oci-conformance}"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:${KKREPO_COMPAT_PORT:-18090}}"
KKREPO_MANAGEMENT_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:${KKREPO_MANAGEMENT_PORT:-18091}}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
KKREPO_BLOB_PATH="${KKREPO_COMPAT_BLOB_PATH:-/tmp/kkrepo-blobs/default}"
KKREPO_REPOSITORY="${DOCKER_OCI_REPOSITORY:-docker-hosted}"
KKREPO_DOCKER_PORT="${DOCKER_OCI_CONNECTOR_PORT:-18180}"
START_TIMEOUT_SECONDS="${LIVE_COMPAT_START_TIMEOUT_SECONDS:-240}"

log() {
  printf '[docker-oci-setup] %s\n' "$*" >&2
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
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
  local label="$1"
  local url="$2"
  local headers_file http_code
  headers_file="$(mktemp)"
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    http_code="$(curl -m 5 -sS -D "$headers_file" -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
    if [[ "$http_code" == "200" || "$http_code" == "401" ]]; then
      if grep -qi '^Docker-Distribution-API-Version:[[:space:]]*registry/2\.0' "$headers_file"; then
        rm -f "$headers_file"
        log "$label is ready"
        return 0
      fi
    fi
    : >"$headers_file"
    sleep 1
  done
  rm -f "$headers_file"
  log "timed out waiting for $label at $url"
  return 1
}

initialize_kkrepo_admin() {
  wait_for_http "kkrepo bootstrap endpoint" "$KKREPO_URL/internal/security/bootstrap"

  local bootstrap_json
  bootstrap_json="$(curl -m 10 -fsS "$KKREPO_URL/internal/security/bootstrap")"
  if [[ "$bootstrap_json" != *'"required":true'* ]]; then
    log "kkrepo admin bootstrap is already complete"
    curl -m 10 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/security/session" >/dev/null
    return 0
  fi

  local cookie_file headers_file token payload
  cookie_file="$(mktemp)"
  headers_file="$(mktemp)"

  curl -m 10 -sS -D "$headers_file" -c "$cookie_file" \
    "$KKREPO_URL/internal/security/session" >/dev/null || true
  token="$(awk 'BEGIN{IGNORECASE=1} /^X-Nexus-Plus-CSRF-Token:/ {gsub("\r","",$2); print $2}' "$headers_file" | tail -n 1)"
  if [[ -z "$token" ]]; then
    log "kkrepo did not expose a CSRF token for bootstrap"
    return 1
  fi

  payload="$(printf '{"password":"%s","passwordConfirm":"%s","anonymousAccessEnabled":true}' "$KKREPO_PASSWORD" "$KKREPO_PASSWORD")"
  log "bootstrapping kkrepo admin"
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
    log "kkrepo blob store exists: default"
    return 0
  fi
  log "creating kkrepo file blob store: default"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "{\"name\":\"default\",\"type\":\"file\",\"path\":\"$(json_escape "$KKREPO_BLOB_PATH")\"}" \
    "$KKREPO_URL/internal/blob-stores" >/dev/null
}

kkrepo_repo_exists() {
  local name="$1"
  local repositories
  repositories="$(curl -m 20 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/repositories?purpose=admin")"
  grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\"" <<<"$repositories"
}

ensure_kkrepo_docker_repository() {
  if kkrepo_repo_exists "$KKREPO_REPOSITORY"; then
    log "kkrepo repository exists: $KKREPO_REPOSITORY"
    return 0
  fi
  log "creating kkrepo Docker hosted repository: $KKREPO_REPOSITORY"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    --data "{
      \"name\":\"$(json_escape "$KKREPO_REPOSITORY")\",
      \"recipe\":\"docker-hosted\",
      \"online\":true,
      \"blobStoreName\":\"default\",
      \"strictContentTypeValidation\":true,
      \"hosted\":{\"writePolicy\":\"ALLOW\"},
      \"docker\":{\"connectorEnabled\":true,\"connectorPort\":$KKREPO_DOCKER_PORT}
    }" \
    "$KKREPO_URL/internal/repositories" >/dev/null
}

refresh_docker_connectors() {
  log "refreshing Docker connectors"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    "$KKREPO_URL/internal/docker/connectors/refresh" >/dev/null
}

wait_for_http "kkrepo management health" "$KKREPO_MANAGEMENT_URL/actuator/health"
initialize_kkrepo_admin
ensure_kkrepo_blob_store
ensure_kkrepo_docker_repository
refresh_docker_connectors
wait_for_docker_registry "kkrepo Docker connector" "http://127.0.0.1:$KKREPO_DOCKER_PORT/v2/"

log "Docker OCI conformance environment is ready"
