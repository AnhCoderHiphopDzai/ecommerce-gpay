#!/bin/sh
set -eu
. "$(dirname "$0")/common.sh"
command -v flock >/dev/null
mkdir -p "$PROD_ROOT/shared"
exec 9>"$PROD_ROOT/shared/deploy.lock"
flock -n 9 || { echo 'Another production deploy/rollback is running.' >&2; exit 1; }
compose pull
# Do not change the running app until the existing database has a verified backup.
BACKUP_LOCK_HELD=true sh "$release_dir/deploy/production/backup.sh"
compose up -d --remove-orphans --wait --wait-timeout 240
sh "$release_dir/deploy/production/smoke.sh"
# Promote the release only after PUBLIC HTTPS smoke has passed.
ln -sfn "$release_dir" "$PROD_ROOT/current.next"
mv -Tf "$PROD_ROOT/current.next" "$PROD_ROOT/current"
echo 'Production release promoted. Keep previous release directories for image rollback.'
