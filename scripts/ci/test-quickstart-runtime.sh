#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-quickstart-runtime.XXXXXX")"
FAKE_BIN="$TMP_ROOT/bin"

cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

mkdir -p "$FAKE_BIN"

cat >"$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "info" ]]; then
  exit 0
fi
if [[ "${1:-}" == "compose" && "${2:-}" == "version" ]]; then
  exit 0
fi
if [[ "${1:-}" == "compose" ]]; then
  if [[ " $* " == *" pull "* ]]; then
    printf '%s|%s\n' "$KKREPO_RUNTIME" "$KKREPO_IMAGE_TAG" >> "$KKREPO_TEST_LOG"
  fi
  exit 0
fi

echo "unexpected docker invocation: $*" >&2
exit 1
EOF

cat >"$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '{"status":"UP"}\n'
EOF

chmod 0755 "$FAKE_BIN/docker" "$FAKE_BIN/curl"

run_quickstart() {
  local name=$1
  shift
  local workdir="$TMP_ROOT/$name"
  local log_file="$TMP_ROOT/$name.log"
  : > "$log_file"
  env \
    PATH="$FAKE_BIN:$PATH" \
    KKREPO_DIR="$workdir" \
    KKREPO_SKIP_PORT_CHECK=true \
    KKREPO_TEST_LOG="$log_file" \
    "$@" \
    bash "$ROOT/scripts/quickstart.sh" >"$TMP_ROOT/$name.out" 2>&1
}

run_quickstart default-jvm
grep -qx 'KKREPO_RUNTIME=jvm' "$TMP_ROOT/default-jvm/.env"
grep -qx 'KKREPO_IMAGE_TAG=0.6.0' "$TMP_ROOT/default-jvm/.env"
grep -qx 'jvm|0.6.0' "$TMP_ROOT/default-jvm.log"

run_quickstart native KKREPO_RUNTIME=native
grep -qx 'KKREPO_RUNTIME=native' "$TMP_ROOT/native/.env"
grep -qx 'KKREPO_IMAGE_TAG=0.6.0-native' "$TMP_ROOT/native/.env"
grep -qx 'native|0.6.0-native' "$TMP_ROOT/native.log"

mkdir -p "$TMP_ROOT/existing-jvm"
cat >"$TMP_ROOT/existing-jvm/.env" <<'EOF'
KKREPO_RUNTIME=jvm
KKREPO_IMAGE_TAG=0.6.0
EOF
run_quickstart existing-jvm KKREPO_RUNTIME=native
grep -qx 'native|0.6.0-native' "$TMP_ROOT/existing-jvm.log"

run_quickstart custom-native \
  KKREPO_RUNTIME=native \
  KKREPO_IMAGE_TAG=custom-native
grep -qx 'native|custom-native' "$TMP_ROOT/custom-native.log"

if run_quickstart invalid-runtime KKREPO_RUNTIME=invalid; then
  echo "invalid runtime unexpectedly succeeded" >&2
  exit 1
fi
grep -q 'KKREPO_RUNTIME must be jvm or native' "$TMP_ROOT/invalid-runtime.out"

echo "[test] quickstart runtime selection passed"
