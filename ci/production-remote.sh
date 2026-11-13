#!/bin/sh
set -eu
# SSH_KEY and SSH_KNOWN_HOSTS are Jenkins credential file paths, never file contents.
: "${SSH_KEY:?}" "${SSH_KNOWN_HOSTS:?}" "${PROD_SSH_HOST:?}" "${PROD_SSH_USER:?}" "${IMAGE_TAG:?}"
printf '%s\n' "$PROD_SSH_HOST" | grep -Eq '^[a-zA-Z0-9][a-zA-Z0-9.-]*$'
printf '%s\n' "$PROD_SSH_USER" | grep -Eq '^[a-z_][a-z0-9_-]*$'
printf '%s\n' "$IMAGE_TAG" | grep -Eq '^build-[0-9]+-[a-f0-9]{12}$'
remote="$PROD_SSH_USER@$PROD_SSH_HOST"
ssh_vm() {
  ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 \
    -o ServerAliveCountMax=3 -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$SSH_KNOWN_HOSTS" "$remote" "$@"
}
case "${1:?upload|baseline|deploy|rollback|diagnostics}" in
  upload)
    ssh_vm "set -eu; mkdir -p /opt/ecommerce-gpay/releases/$IMAGE_TAG; test ! -f /opt/ecommerce-gpay/releases/$IMAGE_TAG/release.env"
    # Stream only deployment files and non-secret image references, never the workspace or .env.
    ssh_vm "set -eu; tar -xzf - -C /opt/ecommerce-gpay/releases/$IMAGE_TAG; mv /opt/ecommerce-gpay/releases/$IMAGE_TAG/ci/production-release.env /opt/ecommerce-gpay/releases/$IMAGE_TAG/release.env" < "${ARTIFACT_DIR:-artifacts}/production-bundle.tar.gz"
    ;;
  baseline) ssh_vm 'test -L /opt/ecommerce-gpay/current' ;;
  deploy) ssh_vm "sh /opt/ecommerce-gpay/releases/$IMAGE_TAG/deploy/production/deploy.sh" ;;
  rollback) ssh_vm "sh /opt/ecommerce-gpay/releases/$IMAGE_TAG/deploy/production/rollback.sh" ;;
  diagnostics)
    ssh_vm "set -eu; cd /opt/ecommerce-gpay/releases/$IMAGE_TAG; docker compose --env-file /opt/ecommerce-gpay/shared/production.env --env-file release.env -f compose.production.yaml -p ecommerce-prod ps; docker compose --env-file /opt/ecommerce-gpay/shared/production.env --env-file release.env -f compose.production.yaml -p ecommerce-prod logs --no-color --tail 100 backend edge"
    ;;
  *) exit 2 ;;
esac
