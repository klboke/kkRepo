#!/usr/bin/env bash
set -euo pipefail

# Fail before the suite can silently skip optional clients such as Flutter/ORAS.
java -version 2>&1 | tee /tmp/e2e-java-version | grep -E 'version "25[.]'
mvn -version
python3 -c 'import sys; assert sys.version_info[:2] == (3, 13); print(sys.version)'
docker --version
case "${1:-}" in
  core)
    node --version | grep '^v24\.'
    npm --version
    pnpm --version | grep '^11\.19\.0$'
    go version | grep 'go1\.25\.'
    dotnet --version | grep '^8\.'
    ruby --version | grep '^ruby 3\.4\.'
    gem --version
    php --version | grep '^PHP 8\.4\.'
    composer --version
    cargo --version
    rustc --version
    helm version --short
    dart --version
    flutter --version
    oras version
    python -c 'import build, twine'
    ;;
  system)
    "$CONDA_BIN" --version
    conan --version | grep '2\.31\.2'
    "$TERRAFORM_013_BIN" version | grep '^Terraform v0\.13\.7$'
    "$TERRAFORM_CURRENT_BIN" version | grep '^Terraform v1\.15\.8$'
    /opt/ansible/2.9/bin/ansible-galaxy --version | grep '^ansible-galaxy 2\.9\.27$'
    /opt/ansible/current/bin/ansible-galaxy --version
    ;;
  swift) ;; # The Swift version matrix uses its existing three client images.
  *) echo 'expected core, system, or swift' >&2; exit 2 ;;
esac
