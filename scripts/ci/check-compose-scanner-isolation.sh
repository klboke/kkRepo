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
database = services["mysql"] if "mysql" in services else services["postgresql"]

assert scanner["environment"]["KKREPO_SCANNER_DB_AUTO_UPDATE"] == "false", source
assert set(scanner["networks"]) == {"scanner-internal"}, source
assert model["networks"]["scanner-internal"]["internal"] is True, source
assert set(database["networks"]) == {"database-internal"}, source
assert model["networks"]["database-internal"]["internal"] is True, source
assert set(scanner["networks"]).isdisjoint(database["networks"]), source

assert "KKREPO_SCANNER_SERVICE_CREDENTIAL" not in updater["environment"], source
assert updater["environment"]["KKREPO_SCANNER_DATABASE_UPDATE_ONLY"] == "true", source
assert updater["environment"]["KKREPO_SCANNER_DB_AUTO_UPDATE"] == "false", source
assert updater["environment"]["KKREPO_SCANNER_DATABASE_UPDATE_LOCK_TIMEOUT"] == "10m", source
assert updater["environment"]["KKREPO_SCANNER_DB_UPDATE_URL"] == "https://grype.anchore.io/databases", source
assert "KKREPO_SCANNER_DB_CA_CERT" not in updater["environment"], source
assert set(updater["networks"]) == {"scanner-update-egress"}, source
assert "scanner-update-egress" not in application["networks"], source
assert set(application["networks"]) == {
    "database-internal",
    "scanner-internal",
    "application-egress",
}, source

def mounted_volume(service):
    for volume in service["volumes"]:
        if volume["target"] == "/var/lib/kkrepo-scanner/grype":
            return volume
    raise AssertionError(f"{source}: scanner database volume is missing")

scanner_volume = mounted_volume(scanner)
updater_volume = mounted_volume(updater)
assert scanner_volume["source"] == updater_volume["source"], source
assert scanner_volume["read_only"] is True, source
assert updater_volume.get("read_only", False) is False, source
PY
  rm -f "$rendered"
  trap - EXIT
done
