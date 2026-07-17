#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SERVER_JAR="${1:-}"
PLATFORM="${2:-}"

if [[ -z "$SERVER_JAR" || ! -f "$SERVER_JAR" ]]; then
  echo "usage: $0 /path/to/kkrepo-server.jar <macos|windows>" >&2
  exit 2
fi
case "$PLATFORM" in
  macos|windows) ;;
  *)
    echo "unsupported Swift platform E2E lane: $PLATFORM" >&2
    exit 2
    ;;
esac

ARTIFACT_DIR="${CLIENT_E2E_ARTIFACT_DIR:-$PROJECT_ROOT/artifacts/swift-platform-e2e}"
WORK_DIR="${CLIENT_E2E_WORK_DIR:-${RUNNER_TEMP:-$PROJECT_ROOT/target}/swift-platform-e2e-work}"
SERVER_LOG="$ARTIFACT_DIR/kkrepo-$PLATFORM.log"
mkdir -p "$ARTIFACT_DIR" "$WORK_DIR"

export KKREPO_MANAGEMENT_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:18091}"
export KKREPO_COMPAT_USERNAME="${KKREPO_COMPAT_USERNAME:-admin}"
export KKREPO_COMPAT_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
export KKREPO_CREDENTIAL_SECRET="${KKREPO_CREDENTIAL_SECRET:-ci-swift-platform-credential-secret}"
export KKREPO_API_KEY_PAYLOAD_SECRET="${KKREPO_API_KEY_PAYLOAD_SECRET:-ci-swift-platform-api-key-secret}"
export KKREPO_FILE_ENABLED=true
export KKREPO_FILE_BASE_DIR="${KKREPO_FILE_BASE_DIR:-$WORK_DIR/blobs}"
export KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES=true
export KKREPO_LOGIN_RATE_LIMIT_PER_MINUTE="${KKREPO_LOGIN_RATE_LIMIT_PER_MINUTE:-200}"
export KKREPO_BOOTSTRAP_RATE_LIMIT_PER_MINUTE="${KKREPO_BOOTSTRAP_RATE_LIMIT_PER_MINUTE:-50}"
export CLIENT_E2E_ARTIFACT_DIR="$ARTIFACT_DIR"
export CLIENT_E2E_WORK_DIR="$WORK_DIR/client"
export CLIENT_E2E_TESTS=swift

TLS_DIR="$WORK_DIR/tls"
TLS_CA_FINGERPRINT=""
TLS_KEYSTORE_PASSWORD="${SWIFT_E2E_TLS_KEYSTORE_PASSWORD:-changeit}"
SERVER_ARGS=(
  --server.port=18090
  --management.server.port=18091
)

if [[ "$PLATFORM" == "macos" ]]; then
  export KKREPO_COMPAT_BASE_URL="${KKREPO_COMPAT_BASE_URL:-https://localhost:18090}"
  if [[ "$KKREPO_COMPAT_BASE_URL" != https://* ]]; then
    echo "[swift-platform-e2e] macOS/Xcode lane requires an HTTPS KKREPO_COMPAT_BASE_URL" >&2
    exit 2
  fi
  "$SCRIPT_DIR/generate-swift-e2e-tls.sh" "$TLS_DIR" localhost
  TLS_CA_FINGERPRINT="$(openssl x509 -in "$TLS_DIR/ca.crt" -noout -fingerprint -sha1 \
    | cut -d= -f2 | tr -d ':')"
  sudo security add-trusted-cert -d -r trustRoot \
    -k /Library/Keychains/System.keychain "$TLS_DIR/ca.crt"
  export CURL_CA_BUNDLE="$TLS_DIR/ca.crt"
  SERVER_ARGS+=(
    --server.ssl.enabled=true
    "--server.ssl.key-store=file:$TLS_DIR/server.p12"
    --server.ssl.key-store-type=PKCS12
    "--server.ssl.key-store-password=$TLS_KEYSTORE_PASSWORD"
    "--server.ssl.key-password=$TLS_KEYSTORE_PASSWORD"
    --server.ssl.key-alias=kkrepo-swift-e2e
    --server.ssl.enabled-protocols=TLSv1.2,TLSv1.3
    --management.server.ssl.enabled=false
  )
else
  export KKREPO_COMPAT_BASE_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:18090}"
fi

cleanup() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "$TLS_CA_FINGERPRINT" ]]; then
    sudo security delete-certificate -Z "$TLS_CA_FINGERPRINT" \
      /Library/Keychains/System.keychain >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "[swift-platform-e2e] starting kkrepo for $PLATFORM"
java -jar "$SERVER_JAR" "${SERVER_ARGS[@]}" \
  >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

"$SCRIPT_DIR/setup-swift-platform-e2e.sh"

case "$PLATFORM" in
  macos)
    if ! command -v xcodebuild >/dev/null 2>&1; then
      echo "[swift-platform-e2e] xcodebuild is required on the macOS lane" >&2
      exit 2
    fi
    export SWIFT_E2E_BINS="xcode=$(xcrun --find swift)"
    export SWIFT_E2E_REQUIRE_XCODE=true
    export SWIFT_E2E_REQUIRE_REGISTRY_LOGIN_LABELS=xcode
    ;;
  windows)
    if [[ "${OS:-}" != "Windows_NT" ]]; then
      echo "[swift-platform-e2e] Windows_NT runner is required for the Windows lane" >&2
      exit 2
    fi
    export SWIFT_E2E_BINS="windows=$(command -v swift)"
    export SWIFT_E2E_REQUIRE_WINDOWS=true
    ;;
esac

"$SCRIPT_DIR/run-client-e2e.sh"
echo "[swift-platform-e2e] $PLATFORM Swift client lane completed"
