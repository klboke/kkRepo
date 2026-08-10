#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_CONFIG_ROOT="${KKREPO_DEPLOY_CONFIG_ROOT:-/opt/kkrepo/runtime/deploy-config}"
NGINX_CONTAINER="${KKREPO_NGINX_CONTAINER:-kkrepo-nginx}"
NGINX_TARGET="${KKREPO_NGINX_CONFIG:-/opt/kkrepo/.github/deploy/dev/nginx/kkrepo.conf}"
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
    install -m 0644 "$backup_dir/kkrepo.conf" "$NGINX_TARGET"
  fi
}

log "installing and validating the 50/50 Nginx upstream"
install -m 0644 "$NGINX_SOURCE" "$NGINX_TARGET"
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
