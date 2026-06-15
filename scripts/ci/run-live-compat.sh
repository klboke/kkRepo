#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

SUITE="${1:-${LIVE_COMPAT_SUITE:-smoke}}"

export NEXUS_COMPAT_BASE_URL="${NEXUS_COMPAT_BASE_URL:-http://127.0.0.1:28090}"
export NEXUS_COMPAT_USERNAME="${NEXUS_COMPAT_USERNAME:-admin}"
export NEXUS_COMPAT_PASSWORD="${NEXUS_COMPAT_PASSWORD:-123456}"
export NEXUS_PLUS_COMPAT_BASE_URL="${NEXUS_PLUS_COMPAT_BASE_URL:-http://127.0.0.1:18090}"
export NEXUS_PLUS_COMPAT_USERNAME="${NEXUS_PLUS_COMPAT_USERNAME:-admin}"
export NEXUS_PLUS_COMPAT_PASSWORD="${NEXUS_PLUS_COMPAT_PASSWORD:-12345678}"
export NEXUS_COMPAT_READ_REPOSITORY="${NEXUS_COMPAT_READ_REPOSITORY:-maven-public}"
export NEXUS_PLUS_COMPAT_READ_REPOSITORY="${NEXUS_PLUS_COMPAT_READ_REPOSITORY:-maven-public}"

COMMON_ARGS=(
  -B
  -ntp
  -pl
  compat-test
  -am
  -DfailIfNoTests=false
  -Dsurefire.failIfNoSpecifiedTests=false
)

run_tests() {
  local tests="$1"
  mvn "${COMMON_ARGS[@]}" "-Dtest=$tests" test
}

case "$SUITE" in
  smoke)
    run_tests "NexusPlusConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest#proxyReadRoundTripMatchesNexusWhenConfigured"
    ;;
  write-smoke)
    export COMPAT_WRITE_ENABLED=true
    run_tests "MavenRepositoryBlackBoxCompatibilityTest#hostedReleaseDeployRoundTripMatchesNexusWhenConfigured+hostedPlainPutDoesNotGenerateSidecarsOrMetadataLikeNexusWhenConfigured+hostedSnapshotDeployRoundTripMatchesNexusWhenConfigured"
    ;;
  extended)
    run_tests "NexusPlusConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest,PypiRepositoryBlackBoxCompatibilityTest,HelmRepositoryBlackBoxCompatibilityTest,NugetRubygemsYumRepositoryBlackBoxCompatibilityTest"
    ;;
  full)
    mvn "${COMMON_ARGS[@]}" test
    ;;
  *)
    echo "Unknown live compatibility suite: $SUITE" >&2
    echo "Available suites: smoke, write-smoke, extended, full" >&2
    exit 2
    ;;
esac
