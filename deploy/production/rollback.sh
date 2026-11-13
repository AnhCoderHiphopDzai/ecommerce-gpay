#!/bin/sh
set -eu
# current remains the last successful release if candidate deployment/smoke failed.
PROD_ROOT="${PROD_ROOT:-/opt/ecommerce-gpay}"
test -L "$PROD_ROOT/current" || { echo 'No successful production baseline exists.' >&2; exit 1; }
target="$(readlink -f "$PROD_ROOT/current")"
case "$target" in "$PROD_ROOT"/releases/build-*) ;; *) echo 'Invalid baseline path' >&2; exit 1 ;; esac
exec sh "$target/deploy/production/deploy.sh"
