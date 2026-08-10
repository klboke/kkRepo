#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BOOTSTRAP="$ROOT/.github/deploy/dev/bootstrap-dual-runtime.sh"
TEST_ROOT="$(mktemp -d)"
SOURCE_CONFIG="$TEST_ROOT/staged.conf"
TARGET_CONFIG="$TEST_ROOT/live.conf"
CONTAINER="kkrepo-nginx-bind-mount-test-$$"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

# shellcheck disable=SC1090
source "$BOOTSTRAP"

file_inode() {
  if stat -c '%i' "$1" >/dev/null 2>&1; then
    stat -c '%i' "$1"
  else
    stat -f '%i' "$1"
  fi
}

printf 'old upstream\n' >"$TARGET_CONFIG"
printf 'new equal-weight upstream\n' >"$SOURCE_CONFIG"

docker run -d --rm --name "$CONTAINER" \
  -v "$TARGET_CONFIG:/tmp/kkrepo.conf:ro" \
  nginx:stable-alpine sh -c 'while :; do sleep 60; done' >/dev/null

for _attempt in $(seq 1 20); do
  if docker exec "$CONTAINER" test -r /tmp/kkrepo.conf >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done
docker exec "$CONTAINER" test -r /tmp/kkrepo.conf

inode_before="$(file_inode "$TARGET_CONFIG")"
[[ "$(file_sha256 "$TARGET_CONFIG")" \
    == "$(docker exec "$CONTAINER" sha256sum /tmp/kkrepo.conf | awk 'NR == 1 {print $1}')" ]]
copy_config_preserving_inode "$SOURCE_CONFIG" "$TARGET_CONFIG"
inode_after="$(file_inode "$TARGET_CONFIG")"

[[ "$inode_after" == "$inode_before" ]]
[[ "$(file_sha256 "$SOURCE_CONFIG")" == "$(file_sha256 "$TARGET_CONFIG")" ]]
[[ "$(file_sha256 "$SOURCE_CONFIG")" \
    == "$(docker exec "$CONTAINER" sha256sum /tmp/kkrepo.conf | awk 'NR == 1 {print $1}')" ]]

printf '[test] dev Nginx single-file bind mount update passed\n'
