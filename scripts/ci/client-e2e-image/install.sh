#!/usr/bin/env bash
set -euo pipefail

# Installed only when building the reusable image, never on a cache hit.
download() { curl --fail --location --retry 3 --silent --show-error "$@"; }
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"
case "${1:-}" in
  core)
    ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
    ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
    python -m pip install --no-cache-dir build twine
    npm install --global pnpm@11.19.0
    download https://sh.rustup.rs -o rustup.sh
    bash rustup.sh -y --profile minimal --default-toolchain stable --no-modify-path
    helm_version="$(download https://api.github.com/repos/helm/helm/releases/latest | jq -er .tag_name)"
    download "https://get.helm.sh/helm-${helm_version}-linux-amd64.tar.gz" -o helm.tar.gz
    download "https://get.helm.sh/helm-${helm_version}-linux-amd64.tar.gz.sha256sum" -o helm.sha256
    # The checksum file names the release archive, so retain that name locally.
    mv helm.tar.gz "helm-${helm_version}-linux-amd64.tar.gz"
    sha256sum --check helm.sha256
    tar -xzf "helm-${helm_version}-linux-amd64.tar.gz"
    install linux-amd64/helm /usr/local/bin/helm
    oras_version="$(download https://api.github.com/repos/oras-project/oras/releases/latest | jq -er .tag_name)"
    oras_version="${oras_version#v}"
    oras_archive="oras_${oras_version}_linux_amd64.tar.gz"
    download "https://github.com/oras-project/oras/releases/download/v${oras_version}/${oras_archive}" -o "$oras_archive"
    download "https://github.com/oras-project/oras/releases/download/v${oras_version}/oras_${oras_version}_checksums.txt" -o oras.sha256
    grep " ${oras_archive}$" oras.sha256 | sha256sum --check -
    tar -xzf "$oras_archive" oras
    install oras /usr/local/bin/oras
    # Flutter supplies the Dart executable used by both Pub flows. Keep only
    # their universal artifacts; these tests do not build desktop applications.
    git clone --depth 1 --branch stable https://github.com/flutter/flutter.git /opt/flutter
    flutter config --no-analytics
    flutter precache --universal
    ;;
  system)
    python -m pip install --no-cache-dir 'conan==2.31.2'
    uv python install 3.9
    uv venv --python 3.9 --seed /opt/ansible/2.9
    uv venv --python 3.13 --seed /opt/ansible/current
    /opt/ansible/2.9/bin/python -m pip install --no-cache-dir 'ansible==2.9.27' 'jinja2<3.1' 'MarkupSafe<2.1'
    /opt/ansible/current/bin/python -m pip install --no-cache-dir ansible-core
    installer=Miniforge3-26.3.2-3-Linux-x86_64.sh
    download "https://github.com/conda-forge/miniforge/releases/download/26.3.2-3/$installer" -o "$installer"
    download "https://github.com/conda-forge/miniforge/releases/download/26.3.2-3/$installer.sha256" -o miniforge.sha256
    sha256sum --check miniforge.sha256
    bash "$installer" -b -p /opt/conda
    /opt/conda/bin/conda clean --all --yes
    for version in 0.13.7 1.15.8; do
      archive="terraform_${version}_linux_amd64.zip"
      download "https://releases.hashicorp.com/terraform/${version}/${archive}" -o "$archive"
      download "https://releases.hashicorp.com/terraform/${version}/terraform_${version}_SHA256SUMS" -o terraform.sha256
      grep " ${archive}$" terraform.sha256 | sha256sum --check -
      mkdir -p "/opt/terraform/$version"
      unzip -q "$archive" -d "/opt/terraform/$version"
    done
    ;;
  *) echo 'expected core or system' >&2; exit 2 ;;
esac
