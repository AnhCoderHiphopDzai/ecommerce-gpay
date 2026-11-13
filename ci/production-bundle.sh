#!/bin/sh
set -eu
# Explicit allowlist: never recursively archive a directory that could contain local secrets.
exec tar -czf "${1:?Output tar.gz path is required}" -- \
  compose.production.yaml \
  deploy/production/Caddyfile \
  deploy/production/.env.example \
  deploy/production/common.sh \
  deploy/production/backup.sh \
  deploy/production/deploy.sh \
  deploy/production/rollback.sh \
  deploy/production/smoke.sh \
  deploy/production/restore-check.sh \
  deploy/production/ecommerce-backup.service \
  deploy/production/ecommerce-backup.timer \
  ci/production-release.env
