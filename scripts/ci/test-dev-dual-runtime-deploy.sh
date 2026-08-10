#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DUAL_DEPLOY_SCRIPT="$ROOT/scripts/deploy/kkrepo-dual-runtime-deploy.sh"
TEST_ROOT="$(mktemp -d)"
JVM_ROOT="$TEST_ROOT/jvm"
NATIVE_ROOT="$TEST_ROOT/native"
SHARED_ENV_FILE="$TEST_ROOT/kkrepo.env"
CALL_LOG="$TEST_ROOT/calls.log"
FAIL_JVM_MARKER="$TEST_ROOT/fail-jvm-once"
OLD_RELEASE=1111111111111111111111111111111111111111
NEW_RELEASE=2222222222222222222222222222222222222222

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

mkdir -p "$JVM_ROOT/releases/$OLD_RELEASE" "$NATIVE_ROOT/releases/$OLD_RELEASE"
printf 'old-jvm\n' >"$JVM_ROOT/releases/$OLD_RELEASE/kkrepo.jar"
printf 'old-native\n' >"$NATIVE_ROOT/releases/$OLD_RELEASE/kkrepo"
chmod +x "$NATIVE_ROOT/releases/$OLD_RELEASE/kkrepo"
ln -s "$JVM_ROOT/releases/$OLD_RELEASE" "$JVM_ROOT/current"
ln -s "$NATIVE_ROOT/releases/$OLD_RELEASE" "$NATIVE_ROOT/current"
printf 'shared=true\n' >"$SHARED_ENV_FILE"
printf 'new-jvm\n' >"$TEST_ROOT/new.jar"
printf 'new-native\n' >"$TEST_ROOT/new-native"

cat >"$TEST_ROOT/fake-runtime-deploy.sh" <<'FAKE_DEPLOY'
#!/usr/bin/env bash
set -Eeuo pipefail

runtime="${KKREPO_RUNTIME:?}"
root="${KKREPO_DEPLOY_ROOT:?}"
command="${1:-status}"
argument="${3:-${2:-}}"
printf '%s|%s|%s\n' "$runtime" "$command" "$argument" >>"$KKREPO_TEST_CALL_LOG"

switch_release() {
  local release="$1"
  local temporary="$root/.current.$$"
  ln -s "$root/releases/$release" "$temporary"
  rm -f "$root/current"
  mv -f "$temporary" "$root/current"
}

case "$command" in
  deploy)
    release="${3:?}"
    if [[ "$runtime" == "jvm" && -f "$KKREPO_TEST_FAIL_JVM_MARKER" ]]; then
      rm -f "$KKREPO_TEST_FAIL_JVM_MARKER"
      exit 19
    fi
    mkdir -p "$root/releases/$release"
    if [[ "$runtime" == "jvm" ]]; then
      cp "$2" "$root/releases/$release/kkrepo.jar"
    else
      cp "$2" "$root/releases/$release/kkrepo"
      chmod +x "$root/releases/$release/kkrepo"
    fi
    switch_release "$release"
    ;;
  activate)
    switch_release "${2:?}"
    ;;
  status)
    [[ -L "$root/current" ]]
    ;;
  stop)
    ;;
  *)
    exit 2
    ;;
esac
FAKE_DEPLOY
chmod +x "$TEST_ROOT/fake-runtime-deploy.sh"

run_dual_deploy() {
  KKREPO_RUNTIME_DEPLOY_SCRIPT="$TEST_ROOT/fake-runtime-deploy.sh" \
  KKREPO_JVM_DEPLOY_ROOT="$JVM_ROOT" \
  KKREPO_NATIVE_DEPLOY_ROOT="$NATIVE_ROOT" \
  KKREPO_SHARED_ENV_FILE="$SHARED_ENV_FILE" \
  KKREPO_PUBLIC_URL= \
  KKREPO_TEST_CALL_LOG="$CALL_LOG" \
  KKREPO_TEST_FAIL_JVM_MARKER="$FAIL_JVM_MARKER" \
    "$DUAL_DEPLOY_SCRIPT" "$TEST_ROOT/new.jar" "$TEST_ROOT/new-native" "$NEW_RELEASE"
}

run_dual_deploy
[[ "$(basename "$(readlink -f "$JVM_ROOT/current")")" == "$NEW_RELEASE" ]]
[[ "$(basename "$(readlink -f "$NATIVE_ROOT/current")")" == "$NEW_RELEASE" ]]
grep -Fq "native|deploy|$NEW_RELEASE" "$CALL_LOG"
grep -Fq "jvm|deploy|$NEW_RELEASE" "$CALL_LOG"

rm -f "$JVM_ROOT/current" "$NATIVE_ROOT/current"
ln -s "$JVM_ROOT/releases/$OLD_RELEASE" "$JVM_ROOT/current"
ln -s "$NATIVE_ROOT/releases/$OLD_RELEASE" "$NATIVE_ROOT/current"
: >"$CALL_LOG"
touch "$FAIL_JVM_MARKER"

if run_dual_deploy; then
  printf 'dual deploy unexpectedly succeeded after the JVM failure\n' >&2
  exit 1
fi

[[ "$(basename "$(readlink -f "$JVM_ROOT/current")")" == "$OLD_RELEASE" ]]
[[ "$(basename "$(readlink -f "$NATIVE_ROOT/current")")" == "$OLD_RELEASE" ]]
grep -Fq "native|activate|$OLD_RELEASE" "$CALL_LOG"
grep -Fq "jvm|activate|$OLD_RELEASE" "$CALL_LOG"

printf '[test] dev dual-runtime orchestration rollback contract passed\n'
