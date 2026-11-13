#!/bin/sh
# Offline contract tests: every Docker/SSH/HTTP operation is mocked in a private fixture.
set -eu
root="$(pwd)"
mkdir -p "$root/artifacts"
fixture="$(mktemp -d "$root/artifacts/production-test.XXXXXX")"
mkdir -p "$fixture/bin" "$fixture/vps/shared" "$fixture/vps/releases/build-1-111111111111" "$fixture/vps/releases/build-2-222222222222"
export PROD_ROOT="$fixture/vps" MOCK_LOG="$fixture/commands.log" MOCK_ACTIVE_RELEASE="$fixture/active-release"
export PATH="$fixture/bin:$PATH"
cat > "$fixture/bin/docker" <<'MOCK'
#!/bin/sh
echo "$*" >> "$MOCK_LOG"
if [ "$1" = inspect ]; then echo true; exit 0; fi
while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) shift; release_manifest="$1" ;;
    config|ps|pull|exec|up|down) command="$1"; shift; break ;;
  esac
  shift
done
case "$command" in
  ps) [ "${MOCK_HAS_DB:-true}" != true ] || echo fixture-db ;;
  exec)
    case "$*" in
      *mysqldump*) [ "${MOCK_BACKUP_FAIL:-false}" = false ] || exit 1; echo 'CREATE TABLE backup_test (id INT);' ;;
      *actuator*) echo '{"status":"UP"}' ;;
      *) cat >/dev/null ;;
    esac
    ;;
  up) [ "${MOCK_UP_FAIL:-false}" = false ] || exit 1; sed -n 's/^RELEASE_ID=//p' "$release_manifest" > "$MOCK_ACTIVE_RELEASE" ;;
esac
MOCK
cat > "$fixture/bin/curl" <<'MOCK'
#!/bin/sh
[ "${MOCK_SMOKE_FAIL:-false}" = false ] || exit 22
case "$*" in
  *OPTIONS*) printf 'HTTP/2 200\r\nAccess-Control-Allow-Origin: https://shop.example.test\r\n' ;;
  *dump-header*) printf 'HTTP/2 200\r\nX-Release-ID: %s\r\n' "${MOCK_HEADER_OVERRIDE:-$(cat "$MOCK_ACTIVE_RELEASE")}" ;;
  *api/auth/me*) printf 401 ;;
  *actuator/health*) echo '{"status":"UP"}' ;;
  *) echo '{}' ;;
esac
MOCK
cat > "$fixture/bin/stat" <<'MOCK'
#!/bin/sh
echo "${MOCK_ENV_MODE:-600}"
MOCK
cat > "$fixture/bin/flock" <<'MOCK'
#!/bin/sh
exit 0
MOCK
cat > "$fixture/bin/sleep" <<'MOCK'
#!/bin/sh
exit 0
MOCK
chmod +x "$fixture/bin/"*
printf 'DOMAIN=shop.example.test\nAPP_PAYMENTS_ENABLED=false\n' > "$PROD_ROOT/shared/production.env"
old="$PROD_ROOT/releases/build-1-111111111111"
new="$PROD_ROOT/releases/build-2-222222222222"
for release in "$old" "$new"; do
  mkdir -p "$release/deploy/production"
  cp deploy/production/*.sh "$release/deploy/production/"
  cp compose.production.yaml "$release/"
  printf 'BACKEND_IMAGE=fixture/backend:build\nFRONTEND_IMAGE=fixture/frontend:build\nEXPECTED_DOMAIN=shop.example.test\nRELEASE_ID=%s\n' "$(basename "$release")" > "$release/release.env"
done
printf 'build-1-111111111111\n' > "$MOCK_ACTIVE_RELEASE"
native_links=true
case "$(uname -s)" in MINGW*|MSYS*) native_links=false ;; esac
if [ "$native_links" = true ]; then
  ln -s "$old" "$PROD_ROOT/current"
else
  mkdir -p "$PROD_ROOT/current"
  touch "$PROD_ROOT/current/baseline-marker"
fi
assert_baseline() {
  if [ "$native_links" = true ]; then
    [ "$(readlink "$PROD_ROOT/current")" = "$old" ]
  else
    test -f "$PROD_ROOT/current/baseline-marker"
  fi
}
expect_failure() {
  if "$@" > "$fixture/last-failure.log" 2>&1; then
    echo "Expected failure: $*" >&2; exit 1
  fi
  assert_baseline || { echo 'A failed release changed the baseline' >&2; exit 1; }
}
: > "$MOCK_LOG"
expect_failure env MOCK_BACKUP_FAIL=true sh "$new/deploy/production/deploy.sh"
! grep -q ' up ' "$MOCK_LOG" || { echo 'App changed after backup failed' >&2; exit 1; }
expect_failure env MOCK_UP_FAIL=true sh "$new/deploy/production/deploy.sh"
expect_failure env MOCK_SMOKE_FAIL=true sh "$new/deploy/production/deploy.sh"
expect_failure env MOCK_HEADER_OVERRIDE=build-0-000000000000 sh "$new/deploy/production/deploy.sh"
expect_failure env MOCK_ENV_MODE=644 sh "$new/deploy/production/deploy.sh"
cp "$new/release.env" "$fixture/new-release.env"
printf 'BACKEND_IMAGE=fixture/backend:build\nEXPECTED_DOMAIN=wrong.example.test\n' > "$new/release.env"
expect_failure sh "$new/deploy/production/deploy.sh"
cp "$fixture/new-release.env" "$new/release.env"
if [ "$native_links" = true ]; then
  sh "$new/deploy/production/rollback.sh"
  [ "$(readlink "$PROD_ROOT/current")" = "$old" ]
  sh "$new/deploy/production/deploy.sh"
  [ "$(readlink "$PROD_ROOT/current")" = "$new" ]
else
  echo 'SKIP symlink promotion/rollback on Windows; these run in the Linux Jenkins validation stage.'
  sh "$new/deploy/production/backup.sh"
  printf 'build-2-222222222222\n' > "$MOCK_ACTIVE_RELEASE"
  sh "$new/deploy/production/smoke.sh"
fi
backup="$(find "$PROD_ROOT/backups" -name '*.sql.gz' | head -1)"
gzip -t "$backup"
gzip -dc "$backup" | grep -q 'CREATE TABLE backup_test'
sh "$new/deploy/production/restore-check.sh" "$backup"
grep -q 'ecommerce-restore-check-.*down --volumes' "$MOCK_LOG"
! grep -q ' -p ecommerce-prod down' "$MOCK_LOG"
echo 'Production orchestration tests passed: failed backup/up/smoke, permissions/domain guards and isolated restore (promotion/rollback also tested on Linux).'
