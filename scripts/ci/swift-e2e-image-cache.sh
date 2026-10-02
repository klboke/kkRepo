#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
version="${2:-}"
cache_dir="${3:-}"
case "$version" in
  5.7) base="swift:5.7.3-jammy" ;;
  5.10) base="swift:5.10.1-jammy" ;;
  6) base="swift:6.3.3-jammy" ;;
  *) echo "unknown Swift E2E version: $version" >&2; exit 2 ;;
esac
image="kkrepo/swift-e2e:${base#swift:}-v1"
if [[ -z "$cache_dir" || ( "$mode" != "build" && "$mode" != "load" ) ]]; then
  echo "usage: $0 build|load 5.7|5.10|6 CACHE_DIRECTORY" >&2
  exit 2
fi

if [[ "$mode" == "build" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  mkdir -p "$cache_dir"
  docker build --pull --build-arg "BASE_IMAGE=$base" -t "$image" "$script_dir/swift-e2e-image"
  docker save --output "$cache_dir/image.tar" "$image"
  docker image inspect --format '{{.Id}}' "$image" > "$cache_dir/image.id"
else
  expected="$(cat "$cache_dir/image.id")"
  if [[ ! "$expected" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "Invalid cached Swift image identity" >&2
    exit 1
  fi
  docker load --input "$cache_dir/image.tar"
  actual="$(docker image inspect --format '{{.Id}}' "$image")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Cached Swift image identity mismatch for $image" >&2
    exit 1
  fi
fi
