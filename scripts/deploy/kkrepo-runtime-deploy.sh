#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_ROOT="${KKREPO_DEPLOY_ROOT:-/opt/kkrepo/runtime}"
RUNTIME="${KKREPO_RUNTIME:-jvm}"
INSTANCE_APPLICATION_ARGS="${KKREPO_INSTANCE_APPLICATION_ARGS:-}"

case "$RUNTIME" in
  jvm)
    RUNTIME_FILE_NAME="kkrepo.jar"
    RUNTIME_FILE_MODE="0644"
    DEFAULT_HEALTH_PORT="8081"
    ;;
  native)
    RUNTIME_FILE_NAME="kkrepo"
    RUNTIME_FILE_MODE="0755"
    DEFAULT_HEALTH_PORT="8091"
    ;;
  *)
    printf '[deploy] ERROR: KKREPO_RUNTIME must be jvm or native, got: %s\n' "$RUNTIME" >&2
    exit 2
    ;;
esac

RELEASES_DIR="$DEPLOY_ROOT/releases"
CURRENT_LINK="$DEPLOY_ROOT/current"
CONFIG_DIR="$DEPLOY_ROOT/config"
RUN_DIR="$DEPLOY_ROOT/run"
LOG_DIR="$DEPLOY_ROOT/logs"
ENV_FILE="${KKREPO_ENV_FILE:-$CONFIG_DIR/kkrepo.env}"
PID_FILE="${KKREPO_PID_FILE:-$RUN_DIR/kkrepo.pid}"
CONSOLE_LOG="${KKREPO_CONSOLE_LOG:-$LOG_DIR/console.log}"
LOCK_DIR="$RUN_DIR/deploy.lock.d"
CURRENT_RUNTIME="$CURRENT_LINK/$RUNTIME_FILE_NAME"

RUNTIME_LOADED=false

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  return 1
}

ensure_layout() {
  mkdir -p "$RELEASES_DIR" "$CONFIG_DIR" "$RUN_DIR" "$LOG_DIR"
}

load_runtime_environment() {
  if [[ "$RUNTIME_LOADED" == "true" ]]; then
    return
  fi
  if [[ ! -r "$ENV_FILE" ]]; then
    fail "runtime environment file is missing or unreadable: $ENV_FILE"
    return 1
  fi

  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  RUNTIME_LOADED=true
}

pid_from_file() {
  if [[ ! -f "$PID_FILE" ]]; then
    return 1
  fi
  tr -dc '0-9' <"$PID_FILE"
}

is_running() {
  local pid="${1:-}"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

is_managed_process() {
  local pid="${1:-}"
  local command_line
  if ! is_running "$pid"; then
    return 1
  fi
  if [[ -r "/proc/$pid/cmdline" ]]; then
    command_line="$(tr '\0' ' ' <"/proc/$pid/cmdline")"
  else
    command_line="$(ps -o command= -p "$pid" 2>/dev/null || true)"
  fi
  if [[ "$RUNTIME" == "jvm" ]]; then
    [[ "$command_line" == *" -jar $CURRENT_RUNTIME"* ]]
    return
  fi
  if [[ -r "/proc/$pid/exe" ]] \
      && [[ "$(readlink -f "/proc/$pid/exe")" == "$(readlink -f "$CURRENT_RUNTIME")" ]]; then
    return 0
  fi
  [[ "$command_line" == *"$CURRENT_RUNTIME"* ]]
}

java_binary() {
  if [[ -n "${KKREPO_JAVA_BIN:-}" ]]; then
    printf '%s\n' "$KKREPO_JAVA_BIN"
  elif [[ -n "${JAVA_HOME:-}" ]]; then
    printf '%s\n' "$JAVA_HOME/bin/java"
  else
    command -v java
  fi
}

verify_java() {
  local java_bin="$1"
  local major
  if [[ ! -x "$java_bin" ]]; then
    fail "Java executable not found: $java_bin"
    return 1
  fi
  major="$($java_bin -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
  if [[ ! "$major" =~ ^[0-9]+$ ]] || (( major < 25 )); then
    fail "Java 25 or newer is required (detected: ${major:-unknown})"
    return 1
  fi
}

health_url() {
  printf '%s\n' "${KKREPO_HEALTH_URL:-http://127.0.0.1:$DEFAULT_HEALTH_PORT/actuator/health/readiness}"
}

health_check() {
  local curl_bin="${KKREPO_CURL_BIN:-curl}"
  local pid
  pid="$(pid_from_file 2>/dev/null || true)"
  is_managed_process "$pid" || return 1
  "$curl_bin" --fail --silent --max-time 5 "$(health_url)" >/dev/null
}

wait_for_health() {
  local timeout="${KKREPO_STARTUP_TIMEOUT_SECONDS:-120}"
  local elapsed=0
  while (( elapsed < timeout )); do
    if health_check; then
      return 0
    fi
    if [[ -f "$PID_FILE" ]]; then
      local pid
      pid="$(pid_from_file 2>/dev/null || true)"
      if [[ -n "$pid" ]] && ! is_running "$pid"; then
        fail "process exited before becoming healthy"
        return 1
      fi
    fi
    sleep 1
    ((elapsed += 1))
  done
  fail "health check did not pass within ${timeout}s: $(health_url)"
}

stop_service() {
  local timeout="${KKREPO_STOP_TIMEOUT_SECONDS:-45}"
  local pid
  pid="$(pid_from_file 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    rm -f "$PID_FILE"
    log "service is already stopped"
    return 0
  fi
  if ! is_running "$pid"; then
    rm -f "$PID_FILE"
    log "removed stale PID file"
    return 0
  fi
  if ! is_managed_process "$pid"; then
    fail "PID $pid does not belong to $CURRENT_RUNTIME; refusing to stop it"
    return 1
  fi

  log "stopping pid=$pid"
  kill "$pid"
  for ((elapsed = 0; elapsed < timeout; elapsed += 1)); do
    if ! is_running "$pid"; then
      rm -f "$PID_FILE"
      log "service stopped"
      return 0
    fi
    sleep 1
  done

  log "graceful stop timed out; sending KILL to pid=$pid"
  kill -9 "$pid" 2>/dev/null || true
  for _ in {1..10}; do
    if ! is_running "$pid"; then
      rm -f "$PID_FILE"
      return 0
    fi
    sleep 0.2
  done
  fail "unable to stop pid=$pid"
}

start_service() {
  local java_bin
  local pid
  local -a command=()
  local -a parsed_java_args=()
  local -a parsed_native_args=()
  local -a parsed_application_args=()
  local -a parsed_instance_application_args=()

  load_runtime_environment
  if [[ ! -f "$CURRENT_RUNTIME" ]]; then
    fail "current $RUNTIME runtime is missing: $CURRENT_RUNTIME"
    return 1
  fi
  if [[ "$RUNTIME" == "native" && ! -x "$CURRENT_RUNTIME" ]]; then
    fail "current native runtime is not executable: $CURRENT_RUNTIME"
    return 1
  fi

  pid="$(pid_from_file 2>/dev/null || true)"
  if is_managed_process "$pid"; then
    log "service is already running, pid=$pid"
    return 0
  fi
  if is_running "$pid"; then
    fail "PID $pid is running but is not a managed kkRepo process"
    return 1
  fi
  rm -f "$PID_FILE"

  if [[ "$RUNTIME" == "native" ]]; then
    command=("$CURRENT_RUNTIME")
    if [[ -n "${KKREPO_NATIVE_OPTS:-}" ]]; then
      # KKREPO_NATIVE_OPTS is intentionally parsed as whitespace-separated Native arguments.
      read -r -a parsed_native_args <<<"$KKREPO_NATIVE_OPTS"
      command+=("${parsed_native_args[@]}")
    fi
  else
    java_bin="$(java_binary)"
    verify_java "$java_bin"
    command=("$java_bin")
    if [[ -n "${JAVA_OPTS:-}" ]]; then
      # JAVA_OPTS is intentionally parsed as whitespace-separated JVM arguments.
      read -r -a parsed_java_args <<<"$JAVA_OPTS"
      command+=("${parsed_java_args[@]}")
    fi
    command+=(-jar "$CURRENT_RUNTIME")
  fi
  if [[ -n "${KKREPO_APPLICATION_ARGS:-}" ]]; then
    read -r -a parsed_application_args <<<"$KKREPO_APPLICATION_ARGS"
    command+=("${parsed_application_args[@]}")
  fi
  if [[ -n "$INSTANCE_APPLICATION_ARGS" ]]; then
    read -r -a parsed_instance_application_args <<<"$INSTANCE_APPLICATION_ARGS"
    command+=("${parsed_instance_application_args[@]}")
  fi

  log "starting runtime=$RUNTIME release=$(basename "$(readlink -f "$CURRENT_LINK")")"
  (
    cd "$DEPLOY_ROOT"
    umask 027
    nohup "${command[@]}" >>"$CONSOLE_LOG" 2>&1 </dev/null &
    printf '%s\n' "$!" >"$PID_FILE.tmp"
    mv -f "$PID_FILE.tmp" "$PID_FILE"
  )
  pid="$(pid_from_file)"
  log "started pid=$pid"
}

switch_current() {
  local release_dir="$1"
  local temporary_link="$DEPLOY_ROOT/.current.$$.tmp"
  ln -s "$release_dir" "$temporary_link"
  if mv --help 2>&1 | grep -q -- '--no-target-directory'; then
    mv -Tf "$temporary_link" "$CURRENT_LINK"
  else
    mv -fh "$temporary_link" "$CURRENT_LINK"
  fi
}

acquire_deploy_lock() {
  local owner=""
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" >"$LOCK_DIR/pid"
    return 0
  fi

  if [[ -f "$LOCK_DIR/pid" ]]; then
    owner="$(tr -dc '0-9' <"$LOCK_DIR/pid")"
  fi
  if is_running "$owner"; then
    fail "another deployment is already running, pid=$owner"
    return 1
  fi

  log "removing stale deployment lock"
  rm -rf -- "$LOCK_DIR"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    fail "unable to acquire deployment lock"
    return 1
  fi
  printf '%s\n' "$$" >"$LOCK_DIR/pid"
}

release_deploy_lock() {
  rm -rf -- "$LOCK_DIR"
}

cleanup_old_releases() {
  local keep="${KKREPO_KEEP_RELEASES:-5}"
  local current_target
  local index=0
  local release_dir
  current_target="$(readlink -f "$CURRENT_LINK")"

  while IFS= read -r release_dir; do
    release_dir="${release_dir%/}"
    ((index += 1))
    if (( index <= keep )) || [[ "$release_dir" == "$current_target" ]]; then
      continue
    fi
    rm -rf -- "$release_dir"
    log "removed old release $(basename "$release_dir")"
  done < <(ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null || true)
}

deploy_release() {
  local staged_runtime="${1:-}"
  local release_id="${2:-}"
  local release_dir
  local previous_target=""
  local failed=false

  if [[ -z "$staged_runtime" || -z "$release_id" ]]; then
    fail "usage: $0 deploy <staged-runtime> <release-id>"
    return 2
  fi
  if [[ ! "$release_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
    fail "release id contains unsupported characters: $release_id"
    return 2
  fi
  if [[ ! -s "$staged_runtime" ]]; then
    fail "staged runtime is missing or empty: $staged_runtime"
    return 1
  fi

  acquire_deploy_lock
  trap release_deploy_lock EXIT

  release_dir="$RELEASES_DIR/$release_id"
  mkdir -p "$release_dir"
  install -m "$RUNTIME_FILE_MODE" \
    "$staged_runtime" "$release_dir/$RUNTIME_FILE_NAME.tmp"
  mv -f "$release_dir/$RUNTIME_FILE_NAME.tmp" "$release_dir/$RUNTIME_FILE_NAME"

  if [[ -L "$CURRENT_LINK" ]]; then
    previous_target="$(readlink -f "$CURRENT_LINK")"
  fi
  if [[ "$previous_target" == "$release_dir" ]] && health_check; then
    log "release $release_id is already current and healthy"
    release_deploy_lock
    trap - EXIT
    return 0
  fi

  if [[ -n "$previous_target" ]]; then
    stop_service
  fi
  switch_current "$release_dir"

  if ! start_service || ! wait_for_health; then
    failed=true
  fi

  if [[ "$failed" == "false" ]]; then
    log "release $release_id is healthy"
    cleanup_old_releases
    release_deploy_lock
    trap - EXIT
    return 0
  fi

  log "release $release_id failed; attempting rollback"
  stop_service || true
  if [[ -n "$previous_target" && -d "$previous_target" ]]; then
    switch_current "$previous_target"
    if start_service && wait_for_health; then
      rm -rf -- "$release_dir"
      log "rollback to $(basename "$previous_target") succeeded"
    else
      fail "rollback to $(basename "$previous_target") failed"
      return 1
    fi
  else
    rm -f "$CURRENT_LINK"
    fail "no previous release is available for rollback"
    return 1
  fi
  fail "release $release_id did not become healthy"
}

activate_release() {
  local release_id="${1:-}"
  local release_dir
  local previous_target=""
  local failed=false

  if [[ -z "$release_id" ]]; then
    fail "usage: $0 activate <release-id>"
    return 2
  fi
  if [[ ! "$release_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
    fail "release id contains unsupported characters: $release_id"
    return 2
  fi

  release_dir="$RELEASES_DIR/$release_id"
  if [[ ! -s "$release_dir/$RUNTIME_FILE_NAME" ]]; then
    fail "$RUNTIME release is missing or empty: $release_dir/$RUNTIME_FILE_NAME"
    return 1
  fi
  if [[ "$RUNTIME" == "native" && ! -x "$release_dir/$RUNTIME_FILE_NAME" ]]; then
    fail "native release is not executable: $release_dir/$RUNTIME_FILE_NAME"
    return 1
  fi

  acquire_deploy_lock
  trap release_deploy_lock EXIT

  if [[ -L "$CURRENT_LINK" ]]; then
    previous_target="$(readlink -f "$CURRENT_LINK")"
  fi
  if [[ "$previous_target" == "$release_dir" ]] && health_check; then
    log "runtime=$RUNTIME release $release_id is already current and healthy"
    release_deploy_lock
    trap - EXIT
    return 0
  fi

  if [[ -n "$previous_target" ]]; then
    stop_service
  fi
  switch_current "$release_dir"

  if ! start_service || ! wait_for_health; then
    failed=true
  fi

  if [[ "$failed" == "false" ]]; then
    log "runtime=$RUNTIME release $release_id is active and healthy"
    release_deploy_lock
    trap - EXIT
    return 0
  fi

  log "activation of runtime=$RUNTIME release $release_id failed; attempting rollback"
  stop_service || true
  if [[ -n "$previous_target" && -d "$previous_target" ]]; then
    switch_current "$previous_target"
    if start_service && wait_for_health; then
      log "rollback to $(basename "$previous_target") succeeded"
    else
      fail "rollback to $(basename "$previous_target") failed"
      return 1
    fi
  else
    rm -f "$CURRENT_LINK"
    fail "no previous release is available for rollback"
    return 1
  fi
  fail "release $release_id did not become healthy"
}

show_status() {
  local pid
  local release="none"
  if [[ -L "$CURRENT_LINK" ]]; then
    release="$(basename "$(readlink -f "$CURRENT_LINK")")"
  fi
  pid="$(pid_from_file 2>/dev/null || true)"
  if ! is_managed_process "$pid"; then
    printf '[status] stopped runtime=%s release=%s\n' "$RUNTIME" "$release"
    return 3
  fi
  if health_check; then
    printf '[status] running runtime=%s pid=%s release=%s health=UP\n' \
      "$RUNTIME" "$pid" "$release"
    return 0
  fi
  printf '[status] running runtime=%s pid=%s release=%s health=DOWN\n' \
    "$RUNTIME" "$pid" "$release"
  return 1
}

ensure_layout
load_runtime_environment

case "${1:-status}" in
  deploy)
    deploy_release "${2:-}" "${3:-}"
    ;;
  activate)
    activate_release "${2:-}"
    ;;
  start)
    start_service
    wait_for_health
    ;;
  stop)
    stop_service
    ;;
  restart)
    stop_service
    start_service
    wait_for_health
    ;;
  status)
    show_status
    ;;
  *)
    fail "usage: $0 {deploy <staged-runtime> <release-id>|activate <release-id>|start|stop|restart|status}"
    exit 2
    ;;
esac
