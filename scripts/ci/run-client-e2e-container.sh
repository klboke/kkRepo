#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"
image="${CLIENT_E2E_IMAGE:?Load the prebuilt client image first}"
runner_temp="${RUNNER_TEMP:?RUNNER_TEMP must identify a directory shared with the Docker host}"
runtime_dir="$runner_temp/client-e2e-runtime"
mkdir -p "$runtime_dir/home" "$runtime_dir/work" "$runtime_dir/tmp" "$runner_temp/client-m2"
args=(--rm --network host
  --mount "type=bind,source=$project_root,target=$project_root"
  --mount "type=bind,source=$runner_temp,target=$runner_temp"
  --mount "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock"
  --workdir "$project_root")
# Forward test configuration by name, never runner credentials or its PATH.
# Matching host/container paths are required by the sibling Docker clients.
while IFS= read -r name; do
  case "$name" in
    KKREPO_*|CLIENT_E2E_*|LIVE_COMPAT_*|DOCKER_PAGINATION_*|SWIFT_*|COMPAT_*|NEXUS_*|APT_*|ALPINE_*|R_*|ANSIBLE_*|CONAN_*|CONDA_*|TERRAFORM_*|PUB_*|COMPOSER_*|GO_*|HELM_*|PNPM_*|CURL_CA_BUNDLE)
      args+=(--env "$name") ;;
  esac
done < <(compgen -e)
args+=(--env "HOME=$runtime_dir/home" --env "TMPDIR=$runtime_dir/tmp"
  --env "CLIENT_E2E_WORK_DIR=$runtime_dir/work"
  --env "RUNNER_TEMP=$runner_temp"
  --env "MAVEN_OPTS=-Dmaven.repo.local=$runner_temp/client-m2")
if (( $# == 0 )); then
  set -- scripts/ci/run-live-compat.sh client-e2e
fi
mkdir -p "$project_root/artifacts/client-e2e"
docker image inspect --format '{{.Id}}' "$image" > "$project_root/artifacts/client-e2e/toolchain-image.txt"
exec docker run "${args[@]}" "$image" bash -c '
  set -euo pipefail
  git config --global --add safe.directory "$PWD"
  cp /opt/e2e/versions.txt artifacts/client-e2e/toolchain-versions.txt
  exec "$@"
' client-e2e "$@"
