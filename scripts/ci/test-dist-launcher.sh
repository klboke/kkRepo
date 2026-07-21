#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d)"
APP_HOME="$TEST_ROOT/kkrepo"
FAKE_JAVA_HOME="$TEST_ROOT/fake-java-home"

cleanup() {
  KKREPO_HOME="$APP_HOME" KKREPO_STOP_TIMEOUT_SECONDS=2 \
    "$APP_HOME/bin/stop.sh" >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

mkdir -p "$APP_HOME/bin" "$APP_HOME/conf" "$APP_HOME/lib" "$FAKE_JAVA_HOME/bin"
cp "$ROOT/server/src/dist/bin/start.sh" "$APP_HOME/bin/start.sh"
cp "$ROOT/server/src/dist/bin/status.sh" "$APP_HOME/bin/status.sh"
cp "$ROOT/server/src/dist/bin/stop.sh" "$APP_HOME/bin/stop.sh"
printf 'server.port=8080\n' >"$APP_HOME/conf/application.properties"

cat >"$APP_HOME/lib/kkrepo" <<'FAKE_NATIVE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >"$KKREPO_TEST_ARGUMENTS"
trap 'exit 0' TERM INT
while true; do
  sleep 1
done
FAKE_NATIVE

cat >"$FAKE_JAVA_HOME/bin/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >"$KKREPO_TEST_ARGUMENTS"
trap 'exit 0' TERM INT
while true; do
  sleep 1
done
FAKE_JAVA

chmod +x "$APP_HOME/bin/"*.sh "$APP_HOME/lib/kkrepo" "$FAKE_JAVA_HOME/bin/java"

wait_for_arguments() {
  local file="$1"
  for _ in $(seq 1 50); do
    if [[ -s "$file" ]]; then
      return 0
    fi
    sleep 0.1
  done
  printf 'launcher did not record its arguments: %s\n' "$file" >&2
  return 1
}

NATIVE_ARGUMENTS="$TEST_ROOT/native-arguments"
native_output="$(
  KKREPO_HOME="$APP_HOME" \
  KKREPO_TEST_ARGUMENTS="$NATIVE_ARGUMENTS" \
  KKREPO_NATIVE_OPTS='--native-option=test-value' \
    "$APP_HOME/bin/start.sh"
)"
wait_for_arguments "$NATIVE_ARGUMENTS"
grep -Fq '[start] runtime=native' <<<"$native_output"
grep -Fxq -- '--native-option=test-value' "$NATIVE_ARGUMENTS"
grep -Fxq -- "--spring.config.additional-location=optional:file:$APP_HOME/conf/" "$NATIVE_ARGUMENTS"
KKREPO_HOME="$APP_HOME" KKREPO_STOP_TIMEOUT_SECONDS=2 "$APP_HOME/bin/stop.sh"

rm "$APP_HOME/lib/kkrepo"
touch "$APP_HOME/lib/kkrepo.jar"

JVM_ARGUMENTS="$TEST_ROOT/jvm-arguments"
jvm_output="$(
  KKREPO_HOME="$APP_HOME" \
  KKREPO_TEST_ARGUMENTS="$JVM_ARGUMENTS" \
  JAVA_HOME="$FAKE_JAVA_HOME" \
  JAVA_OPTS='-Xmx64m' \
    "$APP_HOME/bin/start.sh"
)"
wait_for_arguments "$JVM_ARGUMENTS"
grep -Fq '[start] runtime=jvm' <<<"$jvm_output"
grep -Fxq -- '-Xmx64m' "$JVM_ARGUMENTS"
grep -Fxq -- '-jar' "$JVM_ARGUMENTS"
grep -Fxq -- "$APP_HOME/lib/kkrepo.jar" "$JVM_ARGUMENTS"
grep -Fxq -- "--spring.config.additional-location=optional:file:$APP_HOME/conf/" "$JVM_ARGUMENTS"
KKREPO_HOME="$APP_HOME" KKREPO_STOP_TIMEOUT_SECONDS=2 "$APP_HOME/bin/stop.sh"

printf '[test] distribution launcher contract passed\n'
