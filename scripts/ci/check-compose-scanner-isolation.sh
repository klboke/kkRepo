#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

for compose_file in \
  "$repository_root/docker-compose.quickstart.yml" \
  "$repository_root/docker-compose.quickstart-postgresql.yml"; do
  rendered="$(mktemp)"
  trap 'rm -f "$rendered"' EXIT
  docker compose \
    -f "$compose_file" \
    --profile security-scanning \
    config \
    --format json >"$rendered"
  python3 - "$compose_file" "$rendered" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).name
with open(sys.argv[2], encoding="utf-8") as handle:
    model = json.load(handle)

services = model["services"]
scanner = services["scanner"]
updater = services["scanner-database-updater"]
application = services["kkrepo"]

assert scanner["environment"]["KKREPO_SCANNER_DB_AUTO_UPDATE"] == "false", source
assert set(scanner["networks"]) == {"kkrepo-internal"}, source
assert model["networks"]["kkrepo-internal"]["internal"] is True, source

assert "KKREPO_SCANNER_SERVICE_CREDENTIAL" not in updater["environment"], source
assert updater["environment"]["KKREPO_SCANNER_DATABASE_UPDATE_ONLY"] == "true", source
assert updater["environment"]["KKREPO_SCANNER_DB_AUTO_UPDATE"] == "false", source
assert updater["environment"]["KKREPO_SCANNER_DATABASE_UPDATE_LOCK_TIMEOUT"] == "10m", source
assert set(updater["networks"]) == {"scanner-update-egress"}, source
assert "scanner-update-egress" not in application["networks"], source
assert set(application["networks"]) == {
    "kkrepo-internal",
    "application-egress",
}, source

def mounted_source(service):
    for volume in service["volumes"]:
        if volume["target"] == "/var/lib/kkrepo-scanner/grype":
            return volume["source"]
    raise AssertionError(f"{source}: scanner database volume is missing")

assert mounted_source(scanner) == mounted_source(updater), source
PY
  rm -f "$rendered"
  trap - EXIT
done
