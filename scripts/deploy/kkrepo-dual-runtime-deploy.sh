#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_SCRIPT="${KKREPO_RUNTIME_DEPLOY_SCRIPT:-$SCRIPT_DIR/kkrepo-runtime-deploy.sh}"
JVM_ROOT="${KKREPO_JVM_DEPLOY_ROOT:-/opt/kkrepo/runtime}"
NATIVE_ROOT="${KKREPO_NATIVE_DEPLOY_ROOT:-/opt/kkrepo/runtime/native}"
SHARED_ENV_FILE="${KKREPO_SHARED_ENV_FILE:-$JVM_ROOT/config/kkrepo.env}"
PUBLIC_URL="${KKREPO_PUBLIC_URL-https://kkrepo.cn/}"

JVM_HEALTH_URL="${KKREPO_JVM_HEALTH_URL:-http://127.0.0.1:8081/actuator/health/readiness}"
NATIVE_HEALTH_URL="${KKREPO_NATIVE_HEALTH_URL:-http://127.0.0.1:8091/actuator/health/readiness}"
JVM_INSTANCE_ARGS="${KKREPO_JVM_INSTANCE_ARGS:---management.metrics.tags.runtime=jvm}"
NATIVE_INSTANCE_ARGS="${KKREPO_NATIVE_INSTANCE_ARGS:---server.port=8090 --management.server.port=8091 --management.metrics.tags.runtime=native}"

STAGED_JVM="${1:-}"
STAGED_NATIVE="${2:-}"
RELEASE_ID="${3:-}"

rollback_required=false
previous_jvm=""
previous_native=""

log() {
  printf '[dual-deploy] %s\n' "$*"
}

fail() {
  printf '[dual-deploy] ERROR: %s\n' "$*" >&2
  return 1
}

current_release() {
  local root="$1"
  if [[ -L "$root/current" ]]; then
    basename "$(readlink -f "$root/current")"
  fi
}

run_jvm() {
  env \
    KKREPO_DEPLOY_ROOT="$JVM_ROOT" \
    KKREPO_ENV_FILE="$SHARED_ENV_FILE" \
    KKREPO_RUNTIME=jvm \
    KKREPO_HEALTH_URL="$JVM_HEALTH_URL" \
    KKREPO_INSTANCE_APPLICATION_ARGS="$JVM_INSTANCE_ARGS" \
    "$DEPLOY_SCRIPT" "$@"
}

run_native() {
  env \
    KKREPO_DEPLOY_ROOT="$NATIVE_ROOT" \
    KKREPO_ENV_FILE="$SHARED_ENV_FILE" \
    KKREPO_RUNTIME=native \
    KKREPO_HEALTH_URL="$NATIVE_HEALTH_URL" \
    KKREPO_INSTANCE_APPLICATION_ARGS="$NATIVE_INSTANCE_ARGS" \
    "$DEPLOY_SCRIPT" "$@"
}

restore_runtime() {
  local runtime="$1"
  local previous="$2"
  local root="$3"
  if [[ -n "$previous" && -d "$root/releases/$previous" ]]; then
    log "restoring $runtime release $previous"
    if [[ "$runtime" == "jvm" ]]; then
      run_jvm activate "$previous"
    else
      run_native activate "$previous"
    fi
    return
  fi
  log "no previous $runtime release exists; stopping the new runtime"
  if [[ "$runtime" == "jvm" ]]; then
    run_jvm stop
  else
    run_native stop
  fi
}

rollback_pair() {
  local exit_code="$1"
  rollback_required=false
  set +e
  restore_runtime native "$previous_native" "$NATIVE_ROOT"
  native_rollback_status=$?
  restore_runtime jvm "$previous_jvm" "$JVM_ROOT"
  jvm_rollback_status=$?
  set -e
  if (( native_rollback_status != 0 || jvm_rollback_status != 0 )); then
    fail "pair rollback was incomplete; inspect both direct readiness endpoints"
  fi
  return "$exit_code"
}

on_exit() {
  local exit_code=$?
  if [[ "$rollback_required" == "true" ]]; then
    rollback_pair "$exit_code"
  fi
}
trap on_exit EXIT

if [[ -z "$STAGED_JVM" || -z "$STAGED_NATIVE" || -z "$RELEASE_ID" ]]; then
  fail "usage: $0 <staged-jvm-jar> <staged-native-executable> <release-id>"
  exit 2
fi
if [[ ! "$RELEASE_ID" =~ ^[0-9a-f]{40}$ ]]; then
  fail "release id must be the exact 40-character Git SHA: $RELEASE_ID"
  exit 2
fi
[[ -x "$DEPLOY_SCRIPT" ]] || { fail "runtime deploy script is not executable: $DEPLOY_SCRIPT"; exit 1; }
[[ -s "$STAGED_JVM" ]] || { fail "staged JVM jar is missing or empty: $STAGED_JVM"; exit 1; }
[[ -s "$STAGED_NATIVE" ]] || { fail "staged Native executable is missing or empty: $STAGED_NATIVE"; exit 1; }
[[ -r "$SHARED_ENV_FILE" ]] || { fail "shared runtime environment is unreadable: $SHARED_ENV_FILE"; exit 1; }

previous_jvm="$(current_release "$JVM_ROOT")"
previous_native="$(current_release "$NATIVE_ROOT")"
log "previous releases: jvm=${previous_jvm:-none} native=${previous_native:-none}"

rollback_required=true
log "deploying Native release $RELEASE_ID"
run_native deploy "$STAGED_NATIVE" "$RELEASE_ID"

log "deploying JVM release $RELEASE_ID"
run_jvm deploy "$STAGED_JVM" "$RELEASE_ID"

run_native status
run_jvm status

active_native="$(current_release "$NATIVE_ROOT")"
active_jvm="$(current_release "$JVM_ROOT")"
if [[ "$active_native" != "$RELEASE_ID" || "$active_jvm" != "$RELEASE_ID" ]]; then
  fail "active release mismatch: jvm=$active_jvm native=$active_native expected=$RELEASE_ID"
  exit 1
fi

if [[ -n "$PUBLIC_URL" ]]; then
  curl --fail --silent --show-error --max-time 15 --output /dev/null "$PUBLIC_URL"
fi

rollback_required=false
log "both runtimes are healthy on release $RELEASE_ID"
