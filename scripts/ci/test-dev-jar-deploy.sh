#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JVM_DEPLOY_SCRIPT="$ROOT/scripts/deploy/kkrepo-jar-deploy.sh"
RUNTIME_DEPLOY_SCRIPT="$ROOT/scripts/deploy/kkrepo-runtime-deploy.sh"
TEST_ROOT="$(mktemp -d)"
JVM_ROOT="$TEST_ROOT/jvm"
NATIVE_ROOT="$TEST_ROOT/native"
FAKE_JAVA_HOME="$TEST_ROOT/fake-java-home"
JVM_HEALTH_MARKER="$TEST_ROOT/jvm-healthy"
NATIVE_HEALTH_MARKER="$TEST_ROOT/native-healthy"
NATIVE_ARGUMENTS="$TEST_ROOT/native-arguments"

cleanup() {
  KKREPO_DEPLOY_ROOT="$JVM_ROOT" "$JVM_DEPLOY_SCRIPT" stop >/dev/null 2>&1 || true
  KKREPO_RUNTIME=native \
    KKREPO_DEPLOY_ROOT="$NATIVE_ROOT" \
    "$RUNTIME_DEPLOY_SCRIPT" stop >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

mkdir -p "$FAKE_JAVA_HOME/bin" "$JVM_ROOT/config" "$NATIVE_ROOT/config"

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

cat >"$JVM_ROOT/config/kkrepo.env" <<EOF
JAVA_HOME='$FAKE_JAVA_HOME'
KKREPO_HEALTH_URL='http://127.0.0.1:8081/actuator/health'
KKREPO_STARTUP_TIMEOUT_SECONDS='3'
KKREPO_STOP_TIMEOUT_SECONDS='2'
KKREPO_KEEP_RELEASES='2'
KKREPO_TEST_HEALTH_MARKER='$JVM_HEALTH_MARKER'
KKREPO_CURL_BIN='$TEST_ROOT/fake-curl'
EOF

cat >"$NATIVE_ROOT/config/kkrepo.env" <<EOF
KKREPO_HEALTH_URL='http://127.0.0.1:8091/actuator/health/readiness'
KKREPO_STARTUP_TIMEOUT_SECONDS='3'
KKREPO_STOP_TIMEOUT_SECONDS='2'
KKREPO_KEEP_RELEASES='2'
KKREPO_TEST_HEALTH_MARKER='$NATIVE_HEALTH_MARKER'
KKREPO_TEST_ARGUMENTS='$NATIVE_ARGUMENTS'
KKREPO_CURL_BIN='$TEST_ROOT/fake-curl'
EOF

printf 'GOOD-1\n' >"$TEST_ROOT/good-1.jar"
printf 'GOOD-2\n' >"$TEST_ROOT/good-2.jar"
printf 'BROKEN\n' >"$TEST_ROOT/broken.jar"

export KKREPO_DEPLOY_ROOT="$JVM_ROOT"

"$JVM_DEPLOY_SCRIPT" deploy "$TEST_ROOT/good-1.jar" release-1
"$JVM_DEPLOY_SCRIPT" status
[[ "$(readlink -f "$JVM_ROOT/current")" == "$JVM_ROOT/releases/release-1" ]]

"$JVM_DEPLOY_SCRIPT" deploy "$TEST_ROOT/good-2.jar" release-2
"$JVM_DEPLOY_SCRIPT" status
[[ "$(readlink -f "$JVM_ROOT/current")" == "$JVM_ROOT/releases/release-2" ]]

"$JVM_DEPLOY_SCRIPT" activate release-1
[[ "$(readlink -f "$JVM_ROOT/current")" == "$JVM_ROOT/releases/release-1" ]]
"$JVM_DEPLOY_SCRIPT" activate release-2

if "$JVM_DEPLOY_SCRIPT" deploy "$TEST_ROOT/broken.jar" release-broken; then
  printf 'broken release unexpectedly passed deployment\n' >&2
  exit 1
fi

"$JVM_DEPLOY_SCRIPT" status
[[ "$(readlink -f "$JVM_ROOT/current")" == "$JVM_ROOT/releases/release-2" ]]
[[ ! -d "$JVM_ROOT/releases/release-broken" ]]

cat >"$TEST_ROOT/good-native" <<'FAKE_NATIVE'
#!/usr/bin/env bash
set -euo pipefail

remove_health_marker() {
  rm -f "$KKREPO_TEST_HEALTH_MARKER"
}
trap 'remove_health_marker; exit 0' TERM INT
trap remove_health_marker EXIT
printf '%s\n' "$@" >"$KKREPO_TEST_ARGUMENTS"
touch "$KKREPO_TEST_HEALTH_MARKER"
while true; do
  sleep 1
done
FAKE_NATIVE

cat >"$TEST_ROOT/broken-native" <<'BROKEN_NATIVE'
#!/usr/bin/env bash
exit 12
BROKEN_NATIVE
chmod +x "$TEST_ROOT/good-native" "$TEST_ROOT/broken-native"

export KKREPO_RUNTIME=native
export KKREPO_DEPLOY_ROOT="$NATIVE_ROOT"
export KKREPO_INSTANCE_APPLICATION_ARGS='--server.port=8090 --management.server.port=8091 --management.metrics.tags.runtime=native'

"$RUNTIME_DEPLOY_SCRIPT" deploy "$TEST_ROOT/good-native" release-native-1
"$RUNTIME_DEPLOY_SCRIPT" status
[[ "$(readlink -f "$NATIVE_ROOT/current")" == "$NATIVE_ROOT/releases/release-native-1" ]]
grep -Fxq -- '--server.port=8090' "$NATIVE_ARGUMENTS"
grep -Fxq -- '--management.server.port=8091' "$NATIVE_ARGUMENTS"
grep -Fxq -- '--management.metrics.tags.runtime=native' "$NATIVE_ARGUMENTS"

if "$RUNTIME_DEPLOY_SCRIPT" deploy "$TEST_ROOT/broken-native" release-native-broken; then
  printf 'broken Native release unexpectedly passed deployment\n' >&2
  exit 1
fi

"$RUNTIME_DEPLOY_SCRIPT" status
[[ "$(readlink -f "$NATIVE_ROOT/current")" == "$NATIVE_ROOT/releases/release-native-1" ]]
[[ ! -d "$NATIVE_ROOT/releases/release-native-broken" ]]

JVM_PID="$(tr -dc '0-9' <"$JVM_ROOT/run/kkrepo.pid")"
NATIVE_PID="$(tr -dc '0-9' <"$NATIVE_ROOT/run/kkrepo.pid")"
[[ "$JVM_PID" != "$NATIVE_PID" ]]
kill -0 "$JVM_PID"
kill -0 "$NATIVE_PID"

unset KKREPO_RUNTIME KKREPO_INSTANCE_APPLICATION_ARGS
export KKREPO_DEPLOY_ROOT="$JVM_ROOT"
"$JVM_DEPLOY_SCRIPT" stop
if "$JVM_DEPLOY_SCRIPT" status; then
  printf 'stopped service unexpectedly reported healthy\n' >&2
  exit 1
fi

printf '[test] dev JVM/Native deployment contract passed\n'
