#!/usr/bin/env bash
# Start kkrepo server in dev profile with hot reload.
#   - UI templates, CSS and JS: run mvn -pl admin-ui,browse-ui process-resources, then refresh
#   - Java class changes: open a 2nd terminal and run `scripts/recompile.sh` (or use IDE auto-build)
#     to trigger devtools restart of the Spring context

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/logs"
PID_FILE="$LOG_DIR/server.pid"
LOG_FILE="$LOG_DIR/server.log"
PORT="${KKREPO_PORT:-18090}"
START_TIMEOUT_SECONDS="${KKREPO_START_TIMEOUT_SECONDS:-90}"
export KKREPO_CREDENTIAL_SECRET="${KKREPO_CREDENTIAL_SECRET:-nexus-plus-development-credential-secret}"

mkdir -p "$LOG_DIR"

is_running() {
  local pid="${1:-}"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

command_for_pid() {
  ps -o command= -p "$1" 2>/dev/null || true
}

is_repo_process() {
  local command
  command="$(command_for_pid "$1")"
  [[ "$command" == *"$ROOT"* || "$command" == *"KkRepoApplication"* ]]
}

port_pids() {
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NF && !seen[$0]++'
}

print_recent_log() {
  if [[ -f "$LOG_FILE" ]]; then
    echo "[dev] last 80 log lines:"
    tail -n 80 "$LOG_FILE"
  fi
}

start_background() {
  local -a command=("$@")

  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "${command[@]}" </dev/null >>"$LOG_FILE" 2>&1 &
  elif command -v perl >/dev/null 2>&1; then
    nohup perl -MPOSIX=setsid -e 'setsid() or die "setsid failed: $!\n"; exec @ARGV or die "exec failed: $!\n"' "${command[@]}" </dev/null >>"$LOG_FILE" 2>&1 &
  else
    nohup "${command[@]}" </dev/null >>"$LOG_FILE" 2>&1 &
  fi
  echo $!
}

if [[ -f "$PID_FILE" ]]; then
  PID="$(tr -dc '0-9' <"$PID_FILE" || true)"
  if is_running "$PID" && is_repo_process "$PID"; then
    echo "[dev] already running (pid $PID). Use scripts/restart.sh or scripts/stop.sh first."
    exit 1
  fi
  echo "[dev] removing stale PID file ($PID_FILE)."
  rm -f "$PID_FILE"
fi

PORT_PIDS="$(port_pids || true)"
if [[ -n "$PORT_PIDS" ]]; then
  echo "[dev] port $PORT is already in use by pid(s): $PORT_PIDS"
  echo "[dev] run scripts/stop.sh first if this is a stale kkrepo process."
  exit 1
fi

echo "[dev] building modules (incremental)..."
mvn -pl server -am compile -q

: >"$LOG_FILE"
{
  echo
  echo "[dev] ===== $(date '+%Y-%m-%d %H:%M:%S') starting kkrepo ====="
  echo "[dev] cwd=$ROOT"
  echo "[dev] command=mvn -pl server -am -q spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.fork=false -Dspring-boot.run.arguments=--server.port=$PORT"
} >>"$LOG_FILE"

echo "[dev] starting on http://127.0.0.1:$PORT (profile=dev)"
LAUNCHER_PID="$(start_background mvn -pl server -am -q spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.fork=false \
  -Dspring-boot.run.arguments=--server.port="$PORT")"

echo "$LAUNCHER_PID" >"$PID_FILE"
echo "[dev] launcher pid=$LAUNCHER_PID  log=$LOG_FILE"

for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
  PORT_PIDS="$(port_pids || true)"
  if [[ -n "$PORT_PIDS" ]]; then
    echo "[dev] started; listening pid(s): $PORT_PIDS"
    echo "[dev] pid file: $PID_FILE"
    echo "[dev] tail with: scripts/logs.sh"
    echo "[dev] open http://127.0.0.1:$PORT/admin/"
    exit 0
  fi

  if ! is_running "$LAUNCHER_PID"; then
    echo "[dev] startup failed; launcher pid $LAUNCHER_PID exited before port $PORT opened."
    rm -f "$PID_FILE"
    print_recent_log
    exit 1
  fi

  if grep -q "APPLICATION FAILED TO START" "$LOG_FILE" 2>/dev/null; then
    echo "[dev] startup failed; Spring reported APPLICATION FAILED TO START."
    rm -f "$PID_FILE"
    print_recent_log
    exit 1
  fi

  sleep 1
done

echo "[dev] startup timed out after ${START_TIMEOUT_SECONDS}s; stopping launcher pid $LAUNCHER_PID."
"$ROOT/scripts/stop.sh" >/dev/null 2>&1 || true
rm -f "$PID_FILE"
print_recent_log
exit 1
