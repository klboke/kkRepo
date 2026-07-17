#!/usr/bin/env bash
set -euo pipefail

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:18090}"
KKREPO_MANAGEMENT_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:18091}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
START_TIMEOUT_SECONDS="${LIVE_COMPAT_START_TIMEOUT_SECONDS:-240}"
SWIFT_E2E_BLOB_STORE_PAYLOAD="${SWIFT_E2E_BLOB_STORE_PAYLOAD:-{\"name\":\"default\",\"type\":\"file\",\"path\":\"default\"}}"

log() {
  printf '[swift-platform-setup] %s\n' "$*"
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

initialize_admin() {
  local bootstrap_json cookie_file headers_file token payload
  bootstrap_json="$(curl -m 10 -fsS "$KKREPO_URL/internal/security/bootstrap")"
  if [[ "$bootstrap_json" != *'"required":true'* ]]; then
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
      "$KKREPO_URL/internal/security/session" >/dev/null
    return 0
  fi

  cookie_file="$(mktemp)"
  headers_file="$(mktemp)"
  trap 'rm -f "$cookie_file" "$headers_file"' RETURN
  curl -m 10 -sS -D "$headers_file" -c "$cookie_file" \
    "$KKREPO_URL/internal/security/session" >/dev/null || true
  token="$(awk 'BEGIN{IGNORECASE=1} /^X-Nexus-Plus-CSRF-Token:/ {gsub("\r","",$2); print $2}' \
    "$headers_file" | tail -n 1)"
  if [[ -z "$token" ]]; then
    log "kkrepo did not expose a CSRF token for bootstrap"
    return 1
  fi
  payload="$(python3 - "$KKREPO_PASSWORD" <<'PY'
import json
import sys

print(json.dumps({
    "password": sys.argv[1],
    "passwordConfirm": sys.argv[1],
    "anonymousAccessEnabled": True,
}, separators=(",", ":")))
PY
)"
  log "bootstrapping kkrepo admin"
  curl -m 20 -fsS \
    -b "$cookie_file" \
    -c "$cookie_file" \
    -H "X-Nexus-Plus-CSRF-Token: $token" \
    -H 'Content-Type: application/json' \
    --data "$payload" \
    "$KKREPO_URL/internal/security/bootstrap/admin" >/dev/null
  curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/internal/security/session" >/dev/null
  rm -f "$cookie_file" "$headers_file"
  trap - RETURN
}

blob_store_exists() {
  curl -m 20 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/internal/blob-stores" \
    | grep -q '"name"[[:space:]]*:[[:space:]]*"default"'
}

ensure_blob_store() {
  if blob_store_exists; then
    return 0
  fi
  log "creating configured blob store: default"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    -H 'Content-Type: application/json' \
    --data "$SWIFT_E2E_BLOB_STORE_PAYLOAD" \
    "$KKREPO_URL/internal/blob-stores" >/dev/null
}

repository_exists() {
  local name="$1"
  curl -m 20 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/internal/repositories?purpose=admin" \
    | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"$name\""
}

ensure_repository() {
  local name="$1"
  local payload="$2"
  if repository_exists "$name"; then
    return 0
  fi
  log "creating Swift repository: $name"
  curl -m 30 -fsS \
    -u "$KKREPO_AUTH" \
    -X POST \
    -H 'Content-Type: application/json' \
    --data "$payload" \
    "$KKREPO_URL/internal/repositories" >/dev/null
}

wait_for_http "kkrepo management health" "$KKREPO_MANAGEMENT_URL/actuator/health"
wait_for_http "kkrepo bootstrap endpoint" "$KKREPO_URL/internal/security/bootstrap"
initialize_admin
ensure_blob_store
ensure_repository swift-hosted '{
  "name":"swift-hosted","recipe":"swift-hosted","online":true,
  "blobStoreName":"default","strictContentTypeValidation":true,
  "hosted":{"writePolicy":"ALLOW_ONCE"}
}'
swift_proxy_payload="$(python3 - "${SWIFT_GITHUB_TOKEN:-}" <<'PY'
import json
import sys

proxy = {
    "remoteUrl": "https://github.com/",
    "contentMaxAgeMinutes": 1440,
    "metadataMaxAgeMinutes": 1440,
    "negativeCacheEnabled": True,
    "negativeCacheTtlMinutes": 1,
    "autoBlock": True,
}
if sys.argv[1]:
    proxy["remoteBearerToken"] = sys.argv[1]
print(json.dumps({
    "name": "swift-proxy",
    "recipe": "swift-proxy",
    "online": True,
    "blobStoreName": "default",
    "strictContentTypeValidation": True,
    "proxy": proxy,
}, separators=(",", ":")))
PY
)"
ensure_repository swift-proxy "$swift_proxy_payload"
ensure_repository swift-group '{
  "name":"swift-group","recipe":"swift-group","online":true,
  "blobStoreName":"default","strictContentTypeValidation":true,
  "group":{"memberNames":["swift-hosted","swift-proxy"]}
}'
log "Swift platform repositories are ready"
