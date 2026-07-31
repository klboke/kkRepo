#!/usr/bin/env bash
set -euo pipefail

ROOT_URL="${OCI_ROOT_URL:-${DOCKER_OCI_ROOT_URL:-http://127.0.0.1:18180}}"
NAMESPACE="${OCI_NAMESPACE:-${DOCKER_OCI_NAMESPACE:-kkrepo-conformance}}"
USERNAME="${OCI_USERNAME:-${DOCKER_OCI_USERNAME:-admin}}"
PASSWORD="${OCI_PASSWORD:-${DOCKER_OCI_PASSWORD:-123456}}"
PULL="${OCI_TEST_PULL:-${DOCKER_OCI_TEST_PULL:-1}}"
PUSH="${OCI_TEST_PUSH:-${DOCKER_OCI_TEST_PUSH:-1}}"
CONTENT_DISCOVERY="${OCI_TEST_CONTENT_DISCOVERY:-${DOCKER_OCI_TEST_CONTENT_DISCOVERY:-1}}"
CONTENT_MANAGEMENT="${OCI_TEST_CONTENT_MANAGEMENT:-${DOCKER_OCI_TEST_CONTENT_MANAGEMENT:-1}}"
AUTOMATIC_CROSSMOUNT="${OCI_AUTOMATIC_CROSSMOUNT:-${DOCKER_OCI_AUTOMATIC_CROSSMOUNT:-0}}"
USE_DOCKER="${DOCKER_OCI_CONFORMANCE_USE_DOCKER:-auto}"
IMAGE="${OCI_CONFORMANCE_IMAGE:-ghcr.io/opencontainers/distribution-spec/conformance:v1.1.1}"
DOCKER_NETWORK="${DOCKER_OCI_CONFORMANCE_NETWORK:-host}"
REPORT_DIR="${OCI_REPORT_DIR:-${DOCKER_OCI_REPORT_DIR:-target/oci-conformance/docker}}"
if [[ "$REPORT_DIR" == /* ]]; then
  REPORT_HOST_DIR="$REPORT_DIR"
else
  REPORT_HOST_DIR="$(pwd)/$REPORT_DIR"
fi

log() {
  printf '[docker-oci-conformance] %s\n' "$*" >&2
}

enabled() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

use_docker_runner() {
  case "$USE_DOCKER" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    0|false|FALSE|no|NO|off|OFF) return 1 ;;
    auto)
      ! command -v oci-conformance >/dev/null 2>&1
      ;;
    *)
      log "invalid DOCKER_OCI_CONFORMANCE_USE_DOCKER=$USE_DOCKER"
      exit 2
      ;;
  esac
}

verify_crossmount_mode() {
  local workdir config manifest source_namespace target_namespace automatic_namespace
  local config_digest config_size headers response status location upload_url separator expected_automatic
  workdir="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-oci-crossmount.XXXXXX")"
  config="$workdir/config.json"
  manifest="$workdir/manifest.json"
  headers="$workdir/headers.txt"
  response="$workdir/response.txt"
  source_namespace="${NAMESPACE}-crossmount-source"
  target_namespace="${NAMESPACE}-crossmount-target"
  automatic_namespace="${NAMESPACE}-crossmount-automatic"

  printf '%s' '{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":[]}}' >"$config"
  read -r config_digest config_size < <(python3 - "$config" <<'PY'
import hashlib
import pathlib
import sys

payload = pathlib.Path(sys.argv[1]).read_bytes()
print("sha256:" + hashlib.sha256(payload).hexdigest(), len(payload))
PY
)
  python3 - "$manifest" "$config_digest" "$config_size" <<'PY'
import json
import pathlib
import sys

path, digest, size = sys.argv[1:4]
payload = {
    "schemaVersion": 2,
    "mediaType": "application/vnd.oci.image.manifest.v1+json",
    "config": {
        "mediaType": "application/vnd.oci.image.config.v1+json",
        "digest": digest,
        "size": int(size),
    },
    "layers": [],
}
pathlib.Path(path).write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
PY

  status="$(curl -m 30 -sS -D "$headers" -o "$response" -w '%{http_code}' \
    -u "$USERNAME:$PASSWORD" -X POST \
    "${ROOT_URL%/}/v2/$source_namespace/blobs/uploads/")"
  if [[ "$status" != "202" ]]; then
    log "cross-mount mode fixture upload start returned HTTP $status"
    cat "$response" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi
  location="$(awk 'BEGIN{IGNORECASE=1} /^Location:/ {sub(/^[^:]*:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit}' "$headers")"
  if [[ -z "$location" ]]; then
    log "cross-mount mode fixture upload omitted Location"
    rm -rf "$workdir"
    exit 1
  fi
  case "$location" in
    http://*|https://*) upload_url="$location" ;;
    /*) upload_url="${ROOT_URL%/}$location" ;;
    *) upload_url="${ROOT_URL%/}/$location" ;;
  esac
  separator='?'
  [[ "$upload_url" == *'?'* ]] && separator='&'
  status="$(curl -m 30 -sS -o "$response" -w '%{http_code}' \
    -u "$USERNAME:$PASSWORD" -X PUT \
    -H 'Content-Type: application/octet-stream' \
    --data-binary @"$config" \
    "${upload_url}${separator}digest=$config_digest")"
  if [[ "$status" != "201" ]]; then
    log "cross-mount mode fixture blob upload returned HTTP $status"
    cat "$response" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi
  status="$(curl -m 30 -sS -o "$response" -w '%{http_code}' \
    -u "$USERNAME:$PASSWORD" -X PUT \
    -H 'Content-Type: application/vnd.oci.image.manifest.v1+json' \
    --data-binary @"$manifest" \
    "${ROOT_URL%/}/v2/$source_namespace/manifests/crossmount-mode")"
  if [[ "$status" != "201" ]]; then
    log "cross-mount mode fixture manifest upload returned HTTP $status"
    cat "$response" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi

  status="$(curl -m 30 -sS -o "$response" -w '%{http_code}' \
    -u "$USERNAME:$PASSWORD" -X POST -G \
    --data-urlencode "mount=$config_digest" \
    --data-urlencode "from=$source_namespace" \
    "${ROOT_URL%/}/v2/$target_namespace/blobs/uploads/")"
  if [[ "$status" != "201" ]]; then
    log "explicit-source OCI cross-mount returned HTTP $status instead of 201"
    cat "$response" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi

  case "$AUTOMATIC_CROSSMOUNT" in
    1|true|TRUE) expected_automatic=201 ;;
    *) expected_automatic=202 ;;
  esac
  status="$(curl -m 30 -sS -o "$response" -w '%{http_code}' \
    -u "$USERNAME:$PASSWORD" -X POST -G \
    --data-urlencode "mount=$config_digest" \
    "${ROOT_URL%/}/v2/$automatic_namespace/blobs/uploads/")"
  if [[ "$status" != "$expected_automatic" ]]; then
    log "automatic OCI cross-mount returned HTTP $status; configured mode expects $expected_automatic"
    cat "$response" >&2 || true
    rm -rf "$workdir"
    exit 1
  fi
  rm -rf "$workdir"
  log "cross-mount mode verified: explicit-source=201 automatic=$status"
}

export OCI_ROOT_URL="$ROOT_URL"
export OCI_NAMESPACE="$NAMESPACE"
export OCI_USERNAME="$USERNAME"
export OCI_PASSWORD="$PASSWORD"
export OCI_TEST_PULL="$PULL"
export OCI_TEST_PUSH="$PUSH"
export OCI_TEST_CONTENT_DISCOVERY="$CONTENT_DISCOVERY"
export OCI_TEST_CONTENT_MANAGEMENT="$CONTENT_MANAGEMENT"
export OCI_AUTOMATIC_CROSSMOUNT="$AUTOMATIC_CROSSMOUNT"

case "$OCI_AUTOMATIC_CROSSMOUNT" in
  0|1|true|TRUE|false|FALSE) ;;
  *)
    log "invalid OCI_AUTOMATIC_CROSSMOUNT=$OCI_AUTOMATIC_CROSSMOUNT; expected 0 or 1"
    exit 2
    ;;
esac

mkdir -p "$REPORT_DIR"

log "root=${OCI_ROOT_URL} namespace=${OCI_NAMESPACE}"
log "pull=${OCI_TEST_PULL} push=${OCI_TEST_PUSH} content-discovery=${OCI_TEST_CONTENT_DISCOVERY} content-management=${OCI_TEST_CONTENT_MANAGEMENT} automatic-crossmount=${OCI_AUTOMATIC_CROSSMOUNT}"

if enabled "$PUSH"; then
  verify_crossmount_mode
else
  log "cross-mount mode verification is skipped because push tests are disabled"
fi

if use_docker_runner; then
  if ! command -v docker >/dev/null 2>&1; then
    log "docker is required when DOCKER_OCI_CONFORMANCE_USE_DOCKER=$USE_DOCKER"
    exit 2
  fi
  docker_args=(--rm)
  if [[ "$DOCKER_NETWORK" != "default" ]]; then
    docker_args+=(--network "$DOCKER_NETWORK")
  fi
  log "running OCI distribution conformance image ${IMAGE} on docker network ${DOCKER_NETWORK}"
  docker run "${docker_args[@]}" \
    -e OCI_ROOT_URL \
    -e OCI_NAMESPACE \
    -e OCI_USERNAME \
    -e OCI_PASSWORD \
    -e OCI_TEST_PULL \
    -e OCI_TEST_PUSH \
    -e OCI_TEST_CONTENT_DISCOVERY \
    -e OCI_TEST_CONTENT_MANAGEMENT \
    -e OCI_AUTOMATIC_CROSSMOUNT \
    -e OCI_REPORT_DIR=/conformance/report \
    -v "${REPORT_HOST_DIR}:/conformance/report" \
    "$IMAGE"
else
  if ! command -v oci-conformance >/dev/null 2>&1; then
    log "missing oci-conformance; set DOCKER_OCI_CONFORMANCE_USE_DOCKER=1 to run ${IMAGE}"
    exit 2
  fi
  log "running local oci-conformance"
  export OCI_REPORT_DIR="$REPORT_DIR"
  oci-conformance
fi

for report in junit.xml report.html; do
  if [[ ! -s "$REPORT_DIR/$report" ]]; then
    log "OCI conformance did not produce the required report: $REPORT_DIR/$report"
    exit 1
  fi
done

automatic_report_status="$(python3 - "$REPORT_DIR/junit.xml" "$OCI_AUTOMATIC_CROSSMOUNT" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, configured = sys.argv[1:3]
expected = (
    "automatic content discovery enabled should return a 201"
    if configured.lower() in {"1", "true"}
    else "automatic content discovery disabled should return a 202"
)
matches = [
    case
    for case in ET.parse(path).getroot().iter("testcase")
    if expected in case.attrib.get("name", "")
]
if len(matches) != 1:
    raise SystemExit(
        f"expected exactly one OCI automatic cross-mount test matching {expected!r}, "
        f"found {len(matches)}"
    )
case = matches[0]
if case.find("failure") is not None or case.find("error") is not None:
    raise SystemExit(f"configured OCI automatic cross-mount test failed: {expected}")
print("skipped" if case.find("skipped") is not None else "passed")
PY
)"
log "official automatic cross-mount report case: $automatic_report_status (mode is independently verified with a manifest-referenced source blob)"

if enabled "$CONTENT_MANAGEMENT"; then
  log "Content Management conformance was enabled; DELETE routes and cleanup/GC semantics are part of this run"
fi

log "reports: ${REPORT_DIR}"
