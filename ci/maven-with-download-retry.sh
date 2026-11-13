#!/bin/sh
set -eu
# Usage: sh maven-with-download-retry.sh mvn <arguments>
# Only retry the observed Maven artifact body-transfer failure, never a whole Jenkins stage.
maven_command="${1:?Maven executable is required}"
shift
log_file="$(mktemp "${TMPDIR:-/tmp}/maven-ci-log.XXXXXX")"
status_file="$(mktemp "${TMPDIR:-/tmp}/maven-ci-status.XXXXXX")"
cleanup() { rm -f "$log_file" "$status_file"; }
trap cleanup EXIT

run_maven() {
  : > "$status_file"
  # POSIX sh has no pipefail: keep live output AND preserve Maven's exit code across tee.
  (
    set +e
    "$@"
    result=$?
    printf '%s\n' "$result" > "$status_file"
    exit 0
  ) 2>&1 | tee "$log_file"
  maven_status="$(cat "$status_file")"
  case "$maven_status" in
    ''|*[!0-9]*) echo 'Maven did not report an exit status.' >&2; exit 1 ;;
  esac
}

run_maven "$maven_command" "$@"
[ "$maven_status" -ne 0 ] || exit 0
# Interruptions, bad CLI usage and other exit codes must propagate immediately.
[ "$maven_status" -eq 1 ] || exit "$maven_status"

if grep -Eq '^\[ERROR\].*Could not transfer artifact.*Premature end of Content-Length delimited message body' "$log_file" \
  && ! grep -Eq '^\[ERROR\].*(There are test failures|There was an error in the forked process|COMPILATION ERROR|Compilation failure|Tests run:.*(Failures: [1-9]|Errors: [1-9]))' "$log_file"; then
  echo 'Maven artifact download was truncated; retrying once in 5 seconds with -U.' >&2
  sleep 5
  # -U bypasses cached failed resolution; do not delete the dependency cache or change versions.
  run_maven "$maven_command" -U "$@"
fi
exit "$maven_status"
