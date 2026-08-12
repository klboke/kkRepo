#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

KKREPO_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:${KKREPO_COMPAT_PORT:-18090}}"
SWIFT_KKREPO_URL="${SWIFT_KKREPO_BASE_URL:-$KKREPO_URL}"
KKREPO_USER="${KKREPO_COMPAT_USERNAME:-admin}"
KKREPO_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
KKREPO_AUTH="$KKREPO_USER:$KKREPO_PASSWORD"
KKREPO_MANAGEMENT_URL="${KKREPO_MANAGEMENT_URL:-http://127.0.0.1:${KKREPO_MANAGEMENT_PORT:-18091}}"
KKREPO_DOCKER_HOSTED_REGISTRY="${KKREPO_DOCKER_HOSTED_REGISTRY:-127.0.0.1:${KKREPO_DOCKER_HOSTED_PORT:-18180}}"
ARTIFACT_DIR="${CLIENT_E2E_ARTIFACT_DIR:-$PROJECT_ROOT/artifacts/client-e2e}"
WORK_DIR="${CLIENT_E2E_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-client-e2e.XXXXXX")}"
STAMP="${CLIENT_E2E_STAMP:-$(date +%Y%m%d%H%M%S)}"
START_TIMEOUT_SECONDS="${LIVE_COMPAT_START_TIMEOUT_SECONDS:-240}"
SWIFT_LOGIN_TIMEOUT_SECONDS="${SWIFT_E2E_LOGIN_TIMEOUT_SECONDS:-60}"
ANSIBLE_IMPORT_TIMEOUT_SECONDS="${ANSIBLE_E2E_IMPORT_TIMEOUT_SECONDS:-120}"
CONDA_BIN="${CONDA_E2E_BIN:-${CONDA_BIN:-conda}}"
CONDA_HOSTED_REPOSITORY="${CONDA_E2E_HOSTED_REPOSITORY:-conda-hosted}"
CONDA_PROXY_REPOSITORY="${CONDA_E2E_PROXY_REPOSITORY:-conda-proxy}"
CONDA_GROUP_REPOSITORY="${CONDA_E2E_GROUP_REPOSITORY:-conda-group}"
APT_HOSTED_REPOSITORY="${APT_E2E_HOSTED_REPOSITORY:-apt-hosted}"
APT_CLIENT_IMAGES="${APT_E2E_IMAGES:-debian12=debian:12-slim,ubuntu24=ubuntu:24.04,current=debian:testing-slim}"
CONAN_BIN="${CONAN_E2E_BIN:-conan}"
CONAN_HOSTED_REPOSITORY="${CONAN_E2E_HOSTED_REPOSITORY:-conan-hosted}"
CONAN_PROXY_REPOSITORY="${CONAN_E2E_PROXY_REPOSITORY:-conan-proxy}"
CONAN_PROXY_REFERENCE="${CONAN_E2E_PROXY_REFERENCE:-zlib/1.3.1}"
CONAN_GROUP_REPOSITORY="${CONAN_E2E_GROUP_REPOSITORY:-conan-group}"
KKREPO_AUTH_URL=""
REDACTION_VALUES=("$KKREPO_PASSWORD" "$KKREPO_AUTH")
CLEANUP_FIXTURE_FORMAT=""
CLEANUP_FIXTURE_REPOSITORY=""
CLEANUP_FIXTURE_PATTERN=""
CLEANUP_FIXTURE_LABEL=""
SWIFT_CLEANUP_FIXTURE_AVAILABLE=false
APT_E2E_CONTAINERS=()

if [[ ! "$SWIFT_LOGIN_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  printf '[client-e2e] SWIFT_E2E_LOGIN_TIMEOUT_SECONDS must be a positive integer\n' >&2
  exit 2
fi
if [[ ! "$ANSIBLE_IMPORT_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  printf '[client-e2e] ANSIBLE_E2E_IMPORT_TIMEOUT_SECONDS must be a positive integer\n' >&2
  exit 2
fi

mkdir -p "$ARTIFACT_DIR" "$WORK_DIR"
ARTIFACT_DIR="$(cd "$ARTIFACT_DIR" && pwd)"
WORK_DIR="$(cd "$WORK_DIR" && pwd)"
export CLIENT_E2E_WORK_DIR="$WORK_DIR"
export DOTNET_CLI_TELEMETRY_OPTOUT="${DOTNET_CLI_TELEMETRY_OPTOUT:-1}"
export DOTNET_NOLOGO="${DOTNET_NOLOGO:-1}"
export DOTNET_SKIP_FIRST_TIME_EXPERIENCE="${DOTNET_SKIP_FIRST_TIME_EXPERIENCE:-1}"

log() {
  printf '[client-e2e] %s\n' "$*"
}

cleanup_apt_e2e_containers() {
  local container
  if (( ${#APT_E2E_CONTAINERS[@]} == 0 )); then
    return
  fi
  for container in "${APT_E2E_CONTAINERS[@]}"; do
    docker rm -f "$container" >/dev/null 2>&1 || true
  done
}

trap cleanup_apt_e2e_containers EXIT

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "missing required command: $1"
    exit 2
  fi
}

add_redaction_value() {
  if [[ -n "$1" ]]; then
    REDACTION_VALUES+=("$1")
  fi
}

redact_text() {
  local text="$1"
  local value
  for value in "${REDACTION_VALUES[@]}"; do
    if [[ -n "$value" ]]; then
      text="${text//$value/******}"
    fi
  done
  if [[ -n "$KKREPO_AUTH_URL" ]]; then
    text="${text//$KKREPO_AUTH_URL/$KKREPO_URL}"
  fi
  printf '%s' "$text"
}

print_command() {
  local arg
  printf '$'
  for arg in "$@"; do
    printf ' %q' "$(redact_text "$arg")"
  done
  printf '\n'
}

redact_log_file() {
  local file="$1"
  python3 - "$file" "${REDACTION_VALUES[@]}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
try:
    text = path.read_text(encoding="utf-8", errors="replace")
except FileNotFoundError:
    sys.exit(0)

for value in sys.argv[2:]:
    if value:
        text = text.replace(value, "******")
path.write_text(text, encoding="utf-8")
PY
}

run_logged() {
  local name="$1"
  shift
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    print_command "$@"
    "$@"
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

run_logged_in() {
  local name="$1"
  local dir="$2"
  shift 2
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    printf '$ cd %q\n' "$(redact_text "$dir")"
    print_command "$@"
    (cd "$dir" && "$@")
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

run_with_timeout() {
  local timeout_seconds="$1"
  shift
  python3 - "$timeout_seconds" "$@" <<'PY'
import subprocess
import sys

timeout = int(sys.argv[1])
command = sys.argv[2:]
try:
    completed = subprocess.run(command, timeout=timeout, check=False)
except subprocess.TimeoutExpired:
    print(f"command timed out after {timeout} seconds", file=sys.stderr)
    raise SystemExit(124)
raise SystemExit(completed.returncode)
PY
}

test_jdbc_browser_session() {
  local directory="$WORK_DIR/jdbc-browser-session"
  local cookie_jar="$directory/cookies.txt"
  local login_body="$directory/login.body"
  local session_body="$directory/session.body"
  local login_status session_status attempt
  mkdir -p "$directory"

  login_status="$(curl -m 20 -sS -u "$KKREPO_AUTH" \
    -c "$cookie_jar" -o "$login_body" -w '%{http_code}' \
    "$KKREPO_URL/internal/security/basic/login?returnTo=%2Fbrowse%2F")"
  if [[ "$login_status" != "302" ]]; then
    log "JDBC browser-session login returned HTTP $login_status"
    return 1
  fi

  # Read twice so a Native runtime must deserialize the SessionSubject written by
  # the login request from the shared JDBC session store on subsequent requests.
  for attempt in 1 2; do
    session_status="$(curl -m 20 -sS -b "$cookie_jar" \
      -o "$session_body" -w '%{http_code}' \
      "$KKREPO_URL/internal/security/session")"
    if [[ "$session_status" != "200" ]]; then
      log "JDBC browser-session reload $attempt returned HTTP $session_status"
      return 1
    fi
    python3 - "$session_body" "$KKREPO_USER" <<'PY'
import json
import pathlib
import sys

session = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if session.get("userId") != sys.argv[2]:
    raise SystemExit(
        f"JDBC browser session user mismatch: {session.get('userId')!r}")
PY
  done
  log "JDBC browser session survived two reloads"
}

wait_for_ansible_import_task() {
  local label="$1"
  local publish_log="$2"
  local hosted_url="$3"
  local task_ref task_url task_output curl_log
  local deadline now remaining request_timeout http_status outcome

  if ! task_ref="$(python3 - "$publish_log" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
matches = re.findall(r"api/v3/imports/collections/[A-Za-z0-9._-]+/", text)
if not matches:
    raise SystemExit("publish output did not contain an Ansible import task URL")
print(matches[-1])
PY
)"; then
    log "Ansible $label publish output did not expose a pollable import task"
    return 1
  fi

  task_url="${hosted_url%/}/${task_ref#/}"
  task_output="$ARTIFACT_DIR/ansible-$label-import-task.json"
  curl_log="$ARTIFACT_DIR/ansible-$label-import-task.curl.log"
  deadline=$(( $(date +%s) + ANSIBLE_IMPORT_TIMEOUT_SECONDS ))
  http_status="not-requested"
  outcome="pending"
  log "waiting up to ${ANSIBLE_IMPORT_TIMEOUT_SECONDS}s for Ansible $label import task"

  while true; do
    now="$(date +%s)"
    remaining=$((deadline - now))
    if (( remaining <= 0 )); then
      break
    fi
    request_timeout=5
    if (( remaining < request_timeout )); then
      request_timeout="$remaining"
    fi
    http_status="$(curl -m "$request_timeout" -sS -u "$KKREPO_AUTH" \
      -o "$task_output" -w '%{http_code}' "$task_url" 2>"$curl_log" || true)"
    if [[ "$http_status" == "200" ]]; then
      outcome="$(python3 - "$task_output" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
state = str(payload.get("state") or "").strip().lower()
finished = payload.get("finished_at")
if state in {"failed", "error"} or (finished and state != "completed"):
    print(f"failed:{state or 'unknown'}")
elif finished and state == "completed":
    print("completed")
else:
    print(f"pending:{state or 'unknown'}")
PY
      )" || outcome="invalid-response"
      case "$outcome" in
        completed)
          log "Ansible $label import task completed"
          return 0
          ;;
        failed:*)
          log "Ansible $label import task finished with ${outcome#failed:} state"
          return 1
          ;;
      esac
    fi

    now="$(date +%s)"
    if (( now >= deadline )); then
      break
    fi
    sleep 1
  done

  log "timed out after ${ANSIBLE_IMPORT_TIMEOUT_SECONDS}s waiting for Ansible $label import task (HTTP $http_status, $outcome)"
  return 1
}

run_logged_output() {
  local name="$1"
  local output="$2"
  shift 2
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    print_command "$@"
    "$@" >"$output"
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

run_logged_redacted_output() {
  local name="$1"
  local output="$2"
  shift 2
  local status=0
  run_logged_output "$name" "$output" "$@" || status=$?
  redact_log_file "$output"
  return "$status"
}

run_logged_output_in() {
  local name="$1"
  local dir="$2"
  local output="$3"
  shift 3
  local log_file="$ARTIFACT_DIR/$name.log"
  log "running $name"
  set +e
  {
    printf '$ cd %q\n' "$(redact_text "$dir")"
    print_command "$@"
    (cd "$dir" && "$@" >"$output")
  } >"$log_file" 2>&1
  local status=$?
  set -e
  redact_log_file "$log_file"
  return "$status"
}

write_nuget_config() {
  local file="$1"
  local source_name="$2"
  local source_url="$3"
  cat >"$file" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <config>
EOF
  printf '    <add key="defaultPushSource" value="%s" />\n' "$source_url" >>"$file"
  cat >>"$file" <<'EOF'
  </config>
  <packageSources>
    <clear />
EOF
  printf '    <add key="%s" value="%s" protocolVersion="3" allowInsecureConnections="true" />\n' "$source_name" "$source_url" >>"$file"
  cat >>"$file" <<EOF
  </packageSources>
  <packageSourceCredentials>
    <$source_name>
      <add key="Username" value="$KKREPO_USER" />
      <add key="ClearTextPassword" value="$KKREPO_PASSWORD" />
    </$source_name>
  </packageSourceCredentials>
</configuration>
EOF
}

wait_for_http() {
  local label="$1"
  local url="$2"
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    if curl -m 5 -fsS "$url" >/dev/null 2>&1; then
      log "$label is ready"
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label at $url"
  return 1
}

wait_for_docker_registry() {
  local headers_file http_code
  headers_file="$(mktemp)"
  for ((i = 1; i <= START_TIMEOUT_SECONDS; i++)); do
    http_code="$(curl -m 5 -sS -D "$headers_file" -o /dev/null -w '%{http_code}' \
      "http://$KKREPO_DOCKER_HOSTED_REGISTRY/v2/" 2>/dev/null || true)"
    if [[ "$http_code" == "200" || "$http_code" == "401" ]] \
      && grep -qi '^Docker-Distribution-API-Version:[[:space:]]*registry/2\.0' "$headers_file"; then
      rm -f "$headers_file"
      log "Docker registry is ready"
      return 0
    fi
    : >"$headers_file"
    sleep 1
  done
  rm -f "$headers_file"
  log "timed out waiting for Docker registry at $KKREPO_DOCKER_HOSTED_REGISTRY"
  return 1
}

create_api_key() {
  local domain="$1"
  local display_name="$2"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -H "Content-Type: application/json" \
    --data "{\"domain\":\"$domain\",\"displayName\":\"$display_name\"}" \
    "$KKREPO_URL/internal/security/api-keys/current" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

set_anonymous_access() {
  local enabled="$1"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "{\"enabled\":$enabled}" \
    "$KKREPO_URL/internal/security/anonymous" >/dev/null
}

repository_proxy_remote() {
  local repository="$1"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    "$KKREPO_URL/internal/repositories/$repository" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["proxy"]["remoteUrl"])'
}

set_repository_proxy_remote() {
  local repository="$1"
  local remote_url="$2"
  local payload
  payload="$(python3 - "$remote_url" <<'PY'
import json
import sys
print(json.dumps({"proxy": {"remoteUrl": sys.argv[1]}}, separators=(",", ":")))
PY
)"
  curl -m 20 -fsS \
    -u "$KKREPO_AUTH" \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KKREPO_URL/internal/repositories/$repository" >/dev/null
}

basic_auth_url() {
  python3 - "$KKREPO_URL" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import sys
from urllib.parse import quote, urlsplit, urlunsplit

url, user, password = sys.argv[1:4]
parts = urlsplit(url)
netloc = parts.netloc
if "@" in netloc:
    netloc = netloc.split("@", 1)[1]
auth = quote(user, safe="") + ":" + quote(password, safe="") + "@"
print(urlunsplit((parts.scheme, auth + netloc, parts.path.rstrip("/"), parts.query, parts.fragment)))
PY
}

wait_for_body_contains() {
  local label="$1"
  local needle="$2"
  local url="$3"
  local output="$4"
  for ((i = 1; i <= 60; i++)); do
    if curl -m 10 -fsS -u "$KKREPO_AUTH" "$url" -o "$output" 2>"$ARTIFACT_DIR/$label.curl.log" \
      && grep -Fq "$needle" "$output"; then
      return 0
    fi
    sleep 1
  done
  log "timed out waiting for $label to contain $needle at $url"
  return 1
}

register_cleanup_fixture() {
  CLEANUP_FIXTURE_FORMAT="$1"
  CLEANUP_FIXTURE_REPOSITORY="$2"
  CLEANUP_FIXTURE_PATTERN="$3"
  CLEANUP_FIXTURE_LABEL="$4"
}

run_registered_cleanup() {
  if [[ -z "$CLEANUP_FIXTURE_FORMAT" ]]; then
    return 0
  fi
  if [[ "${CLIENT_E2E_CLEANUP_ENABLED:-true}" != "true" ]]; then
    log "cleanup verification skipped by CLIENT_E2E_CLEANUP_ENABLED"
    return 0
  fi
  log "running cleanup Try Run and Execute for $CLEANUP_FIXTURE_LABEL"
  python3 - \
    "$KKREPO_URL" "$KKREPO_AUTH" "$CLEANUP_FIXTURE_FORMAT" \
    "$CLEANUP_FIXTURE_REPOSITORY" "$CLEANUP_FIXTURE_PATTERN" \
    "$CLEANUP_FIXTURE_LABEL" "$STAMP" "$ARTIFACT_DIR" <<'PY'
import base64
import json
import os
import pathlib
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base_url, credentials, fmt, repository, pattern, label, stamp, artifact_dir = sys.argv[1:]
authorization = "Basic " + base64.b64encode(credentials.encode("utf-8")).decode("ascii")
ca_bundle = os.environ.get("SSL_CERT_FILE") or os.environ.get("CURL_CA_BUNDLE")
ssl_context = ssl.create_default_context(cafile=ca_bundle) if ca_bundle else None


def request(method, path, payload=None, expected=(200,)):
    body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    headers = {"Authorization": authorization, "Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base_url.rstrip("/") + path, body, headers, method=method)
    try:
        open_options = {"timeout": 30}
        if ssl_context is not None:
            open_options["context"] = ssl_context
        with urllib.request.urlopen(req, **open_options) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"cleanup {method} {path} failed with HTTP {error.code}: {detail}") from error
    if status not in expected:
        raise SystemExit(f"cleanup {method} {path} returned unexpected HTTP {status}")
    return json.loads(raw) if raw else None


def await_run(run_id):
    deadline = time.monotonic() + 180
    latest = None
    while time.monotonic() < deadline:
        latest = request("GET", f"/internal/cleanup/runs/{run_id}")
        if latest["run"]["state"] in {
            "SUCCEEDED", "SUCCEEDED_TRUNCATED", "PARTIAL", "PARTIAL_LIMIT_REACHED",
            "FAILED", "CANCELLED",
        }:
            return latest
        time.sleep(0.5)
    raise SystemExit(f"cleanup run {run_id} did not finish: {latest}")


repository_view = request(
    "GET", "/internal/repositories/" + urllib.parse.quote(repository, safe=""))
policy_view = request("POST", "/internal/cleanup/policies", {
    "name": f"client-e2e-cleanup-{label}-{stamp}",
    "format": fmt,
    "notes": "real client cleanup verification",
    "criteria": {
      "patternType": "GLOB",
      "pattern": pattern,
      "publishedOlderThanDays": 0
    },
    "repositoryIds": [repository_view["id"]],
    "scanLimitPerRepository": 10000,
    "deleteLimitPerRepository": 1000,
}, expected=(201,))
policy = policy_view["policy"]

try_queued = request(
    "POST", f"/internal/cleanup/policies/{policy['id']}/runs",
    {"mode": "TRY_RUN", "expectedPolicyRevision": policy["revision"],
     "scanLimitPerRepository": 10000}, expected=(202,))
try_run = await_run(try_queued["run"]["id"])
if try_run["run"]["state"] != "SUCCEEDED" or try_run["run"]["matchedSubjects"] < 1:
    raise SystemExit(f"cleanup Try Run did not produce a complete match: {try_run}")

execute_queued = request(
    "POST", f"/internal/cleanup/policies/{policy['id']}/runs",
    {"mode": "EXECUTE", "expectedPolicyRevision": policy["revision"]}, expected=(202,))
execute_run = await_run(execute_queued["run"]["id"])
if (execute_run["run"]["state"] != "SUCCEEDED"
        or execute_run["run"]["deletedSubjects"] < 1
        or execute_run["run"]["failedSubjects"] != 0):
    raise SystemExit(f"cleanup Execute did not delete the real-client fixture cleanly: {execute_run}")

request(
    "DELETE",
    f"/internal/cleanup/policies/{policy['id']}?revision={policy['revision']}",
    expected=(204,))
report = {"policy": policy_view, "tryRun": try_run, "executeRun": execute_run}
safe_label = "".join(character if character.isalnum() or character in "-_" else "-"
                     for character in label)
pathlib.Path(artifact_dir, f"cleanup-{safe_label}.json").write_text(
    json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
PY
}

capture_rubygems_metadata() {
  local name="$1"
  local dependencies="$ARTIFACT_DIR/rubygems-dependencies.marshal"
  local specs="$ARTIFACT_DIR/rubygems-specs.4.8.gz"
  local quick="$ARTIFACT_DIR/rubygems-quick-spec.rz"
  local gem="$ARTIFACT_DIR/rubygems-package.gem"
  run_logged_output rubygems-dependencies "$dependencies" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/api/v1/dependencies?gems=$name"
  run_logged rubygems-dependencies-decode ruby -e \
    'p Marshal.load(File.binread(ARGV.fetch(0)))' "$dependencies"
  run_logged_output rubygems-specs "$specs" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/specs.4.8.gz"
  run_logged rubygems-specs-decode ruby -rzlib -e \
    'p Marshal.load(Zlib.gunzip(File.binread(ARGV.fetch(0)))).select { |row| row[0].to_s == ARGV.fetch(1) }' "$specs" "$name"
  run_logged_output rubygems-quick-spec "$quick" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/quick/Marshal.4.8/$name-1.0.0.gemspec.rz"
  run_logged_output rubygems-package "$gem" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/rubygems-group/gems/$name-1.0.0.gem"
}

test_raw() {
  local path="client-e2e/$STAMP/payload.txt"
  local payload="$WORK_DIR/raw-payload.txt"
  printf 'kkrepo raw client e2e %s\n' "$STAMP" >"$payload"
  run_logged raw-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$payload" \
    "$KKREPO_URL/repository/asset-api-raw-hosted/$path"
  run_logged_output raw-download "$ARTIFACT_DIR/raw-payload.txt" \
    curl -m 30 -fsS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/asset-api-raw-hosted/$path"
  cmp "$payload" "$ARTIFACT_DIR/raw-payload.txt"
}

test_maven() {
  need mvn
  local dir="$WORK_DIR/maven"
  local local_repo="$dir/.m2"
  local artifact="client-e2e-maven-$STAMP"
  local version="1.0.$STAMP"
  mkdir -p "$dir/src/main/java/com/example" "$local_repo"
  cat >"$dir/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.github.klboke.kkrepo.e2e</groupId>
  <artifactId>$artifact</artifactId>
  <version>$version</version>
  <properties>
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
  </properties>
  <distributionManagement>
    <repository>
      <id>kkrepo</id>
      <url>$KKREPO_URL/repository/maven-releases/</url>
    </repository>
  </distributionManagement>
</project>
EOF
  cat >"$dir/src/main/java/com/example/App.java" <<'EOF'
package com.example;
public final class App {
  public static String message() {
    return "kkrepo client e2e";
  }
}
EOF
  cat >"$dir/settings.xml" <<EOF
<settings>
  <servers>
    <server>
      <id>kkrepo</id>
      <username>$KKREPO_USER</username>
      <password>$KKREPO_PASSWORD</password>
    </server>
  </servers>
</settings>
EOF
  run_logged maven-publish mvn -B -ntp -s "$dir/settings.xml" -Dmaven.repo.local="$local_repo" -f "$dir/pom.xml" deploy
  rm -rf "$local_repo/com/github/klboke/kkrepo/e2e/$artifact"
  run_logged maven-resolve mvn -B -ntp -s "$dir/settings.xml" -Dmaven.repo.local="$local_repo" \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get \
    "-DremoteRepositories=kkrepo::::$KKREPO_URL/repository/maven-public/" \
    "-Dartifact=com.github.klboke.kkrepo.e2e:$artifact:$version"
  test -f "$local_repo/com/github/klboke/kkrepo/e2e/$artifact/$version/$artifact-$version.jar"
  wait_for_body_contains maven-metadata "$version" \
    "$KKREPO_URL/repository/maven-public/com/github/klboke/kkrepo/e2e/$artifact/maven-metadata.xml" \
    "$ARTIFACT_DIR/maven-metadata.xml"
}

test_npm() {
  need npm
  local dir="$WORK_DIR/npm"
  local install_dir="$WORK_DIR/npm-install"
  local package="@kkrepo-client-e2e/npm-$STAMP"
  local npm_registry_host token
  npm_registry_host="$(printf '%s' "$KKREPO_URL" | sed 's#^http[s]*://##')"
  token="$(create_api_key NpmToken "client e2e npm $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$dir" "$install_dir"
  cat >"$dir/package.json" <<EOF
{"name":"$package","version":"1.0.0","description":"kkrepo client e2e","main":"index.js"}
EOF
  echo 'module.exports = "kkrepo client e2e";' >"$dir/index.js"
  cat >"$dir/.npmrc" <<EOF
registry=$KKREPO_URL/repository/npm-hosted/
//$npm_registry_host/repository/npm-hosted/:_authToken=$token
//$npm_registry_host/repository/npm-group/:_authToken=$token
always-auth=true
EOF
  run_logged_in npm-publish "$dir" npm --userconfig "$dir/.npmrc" publish --registry "$KKREPO_URL/repository/npm-hosted/"
  cat >"$install_dir/package.json" <<EOF
{"name":"npm-install-$STAMP","version":"1.0.0","dependencies":{"$package":"1.0.0"}}
EOF
  run_logged npm-install npm --userconfig "$dir/.npmrc" --prefix "$install_dir" install \
    --registry "$KKREPO_URL/repository/npm-group/" --ignore-scripts
  test -f "$install_dir/node_modules/@kkrepo-client-e2e/npm-$STAMP/index.js"
  curl -m 10 -fsS -u "$KKREPO_AUTH" "$KKREPO_URL/repository/npm-group/@kkrepo-client-e2e%2fnpm-$STAMP" \
    -o "$ARTIFACT_DIR/npm-packument.json"
  grep -q '"1.0.0"' "$ARTIFACT_DIR/npm-packument.json"
}

test_pypi() {
  need python3
  need twine
  python3 -m pip --version >/dev/null
  local dir="$WORK_DIR/pypi"
  local install_dir="$WORK_DIR/pypi-install"
  local name="kkrepo_client_e2e_pypi_$STAMP"
  mkdir -p "$dir/src/$name" "$install_dir"
  cat >"$dir/pyproject.toml" <<EOF
[build-system]
requires = ["setuptools>=68", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "$name"
version = "1.0.0"
description = "kkrepo client e2e"
requires-python = ">=3.8"
EOF
  echo 'VALUE = "kkrepo client e2e"' >"$dir/src/$name/__init__.py"
  run_logged pypi-build python3 -m build "$dir" --wheel --outdir "$dir/dist"
  run_logged pypi-upload twine upload --non-interactive \
    --repository-url "$KKREPO_URL/repository/pypi-hosted/" \
    -u "$KKREPO_USER" -p "$KKREPO_PASSWORD" "$dir"/dist/*.whl
  # Hosted simple indexes are rebuilt through the shared marker queue. Wait for the
  # uploaded release to become visible before asking pip to resolve it, otherwise a
  # faster client can race a healthy worker on a slower database backend.
  wait_for_body_contains pypi-simple "$name-1.0.0" \
    "$KKREPO_URL/repository/pypi-group/simple/$name/" \
    "$ARTIFACT_DIR/pypi-simple.html"
  run_logged pypi-install python3 -m pip install --disable-pip-version-check --no-deps \
    --target "$install_dir" \
    --index-url "$KKREPO_AUTH_URL/repository/pypi-group/simple/" \
    "$name==1.0.0"
  test -f "$install_dir/$name/__init__.py"
}

test_go() {
  need go
  local dir="$WORK_DIR/go"
  mkdir -p "$dir"
  cat >"$dir/go.mod" <<'EOF'
module kkrepo-client-e2e.local/probe

go 1.22

require rsc.io/quote v1.5.2
EOF
  # Go refuses userinfo credentials on explicit HTTP module proxy URLs; the
  # disposable kkrepo fixture keeps anonymous read enabled for this resolve-only flow.
  run_logged go-download env \
    GOPROXY="$KKREPO_URL/repository/go-proxy/" \
    GONOSUMDB=rsc.io/quote \
    GOSUMDB=off \
    GOMODCACHE="$dir/gomodcache" \
    GOCACHE="$dir/gocache" \
    go mod download -json rsc.io/quote@v1.5.2
  test -f "$dir/gomodcache/cache/download/rsc.io/quote/@v/v1.5.2.info"
  test -f "$dir/gomodcache/cache/download/rsc.io/quote/@v/v1.5.2.mod"
  test -f "$dir/gomodcache/cache/download/rsc.io/quote/@v/v1.5.2.zip"
}

test_helm() {
  need helm
  local dir="$WORK_DIR/helm"
  local chart="kkrepo-client-e2e-helm-$STAMP"
  mkdir -p "$dir"
  run_logged helm-create helm create "$dir/$chart"
  python3 - "$dir/$chart/Chart.yaml" <<'PY'
import sys
path = sys.argv[1]
data = []
for line in open(path, encoding="utf-8"):
    if line.startswith("version:"):
        data.append("version: 1.0.0\n")
    else:
        data.append(line)
open(path, "w", encoding="utf-8").writelines(data)
PY
  run_logged helm-package helm package "$dir/$chart" --destination "$dir/dist"
  run_logged helm-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$dir/dist/$chart-1.0.0.tgz" \
    "$KKREPO_URL/repository/helm-hosted/$chart-1.0.0.tgz"
  wait_for_body_contains helm-index "$chart" \
    "$KKREPO_URL/repository/helm-hosted/index.yaml" \
    "$ARTIFACT_DIR/helm-index.yaml"
  run_logged helm-repo-add helm repo add "kkrepo-e2e-$STAMP" "$KKREPO_URL/repository/helm-hosted" \
    --username "$KKREPO_USER" --password "$KKREPO_PASSWORD"
  run_logged helm-repo-update helm repo update
  mkdir -p "$dir/pulled"
  run_logged helm-pull helm pull "kkrepo-e2e-$STAMP/$chart" --version 1.0.0 --destination "$dir/pulled"
  test -f "$dir/pulled/$chart-1.0.0.tgz"
}

test_cargo() {
  need cargo
  local dir="$WORK_DIR/cargo"
  local crate="kkrepo_client_e2e_cargo_$STAMP"
  local crate_dir="$dir/$crate"
  local cargo_home="$WORK_DIR/cargo-home"
  local cargo_target="$WORK_DIR/cargo-target"
  local token
  token="$(create_api_key CargoToken "client e2e cargo $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$crate_dir/src" "$crate_dir/.cargo" "$cargo_home" "$cargo_target"
  cat >"$crate_dir/Cargo.toml" <<EOF
[package]
name = "$crate"
version = "0.1.0"
edition = "2021"
description = "kkrepo client e2e"
license = "MIT"
repository = "https://example.invalid/kkrepo-client-e2e"

[lib]
path = "src/lib.rs"
EOF
  cat >"$crate_dir/src/lib.rs" <<'EOF'
pub fn message() -> &'static str {
    "kkrepo client e2e"
}
EOF
  cat >"$crate_dir/.cargo/config.toml" <<EOF
[registry]
global-credential-providers = ["cargo:token"]

[registries.kkrepo]
index = "sparse+$KKREPO_URL/repository/cargo-hosted/"
EOF
  run_logged_in cargo-publish "$crate_dir" env \
    CARGO_HOME="$cargo_home" \
    CARGO_TARGET_DIR="$cargo_target" \
    CARGO_REGISTRIES_KKREPO_TOKEN="$token" \
    cargo publish \
    --registry kkrepo --token "$token" --allow-dirty --no-verify
  local fetch_dir="$WORK_DIR/cargo-fetch"
  run_logged cargo-fetch-new cargo new --bin "$fetch_dir"
  mkdir -p "$fetch_dir/.cargo"
  cat >"$fetch_dir/.cargo/config.toml" <<EOF
[registry]
global-credential-providers = ["cargo:token"]

[registries.kkrepo]
index = "sparse+$KKREPO_URL/repository/cargo-group/"
EOF
  echo "$crate = { version = \"0.1.0\", registry = \"kkrepo\" }" >>"$fetch_dir/Cargo.toml"
  run_logged_in cargo-fetch "$fetch_dir" env \
    CARGO_HOME="$cargo_home" \
    CARGO_TARGET_DIR="$cargo_target-fetch" \
    CARGO_REGISTRIES_KKREPO_TOKEN="$token" \
    cargo fetch
  run_logged_output_in cargo-metadata "$fetch_dir" "$ARTIFACT_DIR/cargo-metadata.json" env \
    CARGO_HOME="$cargo_home" \
    CARGO_TARGET_DIR="$cargo_target-fetch" \
    CARGO_REGISTRIES_KKREPO_TOKEN="$token" \
    cargo metadata --format-version 1
  grep -q "\"$crate\"" "$ARTIFACT_DIR/cargo-metadata.json"
}

test_pub() {
  if ! command -v dart >/dev/null 2>&1; then
    log "dart not found; Pub client flow skipped"
    return 0
  fi
  local dir="$WORK_DIR/pub"
  local package_dir="$dir/package"
  local consumer_dir="$dir/consumer"
  local pub_cache="$WORK_DIR/pub-cache"
  local pub_home="$WORK_DIR/pub-home"
  local package="kkrepo_client_e2e_pub_$STAMP"
  local hosted_url="$KKREPO_URL/repository/pub-hosted"
  local group_url="$KKREPO_URL/repository/pub-group"
  local token
  token="$(create_api_key PubToken "client e2e pub $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$package_dir/lib" "$consumer_dir/bin" "$pub_cache" "$pub_home/.config"

  cat >"$package_dir/pubspec.yaml" <<EOF
name: $package
version: 1.0.0
description: kkRepo client E2E Pub package.
publish_to: "$hosted_url"
environment:
  sdk: ">=3.0.0 <4.0.0"
EOF
  cat >"$package_dir/README.md" <<'EOF'
# kkRepo client E2E Pub package

This package is generated by the kkRepo client E2E suite.
EOF
  cat >"$package_dir/CHANGELOG.md" <<'EOF'
## 1.0.0

- Initial client E2E package.
EOF
  cat >"$package_dir/LICENSE" <<'EOF'
MIT License
EOF
  cat >"$package_dir/lib/$package.dart" <<'EOF'
String message() => 'kkrepo client e2e';
EOF

  run_logged pub-token-hosted env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub token add "$hosted_url" --env-var KKREPO_PUB_TOKEN
  run_logged pub-token-group env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub token add "$group_url" --env-var KKREPO_PUB_TOKEN
  run_logged_in pub-publish "$package_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub publish --force

  cat >"$consumer_dir/pubspec.yaml" <<EOF
name: kkrepo_client_e2e_pub_consumer_$STAMP
version: 1.0.0
environment:
  sdk: ">=3.0.0 <4.0.0"
dependencies:
  $package:
    hosted:
      url: "$group_url"
      name: "$package"
    version: "1.0.0"
EOF
  cat >"$consumer_dir/bin/check.dart" <<EOF
import 'package:$package/$package.dart';

void main() {
  if (message() != 'kkrepo client e2e') {
    throw StateError('unexpected Pub package message');
  }
}
EOF
  run_logged_in pub-get "$consumer_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart pub get
  run_logged_in pub-run "$consumer_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    KKREPO_PUB_TOKEN="$token" \
    dart run bin/check.dart
  cp "$consumer_dir/pubspec.lock" "$ARTIFACT_DIR/pubspec.lock"

  run_logged_output pub-metadata "$ARTIFACT_DIR/pub-metadata.json" \
    curl -m 10 -fsS -H "Authorization: Bearer $token" \
    "$group_url/api/packages/$package"
  python3 - "$ARTIFACT_DIR/pub-metadata.json" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    data = json.load(source)
versions = {entry.get("version"): entry for entry in data.get("versions", [])}
entry = versions.get("1.0.0")
if entry is None:
    raise SystemExit("Pub metadata did not include version 1.0.0")
sha256 = entry.get("archive_sha256")
if not isinstance(sha256, str) or len(sha256) != 64:
    raise SystemExit("Pub metadata did not include a SHA-256 archive hash")
PY
  test_flutter_pub "$package" "$group_url" "$token" "$pub_home" "$pub_cache"
}

test_composer() {
  need composer
  need php
  local dir="$WORK_DIR/composer"
  local package_dir="$dir/package"
  local consumer_dir="$dir/consumer"
  local composer_home="$WORK_DIR/composer-home"
  local package="kkrepo-client-e2e/package-$STAMP"
  local archive="$dir/package-$STAMP.zip"
  local group_url="$KKREPO_URL/repository/composer-group"
  local auth_host composer_auth
  auth_host="$(python3 - "$KKREPO_URL" <<'PY'
import sys
from urllib.parse import urlsplit
print(urlsplit(sys.argv[1]).netloc)
PY
)"
  composer_auth="$(python3 - "$auth_host" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import json
import sys
host, user, password = sys.argv[1:]
print(json.dumps({"http-basic": {host: {"username": user, "password": password}}}, separators=(",", ":")))
PY
)"
  add_redaction_value "$composer_auth"
  mkdir -p "$package_dir/src" "$consumer_dir" "$composer_home"
  cat >"$package_dir/composer.json" <<EOF
{
  "name": "$package",
  "version": "1.0.0",
  "description": "kkRepo Composer client E2E package",
  "type": "library",
  "license": "MIT",
  "require": {"psr/log": "^3.0"},
  "autoload": {"psr-4": {"KkRepoClientE2E\\\\": "src/"}}
}
EOF
  cat >"$package_dir/src/Message.php" <<'EOF'
<?php
namespace KkRepoClientE2E;
final class Message {
    public static function value(): string { return 'kkrepo client e2e'; }
}
EOF
  python3 - "$package_dir" "$archive" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
archive = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            target.write(path, "package/" + path.relative_to(source).as_posix())
PY
  run_logged_in composer-validate "$package_dir" composer validate --strict --no-check-publish --no-check-version
  run_logged composer-upload curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -F "composer.asset=@$archive;type=application/zip" \
    -F "composer.name=$package" \
    -F "composer.version=1.0.0" \
    "$KKREPO_URL/service/rest/v1/components?repository=composer-hosted"

  cat >"$consumer_dir/composer.json" <<EOF
{
  "name": "kkrepo-client-e2e/consumer-$STAMP",
  "repositories": [
    {"type": "composer", "url": "$group_url", "canonical": true},
    {"packagist.org": false}
  ],
  "require": {
    "$package": "1.0.0"
  },
  "config": {
    "secure-http": false,
    "allow-plugins": {}
  }
}
EOF
  run_logged_in composer-install "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer install --prefer-dist --no-interaction --no-plugins --no-scripts
  run_logged_in composer-show "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer show "$package" --locked --no-interaction
  run_logged_in composer-show-transitive "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer show psr/log --locked --no-interaction
  run_logged_in composer-runtime "$consumer_dir" php -r \
    'require "vendor/autoload.php"; if (KkRepoClientE2E\Message::value() !== "kkrepo client e2e" || Psr\Log\LogLevel::INFO !== "info") { exit(1); }'

  local auth_consumer_dir="$dir/auth-consumer"
  local auth_composer_home="$WORK_DIR/composer-auth-home"
  local wrong_composer_auth auth_status
  wrong_composer_auth="$(python3 - "$auth_host" "$KKREPO_USER" <<'PY'
import json
import sys
host, user = sys.argv[1:]
print(json.dumps({"http-basic": {host: {"username": user, "password": "definitely-wrong"}}}, separators=(",", ":")))
PY
)"
  mkdir -p "$auth_consumer_dir" "$auth_composer_home"
  cp "$consumer_dir/composer.json" "$auth_consumer_dir/composer.json"
  set_anonymous_access false
  if run_logged_in composer-auth-rejected "$auth_consumer_dir" env \
      COMPOSER_HOME="$auth_composer_home" \
      COMPOSER_AUTH="$wrong_composer_auth" \
      composer --no-cache install --prefer-dist --no-interaction --no-plugins --no-scripts; then
    auth_status=0
  else
    auth_status=$?
  fi
  set_anonymous_access true
  if [[ "$auth_status" -eq 0 ]]; then
    log "Composer install unexpectedly succeeded with invalid credentials"
    exit 1
  fi
  if ! grep -Eqi '401|authentication required|invalid credentials' "$ARTIFACT_DIR/composer-auth-rejected.log"; then
    log "Composer invalid-credentials failure did not report authentication rejection"
    exit 1
  fi

  cp "$consumer_dir/composer.lock" "$ARTIFACT_DIR/composer.lock"
  run_logged_in composer-clear-cache "$consumer_dir" env \
    COMPOSER_HOME="$composer_home" \
    COMPOSER_AUTH="$composer_auth" \
    composer clear-cache --no-interaction
  rm -rf "$consumer_dir/vendor" "$composer_home/cache"
  local composer_proxy_remote replay_status
  composer_proxy_remote="$(repository_proxy_remote composer-proxy)"
  set_repository_proxy_remote composer-proxy "https://example.com/kkrepo-composer-client-e2e-offline/"
  if run_logged_in composer-lock-replay-offline "$consumer_dir" env \
      COMPOSER_HOME="$composer_home" \
      COMPOSER_AUTH="$composer_auth" \
      composer install --prefer-dist --no-interaction --no-plugins --no-scripts; then
    replay_status=0
  else
    replay_status=$?
  fi
  set_repository_proxy_remote composer-proxy "$composer_proxy_remote"
  if [[ "$replay_status" -ne 0 ]]; then
    log "Composer lock replay failed after client cache clear with proxy upstream unavailable"
    exit "$replay_status"
  fi
  test -f "$consumer_dir/vendor/kkrepo-client-e2e/package-$STAMP/src/Message.php"
  test -f "$consumer_dir/vendor/psr/log/src/LogLevel.php"
  run_logged_output composer-packages "$ARTIFACT_DIR/composer-packages.json" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" "$group_url/packages.json"
  run_logged_output composer-hosted-metadata "$ARTIFACT_DIR/composer-hosted-metadata.json" \
    curl -m 10 -fsS -u "$KKREPO_AUTH" "$group_url/p2/$package.json"
  grep -q '"version":"1.0.0"' "$ARTIFACT_DIR/composer-hosted-metadata.json"
}

test_flutter_pub() {
  local package="$1"
  local group_url="$2"
  local token="$3"
  local pub_home="$4"
  local pub_cache="$5"
  if ! command -v flutter >/dev/null 2>&1; then
    log "flutter not found; Flutter Pub client flow skipped"
    return 0
  fi
  local flutter_dir="$WORK_DIR/flutter-pub"
  mkdir -p "$flutter_dir"
  cat >"$flutter_dir/pubspec.yaml" <<EOF
name: kkrepo_client_e2e_flutter_pub_$STAMP
version: 1.0.0
environment:
  sdk: ">=3.0.0 <4.0.0"
dependencies:
  flutter:
    sdk: flutter
  path: ^1.9.0
  $package:
    hosted:
      url: "$group_url"
      name: "$package"
    version: "1.0.0"
EOF
  run_logged_in flutter-pub-get "$flutter_dir" env \
    HOME="$pub_home" \
    XDG_CONFIG_HOME="$pub_home/.config" \
    PUB_CACHE="$pub_cache" \
    PUB_HOSTED_URL="$group_url" \
    KKREPO_PUB_TOKEN="$token" \
    flutter pub get
  cp "$flutter_dir/pubspec.lock" "$ARTIFACT_DIR/flutter-pubspec.lock"
  grep -q "$package" "$ARTIFACT_DIR/flutter-pubspec.lock"
  grep -q "path" "$ARTIFACT_DIR/flutter-pubspec.lock"
}

test_nuget() {
  need dotnet
  local dir="$WORK_DIR/nuget"
  local restore_dir="$WORK_DIR/nuget-restore"
  local packages_dir="$WORK_DIR/nuget-packages"
  local package="KkRepo.ClientE2E.NuGet.$STAMP"
  local token
  token="$(create_api_key NuGetApiKey "client e2e nuget $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$dir" "$restore_dir" "$packages_dir"
  write_nuget_config "$dir/NuGet.Config" "kkrepoHosted" "$KKREPO_URL/repository/nuget-hosted/index.json"
  write_nuget_config "$restore_dir/NuGet.Config" "kkrepoGroup" "$KKREPO_URL/repository/nuget-group/index.json"
  run_logged nuget-new dotnet new classlib -n "$package" -o "$dir/$package" --framework net8.0
  run_logged nuget-pack dotnet pack "$dir/$package/$package.csproj" \
    -p:PackageId="$package" -p:Version=1.0.0 -o "$dir/out"
  run_logged_in nuget-push "$dir" dotnet nuget push "$dir/out/$package.1.0.0.nupkg" \
    --source kkrepoHosted \
    --api-key "$token" \
    --timeout 120
  run_logged nuget-consumer-new dotnet new console -n Consumer -o "$restore_dir/Consumer" --framework net8.0
  run_logged_in nuget-add-package "$restore_dir/Consumer" dotnet add package "$package" \
    --version 1.0.0 \
    --package-directory "$packages_dir"
  run_logged nuget-restore dotnet restore "$restore_dir/Consumer/Consumer.csproj" \
    --configfile "$restore_dir/NuGet.Config" \
    --packages "$packages_dir"
  test -f "$packages_dir/$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]')/1.0.0/$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]').1.0.0.nupkg"
}

test_rubygems() {
  need ruby
  need gem
  local dir="$WORK_DIR/rubygems"
  local name="kkrepo_client_e2e_rubygems_$STAMP"
  local gem_home="$WORK_DIR/gem-home"
  local gem_user_home="$WORK_DIR/rubygems-home"
  local gem_credentials="$gem_user_home/.gem/credentials"
  local token
  token="$(create_api_key RubyGemsApiKey "client e2e rubygems $STAMP")"
  add_redaction_value "$token"
  mkdir -p "$dir/lib" "$gem_home" "$gem_user_home/.gem"
  printf -- '---\n:kkrepo: %s\n' "$token" >"$gem_credentials"
  chmod 0600 "$gem_credentials"
  cat >"$dir/$name.gemspec" <<EOF
Gem::Specification.new do |spec|
  spec.name = "$name"
  spec.version = "1.0.0"
  spec.summary = "kkrepo client e2e"
  spec.authors = ["kkrepo"]
  spec.files = ["lib/$name.rb"]
  spec.require_paths = ["lib"]
end
EOF
  echo 'module KkRepoClientE2ERubyGems; VALUE = "kkrepo client e2e"; end' >"$dir/lib/$name.rb"
  run_logged_in rubygems-build "$dir" gem build "$name.gemspec" --output "$dir/$name-1.0.0.gem"
  run_logged rubygems-push env HOME="$gem_user_home" gem push "$dir/$name-1.0.0.gem" \
    --host "$KKREPO_URL/repository/rubygems-hosted/" \
    --key kkrepo
  wait_for_body_contains rubygems-versions "$name" \
    "$KKREPO_URL/repository/rubygems-group/versions" \
    "$ARTIFACT_DIR/rubygems-versions"
  capture_rubygems_metadata "$name"
  run_logged rubygems-install env GEM_HOME="$gem_home" GEM_PATH="$gem_home" \
    gem install "$name" --version 1.0.0 --clear-sources \
    --source "$KKREPO_AUTH_URL/repository/rubygems-group/" \
    --no-document --verbose
  test -f "$gem_home/gems/$name-1.0.0/lib/$name.rb"
}

test_yum() {
  need docker
  local dir="$WORK_DIR/yum"
  local rpm_url="${CLIENT_E2E_YUM_FIXTURE_URL:-https://dl.fedoraproject.org/pub/epel/9/Everything/x86_64/Packages/6/6tunnel-0.13-1.el9.x86_64.rpm}"
  local rpm="$dir/$(basename "$rpm_url")"
  local upload_path="Packages/client-e2e-$STAMP/$(basename "$rpm_url")"
  mkdir -p "$dir"
  run_logged yum-fixture curl -L -m 120 -fsS "$rpm_url" -o "$rpm"
  run_logged yum-upload curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$rpm" \
    "$KKREPO_URL/repository/yum-hosted/$upload_path"
  wait_for_body_contains yum-repomd "primary" \
    "$KKREPO_URL/repository/yum-hosted/repodata/repomd.xml" \
    "$ARTIFACT_DIR/yum-repomd.xml"
  run_logged yum-dnf-download docker run --rm --network host \
    -v "$dir:/work" \
    fedora:41 \
    bash -lc "set -euo pipefail
cat >/etc/yum.repos.d/kkrepo-client-e2e.repo <<'EOF'
[kkrepo-client-e2e]
name=kkrepo client e2e
baseurl=$KKREPO_AUTH_URL/repository/yum-hosted/
enabled=1
gpgcheck=0
EOF
dnf -y --setopt=metadata_expire=0 makecache --repo kkrepo-client-e2e
dnf -y download --repo kkrepo-client-e2e --destdir /work 6tunnel"
  ls "$dir"/6tunnel-*.rpm >/dev/null
}

test_conan() {
  if ! command -v "$CONAN_BIN" >/dev/null 2>&1; then
    log "Conan client E2E skipped: set CONAN_E2E_BIN or install Conan 2"
    return 0
  fi
  local major
  major="$("$CONAN_BIN" --version | sed -E 's/.* ([0-9]+)\..*/\1/')"
  if [[ "$major" != "2" ]]; then
    log "Conan client E2E requires Conan 2; found $("$CONAN_BIN" --version)"
    return 2
  fi

  local dir="$WORK_DIR/conan"
  local publish_home="$dir/publish-home"
  local consume_home="$dir/consume-home"
  local proxy_home="$dir/proxy-home"
  local name="kkrepo_conan_e2e_${STAMP}"
  local reference="$name/1.0.0@kkrepo/stable"
  local list_json="$ARTIFACT_DIR/conan-group-list.json"
  local proxy_list_json="$ARTIFACT_DIR/conan-proxy-list.json"
  local removed_json="$ARTIFACT_DIR/conan-hosted-after-remove.json"
  local proxy_exact_reference proxy_info_path
  mkdir -p "$dir/include" "$publish_home" "$consume_home"
  cat >"$dir/conanfile.py" <<'PY'
from conan import ConanFile
from conan.tools.files import copy
import os


class KkRepoConanE2E(ConanFile):
    settings = "os", "arch", "compiler", "build_type"
    exports_sources = "include/*"

    def package(self):
        copy(self, "*.h", src=os.path.join(self.source_folder, "include"),
             dst=os.path.join(self.package_folder, "include"))

    def package_info(self):
        self.cpp_info.includedirs = ["include"]
PY
  printf '#define KKREPO_CONAN_E2E "%s"\n' "$STAMP" >"$dir/include/kkrepo_conan_e2e.h"

  run_logged conan-version "$CONAN_BIN" --version
  run_logged conan-publish-profile env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" profile detect --force
  run_logged conan-hosted-remote env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" remote add kkrepo-hosted \
    "$KKREPO_URL/repository/$CONAN_HOSTED_REPOSITORY" --force
  run_logged conan-hosted-login env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" remote login kkrepo-hosted "$KKREPO_USER" -p "$KKREPO_PASSWORD"
  run_logged_in conan-create "$dir" env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" create . --name="$name" --version=1.0.0 \
    --user=kkrepo --channel=stable
  run_logged conan-upload env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" upload "$reference:*" --remote=kkrepo-hosted --confirm

  run_logged conan-consume-profile env CONAN_HOME="$consume_home" \
    "$CONAN_BIN" profile detect --force
  run_logged conan-group-remote env CONAN_HOME="$consume_home" \
    "$CONAN_BIN" remote add kkrepo-group \
    "$KKREPO_URL/repository/$CONAN_GROUP_REPOSITORY" --force
  run_logged conan-group-login env CONAN_HOME="$consume_home" \
    "$CONAN_BIN" remote login kkrepo-group "$KKREPO_USER" -p "$KKREPO_PASSWORD"
  run_logged_output conan-group-list "$list_json" env CONAN_HOME="$consume_home" \
    "$CONAN_BIN" list "$reference#*:*#*" --remote=kkrepo-group --format=json
  python3 - "$list_json" "$reference" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if sys.argv[2] not in json.dumps(payload, sort_keys=True):
    raise SystemExit(f"Conan group list did not contain {sys.argv[2]}: {payload}")
PY
  run_logged conan-download env CONAN_HOME="$consume_home" \
    "$CONAN_BIN" download "$reference:*" --remote=kkrepo-group
  run_logged conan-install env CONAN_HOME="$consume_home" \
    "$CONAN_BIN" install --requires="$reference" --remote=kkrepo-group --build=never

  run_logged conan-proxy-remote env CONAN_HOME="$proxy_home" \
    "$CONAN_BIN" remote add kkrepo-proxy \
    "$KKREPO_URL/repository/$CONAN_PROXY_REPOSITORY" --force
  run_logged conan-proxy-login env CONAN_HOME="$proxy_home" \
    "$CONAN_BIN" remote login kkrepo-proxy "$KKREPO_USER" -p "$KKREPO_PASSWORD"
  run_logged_output conan-proxy-list "$proxy_list_json" env CONAN_HOME="$proxy_home" \
    "$CONAN_BIN" list "$CONAN_PROXY_REFERENCE#*:*#*" \
    --remote=kkrepo-proxy --format=json
  IFS=$'\t' read -r proxy_exact_reference proxy_info_path < <(
    python3 - "$proxy_list_json" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
remote = payload.get("kkrepo-proxy") or {}
if not remote:
    raise SystemExit(f"Conan proxy returned no recipes: {payload}")
recipe, recipe_value = next(iter(remote.items()))
revisions = recipe_value.get("revisions") or {}
if not revisions:
    raise SystemExit(f"Conan proxy returned no recipe revisions: {payload}")
rrev, revision_value = max(
    revisions.items(), key=lambda item: item[1].get("timestamp") or 0)
packages = revision_value.get("packages") or {}
if not packages:
    raise SystemExit(f"Conan proxy returned no packages: {payload}")
preferred = [
    item for item in packages.items()
    if item[1].get("info", {}).get("settings", {}).get("os") == "Linux"
    and item[1].get("info", {}).get("settings", {}).get("arch") == "x86_64"
]
package_id, package_value = (preferred or list(packages.items()))[0]
package_revisions = package_value.get("revisions") or {}
if not package_revisions:
    raise SystemExit(f"Conan proxy returned no package revisions: {payload}")
prev, _ = max(
    package_revisions.items(), key=lambda item: item[1].get("timestamp") or 0)
base, separator, owner = recipe.partition("@")
name, version = base.split("/", 1)
user, channel = owner.split("/", 1) if separator else ("_", "_")
print(
    f"{recipe}#{rrev}:{package_id}#{prev}\t"
    f"{name}/{version}/{user}/{channel}/revisions/{rrev}/packages/"
    f"{package_id}/revisions/{prev}"
)
PY
  )
  [[ -n "$proxy_exact_reference" && -n "$proxy_info_path" ]] || {
    log "Conan proxy selection did not produce an exact package revision"
    return 1
  }
  run_logged_output conan-proxy-info "$ARTIFACT_DIR/conan-proxy-conaninfo.txt" \
    curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$CONAN_PROXY_REPOSITORY/v2/conans/$proxy_info_path/files/conaninfo.txt"
  run_logged conan-proxy-download env CONAN_HOME="$proxy_home" \
    "$CONAN_BIN" download "$proxy_exact_reference" --remote=kkrepo-proxy

  run_logged conan-remove-package-revisions env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" remove "$reference:*" --remote=kkrepo-hosted --confirm
  run_logged_output conan-hosted-after-remove "$removed_json" env CONAN_HOME="$publish_home" \
    "$CONAN_BIN" list "$reference#*:*#*" --remote=kkrepo-hosted --format=json
  python3 - "$removed_json" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
def contains_packages(value):
    if isinstance(value, dict):
        if value.get("packages"):
            return True
        return any(contains_packages(child) for child in value.values())
    if isinstance(value, list):
        return any(contains_packages(child) for child in value)
    return False

if contains_packages(payload):
    raise SystemExit(f"Conan package revisions remain after remove: {payload}")
PY
}

test_conda() {
  need "$CONDA_BIN"
  local dir="$WORK_DIR/conda"
  local package="kkrepo_conda_e2e_${STAMP}"
  local version="1.0.0"
  local build="0"
  local subdir="${CONDA_E2E_SUBDIR:-linux-64}"
  local filename="$package-$version-$build.tar.bz2"
  local archive="$dir/$filename"
  local marker="kkrepo Conda client E2E $STAMP"
  local marker_path="share/kkrepo-conda-e2e/$package.txt"
  local condarc="$dir/condarc"
  local hosted_search="$ARTIFACT_DIR/conda-hosted-search.json"
  local hosted_list="$ARTIFACT_DIR/conda-hosted-list.json"
  local proxy_search="$ARTIFACT_DIR/conda-proxy-search.json"
  local proxy_list="$ARTIFACT_DIR/conda-proxy-list.json"
  local hosted_prefix="$dir/hosted-prefix"
  local proxy_prefix="$dir/proxy-prefix"
  local hosted_channel="$KKREPO_URL/repository/$CONDA_GROUP_REPOSITORY"
  local proxy_channel="$KKREPO_URL/repository/$CONDA_PROXY_REPOSITORY"
  local proxy_name="${CONDA_E2E_PROXY_PACKAGE:-tzdata}"
  local proxy_spec
  local -a conda_env

  mkdir -p "$dir/pkgs" "$dir/envs"
  python3 "$PROJECT_ROOT/scripts/ci/create-conda-e2e-fixture.py" \
    --name "$package" \
    --version "$version" \
    --build "$build" \
    --subdir "$subdir" \
    --marker "$marker" \
    --output "$archive" \
    >"$ARTIFACT_DIR/conda-hosted-fixture.json"
  cat >"$condarc" <<EOF
channels: []
default_channels: []
channel_priority: strict
show_channel_urls: true
auto_activate_base: false
pkgs_dirs:
  - $dir/pkgs
envs_dirs:
  - $dir/envs
EOF
  conda_env=(
    env
    "CONDARC=$condarc"
    "CONDA_PKGS_DIRS=$dir/pkgs"
    "CONDA_ENVS_PATH=$dir/envs"
    "CONDA_SUBDIR=$subdir"
  )

  run_logged conda-hosted-upload curl -m 60 --fail-with-body -sS \
    -u "$KKREPO_AUTH" \
    -X PUT \
    -H "Content-Type: application/x-tar" \
    --data-binary "@$archive" \
    "$KKREPO_URL/repository/$CONDA_HOSTED_REPOSITORY/$subdir/$filename"
  wait_for_body_contains conda-hosted-repodata "$filename" \
    "$KKREPO_URL/repository/$CONDA_HOSTED_REPOSITORY/$subdir/repodata.json" \
    "$ARTIFACT_DIR/conda-hosted-repodata.json"

  run_logged_output conda-hosted-search "$hosted_search" \
    "${conda_env[@]}" "$CONDA_BIN" search --json --override-channels \
    --channel "$hosted_channel" "$package=$version=$build"
  python3 - "$hosted_search" "$package" "$version" "$build" "$subdir" <<'PY'
import json
import pathlib
import sys

path, name, version, build, subdir = sys.argv[1:6]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
records = payload.get(name) or []
if not any(
    record.get("version") == version
    and record.get("build") == build
    and record.get("subdir") == subdir
    for record in records
):
    raise SystemExit(f"Conda group search did not find the hosted fixture: {payload}")
PY
  run_logged_output conda-hosted-create "$ARTIFACT_DIR/conda-hosted-create.json" \
    "${conda_env[@]}" "$CONDA_BIN" create --json --yes --no-deps \
    --prefix "$hosted_prefix" --override-channels \
    --channel "$hosted_channel" "$package=$version=$build"
  grep -Fxq "$marker" "$hosted_prefix/$marker_path"
  run_logged_output conda-hosted-list "$hosted_list" \
    "${conda_env[@]}" "$CONDA_BIN" list --json --prefix "$hosted_prefix"
  python3 - "$hosted_list" "$package" "$version" "$build" <<'PY'
import json
import pathlib
import sys

path, name, version, build = sys.argv[1:5]
records = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
if not any(
    record.get("name") == name
    and record.get("version") == version
    and record.get("build_string") == build
    for record in records
):
    raise SystemExit(f"Conda group install did not register the hosted fixture: {records}")
PY

  run_logged_output conda-proxy-search "$proxy_search" \
    "${conda_env[@]}" "$CONDA_BIN" search --json --override-channels \
    --channel "$proxy_channel" "$proxy_name"
  proxy_spec="$(python3 - "$proxy_search" "$proxy_name" <<'PY'
import json
import pathlib
import sys

path, name = sys.argv[1:3]
payload = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
records = [
    record for record in payload.get(name) or []
    if record.get("subdir") == "noarch"
    and record.get("noarch") == "generic"
    and not (record.get("depends") or [])
    and record.get("version")
    and record.get("build")
]
if not records:
    raise SystemExit(
        f"Conda proxy search returned no dependency-free noarch generic package for {name}: "
        f"{payload}"
    )
record = min(records, key=lambda item: (int(item.get("size") or 0), item["version"], item["build"]))
print(f"{name}={record['version']}={record['build']}")
PY
)"
  run_logged_output conda-proxy-create "$ARTIFACT_DIR/conda-proxy-create.json" \
    "${conda_env[@]}" "$CONDA_BIN" create --json --yes --no-deps \
    --prefix "$proxy_prefix" --override-channels \
    --channel "$proxy_channel" "$proxy_spec"
  run_logged_output conda-proxy-list "$proxy_list" \
    "${conda_env[@]}" "$CONDA_BIN" list --json --prefix "$proxy_prefix"
  python3 - "$proxy_list" "$proxy_name" <<'PY'
import json
import pathlib
import sys

path, name = sys.argv[1:3]
records = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
if not any(record.get("name") == name for record in records):
    raise SystemExit(f"Conda proxy install did not register {name}: {records}")
PY
}

test_docker_oci() {
  need docker
  wait_for_docker_registry
  local image="kkrepo-client-e2e/docker-oci"
  local ref="$KKREPO_DOCKER_HOSTED_REGISTRY/$image:$STAMP"
  run_logged docker-login bash -lc "printf '%s\n' \"$KKREPO_PASSWORD\" | docker login '$KKREPO_DOCKER_HOSTED_REGISTRY' --username '$KKREPO_USER' --password-stdin"
  run_logged docker-pull-source docker pull alpine:3.20
  run_logged docker-push bash -lc "docker tag alpine:3.20 '$ref' && docker push '$ref'"
  run_logged docker-remove-local docker image rm "$ref"
  run_logged docker-pull docker pull "$ref"
  docker image inspect "$ref" >"$ARTIFACT_DIR/docker-image-inspect.json"
  if command -v oras >/dev/null 2>&1; then
    local oras_dir="$WORK_DIR/oras"
    mkdir -p "$oras_dir/pull"
    echo "kkrepo oci artifact $STAMP" >"$oras_dir/payload.txt"
    run_logged oras-login bash -lc "printf '%s\n' \"$KKREPO_PASSWORD\" | oras login --plain-http '$KKREPO_DOCKER_HOSTED_REGISTRY' --username '$KKREPO_USER' --password-stdin"
    run_logged_in oras-push "$oras_dir" oras push --plain-http "$KKREPO_DOCKER_HOSTED_REGISTRY/$image:oras-$STAMP" "payload.txt:application/vnd.kkrepo.client-e2e"
    run_logged_in oras-pull "$oras_dir" oras pull --plain-http "$KKREPO_DOCKER_HOSTED_REGISTRY/$image:oras-$STAMP" -o "$oras_dir/pull"
    test -f "$oras_dir/pull/payload.txt"
  else
    log "oras not found; Docker image client flow completed, ORAS artifact flow skipped"
  fi
}

test_terraform() {
  need zip
  local terraform_013="${TERRAFORM_013_BIN:-}"
  local terraform_current="${TERRAFORM_CURRENT_BIN:-}"
  if [[ -z "$terraform_013" || ! -x "$terraform_013" ]]; then
    log "TERRAFORM_013_BIN must point to an executable Terraform 0.13 binary"
    exit 2
  fi
  if [[ -z "$terraform_current" || ! -x "$terraform_current" ]]; then
    log "TERRAFORM_CURRENT_BIN must point to an executable current stable Terraform binary"
    exit 2
  fi

  local dir="$WORK_DIR/terraform"
  local fixture_version="1.0.$STAMP"
  local fixture_os="${TERRAFORM_E2E_OS:-$(uname -s | tr '[:upper:]' '[:lower:]')}"
  local fixture_arch="${TERRAFORM_E2E_ARCH:-$(uname -m)}"
  [[ "$fixture_arch" == "x86_64" ]] && fixture_arch="amd64"
  [[ "$fixture_arch" == "aarch64" ]] && fixture_arch="arm64"
  local module_dir="$dir/module"
  local provider_dir="$dir/provider"
  local module_zip="$dir/kkrepo-client-e2e-module_${fixture_version}.zip"
  local provider_zip="$dir/terraform-provider-fixture_${fixture_version}_${fixture_os}_${fixture_arch}.zip"
  local token config basic_token basic_token_encoded module_headers module_download_url
  mkdir -p "$module_dir" "$provider_dir"

  cat >"$module_dir/main.tf" <<'EOF'
output "message" {
  value = "kkrepo Terraform module client e2e"
}
EOF
  run_terraform_fixture
}

file_sha256() {
  python3 - "$1" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
}

assert_swift_registry_pin() {
  local resolved_file="$1"
  local scope="$2"
  local package_name="$3"
  local expected_version="$4"
  local label="$5"
  python3 - "$resolved_file" "$scope" "$package_name" "$expected_version" "$label" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
scope, package_name, expected_version, label = sys.argv[2:]
payload = json.loads(path.read_text(encoding="utf-8"))
pins = payload.get("pins")
if pins is None:
    pins = payload.get("object", {}).get("pins")
if not isinstance(pins, list):
    raise SystemExit(f"{label}: unsupported Package.resolved shape in {path}")

coordinate = f"{scope}.{package_name}".lower()
package_name_lower = package_name.lower()

def target(pin):
    identity = str(pin.get("identity") or pin.get("package") or "").lower()
    location = str(pin.get("location") or pin.get("repositoryURL") or "").lower()
    return identity in {package_name_lower, coordinate} or location.rstrip("/") in {
        coordinate,
        f"https://github.com/{scope}/{package_name}.git".lower(),
        f"https://github.com/{scope}/{package_name}".lower(),
    }

matches = [pin for pin in pins if isinstance(pin, dict) and target(pin)]
if not matches:
    summary = [
        {
            "identity": pin.get("identity") or pin.get("package"),
            "kind": pin.get("kind"),
            "location": pin.get("location") or pin.get("repositoryURL"),
        }
        for pin in pins if isinstance(pin, dict)
    ]
    raise SystemExit(f"{label}: target dependency is absent from Package.resolved: {summary}")

registry_matches = [pin for pin in matches if pin.get("kind") == "registry"]
if not registry_matches:
    raise SystemExit(
        f"{label}: target dependency did not resolve through the registry: {matches}"
    )
if not any(str(pin.get("state", {}).get("version") or "") == expected_version
           for pin in registry_matches):
    raise SystemExit(
        f"{label}: registry pin does not contain expected version {expected_version}: "
        f"{registry_matches}"
    )
PY
}

swift_version_line() {
  "$1" --version | head -n 1
}

swift_proxy_fixture_version() {
  local swift_bin="$1"
  if [[ -n "${SWIFT_E2E_PROXY_VERSION:-}" ]]; then
    printf '%s' "$SWIFT_E2E_PROXY_VERSION"
    return 0
  fi

  local version_line major minor
  version_line="$(swift_version_line "$swift_bin")"
  if [[ "$version_line" =~ Swift[[:space:]]version[[:space:]]([0-9]+)\.([0-9]+) ]]; then
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    if (( major < 5 || (major == 5 && minor < 9) )); then
      # swift-log 1.6.x requires swift-tools-version 5.9. Keep the oldest
      # registry clients on the latest fixture they can actually resolve.
      printf '1.5.4'
      return 0
    fi
  fi
  printf '1.6.3'
}

swift_registry_set() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  local help
  local write_config=false
  help="$("$swift_bin" package-registry set --help 2>&1 || true)"
  if [[ "$registry" == *://*'@'* ]]; then
    write_config=true
  elif [[ "$registry" == https://* ]]; then
    run_logged_in "swift-$label-registry-set" "$directory" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry set "$registry/"
  elif grep -q -- '--allow-insecure-http' <<<"$help"; then
    run_logged_in "swift-$label-registry-set" "$directory" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry set --allow-insecure-http "$registry/"
  else
    write_config=true
  fi
  if [[ "$write_config" == "true" ]]; then
    local config_file="$directory/.swiftpm/configuration/registries.json"
    mkdir -p "$(dirname "$config_file")"
    run_logged "swift-$label-registry-config" python3 - "$config_file" "$registry/" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({
    "registries": {"[default]": {"url": sys.argv[2]}},
    "version": 1,
}, separators=(",", ":")), encoding="utf-8")
PY
    chmod 0600 "$config_file"
  fi
}

prepare_swift_netrc() {
  local home="$1"
  mkdir -p "$home"
  touch "$home/.netrc"
  chmod 0600 "$home/.netrc"
}

swift_registry_login() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  prepare_swift_netrc "$home"
  run_logged_in "swift-$label-registry-login" "$directory" \
    run_with_timeout "$SWIFT_LOGIN_TIMEOUT_SECONDS" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package-registry login "$registry/login" \
    --netrc --netrc-file "$home/.netrc" \
    --username "$KKREPO_USER" --password "$KKREPO_PASSWORD" --no-confirm
}

assert_swift_invalid_login() {
  local label="$1"
  local registry="$2"
  local status=0
  # SwiftPM 5.10 can wait indefinitely after a server returns the Nexus-compatible
  # 401 Basic challenge. The successful path above exercises the real login CLI;
  # use a bounded protocol probe for the negative credential contract.
  if run_logged "swift-$label-registry-login-invalid" \
      curl --connect-timeout 10 --max-time 20 --fail-with-body -sS \
      -X POST -u "$KKREPO_USER:definitely-invalid" \
      -H 'Accept: application/vnd.swift.registry.v1+json' \
      --write-out $'\nhttp_status=%{http_code}\n' \
      "$registry/login"; then
    status=0
  else
    status=$?
  fi
  if [[ "$status" -eq 0 ]]; then
    log "Swift $label registry login unexpectedly accepted invalid credentials"
    return 1
  fi
  if [[ "$status" -ne 22 ]]; then
    log "Swift $label invalid login probe failed before receiving an HTTP rejection"
    return 1
  fi
  grep -Eq '^http_status=401\r?$' \
    "$ARTIFACT_DIR/swift-$label-registry-login-invalid.log"
}

swift_registry_token_login() {
  local label="$1"
  local swift_bin="$2"
  local directory="$3"
  local home="$4"
  local registry="$5"
  local token
  token="$(create_api_key GenericToken "Swift client E2E $label $STAMP")"
  add_redaction_value "$token"
  prepare_swift_netrc "$home"
  run_logged_in "swift-$label-registry-token-login" "$directory" \
    run_with_timeout "$SWIFT_LOGIN_TIMEOUT_SECONDS" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package-registry login "$registry/login" \
    --netrc --netrc-file "$home/.netrc" --token "$token" --no-confirm
}

swift_supports_registry_login() {
  local swift_bin="$1"
  local registry="$2"
  local help
  [[ "$registry" == https://* ]] || return 1
  help="$("$swift_bin" package-registry login --help 2>&1 || true)"
  grep -q -- '--username' <<<"$help" \
    && grep -q -- '--password' <<<"$help" \
    && grep -q -- '--no-confirm' <<<"$help"
}

swift_supports_registry_publish() {
  local help
  help="$("$1" package-registry publish --help 2>&1 || true)"
  grep -q -- '--url' <<<"$help" \
    && grep -q -- '--metadata-path' <<<"$help" \
    && grep -q -- '--scratch-directory' <<<"$help"
}

authenticated_registry_url() {
  local registry="$1"
  local repository_base_url="${2:-$KKREPO_URL}"
  local authenticated_base_url
  if [[ "$registry" != "$repository_base_url"* ]]; then
    log "cannot embed credentials into registry outside repository base URL: $registry"
    return 2
  fi
  authenticated_base_url="$(python3 - "$repository_base_url" "$KKREPO_USER" "$KKREPO_PASSWORD" <<'PY'
import sys
import urllib.parse

url = urllib.parse.urlsplit(sys.argv[1])
username = urllib.parse.quote(sys.argv[2], safe="")
password = urllib.parse.quote(sys.argv[3], safe="")
print(urllib.parse.urlunsplit((url.scheme, f"{username}:{password}@{url.netloc}", url.path, url.query, url.fragment)))
PY
)"
  printf '%s%s' "$authenticated_base_url" "${registry#"$repository_base_url"}"
}

swift_registry_login_is_required() {
  local label="$1"
  case ",${SWIFT_E2E_REQUIRE_REGISTRY_LOGIN_LABELS:-}," in
    *,"$label",*) return 0 ;;
    *) return 1 ;;
  esac
}

is_windows_runner() {
  [[ "${OS:-}" == "Windows_NT" ]] || [[ "$(uname -s)" =~ ^(MINGW|MSYS|CYGWIN) ]]
}

test_swift_proxy_binary() {
  local label="$1"
  local swift_bin="$2"
  local home="$3"
  local dir="$4"
  if [[ "${SWIFT_E2E_PROXY_ENABLED:-true}" != "true" ]]; then
    log "Swift $label SCM-to-registry proxy flow skipped by SWIFT_E2E_PROXY_ENABLED=false"
    return 0
  fi
  local group_url="$SWIFT_KKREPO_URL/repository/swift-group"
  local proxy_dir="$dir/proxy-consumer"
  local proxy_scope="${SWIFT_E2E_PROXY_SCOPE:-apple}"
  local proxy_name="${SWIFT_E2E_PROXY_NAME:-swift-log}"
  local proxy_version
  local -a swift_auth_args=()
  proxy_version="$(swift_proxy_fixture_version "$swift_bin")"
  if [[ -f "$home/.netrc" ]]; then
    swift_auth_args=(--netrc --netrc-file "$home/.netrc")
  fi
  mkdir -p "$proxy_dir/Sources/ProxyConsumer"
  cat >"$proxy_dir/Package.swift" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "ProxyConsumer",
    dependencies: [
        .package(url: "https://github.com/$proxy_scope/$proxy_name.git", exact: "$proxy_version")
    ],
    targets: [
        .executableTarget(
            name: "ProxyConsumer",
            dependencies: [.product(name: "Logging", package: "$proxy_name")]
        )
    ]
)
EOF
  cat >"$proxy_dir/Sources/ProxyConsumer/main.swift" <<'EOF'
import Logging
var logger = Logger(label: "kkrepo.swift.client-e2e")
logger.info("Swift proxy client E2E")
EOF
  swift_registry_set "$label-proxy-group" "$swift_bin" "$proxy_dir" "$home" "$group_url"
  run_logged_in "swift-$label-proxy-resolve" "$proxy_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package resolve --replace-scm-with-registry \
    ${swift_auth_args[@]+"${swift_auth_args[@]}"}
  run_logged_in "swift-$label-proxy-build" "$proxy_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" build --replace-scm-with-registry \
    ${swift_auth_args[@]+"${swift_auth_args[@]}"}
  cp "$proxy_dir/Package.resolved" "$ARTIFACT_DIR/swift-$label-proxy-Package.resolved"
  assert_swift_registry_pin "$ARTIFACT_DIR/swift-$label-proxy-Package.resolved" \
    "$proxy_scope" "$proxy_name" "$proxy_version" "Swift $label proxy"
}

test_swift_binary() {
  local label="$1"
  local swift_bin="$2"
  local ordinal="$3"
  local label_slug package_name module version dir package_dir consumer_dir home target_declaration
  local hosted_url="$SWIFT_KKREPO_URL/repository/swift-hosted"
  local group_url="$SWIFT_KKREPO_URL/repository/swift-group"
  local hosted_access_url group_access_url
  local -a swift_auth_args=()
  label_slug="$(printf '%s' "$label" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-//;s/-$//')"
  package_name="client-e2e-${STAMP}-${label_slug:-swift}"
  module="KkRepoSwiftE2E${ordinal}"
  version="1.0.$ordinal"
  dir="$WORK_DIR/swift-$label_slug"
  package_dir="$dir/package"
  consumer_dir="$dir/consumer"
  home="$dir/home"
  mkdir -p "$package_dir/Sources/$module" "$consumer_dir/Sources/Consumer" "$home"
  if is_windows_runner; then
    log "Swift $label hosted publish skipped on Windows; running documented proxy resolve/build path"
    test_swift_proxy_binary "$label" "$swift_bin" "$home" "$dir"
    return 0
  fi
  if ! swift_supports_registry_publish "$swift_bin"; then
    log "Swift $label has no package-registry publish command; running its supported proxy resolve/build path"
    test_swift_proxy_binary "$label" "$swift_bin" "$home" "$dir"
    return 0
  fi

  target_declaration=".target(name: \"$module\")"
  if [[ "${SWIFT_E2E_LARGE_FIXTURE_BYTES:-0}" =~ ^[0-9]+$ ]] \
      && [[ "${SWIFT_E2E_LARGE_FIXTURE_BYTES:-0}" -gt 0 ]]; then
    target_declaration=".target(name: \"$module\", resources: [.copy(\"LargeFixture.bin\")])"
    python3 - "$package_dir/Sources/$module/LargeFixture.bin" \
        "$SWIFT_E2E_LARGE_FIXTURE_BYTES" <<'PY'
import hashlib
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
remaining = int(sys.argv[2])
counter = 0
with path.open("wb") as stream:
    while remaining:
        block = hashlib.sha256(f"kkrepo-swift-e2e-{counter}".encode()).digest()
        chunk = block[:remaining]
        stream.write(chunk)
        remaining -= len(chunk)
        counter += 1
PY
  fi
  cat >"$package_dir/Package.swift" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "$module",
    products: [.library(name: "$module", targets: ["$module"])],
    targets: [$target_declaration]
)
EOF
  cat >"$package_dir/Sources/$module/$module.swift" <<EOF
public enum $module {
    public static let marker = "kkrepo Swift client E2E $label"
}
EOF
  cat >"$package_dir/Package@swift-5.9.swift" <<EOF
// swift-tools-version:5.9
import PackageDescription
let package = Package(
    name: "$module",
    products: [.library(name: "$module", targets: ["$module"])],
    targets: [$target_declaration]
)
EOF
  cat >"$package_dir/package-metadata.json" <<EOF
{
  "description": "kkrepo Swift client E2E $label",
  "repositoryURLs": ["https://github.com/kkrepo-fixtures/$package_name.git"]
}
EOF

  run_logged_in "swift-$label-version" "$package_dir" "$swift_bin" --version
  hosted_access_url="$hosted_url"
  if swift_supports_registry_login "$swift_bin" "$hosted_url"; then
    swift_registry_set "$label-hosted" "$swift_bin" "$package_dir" "$home" "$hosted_url"
    local basic_login_home="$dir/basic-login-home"
    mkdir -p "$basic_login_home"
    swift_registry_login "$label-hosted" "$swift_bin" "$package_dir" \
      "$basic_login_home" "$hosted_url"
    assert_swift_invalid_login "$label-hosted" "$hosted_url"
    swift_registry_token_login "$label-hosted" "$swift_bin" "$package_dir" \
      "$home" "$hosted_url"
  else
    if swift_registry_login_is_required "$label"; then
      log "Swift $label must execute package-registry login over HTTPS, but the command or HTTPS registry is unavailable"
      return 2
    fi
    hosted_access_url="$(authenticated_registry_url "$hosted_url" "$SWIFT_KKREPO_URL")"
    add_redaction_value "$hosted_access_url"
    swift_registry_set "$label-hosted-embedded-auth" \
      "$swift_bin" "$package_dir" "$home" "$hosted_access_url"
    log "Swift $label registry login CLI skipped: it requires HTTPS and Swift 5.8+; embedded credentials are used"
  fi
  if [[ -f "$home/.netrc" ]]; then
    swift_auth_args=(--netrc --netrc-file "$home/.netrc")
  fi
  local -a publish_transport_args=()
  if [[ "$hosted_access_url" == http://* ]]; then
    publish_transport_args+=(--allow-insecure-http)
  fi
  mkdir -p "$dir/publish-scratch" "$dir/duplicate-scratch"
  run_logged_in "swift-$label-publish" "$package_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package-registry publish "kkrepo.$package_name" "$version" \
    --url "$hosted_access_url/" --metadata-path "$package_dir/package-metadata.json" \
    --scratch-directory "$dir/publish-scratch" \
    ${publish_transport_args[@]+"${publish_transport_args[@]}"} \
    ${swift_auth_args[@]+"${swift_auth_args[@]}"}

  local duplicate_status=0
  if run_logged_in "swift-$label-publish-duplicate" "$package_dir" env \
      HOME="$home" XDG_CONFIG_HOME="$home/.config" \
      "$swift_bin" package-registry publish "kkrepo.$package_name" "$version" \
      --url "$hosted_access_url/" --metadata-path "$package_dir/package-metadata.json" \
      --scratch-directory "$dir/duplicate-scratch" \
      ${publish_transport_args[@]+"${publish_transport_args[@]}"} \
      ${swift_auth_args[@]+"${swift_auth_args[@]}"}; then
    duplicate_status=0
  else
    duplicate_status=$?
  fi
  if [[ "$duplicate_status" -eq 0 ]]; then
    log "Swift $label duplicate publish unexpectedly succeeded"
    return 1
  fi
  if ! grep -Eqi '409|conflict|already exists|already published' \
      "$ARTIFACT_DIR/swift-$label-publish-duplicate.log"; then
    log "Swift $label duplicate publish did not report immutable conflict"
    return 1
  fi

  run_logged_output "swift-$label-release-list" "$ARTIFACT_DIR/swift-$label-releases.json" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+json' \
    "$group_url/kkrepo/$package_name"
  run_logged_output "swift-$label-release-metadata" "$ARTIFACT_DIR/swift-$label-metadata.json" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+json' \
    "$group_url/kkrepo/$package_name/$version"
  run_logged_output "swift-$label-manifest" "$ARTIFACT_DIR/swift-$label-Package.swift" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+swift' \
    "$group_url/kkrepo/$package_name/$version/Package.swift"
  run_logged_output "swift-$label-versioned-manifest" \
    "$ARTIFACT_DIR/swift-$label-Package@swift-5.9.swift" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+swift' \
    "$group_url/kkrepo/$package_name/$version/Package.swift?swift-version=5.9"
  run_logged_output "swift-$label-archive" "$ARTIFACT_DIR/swift-$label-source.zip" \
    curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Accept: application/vnd.swift.registry.v1+zip' \
    "$group_url/kkrepo/$package_name/$version.zip"
  python3 - "$ARTIFACT_DIR/swift-$label-releases.json" \
      "$ARTIFACT_DIR/swift-$label-metadata.json" \
      "$ARTIFACT_DIR/swift-$label-source.zip" "$version" <<'PY'
import hashlib
import json
import pathlib
import sys

releases = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
metadata = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
archive = pathlib.Path(sys.argv[3]).read_bytes()
version = sys.argv[4]
assert version in releases["releases"]
resource = next(item for item in metadata["resources"] if item["name"] == "source-archive")
assert resource["type"] == "application/zip"
assert resource["checksum"] == hashlib.sha256(archive).hexdigest()
PY
  cmp "$package_dir/Package.swift" "$ARTIFACT_DIR/swift-$label-Package.swift"
  cmp "$package_dir/Package@swift-5.9.swift" \
    "$ARTIFACT_DIR/swift-$label-Package@swift-5.9.swift"

  cat >"$consumer_dir/Package.swift" <<EOF
// swift-tools-version:5.7
import PackageDescription
let package = Package(
    name: "Consumer",
    dependencies: [.package(id: "kkrepo.$package_name", exact: "$version")],
    targets: [
        .executableTarget(
            name: "Consumer",
            dependencies: [.product(name: "$module", package: "kkrepo.$package_name")]
        )
    ]
)
EOF
  cat >"$consumer_dir/Sources/Consumer/main.swift" <<EOF
import $module
print($module.marker)
EOF
  group_access_url="$group_url"
  if swift_supports_registry_login "$swift_bin" "$group_url"; then
    swift_registry_set "$label-group" "$swift_bin" "$consumer_dir" "$home" "$group_url"
    swift_registry_token_login "$label-group" "$swift_bin" "$consumer_dir" "$home" "$group_url"
  else
    group_access_url="$(authenticated_registry_url "$group_url" "$SWIFT_KKREPO_URL")"
    add_redaction_value "$group_access_url"
    swift_registry_set "$label-group-embedded-auth" \
      "$swift_bin" "$consumer_dir" "$home" "$group_access_url"
  fi
  run_logged_in "swift-$label-resolve" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package resolve ${swift_auth_args[@]+"${swift_auth_args[@]}"}
  run_logged_in "swift-$label-build" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" build ${swift_auth_args[@]+"${swift_auth_args[@]}"}
  cp "$consumer_dir/Package.resolved" "$ARTIFACT_DIR/swift-$label-Package.resolved"
  assert_swift_registry_pin "$ARTIFACT_DIR/swift-$label-Package.resolved" \
    kkrepo "$package_name" "$version" "Swift $label hosted"
  local first_lock_hash
  first_lock_hash="$(file_sha256 "$consumer_dir/Package.resolved")"
  rm -rf "$consumer_dir/.build"
  run_logged_in "swift-$label-resolve-replay" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" package resolve ${swift_auth_args[@]+"${swift_auth_args[@]}"}
  [[ "$first_lock_hash" == "$(file_sha256 "$consumer_dir/Package.resolved")" ]]
  run_logged_in "swift-$label-build-replay" "$consumer_dir" env \
    HOME="$home" XDG_CONFIG_HOME="$home/.config" \
    "$swift_bin" build ${swift_auth_args[@]+"${swift_auth_args[@]}"}

  if [[ -n "${SWIFT_KKREPO_SECONDARY_BASE_URL:-}" ]]; then
    local secondary_consumer_dir="$dir/secondary-consumer"
    local secondary_home="$dir/secondary-home"
    local secondary_group_url="${SWIFT_KKREPO_SECONDARY_BASE_URL%/}/repository/swift-group"
    local -a secondary_auth_args=()
    mkdir -p "$secondary_consumer_dir/Sources" "$secondary_home"
    cp "$consumer_dir/Package.swift" "$secondary_consumer_dir/Package.swift"
    cp -R "$consumer_dir/Sources/Consumer" "$secondary_consumer_dir/Sources/Consumer"
    swift_registry_set "$label-secondary-group" "$swift_bin" \
      "$secondary_consumer_dir" "$secondary_home" "$secondary_group_url"
    if swift_supports_registry_login "$swift_bin" "$secondary_group_url"; then
      swift_registry_token_login "$label-secondary-group" "$swift_bin" \
        "$secondary_consumer_dir" "$secondary_home" "$secondary_group_url"
      secondary_auth_args=(--netrc --netrc-file "$secondary_home/.netrc")
    fi
    run_logged_in "swift-$label-secondary-resolve" "$secondary_consumer_dir" env \
      HOME="$secondary_home" XDG_CONFIG_HOME="$secondary_home/.config" \
      "$swift_bin" package resolve \
      ${secondary_auth_args[@]+"${secondary_auth_args[@]}"}
    run_logged_in "swift-$label-secondary-build" "$secondary_consumer_dir" env \
      HOME="$secondary_home" XDG_CONFIG_HOME="$secondary_home/.config" \
      "$swift_bin" build ${secondary_auth_args[@]+"${secondary_auth_args[@]}"}
    cp "$secondary_consumer_dir/Package.resolved" \
      "$ARTIFACT_DIR/swift-$label-secondary-Package.resolved"
    assert_swift_registry_pin "$ARTIFACT_DIR/swift-$label-secondary-Package.resolved" \
      kkrepo "$package_name" "$version" "Swift $label secondary hosted"
  fi

  if [[ "$(uname -s)" == "Darwin" && -z "${SWIFT_XCODE_E2E_GENERATED_PACKAGE:-}" ]]; then
    export SWIFT_XCODE_E2E_GENERATED_PACKAGE="$consumer_dir"
    export SWIFT_XCODE_E2E_GENERATED_SCHEME=Consumer
    export SWIFT_XCODE_E2E_GENERATED_HOME="$home"
    export SWIFT_XCODE_E2E_GENERATED_SCOPE=kkrepo
    export SWIFT_XCODE_E2E_GENERATED_NAME="$package_name"
    export SWIFT_XCODE_E2E_GENERATED_VERSION="$version"
  fi

  SWIFT_CLEANUP_FIXTURE_AVAILABLE=true
  test_swift_proxy_binary "$label" "$swift_bin" "$home" "$dir"
}

test_swift_xcode() {
  local project="${SWIFT_XCODE_E2E_PROJECT:-}"
  local package="${SWIFT_XCODE_E2E_PACKAGE:-${SWIFT_XCODE_E2E_GENERATED_PACKAGE:-}}"
  local xcodebuild_bin=""
  xcodebuild_bin="$(command -v xcodebuild 2>/dev/null || true)"
  if [[ "$(uname -s)" != "Darwin" || -z "$xcodebuild_bin" || ! -x "$xcodebuild_bin" ]]; then
    if [[ "${SWIFT_E2E_REQUIRE_XCODE:-false}" == "true" ]]; then
      log "Xcode Swift registry flow is required but xcodebuild is unavailable"
      return 2
    fi
    log "Xcode Swift registry flow skipped: xcodebuild is only available on macOS runners"
    return 0
  fi
  if ! "$xcodebuild_bin" -version >/dev/null 2>&1; then
    if [[ "${SWIFT_E2E_REQUIRE_XCODE:-false}" == "true" ]]; then
      log "Xcode Swift registry flow is required but the active developer directory is not a full Xcode installation"
      return 2
    fi
    log "Xcode Swift registry flow skipped: the active developer directory is not a full Xcode installation"
    return 0
  fi
  if [[ -z "$project" && -z "$package" ]]; then
    if [[ "${SWIFT_E2E_REQUIRE_XCODE:-false}" == "true" ]]; then
      log "Xcode Swift registry flow is required but no generated package or project is available"
      return 2
    fi
    log "Xcode Swift registry flow skipped: no registry fixture package or project is available"
    return 0
  fi

  if [[ -n "$project" ]]; then
    local -a project_command=(xcodebuild -resolvePackageDependencies -project "$project")
    if [[ -n "${SWIFT_XCODE_E2E_SCHEME:-}" ]]; then
      project_command+=( -scheme "$SWIFT_XCODE_E2E_SCHEME" )
    fi
    run_logged swift-xcode-resolve "${project_command[@]}"
    return 0
  fi

  local scheme="${SWIFT_XCODE_E2E_SCHEME:-${SWIFT_XCODE_E2E_GENERATED_SCHEME:-Consumer}}"
  local xcode_home="${SWIFT_XCODE_E2E_HOME:-${SWIFT_XCODE_E2E_GENERATED_HOME:-$HOME}}"
  local xcode_source_packages="$WORK_DIR/xcode-source-packages"
  local xcode_derived_data="$WORK_DIR/xcode-derived-data"
  local xcode_config="$package/.swiftpm/configuration/registries.json"
  local xcode_registry_url
  xcode_registry_url="$(authenticated_registry_url \
    "$SWIFT_KKREPO_URL/repository/swift-group" "$SWIFT_KKREPO_URL")/"
  add_redaction_value "$xcode_registry_url"
  mkdir -p "$(dirname "$xcode_config")" "$xcode_source_packages" "$xcode_derived_data"
  run_logged swift-xcode-registry-config python3 - "$xcode_config" \
      "$xcode_registry_url" <<'PY'
import json
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "registries": {"[default]": {"url": sys.argv[2]}},
    "version": 1,
}, separators=(",", ":")), encoding="utf-8")
PY
  rm -rf "$package/.build"
  rm -f "$package/Package.resolved"
  run_logged_in swift-xcode-resolve "$package" \
    env HOME="$xcode_home" XDG_CONFIG_HOME="$xcode_home/.config" \
    xcodebuild -resolvePackageDependencies \
    -scheme "$scheme" \
    -clonedSourcePackagesDirPath "$xcode_source_packages"
  run_logged_in swift-xcode-build "$package" \
    env HOME="$xcode_home" XDG_CONFIG_HOME="$xcode_home/.config" \
    xcodebuild \
    -scheme "$scheme" \
    -destination 'platform=macOS' \
    -derivedDataPath "$xcode_derived_data" \
    -clonedSourcePackagesDirPath "$xcode_source_packages" \
    CODE_SIGNING_ALLOWED=NO \
    build

  local resolved_file
  resolved_file="$(find "$package" -name Package.resolved -type f -print | head -n 1)"
  if [[ -z "$resolved_file" ]]; then
    log "Xcode did not produce Package.resolved for the registry fixture"
    return 1
  fi
  cp "$resolved_file" "$ARTIFACT_DIR/swift-xcode-Package.resolved"
  if [[ -n "${SWIFT_XCODE_E2E_GENERATED_NAME:-}" ]]; then
    assert_swift_registry_pin "$ARTIFACT_DIR/swift-xcode-Package.resolved" \
      "${SWIFT_XCODE_E2E_GENERATED_SCOPE:-kkrepo}" \
      "$SWIFT_XCODE_E2E_GENERATED_NAME" \
      "$SWIFT_XCODE_E2E_GENERATED_VERSION" \
      "Xcode hosted"
  else
    grep -Eqi '"kind"[[:space:]]*:[[:space:]]*"registry"' \
      "$ARTIFACT_DIR/swift-xcode-Package.resolved"
  fi
}

test_swift() {
  local configured_bins="${SWIFT_E2E_BINS:-}"
  local -a entries=()
  local entry label swift_bin actual_version
  if [[ -n "$configured_bins" ]]; then
    IFS=',' read -r -a entries <<<"$configured_bins"
  elif command -v swift >/dev/null 2>&1; then
    entries=("current=$(command -v swift)")
  else
    log "Swift client matrix skipped: install Swift or set SWIFT_E2E_BINS"
    return 0
  fi

  : >"$ARTIFACT_DIR/swift-client-matrix.tsv"
  local ordinal=0
  for entry in "${entries[@]}"; do
    entry="${entry#${entry%%[![:space:]]*}}"
    entry="${entry%${entry##*[![:space:]]}}"
    [[ -z "$entry" ]] && continue
    if [[ "$entry" != *=* ]]; then
      log "invalid SWIFT_E2E_BINS entry '$entry'; expected label=/path/to/swift"
      return 2
    fi
    label="${entry%%=*}"
    swift_bin="${entry#*=}"
    if [[ ! -x "$swift_bin" ]]; then
      log "Swift $label binary is not executable: $swift_bin"
      return 2
    fi
    actual_version="$(swift_version_line "$swift_bin")"
    printf '%s\t%s\t%s\n' "$label" "$swift_bin" "$actual_version" \
      >>"$ARTIFACT_DIR/swift-client-matrix.tsv"
    ordinal=$((ordinal + 1))
    test_swift_binary "$label" "$swift_bin" "$ordinal"
  done
  [[ "$ordinal" -gt 0 ]]

  if [[ "${SWIFT_E2E_REQUIRE_WINDOWS:-false}" == "true" ]] && ! is_windows_runner; then
    log "strict Windows Swift lane requires a native Windows runner"
    return 2
  fi

  if [[ "${SWIFT_E2E_REQUIRE_5_7_5_9_6:-false}" == "true" ]]; then
    python3 - "$ARTIFACT_DIR/swift-client-matrix.tsv" <<'PY'
import re
import sys

rows = []
for line in open(sys.argv[1], encoding="utf-8"):
    label, binary, reported = line.rstrip("\n").split("\t", 2)
    match = re.search(r"Swift version (\d+)\.(\d+)", reported)
    if not match:
        raise SystemExit(f"cannot parse Swift version for {label}: {reported}")
    rows.append((label, int(match.group(1)), int(match.group(2))))
if not any(label == "5.7" and (major, minor) == (5, 7) for label, major, minor in rows):
    raise SystemExit("strict Swift matrix requires a real 5.7 toolchain labeled 5.7")
if not any(label in {"5.9", "5.9+"} and major == 5 and minor >= 9
           for label, major, minor in rows):
    raise SystemExit("strict Swift matrix requires a real 5.9+ toolchain labeled 5.9 or 5.9+")
if not any(label in {"6", "6.x"} and major == 6 for label, major, minor in rows):
    raise SystemExit("strict Swift matrix requires a real Swift 6 toolchain labeled 6 or 6.x")
PY
  fi
  test_swift_xcode
}

run_terraform_fixture() {
  run_logged_in terraform-module-archive "$module_dir" zip -q -r "$module_zip" .

  cat >"$provider_dir/terraform-provider-fixture_v$fixture_version" <<'EOF'
#!/usr/bin/env sh
echo "kkrepo Terraform provider fixture is install-only" >&2
exit 1
EOF
  chmod +x "$provider_dir/terraform-provider-fixture_v$fixture_version"
  run_logged_in terraform-provider-archive "$provider_dir" zip -q "$provider_zip" "terraform-provider-fixture_v$fixture_version"

  run_logged terraform-module-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    --upload-file "$module_zip" \
    "$KKREPO_URL/repository/terraform-hosted/v1/modules/kkrepo/client-e2e/aws/$fixture_version/$(basename "$module_zip")"
  run_logged terraform-provider-upload curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H "Content-Disposition: attachment; filename=$(basename "$provider_zip")" \
    --upload-file "$provider_zip" \
    "$KKREPO_URL/repository/terraform-hosted/v1/providers/kkrepo/fixture/$fixture_version/download/$fixture_os/$fixture_arch"

  basic_token="$(printf '%s' "$KKREPO_AUTH" | base64 | tr -d '\r\n')"
  basic_token_encoded="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$basic_token")"
  add_redaction_value "$basic_token"
  add_redaction_value "$basic_token_encoded"
  module_headers="$dir/basic-module.headers"
  run_logged terraform-basic-module-metadata curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -D "$module_headers" -o /dev/null \
    "$KKREPO_URL/repository/terraform-hosted/v1/modules/kkrepo/client-e2e/aws/$fixture_version/download"
  module_download_url="$(awk 'BEGIN { IGNORECASE=1 } /^X-Terraform-Get:/ { sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit }' "$module_headers")"
  [[ "$module_download_url" == *"/$basic_token_encoded/"* ]]
  run_logged terraform-basic-module-followup curl -m 20 --fail-with-body -sS \
    -o "$dir/basic-module.zip" "$module_download_url"
  cmp "$module_zip" "$dir/basic-module.zip"

  run_logged_output terraform-basic-provider-metadata "$dir/basic-provider.json" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/terraform-hosted/v1/providers/kkrepo/fixture/$fixture_version/download/$fixture_os/$fixture_arch"
  while IFS= read -r provider_url; do
    [[ "$provider_url" == *"/$basic_token_encoded/"* ]]
    run_logged "terraform-basic-provider-followup-$(basename "$provider_url")" \
      curl -m 20 --fail-with-body -sS -o /dev/null "$provider_url"
  done < <(python3 - "$dir/basic-provider.json" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
for field in ("download_url", "shasums_url", "shasums_signature_url"):
    print(document[field])
PY
  )

  token="$(create_api_key GenericToken "client e2e terraform $STAMP")"
  add_redaction_value "$token"
  config="$dir/terraform.rc"
  cat >"$config" <<EOF
disable_checkpoint = true
host "registry.terraform.io" {
  services = {
    "modules.v1"   = "$KKREPO_URL/repository/terraform-group/v1/modules/$token/"
    "providers.v1" = "$KKREPO_URL/repository/terraform-group/v1/providers/$token/"
  }
}
EOF

  local version_label terraform_bin init_dir
  for version_label in 0.13 current; do
    terraform_bin="$terraform_current"
    [[ "$version_label" == "0.13" ]] && terraform_bin="$terraform_013"
    init_dir="$dir/init-$version_label"
    mkdir -p "$init_dir"
    cat >"$init_dir/main.tf" <<EOF
terraform {
  required_providers {
    fixture = {
      source  = "registry.terraform.io/kkrepo/fixture"
      version = "$fixture_version"
    }
    null = {
      source  = "registry.terraform.io/hashicorp/null"
      version = "3.2.4"
    }
  }
}

module "hosted" {
  source  = "registry.terraform.io/kkrepo/client-e2e/aws"
  version = "$fixture_version"
}
EOF
    run_logged_in "terraform-$version_label-version" "$init_dir" "$terraform_bin" version
    run_logged_in "terraform-$version_label-init" "$init_dir" env \
      TF_CLI_CONFIG_FILE="$config" CHECKPOINT_DISABLE=1 \
      "$terraform_bin" init -backend=false -input=false -no-color
    test -d "$init_dir/.terraform/modules/hosted"
    local provider_root="$init_dir/.terraform/providers"
    [[ "$version_label" == "0.13" ]] && provider_root="$init_dir/.terraform/plugins"
    test -d "$provider_root/registry.terraform.io/kkrepo/fixture/$fixture_version/${fixture_os}_${fixture_arch}"
    test -d "$provider_root/registry.terraform.io/hashicorp/null/3.2.4/${fixture_os}_${fixture_arch}"
  done

  run_logged_redacted_output terraform-provider-metadata "$ARTIFACT_DIR/terraform-provider-metadata.json" \
    curl -m 20 -fsS \
    "$KKREPO_URL/repository/terraform-group/v1/providers/$token/kkrepo/fixture/$fixture_version/download/$fixture_os/$fixture_arch"
  python3 - "$ARTIFACT_DIR/terraform-provider-metadata.json" "$fixture_version" "$fixture_os" "$fixture_arch" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
assert document["filename"].startswith(f"terraform-provider-fixture_{sys.argv[2]}_{sys.argv[3]}_{sys.argv[4]}")
assert document["shasum"]
assert document["signing_keys"]["gpg_public_keys"][0]["ascii_armor"].startswith("-----BEGIN PGP PUBLIC KEY BLOCK-----")
PY
}

ansible_locale() {
  if locale -a 2>/dev/null | grep -Ei '^en_US\.UTF-?8$' >/dev/null; then
    printf '%s' 'en_US.UTF-8'
  else
    printf '%s' 'C.UTF-8'
  fi
}

write_ansible_collection() {
  local collection_dir="$1"
  local namespace="$2"
  local name="$3"
  local version="$4"
  local dependency_name="${5:-}"
  mkdir -p "$collection_dir/plugins/modules"
  if [[ -n "$dependency_name" ]]; then
    cat >"$collection_dir/galaxy.yml" <<EOF
namespace: $namespace
name: $name
version: $version
readme: README.md
authors:
  - kkRepo client E2E
description: Ansible Galaxy client E2E fixture
license:
  - Apache-2.0
tags:
  - kkrepo
dependencies:
  $dependency_name: ">=1.0.0,<2.0.0"
EOF
  else
    cat >"$collection_dir/galaxy.yml" <<EOF
namespace: $namespace
name: $name
version: $version
readme: README.md
authors:
  - kkRepo client E2E
description: Ansible Galaxy client E2E fixture
license:
  - Apache-2.0
tags:
  - kkrepo
dependencies: {}
EOF
  fi
  cat >"$collection_dir/README.md" <<EOF
# $namespace.$name

Generated by the kkRepo Ansible Galaxy client E2E matrix.
EOF
  cat >"$collection_dir/plugins/modules/ping.py" <<'PY'
#!/usr/bin/python
from ansible.module_utils.basic import AnsibleModule


def main():
    module = AnsibleModule(argument_spec={})
    module.exit_json(changed=False, ping="pong")


if __name__ == "__main__":
    main()
PY
}

test_ansible_binary() {
  local label="$1"
  local ansible_galaxy="$2"
  local label_slug="$3"
  local locale_name="$4"
  local namespace="kkrepo_e2e"
  local suffix="${STAMP:0:14}_${label_slug}"
  local base_name="base_${suffix}"
  local app_name="app_${suffix}"
  local version="1.0.0"
  local dir="$WORK_DIR/ansible/$label_slug"
  local base_dir="$dir/$base_name"
  local app_dir="$dir/$app_name"
  local base_archive="$dir/dist/$namespace-$base_name-$version.tar.gz"
  local app_archive="$dir/dist/$namespace-$app_name-$version.tar.gz"
  local hosted_url="$KKREPO_URL/repository/ansible-hosted/"
  local hosted_api_url="${hosted_url%/}/api/v3"
  local group_url="$KKREPO_URL/repository/ansible-group/"
  local bearer_token generic_token requirements install_dir download_dir token_option

  token_option="--api-key"
  if env LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
      "$ansible_galaxy" collection publish --help 2>&1 | grep -- '--token' >/dev/null; then
    token_option="--token"
  fi

  mkdir -p "$dir/dist"
  write_ansible_collection "$base_dir" "$namespace" "$base_name" "$version"
  write_ansible_collection "$app_dir" "$namespace" "$app_name" "$version" \
    "$namespace.$base_name"

  run_logged_in "ansible-$label_slug-build-base" "$base_dir" env \
    LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
    "$ansible_galaxy" collection build . --output-path "$dir/dist" --force
  run_logged_in "ansible-$label_slug-build-app" "$app_dir" env \
    LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
    "$ansible_galaxy" collection build . --output-path "$dir/dist" --force
  test -f "$base_archive"
  test -f "$app_archive"

  bearer_token="$(python3 - "$KKREPO_AUTH" <<'PY'
import base64
import sys
print(base64.b64encode(sys.argv[1].encode("utf-8")).decode("ascii"))
PY
)"
  add_redaction_value "$bearer_token"
  run_logged "ansible-$label_slug-publish-base" env \
    LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
    "$ansible_galaxy" collection publish "$base_archive" \
    --server "$hosted_url" "$token_option" "$bearer_token" --import-timeout 120
  run_logged "ansible-$label_slug-publish-app" env \
    LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
    "$ansible_galaxy" collection publish "$app_archive" \
    --server "$hosted_url" "$token_option" "$bearer_token" --no-wait
  if run_logged "ansible-$label_slug-duplicate-publish" env \
      LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
      "$ansible_galaxy" collection publish "$app_archive" \
      --server "$hosted_url" "$token_option" "$bearer_token" --import-timeout 30; then
    log "Ansible $label duplicate collection version unexpectedly succeeded"
    return 1
  fi

  wait_for_ansible_import_task "$label_slug" \
    "$ARTIFACT_DIR/ansible-$label_slug-publish-app.log" "$hosted_url"

  run_logged "ansible-$label_slug-basic-read" curl -m 20 --fail-with-body -sS \
    -u "$KKREPO_AUTH" \
    "$hosted_api_url/collections/$namespace/$app_name/versions/$version/"
  run_logged "ansible-$label_slug-anonymous-read" curl -m 20 --fail-with-body -sS \
    "$hosted_api_url/collections/$namespace/$app_name/versions/$version/"
  local invalid_status
  invalid_status="$(curl -m 20 -sS -o "$ARTIFACT_DIR/ansible-$label_slug-invalid-auth.json" \
    -w '%{http_code}' -H 'Authorization: Bearer invalid-explicit-token' \
    "$hosted_api_url/collections/$namespace/$app_name/versions/$version/")"
  if [[ "$invalid_status" != "401" ]]; then
    log "Ansible $label invalid explicit credentials returned HTTP $invalid_status instead of 401"
    return 1
  fi

  generic_token="$(create_api_key GenericToken "client e2e ansible $label $STAMP")"
  add_redaction_value "$generic_token"
  requirements="$dir/requirements.yml"
  cat >"$requirements" <<EOF
collections:
  - name: $namespace.$app_name
    version: ">=1.0.0,<2.0.0"
EOF
  install_dir="$dir/install"
  run_logged "ansible-$label_slug-install-group" env \
    LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
    "$ansible_galaxy" collection install -r "$requirements" \
    --server "$group_url" "$token_option" "$generic_token" \
    --collections-path "$install_dir" --force
  test -f "$install_dir/ansible_collections/$namespace/$app_name/MANIFEST.json"
  test -f "$install_dir/ansible_collections/$namespace/$base_name/MANIFEST.json"

  if env LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
      "$ansible_galaxy" collection download --help >/dev/null 2>&1; then
    download_dir="$dir/download"
    mkdir -p "$download_dir"
    run_logged "ansible-$label_slug-download" env \
      LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
      "$ansible_galaxy" collection download "$namespace.$app_name:$version" \
      --server "$group_url" "$token_option" "$generic_token" \
      --download-path "$download_dir" --no-deps
    cmp "$app_archive" "$download_dir/$namespace-$app_name-$version.tar.gz"
  fi

  if [[ "${ANSIBLE_E2E_PROXY_ENABLED:-true}" == "true" ]]; then
    local proxy_dir="$dir/proxy-install"
    run_logged "ansible-$label_slug-install-proxy" env \
      LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
      "$ansible_galaxy" collection install community.general:10.4.0 \
      --server "$group_url" "$token_option" "$generic_token" \
      --collections-path "$proxy_dir" --force --no-deps
    test -f "$proxy_dir/ansible_collections/community/general/MANIFEST.json"
  else
    log "Ansible $label public Galaxy proxy flow skipped by ANSIBLE_E2E_PROXY_ENABLED=false"
  fi

  if [[ -n "${ANSIBLE_KKREPO_SECONDARY_BASE_URL:-}" ]]; then
    run_logged "ansible-$label_slug-secondary-read" curl -m 20 --fail-with-body -sS \
      -u "$KKREPO_AUTH" \
      "${ANSIBLE_KKREPO_SECONDARY_BASE_URL%/}/repository/ansible-group/api/v3/collections/$namespace/$app_name/versions/$version/"
  fi
}

apt_client_base_url() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlsplit, urlunsplit

parts = urlsplit(sys.argv[1])
host = parts.hostname or ""
if host in {"127.0.0.1", "localhost", "::1"}:
    host = "host.docker.internal"
port = f":{parts.port}" if parts.port else ""
print(urlunsplit((parts.scheme, host + port, parts.path.rstrip("/"), "", "")))
PY
}

apt_write_client_config() {
  local directory="$1"
  local base_url="$2"
  local repository="$3"
  local key_file="$4"
  local client_url auth_machine
  client_url="$(apt_client_base_url "$base_url")"
  auth_machine="$(python3 - "$client_url" "$repository" <<'PY'
import sys
from urllib.parse import urlsplit

parts = urlsplit(sys.argv[1])
print(f"{parts.scheme}://{parts.netloc}/repository/{sys.argv[2]}/")
PY
)"
  mkdir -p "$directory"
  printf 'deb [signed-by=/etc/apt/keyrings/kkrepo.asc] %s/repository/%s stable main\n' \
    "$client_url" "$repository" >"$directory/kkrepo.list"
  printf 'machine %s\nlogin %s\npassword %s\n' \
    "$auth_machine" "$KKREPO_USER" "$KKREPO_PASSWORD" >"$directory/kkrepo.conf"
  chmod 0600 "$directory/kkrepo.conf"
  cp "$key_file" "$directory/kkrepo.asc"
}

apt_create_fixture() {
  local output="$1"
  local package="$2"
  local version="$3"
  local architecture="$4"
  local depends="$5"
  local message="$6"
  local -a command=(
    python3 "$PROJECT_ROOT/scripts/ci/create-apt-e2e-fixture.py"
    --output "$output"
    --package "$package"
    --version "$version"
    --architecture "$architecture"
    --message "$message"
  )
  if [[ -n "$depends" ]]; then
    command+=(--depends "$depends")
  fi
  run_logged "apt-fixture-$package-$version" "${command[@]}"
}

apt_upload_fixture() {
  local label="$1"
  local file="$2"
  run_logged_output "apt-$label-upload" "$ARTIFACT_DIR/apt-$label-upload.json" \
    curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Content-Type: multipart/form-data' --data-binary "@$file" \
    "$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/"
}

apt_wait_for_version() {
  local label="$1"
  local package="$2"
  local version="$3"
  local architecture="$4"
  local timeout_seconds="${APT_E2E_PUBLICATION_TIMEOUT_SECONDS:-60}"
  local deadline=$((SECONDS + timeout_seconds))
  local index_url="$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/dists/stable/main/binary-$architecture/Packages"
  while (( SECONDS < deadline )); do
    if curl -m 10 --fail-with-body -sS -u "$KKREPO_AUTH" "$index_url" 2>/dev/null \
        | awk -v package="$package" -v version="$version" '
            BEGIN { RS=""; FS="\n"; matched=0 }
            {
              found_package=0; found_version=0
              for (field=1; field<=NF; field++) {
                if ($field == "Package: " package) found_package=1
                if ($field == "Version: " version) found_version=1
              }
              if (found_package && found_version) { matched=1; exit }
            }
            END { exit matched ? 0 : 1 }
          '; then
      return 0
    fi
    sleep 0.2
  done
  log "APT $label metadata publication did not expose $package=$version within ${timeout_seconds}s"
  return 1
}

apt_start_client() {
  local container="$1"
  local image="$2"
  local config_dir="$3"
  run_logged "apt-$container-start" docker run --detach --name "$container" \
    --add-host host.docker.internal:host-gateway \
    --volume "$config_dir/kkrepo.list:/etc/apt/sources.list.d/kkrepo.list:ro" \
    --volume "$config_dir/kkrepo.conf:/etc/apt/auth.conf.d/kkrepo.conf:ro" \
    --volume "$config_dir/kkrepo.asc:/etc/apt/keyrings/kkrepo.asc:ro" \
    "$image" sleep infinity
  APT_E2E_CONTAINERS+=("$container")
}

apt_prepare_client() {
  local container="$1"
  run_logged "apt-$container-prepare" docker exec "$container" sh -euxc '
    find /etc/apt/sources.list.d -maxdepth 1 -type f ! -name kkrepo.list -delete
    rm -f /etc/apt/sources.list
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb
  '
}

apt_install_version() {
  local container="$1"
  local package="$2"
  local version="$3"
  local expected_message="$4"
  local expected_sha="$5"
  run_logged "apt-$container-install-$version" docker exec \
    -e DEBIAN_FRONTEND=noninteractive \
    -e APT_PACKAGE="$package" \
    -e APT_VERSION="$version" \
    -e APT_MESSAGE="$expected_message" \
    -e APT_SHA256="$expected_sha" \
    "$container" sh -euxc '
      apt-get update
      cd /tmp
      rm -f "${APT_PACKAGE}"_*.deb
      apt-get download "$APT_PACKAGE=$APT_VERSION"
      archive="$(find /tmp -maxdepth 1 -type f -name "${APT_PACKAGE}_*.deb" -print -quit)"
      test -n "$archive"
      test "$(sha256sum "$archive" | cut -d " " -f 1)" = "$APT_SHA256"
      apt-get install -y --no-install-recommends "$APT_PACKAGE=$APT_VERSION"
      test "$(dpkg-query -W -f="\${Version}" "$APT_PACKAGE")" = "$APT_VERSION"
      test "$(cat "/usr/share/$APT_PACKAGE/message.txt")" = "$APT_MESSAGE"
    '
}

apt_upgrade_version() {
  local container="$1"
  local package="$2"
  local version="$3"
  local expected_message="$4"
  run_logged "apt-$container-upgrade-$version" docker exec \
    -e DEBIAN_FRONTEND=noninteractive \
    -e APT_PACKAGE="$package" \
    -e APT_VERSION="$version" \
    -e APT_MESSAGE="$expected_message" \
    "$container" sh -euxc '
      rm -rf /var/lib/apt/lists/*
      apt-get update
      apt-get install -y --no-install-recommends --only-upgrade "$APT_PACKAGE=$APT_VERSION"
      test "$(dpkg-query -W -f="\${Version}" "$APT_PACKAGE")" = "$APT_VERSION"
      test "$(cat "/usr/share/$APT_PACKAGE/message.txt")" = "$APT_MESSAGE"
    '
}

apt_assert_old_version_absent() {
  local container="$1"
  local package="$2"
  local version="$3"
  run_logged "apt-$container-deleted-version" docker exec \
    -e APT_PACKAGE="$package" -e APT_VERSION="$version" "$container" sh -euxc '
      rm -rf /var/lib/apt/lists/*
      apt-get update
      ! apt-cache madison "$APT_PACKAGE" | grep -F " $APT_VERSION "
    '
}

apt_stop_client() {
  local container="$1"
  docker rm -f "$container" >/dev/null
}

apt_test_key_rotation() {
  local directory="$1"
  local image="$2"
  local old_key="$3"
  local old_config="$directory/key-old"
  local new_config="$directory/key-new"
  local old_log="$ARTIFACT_DIR/apt-key-rotation-old-key.log"

  run_logged_output apt-key-rotation "$ARTIFACT_DIR/apt-key-rotation.json" \
    curl -m 60 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -X PUT -H 'Content-Type: application/json' --data '{"generate":true}' \
    "$KKREPO_URL/internal/repositories/$APT_HOSTED_REPOSITORY/apt/signing-key"
  run_logged_output apt-key-rotation-public-key "$directory/new-key.asc" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/gpg.key"

  apt_write_client_config "$old_config" "$KKREPO_URL" "$APT_HOSTED_REPOSITORY" "$old_key"
  set +e
  docker run --rm --add-host host.docker.internal:host-gateway \
    --volume "$old_config/kkrepo.list:/etc/apt/sources.list.d/kkrepo.list:ro" \
    --volume "$old_config/kkrepo.conf:/etc/apt/auth.conf.d/kkrepo.conf:ro" \
    --volume "$old_config/kkrepo.asc:/etc/apt/keyrings/kkrepo.asc:ro" \
    "$image" sh -ec \
    'find /etc/apt/sources.list.d -type f ! -name kkrepo.list -delete; rm -f /etc/apt/sources.list; apt-get update' \
    >"$old_log" 2>&1
  local old_status=$?
  set -e
  redact_log_file "$old_log"
  if [[ "$old_status" -eq 0 ]] || ! grep -Eq \
      'NO_PUBKEY|signatures couldn.t be verified|Missing key .*needed to verify signature|OpenPGP signature verification failed|repository .* is not signed' \
      "$old_log"; then
    log "APT key rotation did not reject metadata with the retired scoped key"
    return 1
  fi

  apt_write_client_config "$new_config" "$KKREPO_URL" "$APT_HOSTED_REPOSITORY" \
    "$directory/new-key.asc"
  run_logged apt-key-rotation-new-key docker run --rm \
    --add-host host.docker.internal:host-gateway \
    --volume "$new_config/kkrepo.list:/etc/apt/sources.list.d/kkrepo.list:ro" \
    --volume "$new_config/kkrepo.conf:/etc/apt/auth.conf.d/kkrepo.conf:ro" \
    --volume "$new_config/kkrepo.asc:/etc/apt/keyrings/kkrepo.asc:ro" \
    "$image" sh -euxc \
    'find /etc/apt/sources.list.d -type f ! -name kkrepo.list -delete; rm -f /etc/apt/sources.list; apt-get update'
}

apt_test_secondary_replica() {
  local directory="$1"
  local image="$2"
  local package="$3"
  local version="$4"
  local expected_message="$5"
  local secondary_url="${APT_KKREPO_SECONDARY_BASE_URL:-}"
  if [[ -z "$secondary_url" ]]; then
    log "APT secondary-replica client check skipped: APT_KKREPO_SECONDARY_BASE_URL is unset"
    return 0
  fi
  run_logged_output apt-primary-release "$directory/primary-Release" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/dists/stable/Release"
  run_logged_output apt-secondary-release "$directory/secondary-Release" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$secondary_url/repository/$APT_HOSTED_REPOSITORY/dists/stable/Release"
  cmp "$directory/primary-Release" "$directory/secondary-Release"
  run_logged_output apt-secondary-key "$directory/secondary-key.asc" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$secondary_url/repository/$APT_HOSTED_REPOSITORY/gpg.key"
  local config="$directory/secondary-config"
  local container="kkrepo-apt-secondary-${STAMP}"
  apt_write_client_config "$config" "$secondary_url" "$APT_HOSTED_REPOSITORY" \
    "$directory/secondary-key.asc"
  apt_start_client "$container" "$image" "$config"
  apt_prepare_client "$container"
  run_logged "apt-secondary-install" docker exec \
    -e DEBIAN_FRONTEND=noninteractive -e APT_PACKAGE="$package" \
    -e APT_VERSION="$version" -e APT_MESSAGE="$expected_message" \
    "$container" sh -euxc '
      apt-get update
      apt-get install -y --no-install-recommends "$APT_PACKAGE=$APT_VERSION"
      test "$(dpkg-query -W -f="\${Version}" "$APT_PACKAGE")" = "$APT_VERSION"
      test "$(cat "/usr/share/$APT_PACKAGE/message.txt")" = "$APT_MESSAGE"
    '
  apt_stop_client "$container"
}

apt_create_proxy_repository() {
  local repository="$1"
  local metadata_mode="$2"
  local remote_url="$3"
  local payload
  payload="$(python3 - "$repository" "$metadata_mode" "$remote_url" <<'PY'
import json
import sys

repository, metadata_mode, remote_url = sys.argv[1:]
print(json.dumps({
    "name": repository,
    "recipe": "apt-proxy",
    "online": True,
    "blobStoreName": "default",
    "strictContentTypeValidation": True,
    "proxy": {
        "remoteUrl": remote_url,
        "contentMaxAgeMinutes": 1440,
        "metadataMaxAgeMinutes": 60,
        "negativeCacheEnabled": True,
        "negativeCacheTtlMinutes": 5,
        "autoBlock": True,
    },
    "apt": {
        "distribution": "stable",
        "component": "main",
        "architectures": ["amd64", "arm64"],
        "flat": False,
        "enforceDistribution": True,
        "metadataMode": metadata_mode,
        "validUntilDays": 30,
        "origin": "kkRepo APT proxy E2E",
        "label": "kkRepo APT proxy E2E",
    },
}, separators=(",", ":")))
PY
)"
  run_logged_output "apt-$repository-create" "$ARTIFACT_DIR/apt-$repository-create.json" \
    curl -m 30 --fail-with-body -sS -u "$KKREPO_AUTH" \
    -H 'Content-Type: application/json' --data "$payload" \
    "$KKREPO_URL/internal/repositories"
}

apt_test_proxy_modes() {
  local directory="$1"
  local image="$2"
  local package="$3"
  local version="$4"
  local expected_message="$5"
  local expected_sha="$6"
  local upstream_url proxy_name resign_name proxy_config resign_config container
  upstream_url="$(apt_client_base_url "$KKREPO_URL")/repository/$APT_HOSTED_REPOSITORY/"
  proxy_name="apt-proxy-$STAMP"
  resign_name="apt-resign-$STAMP"

  apt_create_proxy_repository "$proxy_name" PASSTHROUGH "$upstream_url"
  run_logged_output apt-proxy-upstream-inrelease "$directory/proxy-upstream-InRelease" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/dists/stable/InRelease"
  run_logged_output apt-proxy-inrelease "$directory/proxy-InRelease" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$proxy_name/dists/stable/InRelease"
  cmp "$directory/proxy-upstream-InRelease" "$directory/proxy-InRelease"
  run_logged_output apt-proxy-key "$directory/proxy-key.asc" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$proxy_name/gpg.key"
  proxy_config="$directory/proxy-config"
  apt_write_client_config "$proxy_config" "$KKREPO_URL" "$proxy_name" \
    "$directory/proxy-key.asc"
  container="kkrepo-apt-proxy-${STAMP}"
  apt_start_client "$container" "$image" "$proxy_config"
  apt_prepare_client "$container"
  apt_install_version "$container" "$package" "$version" "$expected_message" "$expected_sha"
  apt_stop_client "$container"

  set_repository_proxy_remote "$proxy_name" "http://host.docker.internal:9/unavailable/"
  container="kkrepo-apt-proxy-offline-${STAMP}"
  apt_start_client "$container" "$image" "$proxy_config"
  apt_prepare_client "$container"
  apt_install_version "$container" "$package" "$version" "$expected_message" "$expected_sha"
  apt_stop_client "$container"

  apt_create_proxy_repository "$resign_name" RESIGN "$upstream_url"
  run_logged_output apt-resign-inrelease "$directory/resign-InRelease" \
    curl -m 120 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$resign_name/dists/stable/InRelease"
  if cmp -s "$directory/proxy-upstream-InRelease" "$directory/resign-InRelease"; then
    log "APT re-sign proxy unexpectedly returned the upstream signed bytes"
    return 1
  fi
  run_logged_output apt-resign-packages "$directory/resign-Packages" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$resign_name/dists/stable/main/binary-all/Packages"
  if grep -Fq 'Filename: .apt/' "$directory/resign-Packages"; then
    log "APT re-sign proxy exposed an internal cache path"
    return 1
  fi
  run_logged_output apt-resign-key "$directory/resign-key.asc" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$resign_name/gpg.key"
  resign_config="$directory/resign-config"
  apt_write_client_config "$resign_config" "$KKREPO_URL" "$resign_name" \
    "$directory/resign-key.asc"
  container="kkrepo-apt-resign-${STAMP}"
  apt_start_client "$container" "$image" "$resign_config"
  apt_prepare_client "$container"
  apt_install_version "$container" "$package" "$version" "$expected_message" "$expected_sha"
  apt_stop_client "$container"
}

test_apt() {
  need docker
  local directory="$WORK_DIR/apt"
  local key_file="$directory/initial-key.asc"
  local base_package="kkrepo-apt-base-$STAMP"
  local base_file="$directory/${base_package}_1.0.0_all.deb"
  mkdir -p "$directory"
  run_logged_output apt-public-key "$key_file" \
    curl -m 20 --fail-with-body -sS -u "$KKREPO_AUTH" \
    "$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/gpg.key"
  apt_create_fixture "$base_file" "$base_package" 1.0.0 all "" \
    "kkRepo APT architecture-all dependency"
  apt_upload_fixture base "$base_file"
  apt_wait_for_version base "$base_package" 1.0.0 all

  local entry label image label_slug architecture package container config
  local v1_file v2_file v1_sha v2_sha v1_message v2_message
  local final_package="" final_message="" final_image="" final_sha=""
  IFS=',' read -r -a entries <<<"$APT_CLIENT_IMAGES"
  for entry in "${entries[@]}"; do
    label="${entry%%=*}"
    image="${entry#*=}"
    if [[ "$entry" != *=* || -z "$label" || -z "$image" ]]; then
      log "invalid APT_E2E_IMAGES entry: $entry"
      return 2
    fi
    label_slug="$(printf '%s' "$label" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-')"
    label_slug="${label_slug#-}"
    label_slug="${label_slug%-}"
    run_logged "apt-$label_slug-pull" docker pull "$image"
    architecture="$(docker run --rm "$image" dpkg --print-architecture)"
    package="kkrepo-apt-${label_slug}-${STAMP}"
    container="kkrepo-apt-${label_slug}-${STAMP}"
    config="$directory/config-$label_slug"
    v1_file="$directory/${package}_1.0.0_${architecture}.deb"
    v2_file="$directory/${package}_1.1.0_${architecture}.deb"
    v1_message="kkRepo APT $label version 1.0.0"
    v2_message="kkRepo APT $label version 1.1.0"
    apt_create_fixture "$v1_file" "$package" 1.0.0 "$architecture" \
      "$base_package (= 1.0.0)" "$v1_message"
    apt_upload_fixture "$label_slug-v1" "$v1_file"
    apt_wait_for_version "$label_slug-v1" "$package" 1.0.0 "$architecture"
    v1_sha="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$v1_file")"
    apt_write_client_config "$config" "$KKREPO_URL" "$APT_HOSTED_REPOSITORY" "$key_file"
    apt_start_client "$container" "$image" "$config"
    apt_prepare_client "$container"
    apt_install_version "$container" "$package" 1.0.0 "$v1_message" "$v1_sha"

    apt_create_fixture "$v2_file" "$package" 1.1.0 "$architecture" \
      "$base_package (= 1.0.0)" "$v2_message"
    apt_upload_fixture "$label_slug-v2" "$v2_file"
    apt_wait_for_version "$label_slug-v2" "$package" 1.1.0 "$architecture"
    v2_sha="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$v2_file")"
    apt_upgrade_version "$container" "$package" 1.1.0 "$v2_message"
    run_logged "apt-$label_slug-v2-bytes" docker exec \
      -e APT_PACKAGE="$package" -e APT_VERSION=1.1.0 -e APT_SHA256="$v2_sha" \
      "$container" sh -euxc '
        cd /tmp
        rm -f "${APT_PACKAGE}"_*.deb
        apt-get download "$APT_PACKAGE=$APT_VERSION"
        archive="$(find /tmp -maxdepth 1 -type f -name "${APT_PACKAGE}_*.deb" -print -quit)"
        test "$(sha256sum "$archive" | cut -d " " -f 1)" = "$APT_SHA256"
      '
    local prefix="${package:0:1}"
    run_logged "apt-$label_slug-delete-v1" curl -m 30 --fail-with-body -sS \
      -u "$KKREPO_AUTH" -X DELETE \
      "$KKREPO_URL/repository/$APT_HOSTED_REPOSITORY/pool/$prefix/$package/${package}_1.0.0_${architecture}.deb"
    apt_assert_old_version_absent "$container" "$package" 1.0.0
    apt_stop_client "$container"
    final_package="$package"
    final_message="$v2_message"
    final_image="$image"
    final_sha="$v2_sha"
  done

  [[ -n "$final_image" ]] || { log "APT client image matrix is empty"; return 2; }
  apt_test_key_rotation "$directory" "$final_image" "$key_file"
  apt_test_secondary_replica "$directory" "$final_image" "$final_package" 1.1.0 \
    "$final_message"
  apt_test_proxy_modes "$directory" "$final_image" "$final_package" 1.1.0 \
    "$final_message" "$final_sha"
}

test_ansible() {
  local matrix="${ANSIBLE_GALAXY_BINS:-}"
  if [[ -z "$matrix" ]] && command -v ansible-galaxy >/dev/null 2>&1; then
    matrix="current=$(command -v ansible-galaxy)"
  fi
  if [[ -z "$matrix" ]]; then
    log "Ansible Galaxy client E2E skipped: set ANSIBLE_GALAXY_BINS or install ansible-galaxy"
    return 0
  fi

  local locale_name
  locale_name="$(ansible_locale)"
  local matrix_file="$ARTIFACT_DIR/ansible-client-matrix.tsv"
  : >"$matrix_file"
  local entry label ansible_galaxy label_slug version_output
  local saw_29=false
  local saw_current=false
  IFS=',' read -r -a entries <<<"$matrix"
  for entry in "${entries[@]}"; do
    label="${entry%%=*}"
    ansible_galaxy="${entry#*=}"
    if [[ "$entry" != *=* || -z "$label" || -z "$ansible_galaxy" || ! -x "$ansible_galaxy" ]]; then
      log "invalid ANSIBLE_GALAXY_BINS entry: $entry"
      return 2
    fi
    label_slug="$(printf '%s' "$label" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9_' '_' | sed 's/^_*//;s/_*$//')"
    [[ -n "$label_slug" ]] || label_slug=ansible
    version_output="$(env LC_ALL="$locale_name" LANG="$locale_name" ANSIBLE_NOCOLOR=1 \
      "$ansible_galaxy" --version 2>&1 | sed -n '1p')"
    printf '%s\t%s\t%s\n' "$label" "$ansible_galaxy" "$version_output" >>"$matrix_file"
    [[ "$label" == 2.9* ]] && saw_29=true
    [[ "$label" == current* ]] && saw_current=true
    test_ansible_binary "$label" "$ansible_galaxy" "$label_slug" "$locale_name"
  done

  if [[ "${ANSIBLE_E2E_REQUIRE_2_9_CURRENT:-false}" == "true" \
      && ("$saw_29" != "true" || "$saw_current" != "true") ]]; then
    log "Ansible E2E requires both 2.9 and current entries"
    return 2
  fi
}

run_selected_tests() {
  local selection="${CLIENT_E2E_TESTS:-all}"
  local -a tests=()
  local test

  if [[ -z "$selection" || "$selection" == "all" ]]; then
    tests=(raw maven npm pypi go helm cargo pub composer nuget rubygems yum apt conda conan terraform swift ansible docker-oci)
  else
    IFS=',' read -r -a tests <<<"$selection"
  fi

  for test in "${tests[@]}"; do
    test="${test//[[:space:]]/}"
    CLEANUP_FIXTURE_FORMAT=""
    CLEANUP_FIXTURE_REPOSITORY=""
    CLEANUP_FIXTURE_PATTERN=""
    CLEANUP_FIXTURE_LABEL=""
    case "$test" in
      raw)
        test_raw
        register_cleanup_fixture raw asset-api-raw-hosted "*$STAMP*" raw
        ;;
      maven)
        test_maven
        register_cleanup_fixture maven2 maven-releases "*client-e2e-maven-$STAMP*" maven
        ;;
      npm)
        test_npm
        register_cleanup_fixture npm npm-hosted "*npm-$STAMP*" npm
        ;;
      pypi)
        test_pypi
        register_cleanup_fixture pypi pypi-hosted "*$STAMP*" pypi
        ;;
      go)
        test_go
        register_cleanup_fixture go go-proxy "*rsc.io/quote*" go
        ;;
      helm)
        test_helm
        register_cleanup_fixture helm helm-hosted "*helm-$STAMP*" helm
        ;;
      cargo)
        test_cargo
        register_cleanup_fixture cargo cargo-hosted "*cargo_$STAMP*" cargo
        ;;
      pub|dart-pub|flutter-pub)
        test_pub
        if command -v dart >/dev/null 2>&1; then
          register_cleanup_fixture pub pub-hosted "*pub_$STAMP*" pub
        fi
        ;;
      composer|php)
        test_composer
        register_cleanup_fixture composer composer-hosted "*package-$STAMP*" composer
        ;;
      nuget)
        test_nuget
        register_cleanup_fixture nuget nuget-hosted "*$STAMP*" nuget
        ;;
      rubygems|ruby)
        test_rubygems
        register_cleanup_fixture rubygems rubygems-hosted "*rubygems_$STAMP*" rubygems
        ;;
      yum)
        test_yum
        register_cleanup_fixture yum yum-hosted "*6tunnel*" yum
        ;;
      apt|debian)
        test_apt
        register_cleanup_fixture apt "$APT_HOSTED_REPOSITORY" "*kkrepo-apt-*-$STAMP*" apt
        ;;
      conda)
        test_conda
        register_cleanup_fixture conda "$CONDA_HOSTED_REPOSITORY" "*kkrepo_conda_e2e_$STAMP*" conda
        ;;
      conan)
        test_conan
        if command -v "$CONAN_BIN" >/dev/null 2>&1; then
          register_cleanup_fixture conan "$CONAN_HOSTED_REPOSITORY" "*kkrepo_conan_e2e_$STAMP*" conan
        fi
        ;;
      terraform)
        test_terraform
        register_cleanup_fixture terraform terraform-hosted "*client-e2e*" terraform
        ;;
      swift|swiftpm)
        SWIFT_CLEANUP_FIXTURE_AVAILABLE=false
        test_swift
        if [[ "$SWIFT_CLEANUP_FIXTURE_AVAILABLE" == "true" ]]; then
          register_cleanup_fixture swift swift-hosted "*client-e2e-${STAMP}*" swift
        fi
        ;;
      ansible|ansible-galaxy)
        test_ansible
        if [[ -n "${ANSIBLE_GALAXY_BINS:-}" ]] || command -v ansible-galaxy >/dev/null 2>&1; then
          register_cleanup_fixture ansiblegalaxy ansible-hosted "*${STAMP:0:14}*" ansible
        fi
        ;;
      docker|docker-oci|oci)
        test_docker_oci
        register_cleanup_fixture docker docker-hosted "*kkrepo-client-e2e/docker-oci*" docker-oci
        ;;
      "")
        ;;
      *)
        log "unknown client E2E test: $test"
        exit 2
        ;;
    esac
    run_registered_cleanup
  done
}

need curl
need python3

KKREPO_AUTH_URL="$(basic_auth_url)"
add_redaction_value "$KKREPO_AUTH_URL"
wait_for_http "kkrepo management health" "$KKREPO_MANAGEMENT_URL/actuator/health"

test_jdbc_browser_session
run_selected_tests

log "real client E2E matrix completed"
