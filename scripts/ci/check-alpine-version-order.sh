#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGES="${ALPINE_VERSION_CHECK_IMAGES:-alpine:3.20,alpine:3.23}"

# apk-tools v2 deliberately treated commit-hash suffixes as equal, while v3
# orders them. Keep the cross-generation corpus to semantics shared by both;
# the Java unit corpus additionally locks the current apk-tools v3 behavior.
corpus=$'1.0\t1.0_alpha\t>\n1.0_alpha2\t1.0_alpha\t>\n1.0_alpha\t1.0_beta\t<\n1.0_beta\t1.0_pre\t<\n1.0_pre\t1.0_rc\t<\n1.0_rc1\t1.0\t<\n1.0\t1.0_cvs\t<\n1.0_git\t1.0_p\t<\n1.0_p1\t1.0_p2\t<\n1.0-r9\t1.0-r10\t<\n1.06-r6\t006\t<\n1.2.10\t1.2.2\t>\n2.5.1-r8\t2.5.1a-r1\t<\n1.0.01\t1.0.1\t<'

IFS=',' read -r -a images <<<"$IMAGES"
current_image="${ALPINE_VERSION_CHECK_CURRENT_IMAGE:-alpine:3.23}"
for image in "${images[@]}"; do
  [[ -n "$image" ]] || continue
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker pull "$image" >/dev/null
  fi
  image_corpus="$corpus"
  if [[ "$image" == "$current_image" ]]; then
    image_corpus+=$'\n1.0~dead\t1.0~beef\t>'
  fi
  docker run --rm \
    -e KKREPO_VERSION_CORPUS="$image_corpus" \
    -e KKREPO_VERSION_IMAGE="$image" \
    "$image" sh -euc '
    printf "%s\n" "$KKREPO_VERSION_CORPUS" |
      while IFS="$(printf "\t")" read -r left right expected; do
        actual="$(apk version -t "$left" "$right")"
        if [ "$actual" != "$expected" ]; then
          printf "%s: apk version -t %s %s returned %s, expected %s\n" \
            "$KKREPO_VERSION_IMAGE" "$left" "$right" "$actual" "$expected" >&2
          exit 1
        fi
      done
  '
  count="$(printf '%s\n' "$image_corpus" | wc -l | tr -d ' ')"
  printf '[alpine-version-check] %s matched %s ordering pairs\n' \
    "$image" "$count"
done

cd "$PROJECT_ROOT"
mvn -B -ntp -pl protocol-alpine -am \
  -Dtest=AlpineVersionsTest -Dsurefire.failIfNoSpecifiedTests=false test
