#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export KKREPO_RUNTIME=jvm
exec "$SCRIPT_DIR/kkrepo-runtime-deploy.sh" "$@"
