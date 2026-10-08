#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

SUITE="${1:-${LIVE_COMPAT_SUITE:-smoke}}"

export NEXUS_COMPAT_BASE_URL="${NEXUS_COMPAT_BASE_URL:-http://127.0.0.1:${NEXUS_COMPAT_PORT:-28090}}"
export NEXUS_COMPAT_USERNAME="${NEXUS_COMPAT_USERNAME:-admin}"
export NEXUS_COMPAT_PASSWORD="${NEXUS_COMPAT_PASSWORD:-Admin1234}"
export KKREPO_COMPAT_BASE_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:${KKREPO_COMPAT_PORT:-18090}}"
export KKREPO_COMPAT_USERNAME="${KKREPO_COMPAT_USERNAME:-admin}"
export KKREPO_COMPAT_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
export NEXUS_COMPAT_READ_REPOSITORY="${NEXUS_COMPAT_READ_REPOSITORY:-maven-public}"
export KKREPO_COMPAT_READ_REPOSITORY="${KKREPO_COMPAT_READ_REPOSITORY:-maven-public}"

NEXUS_COMPAT_TESTS="GitLfsRepositoryBlackBoxCompatibilityTest,MavenGroupFailureCompatibilityTest,KkRepoConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest#proxyReadRoundTripMatchesNexusWhenConfigured+hostedReleaseDeployRoundTripMatchesNexusWhenConfigured+hostedPlainPutDoesNotGenerateSidecarsOrMetadataLikeNexusWhenConfigured+hostedSnapshotDeployRoundTripMatchesNexusWhenConfigured,NpmRepositoryBlackBoxCompatibilityTest,PypiRepositoryBlackBoxCompatibilityTest,GoProxyBlackBoxCompatibilityTest#proxyModuleEndpointsMatchNexusWhenConfigured,HelmRepositoryBlackBoxCompatibilityTest#hostedRoundTripMatchesNexusWhenConfigured+groupIndexAggregationMatchesNexusAndResolvesChartsWhenConfigured,CargoRepositoryBlackBoxCompatibilityTest,PubRepositoryBlackBoxCompatibilityTest,ComposerRepositoryBlackBoxCompatibilityTest,NugetRubygemsYumRepositoryBlackBoxCompatibilityTest#nugetHostedServiceIndexAndProxyReadsMatchNexusWhenConfigured+nugetHostedMultipartPushMatchesNexusWhenWriteEnabled+rubygemsHostedPushAndGroupReadMatchNexusWhenConfigured+rubygemsRepositoryRootPagesMatchNexusWhenConfigured+yumHostedRootAndMissingPackageResponsesMatchNexusWhenConfigured+yumHostedRpmPutMatchesNexusWhenWriteEnabled,NexusAssetManagementBlackBoxCompatibilityTest,RawRepositoryBlackBoxCompatibilityTest,TerraformRepositoryBlackBoxCompatibilityTest,SwiftRepositoryBlackBoxCompatibilityTest,AnsibleGalaxyRepositoryBlackBoxCompatibilityTest,AptRepositoryBlackBoxCompatibilityTest,AlpineRepositoryBlackBoxCompatibilityTest,RRepositoryBlackBoxCompatibilityTest,ConanRepositoryBlackBoxCompatibilityTest,ComponentUploadBlackBoxCompatibilityTest#uploadSpecsExposeNexusCompatibleSupportedFormatsWhenConfigured,SecurityAdminBlackBoxCompatibilityTest#privilegeFormRepositoryFieldsMatchNexusStoreContracts+repositoryReferenceStoreIncludesAllRepositorySelectors+roleAndPrivilegeReadContractsIncludeCoreBuiltIns+nonAdminRepositoryRoleCanBrowseButCannotUseSecurityAdministration+nonAdminContentSelectorRoleAllowsMatchingPathAndAccountsForDefaultReadGrant+contentSelectorPreviewValidatesCselBeforeSearching+contentSelectorManagementPreviewAndWriteIsolationMatchNexus"

NEXUS_COMPAT_TESTS="BlobStoreDeletionReferenceTest,$NEXUS_COMPAT_TESTS"

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

run_public_https_tests() {
  # These acceptance checks own disposable fixture writes; prerequisites apply only here.
  for client in go helm docker; do
    command -v "$client" >/dev/null || { echo "Public HTTPS acceptance requires $client" >&2; return 1; }
  done
  docker info >/dev/null
  export COMPAT_WRITE_ENABLED=true
  export PUBLIC_HTTPS_COMPAT_REQUIRED=true
  local report="compat-test/target/surefire-reports/TEST-com.github.klboke.kkrepo.compat.PublicHttpsContentRedirectCompatibilityTest.xml"
  rm -f "$report"
  run_tests "PublicHttpsContentRedirectCompatibilityTest"
  mkdir -p artifacts/public-https-redirects
  cp "$report" artifacts/public-https-redirects/
  python3 scripts/ci/check-public-https-report.py "$report"
}

case "$SUITE" in
  smoke)
    run_tests "KkRepoConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest#proxyReadRoundTripMatchesNexusWhenConfigured"
    ;;
  write-smoke)
    export COMPAT_WRITE_ENABLED=true
    run_tests "MavenRepositoryBlackBoxCompatibilityTest#hostedReleaseDeployRoundTripMatchesNexusWhenConfigured+hostedPlainPutDoesNotGenerateSidecarsOrMetadataLikeNexusWhenConfigured+hostedSnapshotDeployRoundTripMatchesNexusWhenConfigured"
    ;;
  extended)
    run_tests "KkRepoConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest,PypiRepositoryBlackBoxCompatibilityTest,HelmRepositoryBlackBoxCompatibilityTest,NugetRubygemsYumRepositoryBlackBoxCompatibilityTest,PubRepositoryBlackBoxCompatibilityTest,ComposerRepositoryBlackBoxCompatibilityTest"
    ;;
  nexus|nexus-compat)
    export COMPAT_WRITE_ENABLED="${COMPAT_WRITE_ENABLED:-true}"
    export CARGO_COMPAT_ENABLED=true
    export PUB_COMPAT_ENABLED=true
    export COMPOSER_COMPAT_ENABLED=true
    export SWIFT_COMPAT_ENABLED=true
    export ANSIBLE_COMPAT_ENABLED=true
    export APT_COMPAT_ENABLED=true
    export ALPINE_COMPAT_ENABLED=true
    export R_COMPAT_ENABLED=true
    export CONAN_COMPAT_ENABLED=true
    export SWIFT_COMPAT_PROXY_ENABLED="${SWIFT_COMPAT_PROXY_ENABLED:-true}"
    if [[ -n "${SWIFT_COMPAT_PROXY_TAG_CASES:-}" ]]; then
      export SWIFT_COMPAT_REQUIRE_PROXY_TAG_CASES=true
    fi
    export COMPAT_SECURITY_ENABLED="${COMPAT_SECURITY_ENABLED:-true}"
    export GO_NEXUS_COMPAT_BASE_URL="${GO_NEXUS_COMPAT_BASE_URL:-$NEXUS_COMPAT_BASE_URL}"
    export GO_KKREPO_COMPAT_BASE_URL="${GO_KKREPO_COMPAT_BASE_URL:-$KKREPO_COMPAT_BASE_URL}"
    export GO_KKREPO_COMPAT_USERNAME="${GO_KKREPO_COMPAT_USERNAME:-$KKREPO_COMPAT_USERNAME}"
    export GO_KKREPO_COMPAT_PASSWORD="${GO_KKREPO_COMPAT_PASSWORD:-$KKREPO_COMPAT_PASSWORD}"
    run_tests "$NEXUS_COMPAT_TESTS"
    run_public_https_tests
    ;;
  client-e2e)
    export PUB_COMPAT_ENABLED=true
    scripts/ci/run-client-e2e.sh
    ;;
  swift)
    export SWIFT_COMPAT_ENABLED=true
    export SWIFT_COMPAT_PROXY_ENABLED="${SWIFT_COMPAT_PROXY_ENABLED:-true}"
    run_tests "SwiftRepositoryBlackBoxCompatibilityTest"
    ;;
  ansible|ansible-galaxy)
    export ANSIBLE_COMPAT_ENABLED=true
    run_tests "AnsibleGalaxyRepositoryBlackBoxCompatibilityTest"
    ;;
  apt|debian)
    export APT_COMPAT_ENABLED=true
    run_tests "AptRepositoryBlackBoxCompatibilityTest"
    ;;
  conan)
    export CONAN_COMPAT_ENABLED=true
    run_tests "ConanRepositoryBlackBoxCompatibilityTest"
    ;;
  r|cran)
    export R_COMPAT_ENABLED=true
    run_tests "RRepositoryBlackBoxCompatibilityTest"
    ;;
  public-https)
    run_public_https_tests
    ;;
  full)
    export CARGO_COMPAT_ENABLED=true
    export PUB_COMPAT_ENABLED=true
    export COMPOSER_COMPAT_ENABLED=true
    export SWIFT_COMPAT_ENABLED=true
    export ANSIBLE_COMPAT_ENABLED=true
    export APT_COMPAT_ENABLED=true
    export ALPINE_COMPAT_ENABLED=true
    export R_COMPAT_ENABLED=true
    export CONAN_COMPAT_ENABLED=true
    export SWIFT_COMPAT_PROXY_ENABLED="${SWIFT_COMPAT_PROXY_ENABLED:-true}"
    if [[ -n "${SWIFT_COMPAT_PROXY_TAG_CASES:-}" ]]; then
      export SWIFT_COMPAT_REQUIRE_PROXY_TAG_CASES=true
    fi
    mvn "${COMMON_ARGS[@]}" "-Dtest=*,!PublicHttpsContentRedirectCompatibilityTest" test
    run_public_https_tests
    ;;
  *)
    echo "Unknown live compatibility suite: $SUITE" >&2
    echo "Available suites: smoke, write-smoke, extended, nexus, client-e2e, swift, ansible, apt, conan, r, public-https, full" >&2
    exit 2
    ;;
esac
