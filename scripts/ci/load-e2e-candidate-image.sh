#!/usr/bin/env bash
set -euo pipefail

ARTIFACT_DIR="${1:-}"
EXPECTED_RUNTIME="${2:-}"
EXPECTED_SOURCE_SHA="${3:-}"
EXPECTED_SOURCE_TAG="${4:-kkrepo:compat}"
TARGET_TAG="${5:-$EXPECTED_SOURCE_TAG}"

if [[ -z "$ARTIFACT_DIR" || -z "$EXPECTED_RUNTIME" || -z "$EXPECTED_SOURCE_SHA" ]]; then
  echo "usage: $0 ARTIFACT_DIR RUNTIME SOURCE_SHA [SOURCE_TAG] [TARGET_TAG]" >&2
  exit 2
fi
if [[ "$EXPECTED_RUNTIME" != "jvm" && "$EXPECTED_RUNTIME" != "native" ]]; then
  echo "expected runtime must be jvm or native" >&2
  exit 2
fi
if [[ ! "$EXPECTED_SOURCE_SHA" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
  echo "expected source SHA must be a full Git object ID" >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1 || ! command -v zstd >/dev/null 2>&1; then
  echo "docker and zstd are required to load an E2E candidate image" >&2
  exit 2
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    LC_ALL=C sha256sum "$1" | awk '{print $1}'
  else
    LC_ALL=C shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for file in image.tar.zst image.tar.zst.sha256 image-tag image-id runtime source-sha; do
  if [[ ! -f "$ARTIFACT_DIR/$file" ]]; then
    echo "candidate image artifact is missing $file" >&2
    exit 1
  fi
done

actual_runtime="$(<"$ARTIFACT_DIR/runtime")"
actual_source_sha="$(<"$ARTIFACT_DIR/source-sha")"
actual_source_tag="$(<"$ARTIFACT_DIR/image-tag")"
expected_image_id="$(<"$ARTIFACT_DIR/image-id")"
expected_archive_sha="$(<"$ARTIFACT_DIR/image.tar.zst.sha256")"
actual_archive_sha="$(sha256_file "$ARTIFACT_DIR/image.tar.zst")"
normalized_source_sha="$(printf '%s' "$EXPECTED_SOURCE_SHA" | tr '[:upper:]' '[:lower:]')"

if [[ "$actual_runtime" != "$EXPECTED_RUNTIME" ]]; then
  echo "candidate runtime mismatch: expected $EXPECTED_RUNTIME, got $actual_runtime" >&2
  exit 1
fi
if [[ "$actual_source_sha" != "$normalized_source_sha" ]]; then
  echo "candidate source mismatch: expected $normalized_source_sha, got $actual_source_sha" >&2
  exit 1
fi
if [[ "$actual_source_tag" != "$EXPECTED_SOURCE_TAG" ]]; then
  echo "candidate image tag mismatch: expected $EXPECTED_SOURCE_TAG, got $actual_source_tag" >&2
  exit 1
fi
if [[ "$actual_archive_sha" != "$expected_archive_sha" ]]; then
  echo "candidate image archive checksum mismatch" >&2
  exit 1
fi

zstd -dc "$ARTIFACT_DIR/image.tar.zst" | docker load
actual_image_id="$(docker image inspect --format '{{.Id}}' "$EXPECTED_SOURCE_TAG")"
if [[ "$actual_image_id" != "$expected_image_id" ]]; then
  echo "loaded candidate image ID mismatch: expected $expected_image_id, got $actual_image_id" >&2
  exit 1
fi
if [[ "$TARGET_TAG" != "$EXPECTED_SOURCE_TAG" ]]; then
  docker tag "$EXPECTED_SOURCE_TAG" "$TARGET_TAG"
fi

echo "[e2e-image] loaded verified $EXPECTED_RUNTIME candidate $TARGET_TAG at $normalized_source_sha"
