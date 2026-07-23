#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-e2e-image-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

MOCK_BIN="$TEST_ROOT/bin"
ARTIFACT_DIR="$TEST_ROOT/artifact"
MOCK_STATE="$TEST_ROOT/state"
SOURCE_SHA=0123456789abcdef0123456789abcdef01234567
IMAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
mkdir -p "$MOCK_BIN" "$MOCK_STATE"

cat > "$MOCK_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$1 ${2:-}" in
  "image inspect")
    printf '%s\n' "$MOCK_IMAGE_ID"
    ;;
  "save kkrepo:compat")
    printf 'mock image archive\n'
    ;;
  "load ")
    cat > "$MOCK_STATE/loaded-image"
    printf 'Loaded image: kkrepo:compat\n'
    ;;
  "tag kkrepo:compat")
    printf '%s\n' "$3" > "$MOCK_STATE/target-tag"
    ;;
  *)
    echo "unexpected docker invocation: $*" >&2
    exit 2
    ;;
esac
EOF
cat > "$MOCK_BIN/zstd" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "-dc" ]]; then
  cat "$2"
  exit 0
fi
output=""
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "-o" ]]; then
    output="$2"
    shift 2
  else
    shift
  fi
done
cat > "$output"
EOF
chmod 0755 "$MOCK_BIN/docker" "$MOCK_BIN/zstd"

export PATH="$MOCK_BIN:$PATH"
export MOCK_IMAGE_ID="$IMAGE_ID"
export MOCK_STATE

"$SCRIPT_DIR/package-e2e-candidate-image.sh" \
  kkrepo:compat jvm "$SOURCE_SHA" "$ARTIFACT_DIR"
"$SCRIPT_DIR/load-e2e-candidate-image.sh" \
  "$ARTIFACT_DIR" jvm "$SOURCE_SHA" kkrepo:compat kkrepo:test

grep -qx 'mock image archive' "$MOCK_STATE/loaded-image"
grep -qx 'kkrepo:test' "$MOCK_STATE/target-tag"

printf '%s\n' native > "$ARTIFACT_DIR/runtime"
if "$SCRIPT_DIR/load-e2e-candidate-image.sh" \
    "$ARTIFACT_DIR" jvm "$SOURCE_SHA" >/dev/null 2>&1; then
  echo "runtime mismatch should reject the candidate artifact" >&2
  exit 1
fi

printf '%s\n' jvm > "$ARTIFACT_DIR/runtime"
printf 'corrupt archive\n' >> "$ARTIFACT_DIR/image.tar.zst"
if "$SCRIPT_DIR/load-e2e-candidate-image.sh" \
    "$ARTIFACT_DIR" jvm "$SOURCE_SHA" >/dev/null 2>&1; then
  echo "checksum mismatch should reject the candidate artifact" >&2
  exit 1
fi

echo "E2E candidate image artifact tests passed"
