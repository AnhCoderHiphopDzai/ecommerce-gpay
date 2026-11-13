#!/bin/sh
set -eu

for tool in docker curl git; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required command not found on Jenkins agent: $tool" >&2
    exit 1
  }
done

case "${DEPLOY_HOST:-}" in
  ''|*[!a-zA-Z0-9.-]*)
    echo 'DEPLOY_HOST must be an IPv4 address or DNS hostname without a scheme, port or path.' >&2
    exit 1
    ;;
esac

docker compose version
docker version --format 'Docker server: {{.Server.Version}}'
[ "$(docker info --format '{{.OSType}}')" = linux ] || {
  echo 'The pipeline requires a Linux Docker daemon.' >&2
  exit 1
}

for script in ci/*.sh deploy/production/*.sh; do
  sh -n "$script"
done

# Docker resolves bind paths on the daemon host, which may differ from a containerized agent.
docker run --rm --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$(pwd),dst=/workspace,readonly" \
  node:22-alpine test -f /workspace/Jenkinsfile

echo 'Runner preflight passed.'
