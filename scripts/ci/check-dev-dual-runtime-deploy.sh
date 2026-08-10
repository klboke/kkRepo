#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NGINX_CONFIG="$ROOT/.github/deploy/dev/nginx/kkrepo.conf"
JVM_UNIT="$ROOT/.github/deploy/dev/systemd/kkrepo.service"
NATIVE_UNIT="$ROOT/.github/deploy/dev/systemd/kkrepo-native.service"
DEPLOY_WORKFLOW="$ROOT/.github/workflows/deploy-dev.yml"
TMP_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

[[ "$(grep -Fc 'server 127.0.0.1:8080 weight=1' "$NGINX_CONFIG")" == "1" ]]
[[ "$(grep -Fc 'server 127.0.0.1:8090 weight=1' "$NGINX_CONFIG")" == "1" ]]
grep -Fq 'proxy_next_upstream_tries 2;' "$NGINX_CONFIG"
grep -Fq 'upstream="$upstream_addr"' "$NGINX_CONFIG"

grep -Fq 'Environment=KKREPO_RUNTIME=jvm' "$JVM_UNIT"
grep -Fq 'management.metrics.tags.runtime=jvm' "$JVM_UNIT"
grep -Fq 'Environment=KKREPO_RUNTIME=native' "$NATIVE_UNIT"
grep -Fq 'Environment=KKREPO_DEPLOY_ROOT=/opt/kkrepo/runtime/native' "$NATIVE_UNIT"
grep -Fq -- '--server.port=8090 --management.server.port=8091' "$NATIVE_UNIT"
grep -Fq 'http://127.0.0.1:8091/actuator/health/readiness' "$NATIVE_UNIT"

grep -Fq 'scripts/build-dist.sh --native' "$DEPLOY_WORKFLOW"
grep -Fq 'kkrepo-dual-runtime-deploy.sh' "$DEPLOY_WORKFLOW"
grep -Fq 'timeout-minutes: 90' "$DEPLOY_WORKFLOW"
grep -Fq 'deploy-config/dual-runtime-active' "$DEPLOY_WORKFLOW"

openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
  -subj '/CN=kkrepo.test' \
  -keyout "$TMP_ROOT/kkrepo.cn.key" \
  -out "$TMP_ROOT/kkrepo.cn_bundle.crt" >/dev/null 2>&1

docker run --rm \
  -v "$NGINX_CONFIG:/etc/nginx/conf.d/default.conf:ro" \
  -v "$TMP_ROOT/kkrepo.cn.key:/etc/nginx/tls/kkrepo.cn.key:ro" \
  -v "$TMP_ROOT/kkrepo.cn_bundle.crt:/etc/nginx/tls/kkrepo.cn_bundle.crt:ro" \
  nginx:stable-alpine nginx -t

printf '[check] dev JVM/Native deployment contract passed\n'
