#!/bin/sh
set -eu

wait_for_up() {
  name="$1"
  url="$2"
  attempt=1
  until curl --fail --silent --show-error "$url" >/dev/null; do
    if [ "$attempt" -ge 30 ]; then
      echo "Timed out waiting for ${name}: ${url}" >&2
      exit 1
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
}

wait_for_up "backend health" "http://backend:8081/actuator/health"
wait_for_up "public products API" "http://backend:8081/api/products"
wait_for_up "frontend" "http://frontend/"

status="$(curl --silent --output /dev/null --write-out '%{http_code}' http://backend:8081/api/auth/me)"
[ "$status" = "401" ] || {
  echo "Expected /api/auth/me to reject anonymous users with 401, got ${status}" >&2
  exit 1
}

headers="$(curl --silent --show-error --dump-header - --output /dev/null --request OPTIONS \
  --header 'Origin: http://frontend' \
  --header 'Access-Control-Request-Method: GET' \
  http://backend:8081/api/products)"
case "$headers" in
  *"Access-Control-Allow-Origin: http://frontend"*|*"access-control-allow-origin: http://frontend"*) ;;
  *)
    echo 'CORS preflight did not allow the frontend origin' >&2
    exit 1
    ;;
esac

echo 'Candidate smoke checks passed.'
