#!/bin/sh
set -eu
umask 077
PROD_ROOT="${PROD_ROOT:-/opt/ecommerce-gpay}"
case "$PROD_ROOT" in /*) ;; *) echo 'PROD_ROOT must be absolute' >&2; exit 1 ;; esac
release_dir="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
env_file="$PROD_ROOT/shared/production.env"
test -f "$env_file" || { echo 'Missing shared/production.env; see docs/PRODUCTION.md' >&2; exit 1; }
test -f "$release_dir/release.env" || { echo 'Missing release.env' >&2; exit 1; }
if grep -q 'CHANGE_ME' "$env_file"; then
  echo 'Replace all CHANGE_ME values in production.env before deploying.' >&2
  exit 1
fi
domain="$(sed -n 's/^DOMAIN=//p' "$env_file" | tr -d '\r')"
printf '%s\n' "$domain" | grep -Eq '^([a-zA-Z0-9][a-zA-Z0-9-]*\.)+[a-zA-Z]{2,}$' || {
  echo 'DOMAIN must be an unquoted DNS hostname without a scheme/port/path.' >&2; exit 1;
}
compose() {
  docker compose --env-file "$env_file" --env-file "$release_dir/release.env" \
    -f "$release_dir/compose.production.yaml" -p ecommerce-prod "$@"
}
expected_domain="$(sed -n 's/^EXPECTED_DOMAIN=//p' "$release_dir/release.env" | tr -d '\r')"
[ "$expected_domain" = "$domain" ] || { echo 'Release hostname differs from production.env DOMAIN. Rebuild for the correct origin.' >&2; exit 1; }
case "$(stat -c '%a' "$env_file")" in 600|400) ;; *) echo 'production.env must have chmod 600 or 400' >&2; exit 1 ;; esac
payments_enabled="$(sed -n 's/^APP_PAYMENTS_ENABLED=//p' "$env_file" | tr -d '\r')"
case "${payments_enabled:-false}" in true|false) ;; *) echo 'APP_PAYMENTS_ENABLED must be true or false' >&2; exit 1 ;; esac
release_id="$(sed -n 's/^RELEASE_ID=//p' "$release_dir/release.env" | tr -d '\r')"
printf '%s\n' "$release_id" | grep -Eq '^build-[0-9]+-[a-f0-9]{12}$' || { echo 'Invalid RELEASE_ID' >&2; exit 1; }
if [ "$payments_enabled" = true ]; then
  for key in GPAY_CLIENT_ID GPAY_MERCHANT_CODE GPAY_MERCHANT_ID GPAY_CLIENT_SECRET GPAY_PRIVATE_KEY GPAY_CERTIFICATE; do
    value="$(sed -n "s/^$key=//p" "$env_file" | tr -d '\r')"
    case "$value" in ''|disabled|*CHANGE_ME*) echo "Set $key before enabling payments." >&2; exit 1 ;; esac
  done
  test -s "$PROD_ROOT/shared/payment/public.pem" || { echo 'Missing verified G-Pay public.pem' >&2; exit 1; }
fi
compose config --quiet
