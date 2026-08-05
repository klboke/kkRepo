#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/../.." && pwd)"
backend="${1:-all}"

component_count="${CLEANUP_PERF_COMPONENT_COUNT:-1000000}"
unbound_asset_count="${CLEANUP_PERF_UNBOUND_ASSET_COUNT:-100000}"
repository_count="${CLEANUP_PERF_REPOSITORY_COUNT:-100}"
policy_count="${CLEANUP_PERF_POLICY_COUNT:-100}"
history_run_count="${CLEANUP_PERF_HISTORY_RUN_COUNT:-5000}"
history_items_per_run="${CLEANUP_PERF_HISTORY_ITEMS_PER_RUN:-100}"
detail_repository_count="${CLEANUP_PERF_DETAIL_REPOSITORY_COUNT:-100}"
detail_items_per_repository="${CLEANUP_PERF_DETAIL_ITEMS_PER_REPOSITORY:-1000}"
mysql_container="${CLEANUP_PERF_MYSQL_CONTAINER:-kkrepo-mysql}"
postgresql_container="${CLEANUP_PERF_POSTGRESQL_CONTAINER:-kkrepo-swift-s3-final-postgresql-1}"
keep_databases="${CLEANUP_PERF_KEEP_DATABASES:-false}"
mysql_database="kkrepo_cleanup_perf"
postgresql_database="kkrepo_cleanup_perf"
report_dir="${repository_root}/target/cleanup-performance"

if [[ "${backend}" != "all" && "${backend}" != "mysql" && "${backend}" != "postgresql" ]]; then
  echo "Usage: $0 [all|mysql|postgresql]" >&2
  exit 2
fi

for value in \
  "${component_count}" \
  "${unbound_asset_count}" \
  "${repository_count}" \
  "${policy_count}" \
  "${history_run_count}" \
  "${history_items_per_run}" \
  "${detail_repository_count}" \
  "${detail_items_per_repository}"; do
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]] || (( value > 1000000 )); then
    echo "All dataset sizes must be integers between 1 and 1000000" >&2
    exit 2
  fi
done

if (( detail_repository_count > repository_count )); then
  echo "CLEANUP_PERF_DETAIL_REPOSITORY_COUNT cannot exceed repository count" >&2
  exit 2
fi
if (( policy_count > 1000 || repository_count > 1000 )); then
  echo "Policy and repository counts are capped at 1000 for this probe" >&2
  exit 2
fi

mkdir -p "${report_dir}"

mysql_client() {
  docker exec -i "${mysql_container}" sh -lc \
    "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot --database=${mysql_database}"
}

mysql_admin() {
  docker exec -i "${mysql_container}" sh -lc \
    "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot"
}

postgresql_client() {
  docker exec -i "${postgresql_container}" sh -lc \
    'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d kkrepo_cleanup_perf "$@"' \
    sh "$@"
}

seed_mysql() {
  docker inspect "${mysql_container}" >/dev/null
  mysql_admin <<SQL
DROP DATABASE IF EXISTS ${mysql_database};
CREATE DATABASE ${mysql_database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL

  while IFS= read -r migration; do
    mysql_client < "${migration}"
  done < <(find "${repository_root}/persistence-mysql/src/main/resources/db/migration/mysql" \
    -maxdepth 1 -name '*.sql' | sort -V)

  {
    printf 'SET @component_count = %s;\n' "${component_count}"
    printf 'SET @unbound_asset_count = %s;\n' "${unbound_asset_count}"
    printf 'SET @repository_count = %s;\n' "${repository_count}"
    printf 'SET @policy_count = %s;\n' "${policy_count}"
    printf 'SET @history_run_count = %s;\n' "${history_run_count}"
    printf 'SET @history_items_per_run = %s;\n' "${history_items_per_run}"
    printf 'SET @detail_repository_count = %s;\n' "${detail_repository_count}"
    printf 'SET @detail_items_per_repository = %s;\n' "${detail_items_per_repository}"
    sed -n '1,$p' "${script_dir}/cleanup-large-repository-mysql.sql"
  } | mysql_client | tee "${report_dir}/mysql-seed.txt"

  local counts
  counts="$(docker exec "${mysql_container}" sh -lc \
    "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot -Nse \
      \"SELECT COUNT(*), (SELECT COUNT(*) FROM ${mysql_database}.asset), \
       (SELECT COUNT(*) FROM ${mysql_database}.cleanup_run_item) \
       FROM ${mysql_database}.component\"")"
  local expected_assets=$((component_count + unbound_asset_count))
  local expected_items=$((history_run_count * history_items_per_run
    + detail_repository_count * detail_items_per_repository))
  if [[ "${counts}" != "${component_count}"$'\t'"${expected_assets}"$'\t'"${expected_items}" ]]; then
    echo "Unexpected MySQL seed counts: ${counts}" >&2
    exit 1
  fi

  {
    printf 'SET @component_count = %s;\n' "${component_count}"
    printf 'SET @unbound_asset_count = %s;\n' "${unbound_asset_count}"
    printf 'SET @history_run_count = %s;\n' "${history_run_count}"
    sed -n '1,$p' "${script_dir}/cleanup-large-repository-mysql-report.sql"
  } | mysql_client | tee "${report_dir}/mysql-report.txt"

  local component_index_uses asset_index_uses
  component_index_uses="$(rg -c 'idx_component_cleanup_scan' \
    "${report_dir}/mysql-report.txt" || true)"
  asset_index_uses="$(rg -c 'idx_asset_cleanup_unbound' \
    "${report_dir}/mysql-report.txt" || true)"
  if (( component_index_uses < 2 || asset_index_uses < 2 )); then
    echo "MySQL cleanup keyset plans did not use both dedicated indexes" >&2
    exit 1
  fi
  rg -q 'idx_cleanup_run_item_repository' "${report_dir}/mysql-report.txt"
  rg -qi 'window' "${report_dir}/mysql-report.txt"
  if rg -qi 'dependent subquery' "${report_dir}/mysql-report.txt"; then
    echo "MySQL history retention regressed to a correlated subquery" >&2
    exit 1
  fi
  local history_result
  history_result="$(rg -o 'bounded-history-result=[0-9]+,[0-9]+,[0-9]+' \
    "${report_dir}/mysql-report.txt" | tail -1)"
  IFS=',' read -r deleted_items deleted_runs observed_delta \
    <<< "${history_result#bounded-history-result=}"
  if (( deleted_items < 1 || deleted_items > 500 || deleted_items != observed_delta )); then
    echo "MySQL history pruning exceeded its 500-item write bound: ${history_result}" >&2
    exit 1
  fi

  if [[ "${keep_databases}" != "true" ]]; then
    mysql_admin <<SQL
DROP DATABASE ${mysql_database};
SQL
  fi
}

seed_postgresql() {
  docker inspect "${postgresql_container}" >/dev/null
  docker exec "${postgresql_container}" sh -lc \
    "dropdb -U \"\$POSTGRES_USER\" --if-exists ${postgresql_database}; \
     createdb -U \"\$POSTGRES_USER\" ${postgresql_database}"

  while IFS= read -r migration; do
    postgresql_client < "${migration}"
  done < <(find \
    "${repository_root}/persistence-postgresql/src/main/resources/db/migration/postgresql" \
    -maxdepth 1 -name '*.sql' | sort -V)

  postgresql_client \
    -v component_count="${component_count}" \
    -v unbound_asset_count="${unbound_asset_count}" \
    -v repository_count="${repository_count}" \
    -v policy_count="${policy_count}" \
    -v history_run_count="${history_run_count}" \
    -v history_items_per_run="${history_items_per_run}" \
    -v detail_repository_count="${detail_repository_count}" \
    -v detail_items_per_repository="${detail_items_per_repository}" \
    < "${script_dir}/cleanup-large-repository-postgresql.sql" \
    | tee "${report_dir}/postgresql-seed.txt"

  local counts
  counts="$(docker exec "${postgresql_container}" sh -lc \
    "psql -U \"\$POSTGRES_USER\" -d ${postgresql_database} -Atqc \
      \"SELECT COUNT(*) || E'\\t' || (SELECT COUNT(*) FROM asset) || E'\\t' || \
       (SELECT COUNT(*) FROM cleanup_run_item) FROM component\"")"
  local expected_assets=$((component_count + unbound_asset_count))
  local expected_items=$((history_run_count * history_items_per_run
    + detail_repository_count * detail_items_per_repository))
  if [[ "${counts}" != "${component_count}"$'\t'"${expected_assets}"$'\t'"${expected_items}" ]]; then
    echo "Unexpected PostgreSQL seed counts: ${counts}" >&2
    exit 1
  fi

  postgresql_client \
    -v component_count="${component_count}" \
    -v unbound_asset_count="${unbound_asset_count}" \
    -v history_run_count="${history_run_count}" \
    < "${script_dir}/cleanup-large-repository-postgresql-report.sql" \
    | tee "${report_dir}/postgresql-report.txt"

  local component_index_uses asset_index_uses
  component_index_uses="$(rg -c 'idx_component_cleanup_scan' \
    "${report_dir}/postgresql-report.txt" || true)"
  asset_index_uses="$(rg -c 'idx_asset_cleanup_unbound' \
    "${report_dir}/postgresql-report.txt" || true)"
  if (( component_index_uses < 2 || asset_index_uses < 2 )); then
    echo "PostgreSQL cleanup keyset plans did not use both dedicated indexes" >&2
    exit 1
  fi
  rg -q 'idx_cleanup_run_item_repository' "${report_dir}/postgresql-report.txt"
  rg -q 'WindowAgg' "${report_dir}/postgresql-report.txt"
  if rg -q 'SubPlan' "${report_dir}/postgresql-report.txt"; then
    echo "PostgreSQL history retention regressed to a correlated subplan" >&2
    exit 1
  fi
  local history_result
  history_result="$(rg -o 'bounded-history-result=[0-9]+,[0-9]+,[0-9]+' \
    "${report_dir}/postgresql-report.txt" | tail -1)"
  IFS=',' read -r deleted_items deleted_runs observed_delta \
    <<< "${history_result#bounded-history-result=}"
  if (( deleted_items < 1 || deleted_items > 500 || deleted_items != observed_delta )); then
    echo "PostgreSQL history pruning exceeded its 500-item write bound: ${history_result}" >&2
    exit 1
  fi

  if [[ "${keep_databases}" != "true" ]]; then
    docker exec "${postgresql_container}" sh -lc \
      "dropdb -U \"\$POSTGRES_USER\" ${postgresql_database}"
  fi
}

start_epoch="$(date +%s)"
if [[ "${backend}" == "all" || "${backend}" == "mysql" ]]; then
  echo "Seeding and probing MySQL ${component_count}-component cleanup dataset"
  seed_mysql
fi
if [[ "${backend}" == "all" || "${backend}" == "postgresql" ]]; then
  echo "Seeding and probing PostgreSQL ${component_count}-component cleanup dataset"
  seed_postgresql
fi
elapsed_seconds=$(( $(date +%s) - start_epoch ))

echo "Cleanup large-repository probe passed in ${elapsed_seconds}s"
echo "Reports: ${report_dir}"
if [[ "${keep_databases}" == "true" ]]; then
  echo "Isolated performance database retained on each selected backend"
fi
