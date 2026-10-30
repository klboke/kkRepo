#!/usr/bin/env bash
# Minimal CLI for the kkrepo multi-repository staging API.
#
# Required environment:
#   KKREPO_URL       Base URL, for example https://repo.example
#   KKREPO_USER      User name
#   KKREPO_PASSWORD  Password or API token
#
# Optional:
#   KKREPO_CACERT    CA bundle passed to curl
set -euo pipefail

BASE="${KKREPO_URL:?set KKREPO_URL}"
USER="${KKREPO_USER:?set KKREPO_USER}"
PASSWORD="${KKREPO_PASSWORD:?set KKREPO_PASSWORD}"
CURL=(curl -fsS -u "${USER}:${PASSWORD}" -H "Accept: application/json")
if [[ -n "${KKREPO_CACERT:-}" ]]; then
  CURL+=(--cacert "${KKREPO_CACERT}")
fi

usage() {
  cat <<'EOF'
Usage: kkrepo-staging.sh <command> [args]

Commands:
  create <name> [repository...] [--approval-required]
  list [state]
  get <session>
  items <session>
  add-repository <session> <repository>
  remove-repository <session> <repositoryId>
  add-group <session> <groupId> <memberId> <ADD|REMOVE> [position]
  groups <session>
  remove-group <session> <changeId>
  approve <session>
  preflight <session>
  promote <session>
  compensate <session>
  report <session>
  cancel <session>
  delete <session>
  metrics

Examples:
  kkrepo-staging.sh create release-2026-10 raw-release maven-release --approval-required
  kkrepo-staging.sh add-group 1 10 11 ADD
  kkrepo-staging.sh preflight 1
  kkrepo-staging.sh approve 1
  kkrepo-staging.sh promote 1
EOF
}

json_array() {
  local first=1
  printf '['
  for value in "$@"; do
    [[ $first -eq 1 ]] || printf ','
    first=0
    printf '"%s"' "${value//\"/\\\"}"
  done
  printf ']'
}

session_path() {
  printf '%s/internal/staging/sessions/%s' "$BASE" "$1"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  create)
    [[ $# -gt 0 ]] || { usage; exit 2; }
    name="$1"; shift
    approval=false
    repositories=()
    for argument in "$@"; do
      if [[ "$argument" == "--approval-required" ]]; then
        approval=true
      else
        repositories+=("$argument")
      fi
    done
    body=$(printf '{"name":"%s","repositories":%s,"approvalRequired":%s}' \
      "$name" \
      "$(if [[ ${#repositories[@]} -eq 0 ]]; then printf '[]'; else json_array "${repositories[@]}"; fi)" \
      "$approval")
    "${CURL[@]}" -X POST -H 'Content-Type: application/json' \
      -d "$body" "$BASE/internal/staging/sessions"
    ;;
  list)
    state="${1:-}"
    if [[ -n "$state" ]]; then
      "${CURL[@]}" "$BASE/internal/staging/sessions?state=$state"
    else
      "${CURL[@]}" "$BASE/internal/staging/sessions"
    fi
    ;;
  get)
    "${CURL[@]}" "$(session_path "$1")"
    ;;
  items)
    "${CURL[@]}" "$(session_path "$1")/items"
    ;;
  add-repository)
    "${CURL[@]}" -X POST -H 'Content-Type: application/json' \
      -d "{\"repository\":\"$2\"}" "$(session_path "$1")/repositories"
    ;;
  remove-repository)
    "${CURL[@]}" -X DELETE "$(session_path "$1")/repositories/$2"
    ;;
  add-group)
    position="${5:-null}"
    "${CURL[@]}" -X POST -H 'Content-Type: application/json' \
      -d "{\"memberRepositoryId\":$3,\"action\":\"$4\",\"position\":$position}" \
      "$(session_path "$1")/groups/$2/members"
    ;;
  groups)
    "${CURL[@]}" "$(session_path "$1")/groups"
    ;;
  remove-group)
    "${CURL[@]}" -X DELETE "$(session_path "$1")/groups/$2"
    ;;
  approve)
    "${CURL[@]}" -X POST "$(session_path "$1")/approve"
    ;;
  preflight)
    "${CURL[@]}" -X POST "$(session_path "$1")/preflight"
    ;;
  promote)
    "${CURL[@]}" -X POST "$(session_path "$1")/promote"
    ;;
  compensate)
    "${CURL[@]}" -X POST "$(session_path "$1")/compensate"
    ;;
  report)
    "${CURL[@]}" "$(session_path "$1")/report"
    ;;
  cancel)
    "${CURL[@]}" -X POST "$(session_path "$1")/cancel"
    ;;
  delete)
    "${CURL[@]}" -X DELETE "$(session_path "$1")"
    ;;
  metrics)
    "${CURL[@]}" "$BASE/internal/staging/metrics"
    ;;
  *)
    usage
    exit 2
    ;;
esac
