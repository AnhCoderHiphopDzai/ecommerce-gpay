#!/bin/sh
set -eu
. "$(dirname "$0")/common.sh"
url="https://$domain"
# All requests verify the public certificate; never use curl -k.
request() { curl --connect-timeout 5 --max-time 15 --silent --show-error "$@"; }
attempt=1
until request --fail "$url/actuator/health" | grep -q '"status":"UP"'; do
  [ "$attempt" -lt 24 ] || { echo 'HTTPS health timed out; inspect edge/backend logs.' >&2; exit 1; }
  attempt=$((attempt + 1))
  sleep 5
done
release_headers="$(request --fail --dump-header - --output /dev/null "$url/")"
printf '%s\n' "$release_headers" | tr -d '\r' | grep -Fixq "X-Release-ID: $release_id" || {
  echo 'Public hostname is not serving this release; check DNS, proxy and image versions.' >&2; exit 1;
}
request --fail "$url/api/products" >/dev/null
status="$(request --output /dev/null --write-out '%{http_code}' "$url/api/auth/me")"
[ "$status" = 401 ] || { echo "Expected anonymous 401, got $status" >&2; exit 1; }
request --fail "$url/ws/info" >/dev/null
headers="$(request --dump-header - --output /dev/null --request OPTIONS \
  --header "Origin: $url" --header 'Access-Control-Request-Method: GET' "$url/api/products")"
printf '%s\n' "$headers" | tr -d '\r' | grep -Fixq "Access-Control-Allow-Origin: $url" || {
  echo 'CORS did not allow the public HTTPS origin.' >&2; exit 1;
}
echo "Production HTTPS smoke passed: $url"
