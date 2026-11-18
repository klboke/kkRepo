#!/usr/bin/env bash
set -euo pipefail

# Swift image archives, loaded layers, and the remaining client containers can
# coexist below this budget. Keep a margin and report usage throughout the job.
minimum_gib="${E2E_MIN_FREE_DISK_GIB:-30}"
if [[ ! "$minimum_gib" =~ ^[1-9][0-9]*$ ]]; then
  echo "E2E_MIN_FREE_DISK_GIB must be a positive integer" >&2
  exit 2
fi
available_kib() { df -Pk / | awk 'NR == 2 {print $4}'; }
df -h /
if (( $(available_kib) >= minimum_gib * 1024 * 1024 )); then
  echo "At least ${minimum_gib} GiB available; skipping runner disk cleanup"
  exit 0
fi

# Call before starting services/loading images. Avoid evicting Docker images
# and build caches: deleting unused preinstalled SDKs is normally sufficient.
sudo rm -rf /opt/ghc /opt/hostedtoolcache/CodeQL /usr/local/.ghcup /usr/local/lib/android
sudo apt-get clean
df -h /
if (( $(available_kib) < minimum_gib * 1024 * 1024 )); then
  # Smaller hosted runners can still be below budget after the first pass.
  # Client suites use the .NET SDK in client-e2e-image, not this host SDK.
  # Keep Docker layers, cached image archives and the workspace intact.
  echo "Reclaiming unused host .NET SDK to meet the ${minimum_gib} GiB disk budget"
  sudo rm -rf /usr/share/dotnet
  df -h /
fi
if (( $(available_kib) < minimum_gib * 1024 * 1024 )); then
  echo "Less than ${minimum_gib} GiB available after runner disk cleanup" >&2
  exit 1
fi
