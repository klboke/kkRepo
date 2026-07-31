#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-swift-wrapper-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/runner"
touch "$TEST_ROOT/github-env"
"$SCRIPT_DIR/prepare-swift-e2e-tls.sh" "$TEST_ROOT/tls" >/dev/null 2>&1
openssl verify -CAfile "$TEST_ROOT/tls/ca.crt" "$TEST_ROOT/tls/server.crt" | grep -Fq ': OK'
grep -Fq 'listen 18443 ssl;' "$TEST_ROOT/tls/nginx.conf"
grep -Fq 'listen 18444 ssl;' "$TEST_ROOT/tls/nginx.conf"
cat > "$TEST_ROOT/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$SWIFT_WRAPPER_DOCKER_ARGS"
EOF
chmod 0755 "$TEST_ROOT/bin/docker"

export PATH="$TEST_ROOT/bin:$PATH"
export RUNNER_TEMP="$TEST_ROOT/runner"
export GITHUB_ENV="$TEST_ROOT/github-env"
export SWIFT_WRAPPER_DOCKER_ARGS="$TEST_ROOT/docker-args"

"$SCRIPT_DIR/setup-swift-e2e-wrappers.sh" matrix "$TEST_ROOT/tls/ca.crt"
export SWIFT_E2E_CA_CERTIFICATE="$TEST_ROOT/tls/ca.crt"
"$RUNNER_TEMP/swift-e2e-bin/swift-5.10" package --version

grep -Fxq 'kkrepo/swift-e2e:5.10.1-jammy-v1' "$SWIFT_WRAPPER_DOCKER_ARGS"
grep -Fxq "$TEST_ROOT/tls/ca.crt:/usr/local/share/ca-certificates/kkrepo-swift-e2e.crt:ro" "$SWIFT_WRAPPER_DOCKER_ARGS"
grep -Fq 'update-ca-certificates' "$SWIFT_WRAPPER_DOCKER_ARGS"
grep -Fq 'setpriv' "$SWIFT_WRAPPER_DOCKER_ARGS"
grep -Fq 'SWIFT_E2E_BINS=5.7=' "$GITHUB_ENV"
grep -Fq 'SWIFT_E2E_CA_CERTIFICATE=' "$GITHUB_ENV"
grep -Fxq 'SWIFT_E2E_REQUIRE_5_7_5_9_6=true' "$GITHUB_ENV"

"$SCRIPT_DIR/setup-swift-e2e-wrappers.sh" single
grep -Fq 'SWIFT_S3_E2E_SWIFT_BIN=' "$GITHUB_ENV"

echo "Swift E2E wrapper tests passed"
