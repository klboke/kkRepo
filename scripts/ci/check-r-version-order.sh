#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGES="${R_VERSION_CHECK_IMAGES:-r-base:4.5.3,r-base:4.6.1}"

corpus=$'0.9\t0.75\t-1\n0.01.0\t0.1-0\t0\n1.2-9\t1.2-10\t-1\n1.0\t1.0.0\t-1\n2.0.0\t1.999.999\t1\n10.2\t2.100\t1'

IFS=',' read -r -a images <<<"$IMAGES"
for image in "${images[@]}"; do
  [[ -n "$image" ]] || continue
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker pull "$image" >/dev/null
  fi
  docker run --rm -e KKREPO_R_VERSION_CORPUS="$corpus" -e KKREPO_R_IMAGE="$image" \
    "$image" Rscript -e '
      rows <- strsplit(Sys.getenv("KKREPO_R_VERSION_CORPUS"), "\n", fixed = TRUE)[[1]]
      for (row in rows) {
        values <- strsplit(row, "\t", fixed = TRUE)[[1]]
        actual <- sign(compareVersion(values[[1]], values[[2]]))
        expected <- as.integer(values[[3]])
        if (is.na(actual) || actual != expected) {
          stop(sprintf("%s: compareVersion(%s, %s) returned %d, expected %d",
            Sys.getenv("KKREPO_R_IMAGE"), values[[1]], values[[2]], actual, expected))
        }
      }
    '
  count="$(printf '%s\n' "$corpus" | wc -l | tr -d ' ')"
  printf '[r-version-check] %s matched %s ordering pairs\n' "$image" "$count"
done

cd "$PROJECT_ROOT"
mvn -B -ntp -pl protocol-r -am \
  -Dtest=RVersionsTest -Dsurefire.failIfNoSpecifiedTests=false test
