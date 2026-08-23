#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "usage: $0 <repository-base-url> <username:password> <package> [r-image]" >&2
  exit 2
fi

BASE_URL="$1"
AUTH="$2"
PACKAGE="$3"
R_IMAGE="${4:-r-base:4.6.1}"
USERNAME="${AUTH%%:*}"
PASSWORD="${AUTH#*:}"

if [[ -z "$USERNAME" || "$PASSWORD" == "$AUTH" ]]; then
  echo "R client performance authentication must use username:password" >&2
  exit 2
fi

REPOSITORY_URL="$(python3 - "$BASE_URL" "$USERNAME" "$PASSWORD" <<'PY'
import sys
from urllib.parse import quote, urlsplit, urlunsplit

base, username, password = sys.argv[1:4]
parts = urlsplit(base)
host = parts.hostname or ""
if host in {"127.0.0.1", "localhost", "::1"}:
    host = "host.docker.internal"
port = f":{parts.port}" if parts.port else ""
authority = f"{quote(username, safe='')}:{quote(password, safe='')}@{host}{port}"
print(urlunsplit((parts.scheme, authority, parts.path.rstrip("/"), "", "")))
PY
)"

docker run --rm --pull=missing \
  --add-host host.docker.internal:host-gateway \
  -e R_REPOSITORY_URL="$REPOSITORY_URL" \
  -e R_PACKAGE="$PACKAGE" \
  "$R_IMAGE" Rscript --vanilla -e '
    repository <- Sys.getenv("R_REPOSITORY_URL")
    package <- Sys.getenv("R_PACKAGE")
    available <- available.packages(repos = repository, type = "source", filters = list())
    stopifnot(package %in% rownames(available))
    library_path <- "/tmp/kkrepo-r-performance-library"
    dir.create(library_path, recursive = TRUE, showWarnings = FALSE)
    install.packages(package, repos = repository, lib = library_path,
                     dependencies = FALSE, type = "source", quiet = TRUE)
    stopifnot(as.character(packageVersion(package, lib.loc = library_path)) ==
              available[package, "Version"])
  '
