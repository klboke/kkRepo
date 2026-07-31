#!/usr/bin/env bash
set -euo pipefail

IMAGE_TAG="${1:-}"
RUNTIME="${2:-}"
SOURCE_SHA="${3:-}"
OUTPUT_DIR="${4:-}"

if [[ -z "$IMAGE_TAG" || -z "$RUNTIME" || -z "$SOURCE_SHA" || -z "$OUTPUT_DIR" ]]; then
  echo "usage: $0 IMAGE_TAG RUNTIME SOURCE_SHA OUTPUT_DIR" >&2
  exit 2
fi
if [[ "$RUNTIME" != "jvm" && "$RUNTIME" != "native" ]]; then
  echo "candidate runtime must be jvm or native" >&2
  exit 2
fi
if [[ ! "$SOURCE_SHA" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
  echo "candidate source SHA must be a full Git object ID" >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1 || ! command -v zstd >/dev/null 2>&1; then
  echo "docker and zstd are required to package an E2E candidate image" >&2
  exit 2
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    LC_ALL=C sha256sum "$1" | awk '{print $1}'
  else
    LC_ALL=C shasum -a 256 "$1" | awk '{print $1}'
  fi
}

mkdir -p "$OUTPUT_DIR"
archive="$OUTPUT_DIR/image.tar.zst"
image_id="$(docker image inspect --format '{{.Id}}' "$IMAGE_TAG")"
normalized_source_sha="$(printf '%s' "$SOURCE_SHA" | tr '[:upper:]' '[:lower:]')"

docker save "$IMAGE_TAG" | zstd -T0 -3 -f -o "$archive"
printf '%s\n' "$(sha256_file "$archive")" > "$OUTPUT_DIR/image.tar.zst.sha256"
printf '%s\n' "$IMAGE_TAG" > "$OUTPUT_DIR/image-tag"
printf '%s\n' "$image_id" > "$OUTPUT_DIR/image-id"
printf '%s\n' "$RUNTIME" > "$OUTPUT_DIR/runtime"
printf '%s\n' "$normalized_source_sha" > "$OUTPUT_DIR/source-sha"

echo "[e2e-image] packaged $IMAGE_TAG ($image_id) for $RUNTIME at $normalized_source_sha"
