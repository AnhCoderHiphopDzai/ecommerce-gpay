#!/bin/sh
set -eu

backend_url="${BACKEND_URL:-http://127.0.0.1:${BACKEND_PORT:-8081}}"
frontend_url="${FRONTEND_URL:-http://127.0.0.1:${FRONTEND_PORT:-8088}}"
origin="${FRONTEND_ORIGIN:-http://${DEPLOY_HOST:-192.168.179.129}:${FRONTEND_PORT:-8088}}"

wait_for_up() {
  name="$1"
  url="$2"
  attempt=1
  until curl --fail --silent --show-error "$url" >/dev/null; do
    if [ "$attempt" -ge 36 ]; then
      echo "Timed out waiting for ${name}: ${url}" >&2
      exit 1
    fi
    attempt=$((attempt + 1))
    sleep 5
  done
}

wait_for_up "backend health" "${backend_url}/actuator/health"
wait_for_up "public products API" "${backend_url}/api/products"
wait_for_up "frontend" "${frontend_url}/"

status="$(curl --silent --output /dev/null --write-out '%{http_code}' "${backend_url}/api/auth/me")"
[ "$status" = "401" ] || {
  echo "Expected /api/auth/me to reject anonymous users with 401, got ${status}" >&2
  exit 1
}

headers="$(curl --silent --show-error --dump-header - --output /dev/null --request OPTIONS \
  --header "Origin: ${origin}" \
  --header 'Access-Control-Request-Method: GET' \
  "${backend_url}/api/products")"
case "$headers" in
  *"Access-Control-Allow-Origin: ${origin}"*|*"access-control-allow-origin: ${origin}"*) ;;
  *)
    echo 'CORS preflight did not allow the deployed frontend origin' >&2
    exit 1
    ;;
esac

echo 'Deployed smoke checks passed.'
