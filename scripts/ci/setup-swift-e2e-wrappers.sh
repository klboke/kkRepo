#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
CA_CERTIFICATE="${2:-}"

if [[ "$MODE" != "matrix" && "$MODE" != "single" ]]; then
  echo "usage: $0 matrix|single [CA_BUNDLE]" >&2
  exit 2
fi
if [[ -n "$CA_CERTIFICATE" && ! -r "$CA_CERTIFICATE" ]]; then
  echo "Swift E2E CA certificate is not readable: $CA_CERTIFICATE" >&2
  exit 2
fi

BIN_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/swift-e2e-bin"
mkdir -p "$BIN_DIR"

cat > "$BIN_DIR/swift-e2e" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "$(basename "$0")" in
  swift-5.7) image="kkrepo/swift-e2e:5.7.3-jammy-v1" ;;
  swift-5.10) image="kkrepo/swift-e2e:5.10.1-jammy-v1" ;;
  swift-6) image="kkrepo/swift-e2e:6.3.3-jammy-v1" ;;
  *) echo "unknown Swift E2E wrapper: $0" >&2; exit 2 ;;
esac

args=(
  --rm
  --network host
  -e HOME
  -e XDG_CONFIG_HOME
  -v "$PWD:$PWD"
  -w "$PWD"
)
if [[ -n "${CLIENT_E2E_WORK_DIR:-}" && -d "$CLIENT_E2E_WORK_DIR" ]]; then
  args+=( -v "$CLIENT_E2E_WORK_DIR:$CLIENT_E2E_WORK_DIR" )
fi
if [[ -n "${HOME:-}" && "$HOME" != "$PWD" ]]; then
  args+=( -v "$HOME:$HOME" )
fi
if [[ -n "${SWIFT_E2E_CA_CERTIFICATE:-}" ]]; then
  if [[ ! -r "$SWIFT_E2E_CA_CERTIFICATE" ]]; then
    echo "Swift E2E CA certificate is not readable: $SWIFT_E2E_CA_CERTIFICATE" >&2
    exit 2
  fi
  args+=(
    -v "$SWIFT_E2E_CA_CERTIFICATE:/usr/local/share/ca-certificates/kkrepo-swift-e2e.crt:ro"
    -e "SWIFT_E2E_RUN_UID=$(id -u)"
    -e "SWIFT_E2E_RUN_GID=$(id -g)"
  )
  exec docker run "${args[@]}" "$image" sh -ec '
    update-ca-certificates >/dev/null 2>&1
    exec setpriv \
      --reuid="$SWIFT_E2E_RUN_UID" \
      --regid="$SWIFT_E2E_RUN_GID" \
      --clear-groups \
      swift "$@"
  ' sh "$@"
fi
exec docker run "${args[@]}" --user "$(id -u):$(id -g)" "$image" swift "$@"
EOF
chmod 0755 "$BIN_DIR/swift-e2e"
ln -sfn swift-e2e "$BIN_DIR/swift-5.7"
ln -sfn swift-e2e "$BIN_DIR/swift-5.10"
ln -sfn swift-e2e "$BIN_DIR/swift-6"

if [[ -n "$CA_CERTIFICATE" ]]; then
  printf 'SWIFT_E2E_CA_CERTIFICATE=%s\n' "$CA_CERTIFICATE" >> "$GITHUB_ENV"
fi
if [[ "$MODE" == "matrix" ]]; then
  printf 'SWIFT_E2E_BINS=5.7=%s/swift-5.7,5.9+=%s/swift-5.10,6.x=%s/swift-6\n' \
    "$BIN_DIR" "$BIN_DIR" "$BIN_DIR" >> "$GITHUB_ENV"
  printf 'SWIFT_E2E_REQUIRE_5_7_5_9_6=true\n' >> "$GITHUB_ENV"
else
  printf 'SWIFT_S3_E2E_SWIFT_BIN=%s/swift-6\n' "$BIN_DIR" >> "$GITHUB_ENV"
fi
