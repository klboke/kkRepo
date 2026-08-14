#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_ID="${RANDOM}-$$"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-quickstart-permissions.XXXXXX")"
ACTIVE_PROJECTS=()

cleanup() {
  local project compose_file
  for entry in "${ACTIVE_PROJECTS[@]:-}"; do
    project="${entry%%|*}"
    compose_file="${entry#*|}"
    docker compose -p "$project" -f "$compose_file" down -v --remove-orphans >/dev/null 2>&1 || true
  done
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

verify_owner() {
  local compose_file=$1
  local runtime=$2
  local expected_owner=$3
  local label=$4
  local project="kkrepo-perms-${label}-${runtime}-${RUN_ID}"
  local output_file="$TMP_ROOT/${project}.log"
  local owners

  ACTIVE_PROJECTS+=("${project}|${compose_file}")
  if ! KKREPO_RUNTIME="$runtime" docker compose -p "$project" -f "$compose_file" \
      run --rm --no-deps app-data-perms >"$output_file" 2>&1; then
    cat "$output_file" >&2
    return 1
  fi

  owners="$(KKREPO_RUNTIME="$runtime" docker compose -p "$project" -f "$compose_file" \
    run --rm --no-deps app-data-perms stat -c '%u:%g' /data /data/blobs)"
  if [[ "$owners" != "${expected_owner}"$'\n'"${expected_owner}" ]]; then
    printf 'unexpected %s %s data owners: expected %s, got:\n%s\n' \
      "$label" "$runtime" "$expected_owner" "$owners" >&2
    return 1
  fi

  docker compose -p "$project" -f "$compose_file" down -v --remove-orphans >/dev/null
  rm -f "$output_file"
}

verify_invalid_runtime() {
  local compose_file=$1
  local project="kkrepo-perms-invalid-${RUN_ID}"
  local output_file="$TMP_ROOT/${project}.log"

  ACTIVE_PROJECTS+=("${project}|${compose_file}")
  if KKREPO_RUNTIME=invalid docker compose -p "$project" -f "$compose_file" \
      run --rm --no-deps app-data-perms >"$output_file" 2>&1; then
    echo "invalid quickstart runtime unexpectedly initialized the data volume" >&2
    return 1
  fi
  grep -q 'Unsupported KKREPO_RUNTIME for data permissions: invalid' "$output_file"
  docker compose -p "$project" -f "$compose_file" down -v --remove-orphans >/dev/null
  rm -f "$output_file"
}

verify_owner "$ROOT/docker-compose.quickstart.yml" jvm 999:999 mysql
verify_owner "$ROOT/docker-compose.quickstart.yml" native 1002:1001 mysql
verify_owner "$ROOT/docker-compose.quickstart-postgresql.yml" jvm 999:999 postgresql
verify_owner "$ROOT/docker-compose.quickstart-postgresql.yml" native 1002:1001 postgresql
verify_invalid_runtime "$ROOT/docker-compose.quickstart.yml"

echo "[test] quickstart data permissions passed"
