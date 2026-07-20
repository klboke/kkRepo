#!/usr/bin/env bash
set -euo pipefail

BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_APP_HOME="$(cd "$BIN_DIR/.." && pwd)"
APP_HOME="${KKREPO_HOME:-$DEFAULT_APP_HOME}"
CONF_DIR="${KKREPO_CONF_DIR:-$APP_HOME/conf}"
LOG_DIR="${KKREPO_LOG_DIR:-$APP_HOME/logs}"
PID_FILE="${KKREPO_PID_FILE:-$LOG_DIR/kkrepo.pid}"
CONSOLE_LOG="${KKREPO_CONSOLE_LOG:-$LOG_DIR/console.log}"
JAR_FILE="${KKREPO_JAR_FILE:-$APP_HOME/lib/kkrepo.jar}"
NATIVE_FILE="${KKREPO_NATIVE_FILE:-$APP_HOME/lib/kkrepo}"
RUNTIME="${KKREPO_RUNTIME:-auto}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

is_running() {
  local pid="${1:-}"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

mkdir -p "$LOG_DIR" "$APP_HOME/data"

if [[ ! -f "$CONF_DIR/application.properties" ]]; then
  echo "[start] missing config: $CONF_DIR/application.properties" >&2
  exit 1
fi

case "$RUNTIME" in
  auto)
    if [[ -f "$NATIVE_FILE" ]]; then
      RUNTIME="native"
    else
      RUNTIME="jvm"
    fi
    ;;
  native|jvm)
    ;;
  *)
    echo "[start] unsupported KKREPO_RUNTIME: $RUNTIME (expected auto, native, or jvm)" >&2
    exit 2
    ;;
esac

if [[ "$RUNTIME" == "native" ]]; then
  if [[ ! -f "$NATIVE_FILE" ]]; then
    echo "[start] missing native executable: $NATIVE_FILE" >&2
    exit 1
  fi
  if [[ ! -x "$NATIVE_FILE" ]]; then
    echo "[start] native executable is not executable: $NATIVE_FILE" >&2
    exit 1
  fi
elif [[ ! -f "$JAR_FILE" ]]; then
  echo "[start] missing jar: $JAR_FILE" >&2
  exit 1
fi

if [[ -f "$PID_FILE" ]]; then
  PID="$(tr -dc '0-9' <"$PID_FILE" || true)"
  if is_running "$PID"; then
    echo "[start] already running, pid=$PID"
    exit 0
  fi
  echo "[start] removing stale PID file: $PID_FILE"
  rm -f "$PID_FILE"
fi

export KKREPO_HOME="$APP_HOME"

if [[ "$RUNTIME" == "native" ]]; then
  COMMAND=("$NATIVE_FILE")
  if [[ -n "${KKREPO_NATIVE_OPTS:-}" ]]; then
    # shellcheck disable=SC2206
    COMMAND+=($KKREPO_NATIVE_OPTS)
  fi
else
  COMMAND=("$JAVA_BIN")
  if [[ -n "${JAVA_OPTS:-}" ]]; then
    # shellcheck disable=SC2206
    COMMAND+=($JAVA_OPTS)
  fi
  COMMAND+=( -jar "$JAR_FILE" )
fi
COMMAND+=(
  "--spring.config.additional-location=optional:file:$CONF_DIR/"
)

echo "[start] starting kkrepo"
echo "[start] runtime=$RUNTIME"
echo "[start] home=$APP_HOME"
echo "[start] config=$CONF_DIR/application.properties"
echo "[start] log=$CONSOLE_LOG"

nohup "${COMMAND[@]}" >>"$CONSOLE_LOG" 2>&1 &
PID="$!"
echo "$PID" >"$PID_FILE"

echo "[start] pid=$PID"
echo "[start] status: $BIN_DIR/status.sh"
