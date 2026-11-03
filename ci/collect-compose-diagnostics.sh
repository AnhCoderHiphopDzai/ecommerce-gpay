#!/bin/sh
set -u

# Giữ tham số compose file để interface của script rõ ràng và tương thích với các lệnh gọi cũ.
# Không gọi `docker compose ... logs` ở đây: file production có biến secret bắt buộc,
# còn hook `post` của Jenkins cố ý không giữ secret sau stage deploy.
compose_file="${1:?compose file is required}"
project_name="${2:?compose project name is required}"
output_dir="${3:?output directory is required}"

if [ ! -f "$compose_file" ]; then
  echo "Compose file not found: $compose_file" >&2
fi

mkdir -p "$output_dir"
label="com.docker.compose.project=${project_name}"

docker ps -a --filter "label=${label}" \
  --format 'table {{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}' >"$output_dir/compose-ps.txt" 2>&1 || true

: >"$output_dir/compose.log"
for container_id in $(docker ps -aq --filter "label=${label}" 2>/dev/null); do
  name="$(docker inspect --format '{{.Name}}' "$container_id" 2>/dev/null || printf '%s' "$container_id")"
  {
    printf '\n===== %s (%s) =====\n' "$name" "$container_id"
    docker logs --timestamps --tail=300 "$container_id"
  } >>"$output_dir/compose.log" 2>&1 || true
done
