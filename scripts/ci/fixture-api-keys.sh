#!/usr/bin/env bash

# Keep the secrets returned by creation in this shell for the lifetime of the fixture.
CI_FIXTURE_KEY_DOMAINS=()
CI_FIXTURE_KEY_IDS=()
CI_FIXTURE_KEY_TOKENS=()

fixture_api_key() {
  local fixture_result="$1" fixture_domain="$2" fixture_name="$3"
  local fixture_index fixture_payload fixture_response fixture_created fixture_id fixture_token
  for fixture_index in "${!CI_FIXTURE_KEY_DOMAINS[@]}"; do
    if [[ "${CI_FIXTURE_KEY_DOMAINS[$fixture_index]}" == "$fixture_domain" ]]; then
      printf -v "$fixture_result" '%s' "${CI_FIXTURE_KEY_TOKENS[$fixture_index]}"
      return
    fi
  done

  fixture_payload="$(python3 -c \
    'import json,sys; print(json.dumps({"domain": sys.argv[1], "displayName": sys.argv[2]}))' \
    "$fixture_domain" "$fixture_name")" || return
  fixture_response="$(curl -m 20 -fsS -u "$KKREPO_AUTH" \
    -H 'Content-Type: application/json' --data "$fixture_payload" \
    "${KKREPO_URL%/}/internal/security/api-keys/current")" || return
  fixture_created="$(python3 -c '
import json
import sys

created = json.load(sys.stdin)
key_id = int(created["apiKey"]["id"])
token = created["token"]
if key_id < 1 or not isinstance(token, str) or not token:
    raise SystemExit("Fixture API key response is incomplete")
print(f"{key_id}\t{token}")
' <<<"$fixture_response")" || return
  IFS=$'\t' read -r fixture_id fixture_token <<<"$fixture_created"
  CI_FIXTURE_KEY_DOMAINS+=("$fixture_domain")
  CI_FIXTURE_KEY_IDS+=("$fixture_id")
  CI_FIXTURE_KEY_TOKENS+=("$fixture_token")
  printf -v "$fixture_result" '%s' "$fixture_token"
}

cleanup_fixture_api_keys() {
  local fixture_index fixture_failed=0
  for fixture_index in "${!CI_FIXTURE_KEY_IDS[@]}"; do
    if curl -m 20 -fsS -u "$KKREPO_AUTH" -X DELETE \
      "${KKREPO_URL%/}/internal/security/api-keys/current/${CI_FIXTURE_KEY_IDS[$fixture_index]}" \
      >/dev/null; then
      unset "CI_FIXTURE_KEY_DOMAINS[$fixture_index]" "CI_FIXTURE_KEY_IDS[$fixture_index]" \
        "CI_FIXTURE_KEY_TOKENS[$fixture_index]"
    else
      fixture_failed=1
    fi
  done
  return "$fixture_failed"
}
