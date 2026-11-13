#!/bin/sh
set -eu
root="$(pwd)"
mkdir -p "$root/artifacts"
fixture="$(mktemp -d "$root/artifacts/maven-retry-test.XXXXXX")"
export RETRY_FIXTURE="$fixture"
mkdir -p "$fixture/bin" "$fixture/tmp"
export TMPDIR="$fixture/tmp"

cat > "$fixture/bin/fake-maven" <<'MOCK'
#!/bin/sh
echo run >> "$RETRY_FIXTURE/attempts"
printf '%s\n' "$*" >> "$RETRY_FIXTURE/arguments"
attempt="$(wc -l < "$RETRY_FIXTURE/attempts" | tr -d ' ')"
[ "${1:-}" != -U ] || shift
[ "$#" -eq 3 ] && [ "$1" = -B ] && [ "$2" = '-Dtest=value with spaces' ] && [ "$3" = test ] || exit 91
case "$RETRY_SCENARIO" in
  success) echo '[INFO] BUILD SUCCESS'; exit 0 ;;
  truncated-then-success) [ "$attempt" -eq 1 ] || exit 0 ;;
  truncated-always) ;;
  assertion)
    echo '[ERROR] There are test failures.'
    exit 1 ;;
  compile)
    echo '[ERROR] COMPILATION ERROR :'
    exit 1 ;;
  missing)
    echo '[ERROR] Could not find artifact example:missing:jar:1.0 in central'
    exit 1 ;;
  checksum)
    echo '[ERROR] Could not transfer artifact example:bad:jar:1.0: Checksum validation failed'
    exit 1 ;;
  mixed)
    echo '[ERROR] There are test failures.' ;;
  interrupted) exit 130 ;;
  *) exit 17 ;;
esac
echo '[ERROR] Could not transfer artifact org.hibernate.orm:hibernate-core:jar:7.4.13.Final from/to central (https://repo.maven.apache.org/maven2): Premature end of Content-Length delimited message body (expected: 15334914; received: 4358099)'
exit 1
MOCK
cat > "$fixture/bin/sleep" <<'MOCK'
#!/bin/sh
echo "$*" >> "$RETRY_FIXTURE/sleeps"
MOCK
chmod +x "$fixture/bin/"*
export PATH="$fixture/bin:$PATH"

check() {
  export RETRY_SCENARIO="$1"
  expected_status="$2"
  expected_attempts="$3"
  : > "$fixture/attempts"
  : > "$fixture/arguments"
  : > "$fixture/sleeps"
  actual_status=0
  sh ci/maven-with-download-retry.sh "$fixture/bin/fake-maven" -B '-Dtest=value with spaces' test \
    > "$fixture/$1.log" 2>&1 || actual_status=$?
  [ "$actual_status" -eq "$expected_status" ] || { echo "$1: wrong exit status $actual_status" >&2; exit 1; }
  [ "$(wc -l < "$fixture/attempts" | tr -d ' ')" -eq "$expected_attempts" ] || { echo "$1: wrong number of attempts" >&2; exit 1; }
  # A retry must preserve the command arguments and force a dependency-resolution recheck.
  if [ "$expected_attempts" -eq 2 ]; then
    sed -n '2p' "$fixture/arguments" | grep -q -- '^-U -B -Dtest=value with spaces test$'
    [ "$(cat "$fixture/sleeps")" = 5 ]
  else
    [ ! -s "$fixture/sleeps" ]
  fi
  [ -z "$(ls -A "$fixture/tmp")" ] || { echo "$1: temporary logs were not cleaned" >&2; exit 1; }
}
check success 0 1
check truncated-then-success 0 2
check truncated-always 1 2
check assertion 1 1
check compile 1 1
check missing 1 1
check checksum 1 1
check mixed 1 1
check interrupted 130 1
echo 'Maven wrapper tests passed: bounded transfer retry, permanent failures, interrupts and exit-code propagation.'
