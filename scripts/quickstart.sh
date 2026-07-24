#!/usr/bin/env bash
set -Eeuo pipefail

STEP=0
TOTAL_STEPS=7
COMPOSE_READY=false

log() {
  printf '[%s] [kkrepo quickstart] %s\n' "$(date '+%H:%M:%S')" "$*"
}

warn() {
  printf '[%s] [kkrepo quickstart] WARN: %s\n' "$(date '+%H:%M:%S')" "$*" >&2
}

fail() {
  printf '[%s] [kkrepo quickstart] ERROR: %s\n' "$(date '+%H:%M:%S')" "$*" >&2
  exit 1
}

step() {
  STEP=$((STEP + 1))
  printf '\n[%s] [kkrepo quickstart] Step %d/%d: %s\n' "$(date '+%H:%M:%S')" "$STEP" "$TOTAL_STEPS" "$*"
}

on_error() {
  local code=$?
  warn "Quickstart stopped unexpectedly with exit code ${code}."
  if [[ "${COMPOSE_READY}" == "true" && -f "${COMPOSE_FILE:-}" ]]; then
    warn "Recent kkrepo logs:"
    "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" logs --tail 120 kkrepo >&2 || true
  fi
  exit "$code"
}

trap on_error ERR

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

generate_secret() {
  if command_exists openssl; then
    openssl rand -hex 32
  elif command_exists uuidgen; then
    printf 'quickstart-%s-%s\n' "$(date '+%s')" "$(uuidgen | tr -d '-')"
  else
    printf 'quickstart-%s-%s%s%s\n' "$(date '+%s')" "$RANDOM" "$RANDOM" "$RANDOM"
  fi
}

env_file_value() {
  local key=$1
  local file=$2
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

load_env_file_defaults() {
  local file=$1
  local key value
  [[ -f "$file" ]] || return 0
  for key in \
    KKREPO_DATABASE_TYPE \
    KKREPO_RUNTIME \
    KKREPO_IMAGE_TAG \
    KKREPO_HTTP_PORT \
    KKREPO_MANAGEMENT_PORT \
    KKREPO_PROJECT_NAME \
    KKREPO_CREDENTIAL_SECRET \
    KKREPO_API_KEY_PAYLOAD_SECRET; do
    if [[ -z "${!key+x}" ]]; then
      value=$(env_file_value "$key" "$file" || true)
      if [[ -n "$value" ]]; then
        printf -v "$key" '%s' "$value"
        export "$key"
      fi
    fi
  done
}

create_env_file_if_missing() {
  local file=$1
  if [[ -f "$file" ]]; then
    log "Using existing environment file: $file"
    return 0
  fi

  : "${KKREPO_CREDENTIAL_SECRET:=$(generate_secret)}"
  : "${KKREPO_API_KEY_PAYLOAD_SECRET:=$(generate_secret)}"
  export KKREPO_CREDENTIAL_SECRET KKREPO_API_KEY_PAYLOAD_SECRET

  cat >"$file" <<EOF
KKREPO_RUNTIME=${KKREPO_RUNTIME}
KKREPO_IMAGE_TAG=${KKREPO_IMAGE_TAG}
KKREPO_HTTP_PORT=${KKREPO_HTTP_PORT}
KKREPO_MANAGEMENT_PORT=${KKREPO_MANAGEMENT_PORT}
KKREPO_PROJECT_NAME=${KKREPO_PROJECT_NAME}
KKREPO_CREDENTIAL_SECRET=${KKREPO_CREDENTIAL_SECRET}
KKREPO_API_KEY_PAYLOAD_SECRET=${KKREPO_API_KEY_PAYLOAD_SECRET}
KKREPO_DATABASE_TYPE=${KKREPO_DATABASE_TYPE}
EOF
  log "Created environment file: $file"
}

resolve_compose_command() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
    COMPOSE_DISPLAY="docker compose"
  elif command_exists docker-compose; then
    COMPOSE_CMD=(docker-compose)
    COMPOSE_DISPLAY="docker-compose"
  else
    fail "Docker Compose is required. Install Docker Desktop or the docker compose plugin."
  fi
}

port_in_use() {
  local port=$1
  if command_exists lsof; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  elif command_exists nc; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  else
    return 1
  fi
}

download_compose_file() {
  local destination=$1
  local local_compose

  if [[ -n "${SCRIPT_DIR:-}" ]]; then
    local_compose="${SCRIPT_DIR}/../${KKREPO_COMPOSE_SOURCE_NAME}"
    if [[ -f "$local_compose" && "${KKREPO_FORCE_DOWNLOAD:-false}" != "true" ]]; then
      cp "$local_compose" "$destination"
      log "Copied compose file from local checkout: $local_compose"
      return 0
    fi
  fi

  log "Downloading compose file from: $KKREPO_COMPOSE_URL"
  curl -fsSL "$KKREPO_COMPOSE_URL" -o "${destination}.tmp"
  mv "${destination}.tmp" "$destination"
}

wait_for_health() {
  local health_url=$1
  local deadline attempt body
  deadline=$((SECONDS + KKREPO_WAIT_TIMEOUT))
  attempt=0

  while (( SECONDS < deadline )); do
    attempt=$((attempt + 1))
    body="$(curl -fsS -m 3 "$health_url" 2>/dev/null || true)"
    if [[ "$body" == *'"status":"UP"'* ]]; then
      log "Health check is UP: $body"
      return 0
    fi
    if (( attempt == 1 || attempt % 5 == 0 )); then
      log "Waiting for service readiness at $health_url ..."
    fi
    sleep 2
  done

  warn "Health check did not become UP within ${KKREPO_WAIT_TIMEOUT}s."
  warn "Container status:"
  "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" ps >&2 || true
  warn "Recent kkrepo logs:"
  "${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" logs --tail 160 kkrepo >&2 || true
  return 1
}

WORKDIR="${KKREPO_DIR:-kkrepo-quickstart}"
RUNTIME_WAS_EXPLICIT=false
IMAGE_TAG_WAS_EXPLICIT=false
if [[ -n "${KKREPO_RUNTIME+x}" ]]; then
  RUNTIME_WAS_EXPLICIT=true
fi
if [[ -n "${KKREPO_IMAGE_TAG+x}" ]]; then
  IMAGE_TAG_WAS_EXPLICIT=true
fi
if [[ -f "${WORKDIR}/.env" ]]; then
  load_env_file_defaults "${WORKDIR}/.env"
fi

KKREPO_RUNTIME="${KKREPO_RUNTIME:-jvm}"
case "$KKREPO_RUNTIME" in
  jvm)
    DEFAULT_IMAGE_TAG="0.6.0"
    ;;
  native)
    DEFAULT_IMAGE_TAG="0.6.0-native"
    ;;
  *)
    fail "KKREPO_RUNTIME must be jvm or native, got: $KKREPO_RUNTIME"
    ;;
esac
if [[ "$RUNTIME_WAS_EXPLICIT" == "true" && "$IMAGE_TAG_WAS_EXPLICIT" == "false" ]]; then
  unset KKREPO_IMAGE_TAG
fi
: "${KKREPO_IMAGE_TAG:=$DEFAULT_IMAGE_TAG}"

KKREPO_DATABASE_TYPE="${KKREPO_DATABASE_TYPE:-mysql}"
case "$KKREPO_DATABASE_TYPE" in
  mysql)
    KKREPO_COMPOSE_SOURCE_NAME="docker-compose.quickstart.yml"
    ;;
  postgresql)
    KKREPO_COMPOSE_SOURCE_NAME="docker-compose.quickstart-postgresql.yml"
    ;;
  *)
    fail "KKREPO_DATABASE_TYPE must be mysql or postgresql, got: $KKREPO_DATABASE_TYPE"
    ;;
esac
COMPOSE_FILE="${KKREPO_COMPOSE_FILE:-$KKREPO_COMPOSE_SOURCE_NAME}"
KKREPO_COMPOSE_URL="${KKREPO_COMPOSE_URL:-https://raw.githubusercontent.com/klboke/kkrepo/main/$KKREPO_COMPOSE_SOURCE_NAME}"
KKREPO_WAIT_TIMEOUT="${KKREPO_WAIT_TIMEOUT:-180}"
KKREPO_SKIP_PORT_CHECK="${KKREPO_SKIP_PORT_CHECK:-false}"
SCRIPT_SOURCE="${BASH_SOURCE[0]:-}"
SCRIPT_DIR=""
if [[ -n "$SCRIPT_SOURCE" && "$SCRIPT_SOURCE" != /dev/fd/* && -f "$SCRIPT_SOURCE" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd -P)"
fi

step "Checking local prerequisites"
command_exists docker || fail "Docker is required. Install Docker Desktop first."
command_exists curl || fail "curl is required to download files and run health checks."
docker info >/dev/null 2>&1 || fail "Docker is not running or is not reachable."
resolve_compose_command
log "Docker is available."
log "Compose command: $COMPOSE_DISPLAY"

step "Preparing quickstart directory"
mkdir -p "$WORKDIR"
cd "$WORKDIR"
log "Working directory: $(pwd)"

if [[ -f .env ]]; then
  load_env_file_defaults .env
fi

: "${KKREPO_HTTP_PORT:=19090}"
: "${KKREPO_MANAGEMENT_PORT:=19091}"
: "${KKREPO_PROJECT_NAME:=kkrepo-quickstart}"
export \
  KKREPO_RUNTIME \
  KKREPO_IMAGE_TAG \
  KKREPO_HTTP_PORT \
  KKREPO_MANAGEMENT_PORT \
  KKREPO_PROJECT_NAME \
  KKREPO_DATABASE_TYPE \
  KKREPO_CREDENTIAL_SECRET \
  KKREPO_API_KEY_PAYLOAD_SECRET

create_env_file_if_missing .env

step "Fetching Docker Compose quickstart file"
download_compose_file "$COMPOSE_FILE"
COMPOSE_READY=true
log "Compose file: $(pwd)/$COMPOSE_FILE"
log "Database backend: ${KKREPO_DATABASE_TYPE}"
log "Runtime: ${KKREPO_RUNTIME}"
log "Image tag: ghcr.io/klboke/kkrepo:${KKREPO_IMAGE_TAG}"
log "HTTP port: ${KKREPO_HTTP_PORT}"
log "Management port: ${KKREPO_MANAGEMENT_PORT}"

step "Checking host port availability"
existing_container="$("${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" ps -q kkrepo 2>/dev/null || true)"
if [[ -n "$existing_container" ]]; then
  log "Existing quickstart container detected; Compose will reconcile it."
elif [[ "$KKREPO_SKIP_PORT_CHECK" == "true" ]]; then
  warn "Port check skipped because KKREPO_SKIP_PORT_CHECK=true."
else
  port_in_use "$KKREPO_HTTP_PORT" && fail "Port ${KKREPO_HTTP_PORT} is already in use. Set KKREPO_HTTP_PORT to another port and retry."
  port_in_use "$KKREPO_MANAGEMENT_PORT" && fail "Port ${KKREPO_MANAGEMENT_PORT} is already in use. Set KKREPO_MANAGEMENT_PORT to another port and retry."
  log "Ports are available."
fi

step "Pulling container images"
"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" pull

step "Starting kkrepo"
"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" up -d
"${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE" ps

step "Waiting for kkrepo readiness"
wait_for_health "http://127.0.0.1:${KKREPO_MANAGEMENT_PORT}/actuator/health"

cat <<EOF

[kkrepo quickstart] Ready.

Open:
  Admin console : http://127.0.0.1:${KKREPO_HTTP_PORT}/admin/
  User browser  : http://127.0.0.1:${KKREPO_HTTP_PORT}/browse/
  Health check  : http://127.0.0.1:${KKREPO_MANAGEMENT_PORT}/actuator/health
  Prometheus    : http://127.0.0.1:${KKREPO_MANAGEMENT_PORT}/actuator/prometheus

First visit:
  Create the initial Local/admin administrator password in the UI.
  Then create a blob store named "default". File storage is fine for this local trial.

Useful commands:
  cd $(pwd)
  ${COMPOSE_DISPLAY} -f ${COMPOSE_FILE} ps
  ${COMPOSE_DISPLAY} -f ${COMPOSE_FILE} logs -f kkrepo
  ${COMPOSE_DISPLAY} -f ${COMPOSE_FILE} down
  ${COMPOSE_DISPLAY} -f ${COMPOSE_FILE} down -v

EOF
