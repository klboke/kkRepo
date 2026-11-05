#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

MODE="${1:-blackbox}"
: "${KKREPO_COMPAT_BASE_URL:?Set KKREPO_COMPAT_BASE_URL to a disposable candidate}"
export OHPM_COMPAT_ENABLED=true
TEST_CLASS="OhpmRepositoryBlackBoxCompatibilityTest"
ARGS=(-B -ntp -pl compat-test -am -Dsurefire.failIfNoSpecifiedTests=false)
case "$MODE" in
  blackbox)
    ARGS+=("-Dtest=$TEST_CLASS#hostedHarAndHspSupportNormalAndStreamingPublication+proxyAndGroupResolveBothFilesWithoutMixingSources+managementUploadAndResumableImportUseTheSameHostedContent+optionalSignedLoginHonorsConfigurationReplayAndTokenScope")
    EXPECTED_TESTS=7
    ;;
  client-e2e)
    OHPM_COMMAND="${OHPM_COMMAND:-ohpm}"
    command -v "$OHPM_COMMAND" >/dev/null || { echo "OHPM_COMMAND must identify an installed OHPM client" >&2; exit 1; }
    : "${OHPM_EXPECTED_VERSION:?Pin the installed client with OHPM_EXPECTED_VERSION}"
    ARGS+=("-Dtest=$TEST_CLASS" -Dkkrepo.ohpm.client=true
      "-Dkkrepo.ohpm.command=$OHPM_COMMAND" "-Dkkrepo.ohpm.expectedVersion=$OHPM_EXPECTED_VERSION")
    EXPECTED_TESTS=8
    ;;
  *)
    echo "Usage: bash scripts/ci/run-ohpm-compat.sh [blackbox|client-e2e]" >&2
    exit 2
    ;;
esac

REPORT="compat-test/target/surefire-reports/TEST-com.github.klboke.kkrepo.compat.$TEST_CLASS.xml"
rm -f "$REPORT"
mvn "${ARGS[@]}" test
python3 - "$REPORT" "$EXPECTED_TESTS" <<'PY'
import sys
import xml.etree.ElementTree as ET

suite = ET.parse(sys.argv[1]).getroot()
assert int(suite.attrib["tests"]) == int(sys.argv[2]), "OHPM acceptance test count changed"
assert all(int(suite.attrib.get(name, 0)) == 0 for name in ("failures", "errors", "skipped")), \
    "Every requested OHPM acceptance test must run successfully"
print(f"OHPM acceptance: {suite.attrib['tests']} passed, no skipped cases")
PY
