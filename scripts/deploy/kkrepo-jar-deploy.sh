#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_ROOT="${KKREPO_DEPLOY_ROOT:-/opt/kkrepo/runtime}"
RELEASES_DIR="$DEPLOY_ROOT/releases"
CURRENT_LINK="$DEPLOY_ROOT/current"
CONFIG_DIR="$DEPLOY_ROOT/config"
RUN_DIR="$DEPLOY_ROOT/run"
LOG_DIR="$DEPLOY_ROOT/logs"
ENV_FILE="${KKREPO_ENV_FILE:-$CONFIG_DIR/kkrepo.env}"
PID_FILE="${KKREPO_PID_FILE:-$RUN_DIR/kkrepo.pid}"
CONSOLE_LOG="${KKREPO_CONSOLE_LOG:-$LOG_DIR/console.log}"
LOCK_DIR="$RUN_DIR/deploy.lock.d"
CURRENT_JAR="$CURRENT_LINK/kkrepo.jar"

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
  [[ "$command_line" == *" -jar $CURRENT_JAR"* ]]
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
  printf '%s\n' "${KKREPO_HEALTH_URL:-http://127.0.0.1:8081/actuator/health}"
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
    fail "PID $pid does not belong to $CURRENT_JAR; refusing to stop it"
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
  local -a parsed_application_args=()

  load_runtime_environment
  if [[ ! -f "$CURRENT_JAR" ]]; then
    fail "current executable jar is missing: $CURRENT_JAR"
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

  java_bin="$(java_binary)"
  verify_java "$java_bin"
  command=("$java_bin")
  if [[ -n "${JAVA_OPTS:-}" ]]; then
    # JAVA_OPTS is intentionally parsed as whitespace-separated JVM arguments.
    read -r -a parsed_java_args <<<"$JAVA_OPTS"
    command+=("${parsed_java_args[@]}")
  fi
  command+=(-jar "$CURRENT_JAR")
  if [[ -n "${KKREPO_APPLICATION_ARGS:-}" ]]; then
    read -r -a parsed_application_args <<<"$KKREPO_APPLICATION_ARGS"
    command+=("${parsed_application_args[@]}")
  fi

  log "starting release=$(basename "$(readlink -f "$CURRENT_LINK")")"
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
  local staged_jar="${1:-}"
  local release_id="${2:-}"
  local release_dir
  local previous_target=""
  local failed=false

  if [[ -z "$staged_jar" || -z "$release_id" ]]; then
    fail "usage: $0 deploy <staged-jar> <release-id>"
    return 2
  fi
  if [[ ! "$release_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
    fail "release id contains unsupported characters: $release_id"
    return 2
  fi
  if [[ ! -s "$staged_jar" ]]; then
    fail "staged jar is missing or empty: $staged_jar"
    return 1
  fi

  acquire_deploy_lock
  trap release_deploy_lock EXIT

  release_dir="$RELEASES_DIR/$release_id"
  mkdir -p "$release_dir"
  install -m 0644 "$staged_jar" "$release_dir/kkrepo.jar.tmp"
  mv -f "$release_dir/kkrepo.jar.tmp" "$release_dir/kkrepo.jar"

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

show_status() {
  local pid
  local release="none"
  if [[ -L "$CURRENT_LINK" ]]; then
    release="$(basename "$(readlink -f "$CURRENT_LINK")")"
  fi
  pid="$(pid_from_file 2>/dev/null || true)"
  if ! is_managed_process "$pid"; then
    printf '[status] stopped release=%s\n' "$release"
    return 3
  fi
  if health_check; then
    printf '[status] running pid=%s release=%s health=UP\n' "$pid" "$release"
    return 0
  fi
  printf '[status] running pid=%s release=%s health=DOWN\n' "$pid" "$release"
  return 1
}

ensure_layout
load_runtime_environment

case "${1:-status}" in
  deploy)
    deploy_release "${2:-}" "${3:-}"
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
    fail "usage: $0 {deploy <staged-jar> <release-id>|start|stop|restart|status}"
    exit 2
    ;;
esac
