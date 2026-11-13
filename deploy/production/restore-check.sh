#!/bin/sh
set -eu
. "$(dirname "$0")/common.sh"
backup="${1:?Usage: restore-check.sh /absolute/path/shop-backup.sql.gz}"
gzip -t "$backup"
# Restore into NEW containers/volumes only, never the running ecommerce-prod project.
restore_project="ecommerce-restore-check-$(date +%s)-$$"
restore_compose() {
  docker compose --env-file "$env_file" --env-file "$release_dir/release.env" \
    -f "$release_dir/compose.production.yaml" -p "$restore_project" "$@"
}
cleanup() {
  result=$?
  trap - EXIT
  case "$restore_project" in ecommerce-restore-check-*) restore_compose down --volumes --remove-orphans || true ;; esac
  exit "$result"
}
trap cleanup EXIT
restore_compose up -d --wait --wait-timeout 180 db
gzip -dc "$backup" | restore_compose exec -T db sh -ec '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysql -uroot "$MYSQL_DATABASE"
'
restore_compose up -d --wait --wait-timeout 240 redis backend
restore_compose exec -T backend curl --fail --silent --max-time 10 http://localhost:8081/actuator/health
echo 'Isolated restore and application schema validation passed.'
