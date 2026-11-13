#!/bin/sh
set -eu
. "$(dirname "$0")/common.sh"
if [ "${BACKUP_LOCK_HELD:-false}" != true ]; then
  exec 9>"$PROD_ROOT/shared/deploy.lock"
  flock -n 9 || { echo 'Deploy/backup is already running.' >&2; exit 1; }
fi
container="$(compose ps -aq db)"
if [ -z "$container" ]; then
  echo 'No existing production DB container; first deployment has no database to back up.'
  exit 0
fi
test "$(docker inspect --format '{{.State.Running}}' "$container")" = true || {
  echo 'Existing database is stopped. Recover it before deploying; backup is required.' >&2; exit 1;
}
mkdir -p "$PROD_ROOT/backups"
backup="$PROD_ROOT/backups/shop-$(date -u +%Y%m%dT%H%M%SZ)-$$.sql"
trap 'rm -f "$backup.partial"' EXIT
compose exec -T db sh -ec '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysqldump -uroot --single-transaction --routines --events --triggers \
    --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"
' > "$backup.partial"
test -s "$backup.partial"
mv "$backup.partial" "$backup"
gzip "$backup"
gzip -t "$backup.gz"
echo "Database backup complete: $backup.gz"
