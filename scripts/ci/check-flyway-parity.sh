#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
MYSQL_DIR="$ROOT_DIR/persistence-mysql/src/main/resources/db/migration/mysql"
POSTGRESQL_DIR="$ROOT_DIR/persistence-postgresql/src/main/resources/db/migration/postgresql"

versions() {
  local directory=$1
  find "$directory" -maxdepth 1 -type f -name 'V*__*.sql' -print \
    | sed -E 's#^.*/V([0-9]+)__.*#\1#' \
    | sort -n -u
}

mysql_versions="$(versions "$MYSQL_DIR")"
postgresql_versions="$(versions "$POSTGRESQL_DIR")"
mysql_max="$(printf '%s\n' "$mysql_versions" | tail -1)"
postgresql_max="$(printf '%s\n' "$postgresql_versions" | tail -1)"

if [[ "$mysql_max" != "$postgresql_max" ]]; then
  printf 'Flyway version mismatch: MySQL=%s PostgreSQL=%s\n' "$mysql_max" "$postgresql_max" >&2
  exit 1
fi

if [[ ! -f "$POSTGRESQL_DIR/V29__postgresql_baseline.sql" ]]; then
  printf 'PostgreSQL V29 baseline is missing\n' >&2
  exit 1
fi

while IFS= read -r version; do
  [[ -n "$version" ]] || continue
  if (( version >= 30 )) && ! grep -qx "$version" <<<"$postgresql_versions"; then
    printf 'PostgreSQL migration V%s is missing\n' "$version" >&2
    exit 1
  fi
done <<<"$mysql_versions"

while IFS= read -r version; do
  [[ -n "$version" ]] || continue
  if (( version >= 30 )) && ! grep -qx "$version" <<<"$mysql_versions"; then
    printf 'MySQL migration V%s is missing\n' "$version" >&2
    exit 1
  fi
done <<<"$postgresql_versions"

if find "$ROOT_DIR/server/src/main/resources/db/migration" -maxdepth 1 -type f -name 'V*.sql' -print -quit 2>/dev/null | grep -q .; then
  printf 'Database migrations must be owned by a backend module, not server\n' >&2
  exit 1
fi

printf 'Flyway parity verified at V%s\n' "$mysql_max"
