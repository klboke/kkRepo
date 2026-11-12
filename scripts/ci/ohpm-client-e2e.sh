#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"
: "${KKREPO_COMPAT_BASE_URL:?Set the Login-disabled candidate URL}"
: "${OHPM_LOGIN_BASE_URL:?Set the Login-enabled candidate URL using the same candidate artifact}"
artifact_dir="${CLIENT_E2E_ARTIFACT_DIR:-$project_root/artifacts/client-e2e}"
mkdir -p "$artifact_dir/ohpm"
artifact_dir="$(cd "$artifact_dir" && pwd)"
rm -f "$artifact_dir/cleanup-ohpm.json"
if [[ -z "${OHPM_COMMAND:-}" ]]; then
  export OHPM_INSTALL_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kkrepo-ohpm-$(date +%s)-$$"
  bash "$script_dir/setup-ohpm-legacy.sh"
  export OHPM_COMMAND="$OHPM_INSTALL_DIR/oh-command-line-tools/ohpm/bin/ohpm"
fi
export OHPM_EXPECTED_VERSION="${OHPM_EXPECTED_VERSION:-5.0.2}"
disabled_url="$KKREPO_COMPAT_BASE_URL"
python3 - "$disabled_url" "$OHPM_LOGIN_BASE_URL" <<'PY'
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

credentials = os.environ.get("KKREPO_COMPAT_USERNAME", "admin") + ":" + os.environ["KKREPO_COMPAT_PASSWORD"]
headers = {"Authorization": "Basic " + base64.b64encode(credentials.encode()).decode()}
for base, enabled in zip(sys.argv[1:], (False, True)):
    deadline = time.monotonic() + int(os.environ.get("LIVE_COMPAT_START_TIMEOUT_SECONDS", "240"))
    while True:
        try:
            request = urllib.request.Request(base.rstrip("/") + "/internal/ohpm/login", headers=headers)
            with urllib.request.urlopen(request, timeout=10) as response:
                if json.load(response).get("enabled") == enabled:
                    break
        except (OSError, urllib.error.URLError):
            pass
        if time.monotonic() >= deadline:
            raise SystemExit(f"OHPM candidate did not start with Login={enabled}: {base}")
        time.sleep(1)
PY
for login in false true; do
  export OHPM_COMPAT_LOGIN_ENABLED="$login"
  export KKREPO_COMPAT_BASE_URL="$disabled_url"
  [[ "$login" != true ]] || export KKREPO_COMPAT_BASE_URL="$OHPM_LOGIN_BASE_URL"
  report_dir="$artifact_dir/ohpm/login-$login"
  mkdir -p "$report_dir"
  export OHPM_CLEANUP_REPORT="$report_dir/cleanup.json"
  rm -f "$OHPM_CLEANUP_REPORT"
  status=0
  bash "$script_dir/run-ohpm-compat.sh" client-e2e > "$report_dir/acceptance.log" 2>&1 || status=$?
  cp "$project_root"/compat-test/target/surefire-reports/*OhpmRepositoryBlackBoxCompatibilityTest* \
    "$report_dir/" 2>/dev/null || true
  if (( status != 0 )); then
    cat "$report_dir/acceptance.log"
    exit "$status"
  fi
done
python3 - "$artifact_dir" <<'PY'
import json
import pathlib
import sys

directory = pathlib.Path(sys.argv[1])
variants = {}
for login in ("false", "true"):
    report = json.loads((directory / "ohpm" / f"login-{login}" / "cleanup.json").read_text())
    if report.get("automaticCleanupRejected") is not True or report.get("protocolDeletionVerified") is not True:
        raise SystemExit(f"OHPM cleanup acceptance incomplete for Login={login}")
    variants[login] = report
(directory / "cleanup-ohpm.json").write_text(json.dumps({"loginVariants": variants}, indent=2))
print("OHPM real-client acceptance passed with Login disabled and enabled, including explicit protocol deletion.")
PY
