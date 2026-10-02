#!/usr/bin/env bash
set -euo pipefail
mode="${1:-}"
shard="${2:-}"
cache_dir="${3:-}"
case "$shard" in core|system|swift) ;; *) echo 'invalid client image shard' >&2; exit 2 ;; esac
if [[ -z "$cache_dir" || ( "$mode" != build && "$mode" != load ) ]]; then
  echo 'usage: client-e2e-image-cache.sh build|load core|system|swift CACHE_DIRECTORY' >&2
  exit 2
fi
image="kkrepo/client-e2e:$shard-v1"
if [[ "$mode" == build ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  mkdir -p "$cache_dir"
  docker build --pull --target "$shard" -t "$image" "$script_dir/client-e2e-image"
  docker save --output "$cache_dir/image.tar" "$image"
  docker image inspect --format '{{.Id}}' "$image" > "$cache_dir/image.id"
else
  expected="$(cat "$cache_dir/image.id")"
  [[ "$expected" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'Invalid client image identity' >&2; exit 1; }
  docker load --input "$cache_dir/image.tar"
  actual="$(docker image inspect --format '{{.Id}}' "$image")"
  [[ "$actual" == "$expected" ]] || { echo 'Client image identity mismatch' >&2; exit 1; }
fi
# Exercise the SDK executables as well as checking the archive's identity.
docker run --rm "$image" bash /opt/e2e/verify.sh "$shard"
