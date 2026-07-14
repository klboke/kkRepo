#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="$ROOT/scripts/deploy/kkrepo-jar-deploy.sh"
TEST_ROOT="$(mktemp -d)"
FAKE_JAVA_HOME="$TEST_ROOT/fake-java-home"
HEALTH_MARKER="$TEST_ROOT/healthy"

cleanup() {
  KKREPO_DEPLOY_ROOT="$TEST_ROOT/app" "$DEPLOY_SCRIPT" stop >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

mkdir -p "$FAKE_JAVA_HOME/bin" "$TEST_ROOT/app/config"

cat >"$FAKE_JAVA_HOME/bin/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-version" ]]; then
  printf 'openjdk version "25.0.1"\n' >&2
  exit 0
fi

jar_file=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "-jar" ]]; then
    jar_file="$argument"
    break
  fi
  previous="$argument"
done
if [[ -z "$jar_file" ]]; then
  exit 2
fi
if grep -q '^BROKEN$' "$jar_file"; then
  exit 12
fi

remove_health_marker() {
  rm -f "$KKREPO_TEST_HEALTH_MARKER"
}
trap 'remove_health_marker; exit 0' TERM INT
trap remove_health_marker EXIT
touch "$KKREPO_TEST_HEALTH_MARKER"
while true; do
  sleep 1
done
FAKE_JAVA

cat >"$TEST_ROOT/fake-curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
[[ -f "$KKREPO_TEST_HEALTH_MARKER" ]]
FAKE_CURL

chmod +x "$FAKE_JAVA_HOME/bin/java" "$TEST_ROOT/fake-curl"

cat >"$TEST_ROOT/app/config/kkrepo.env" <<EOF
JAVA_HOME='$FAKE_JAVA_HOME'
KKREPO_HEALTH_URL='http://127.0.0.1:8081/actuator/health'
KKREPO_STARTUP_TIMEOUT_SECONDS='3'
KKREPO_STOP_TIMEOUT_SECONDS='2'
KKREPO_KEEP_RELEASES='2'
KKREPO_TEST_HEALTH_MARKER='$HEALTH_MARKER'
KKREPO_CURL_BIN='$TEST_ROOT/fake-curl'
EOF

printf 'GOOD-1\n' >"$TEST_ROOT/good-1.jar"
printf 'GOOD-2\n' >"$TEST_ROOT/good-2.jar"
printf 'BROKEN\n' >"$TEST_ROOT/broken.jar"

export KKREPO_DEPLOY_ROOT="$TEST_ROOT/app"

"$DEPLOY_SCRIPT" deploy "$TEST_ROOT/good-1.jar" release-1
"$DEPLOY_SCRIPT" status
[[ "$(readlink -f "$TEST_ROOT/app/current")" == "$TEST_ROOT/app/releases/release-1" ]]

"$DEPLOY_SCRIPT" deploy "$TEST_ROOT/good-2.jar" release-2
"$DEPLOY_SCRIPT" status
[[ "$(readlink -f "$TEST_ROOT/app/current")" == "$TEST_ROOT/app/releases/release-2" ]]

if "$DEPLOY_SCRIPT" deploy "$TEST_ROOT/broken.jar" release-broken; then
  printf 'broken release unexpectedly passed deployment\n' >&2
  exit 1
fi

"$DEPLOY_SCRIPT" status
[[ "$(readlink -f "$TEST_ROOT/app/current")" == "$TEST_ROOT/app/releases/release-2" ]]
[[ ! -d "$TEST_ROOT/app/releases/release-broken" ]]

"$DEPLOY_SCRIPT" stop
if "$DEPLOY_SCRIPT" status; then
  printf 'stopped service unexpectedly reported healthy\n' >&2
  exit 1
fi

printf '[test] dev jar deployment contract passed\n'
