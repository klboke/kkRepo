#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

helm template auth-check "$repository_root/deploy/helm/kkrepo" > "$work_dir/password.yaml"
grep -q 'name: SPRING_DATASOURCE_PASSWORD' "$work_dir/password.yaml"
grep -q 'name: kkrepo-database' "$work_dir/password.yaml"

for backend in mysql postgresql; do
  helm template auth-check "$repository_root/deploy/helm/kkrepo" \
    --set database.type="$backend" --set database.auth=iam \
    --set database.iam.region=us-east-1 --set database.existingSecret= \
    --set 'extraVolumes[0].name=rds-ca' \
    --set 'extraVolumes[0].configMap.name=rds-ca' \
    --set 'extraVolumeMounts[0].name=rds-ca' \
    --set 'extraVolumeMounts[0].mountPath=/etc/rds' \
    --set 'extraVolumeMounts[0].readOnly=true' > "$work_dir/iam.yaml"
  grep -A1 'name: KKREPO_DATABASE_AUTH' "$work_dir/iam.yaml" | grep -q 'value: "iam"'
  grep -A1 'name: KKREPO_DATABASE_IAM_REGION' "$work_dir/iam.yaml" | grep -q 'value: "us-east-1"'
  grep -q 'mountPath: /etc/rds' "$work_dir/iam.yaml"
  if grep -q 'SPRING_DATASOURCE_PASSWORD\|kkrepo-database' "$work_dir/iam.yaml"; then
    echo 'IAM deployment must not reference a database password Secret' >&2
    exit 1
  fi
done
if helm template auth-check "$repository_root/deploy/helm/kkrepo" \
    --set database.auth=invalid > "$work_dir/invalid.log" 2>&1; then
  echo 'Invalid database auth mode was accepted' >&2
  exit 1
fi
grep -q 'database.auth must be password or iam' "$work_dir/invalid.log"
echo 'Helm database authentication checks passed'
