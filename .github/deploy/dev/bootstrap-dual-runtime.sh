#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_CONFIG_ROOT="${KKREPO_DEPLOY_CONFIG_ROOT:-/opt/kkrepo/runtime/deploy-config}"
NGINX_CONTAINER="${KKREPO_NGINX_CONTAINER:-kkrepo-nginx}"
NGINX_TARGET="${KKREPO_NGINX_CONFIG:-/opt/kkrepo/.github/deploy/dev/nginx/kkrepo.conf}"
NGINX_CONTAINER_CONFIG="${KKREPO_NGINX_CONTAINER_CONFIG:-/etc/nginx/conf.d/default.conf}"
SYSTEMD_DIR="${KKREPO_SYSTEMD_DIR:-/etc/systemd/system}"
BACKUP_ROOT="$DEPLOY_CONFIG_ROOT/backups"
ACTIVATION_MARKER="$DEPLOY_CONFIG_ROOT/dual-runtime-active"

JVM_UNIT_SOURCE="$DEPLOY_CONFIG_ROOT/systemd/kkrepo.service"
NATIVE_UNIT_SOURCE="$DEPLOY_CONFIG_ROOT/systemd/kkrepo-native.service"
NGINX_SOURCE="$DEPLOY_CONFIG_ROOT/nginx/kkrepo.conf"

log() {
  printf '[bootstrap] %s\n' "$*"
}

fail() {
  printf '[bootstrap] ERROR: %s\n' "$*" >&2
  return 1
}

require_file() {
  local path="$1"
  [[ -s "$path" ]] || fail "required file is missing or empty: $path"
}

# The Nginx container bind-mounts this single file. Replacing the destination inode (for example
# with install(1)) leaves the running container attached to the unlinked old file, so update an
# existing target in place and prove that the container observes the exact staged bytes.
copy_config_preserving_inode() {
  local source="$1"
  local target="$2"
  if [[ -e "$target" ]]; then
    cp -- "$source" "$target"
    chmod 0644 "$target"
  else
    install -m 0644 "$source" "$target"
  fi
}

file_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk 'NR == 1 {print $1}'
  else
    LC_ALL=C shasum -a 256 "$1" | awk 'NR == 1 {print $1}'
  fi
}

container_nginx_sha256() {
  docker exec "$NGINX_CONTAINER" sha256sum "$NGINX_CONTAINER_CONFIG" \
    | awk 'NR == 1 {print $1}'
}

main() {
  local backup_dir
  local expected_nginx_sha
  local mounted_nginx_sha

  if (( EUID != 0 )); then
    fail "run this one-time bootstrap as root"
    exit 1
  fi

  require_file "$JVM_UNIT_SOURCE"
  require_file "$NATIVE_UNIT_SOURCE"
  require_file "$NGINX_SOURCE"
  rm -f "$ACTIVATION_MARKER"

  grep -Fq '127.0.0.1:8080 weight=1' "$NGINX_SOURCE"
  grep -Fq '127.0.0.1:8090 weight=1' "$NGINX_SOURCE"

  log "installing JVM and Native systemd units"
  install -m 0644 "$JVM_UNIT_SOURCE" "$SYSTEMD_DIR/kkrepo.service"
  install -m 0644 "$NATIVE_UNIT_SOURCE" "$SYSTEMD_DIR/kkrepo-native.service"
  systemctl daemon-reload
  systemctl enable kkrepo.service kkrepo-native.service >/dev/null
  systemctl start kkrepo.service
  systemctl start kkrepo-native.service

  log "verifying both runtime readiness endpoints"
  curl --fail --silent --show-error --max-time 10 \
    http://127.0.0.1:8081/actuator/health/readiness >/dev/null
  curl --fail --silent --show-error --max-time 10 \
    http://127.0.0.1:8091/actuator/health/readiness >/dev/null

  mkdir -p "$BACKUP_ROOT"
  backup_dir="$(mktemp -d "$BACKUP_ROOT/bootstrap-XXXXXXXX")"
  if [[ -f "$NGINX_TARGET" ]]; then
    install -m 0644 "$NGINX_TARGET" "$backup_dir/kkrepo.conf"
  fi

  restore_nginx() {
    if [[ -f "$backup_dir/kkrepo.conf" ]]; then
      copy_config_preserving_inode "$backup_dir/kkrepo.conf" "$NGINX_TARGET"
    fi
  }

  log "installing and validating the 50/50 Nginx upstream"
  copy_config_preserving_inode "$NGINX_SOURCE" "$NGINX_TARGET"
  expected_nginx_sha="$(file_sha256 "$NGINX_SOURCE")"
  mounted_nginx_sha="$(container_nginx_sha256 2>/dev/null || true)"
  if [[ "$mounted_nginx_sha" != "$expected_nginx_sha" ]]; then
    restore_nginx
    fail "Nginx bind mount did not observe the staged configuration; restored the previous configuration"
    exit 1
  fi
  if ! docker exec "$NGINX_CONTAINER" nginx -t; then
    restore_nginx
    fail "Nginx validation failed; restored the previous configuration"
    exit 1
  fi
  if ! docker exec "$NGINX_CONTAINER" nginx -s reload; then
    restore_nginx
    docker exec "$NGINX_CONTAINER" nginx -s reload || true
    fail "Nginx reload failed; restored the previous configuration"
    exit 1
  fi

  touch "$ACTIVATION_MARKER"
  chmod 0644 "$ACTIVATION_MARKER"
  log "dual runtime bootstrap complete: JVM 8080/8081, Native 8090/8091, traffic 50/50"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
