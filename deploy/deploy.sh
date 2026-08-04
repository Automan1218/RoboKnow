#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$APP_DIR/docker-compose.yaml"
ENV_FILE="$APP_DIR/backend.env"
STATE_FILE="$APP_DIR/.previous_backend_image"

cd "$APP_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy deploy/backend.env.example and configure runtime secrets on EC2." >&2
  exit 1
fi

requested_image="${ROBO_BACKEND_IMAGE:-}"
set -a
source "$ENV_FILE"
set +a
if [[ -n "$requested_image" ]]; then
  ROBO_BACKEND_IMAGE="$requested_image"
fi

if [[ -z "${ROBO_BACKEND_IMAGE:-}" ]]; then
  echo "ROBO_BACKEND_IMAGE is required." >&2
  exit 1
fi

compose() {
  sudo docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

wait_for_container_health() {
  local container="$1"
  local attempts="${2:-60}"
  for _ in $(seq 1 "$attempts"); do
    status="$(sudo docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then
      return 0
    fi
    sleep 5
  done
  echo "Container did not become healthy: $container" >&2
  sudo docker logs --tail 100 "$container" || true
  return 1
}

wait_for_backend_http() {
  for _ in $(seq 1 60); do
    code="$(curl -sS --connect-timeout 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/ || true)"
    if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then
      return 0
    fi
    sleep 5
  done
  echo "Backend did not accept HTTP connections on 127.0.0.1:8081." >&2
  sudo docker logs --tail 100 roboknow-backend || true
  return 1
}

restore_previous_backend() {
  if [[ -n "$previous_image" ]]; then
    echo "==> Restoring previous backend image: $previous_image" >&2
    sudo env ROBO_BACKEND_IMAGE="$previous_image" docker compose \
      --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull backend
    sudo env ROBO_BACKEND_IMAGE="$previous_image" docker compose \
      --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d backend
  fi
}

previous_image="$(sudo docker inspect --format '{{.Config.Image}}' roboknow-backend 2>/dev/null || true)"
if [[ -n "$previous_image" ]]; then
  printf '%s\n' "$previous_image" > "$STATE_FILE"
fi

echo "==> Bootstrap host prerequisites"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
if ! command -v nginx >/dev/null 2>&1 || ! command -v rsync >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y nginx rsync
fi
if ! grep -q 'vm.max_map_count' /etc/sysctl.conf; then
  echo 'vm.max_map_count=262144' | sudo tee -a /etc/sysctl.conf >/dev/null
fi
sudo sysctl -w vm.max_map_count=262144 >/dev/null

echo "==> Start infrastructure"
compose up -d mysql redis kafka es minio ocr-service
wait_for_container_health mysql 60
wait_for_container_health redis 60
wait_for_container_health kafka 60
wait_for_container_health es 60
wait_for_container_health ocr-service 60

echo "==> Pull and start backend image: $ROBO_BACKEND_IMAGE"
compose pull backend
compose up -d backend

if ! wait_for_backend_http; then
  restore_previous_backend
  exit 1
fi

echo "==> Ensure MinIO bucket exists"
sudo docker run --rm --network roboknow_default --entrypoint sh minio/mc -c \
  "mc alias set m http://minio:19000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" && mc mb -p m/uploads" || true

echo "==> Publish frontend via Nginx"
sudo mkdir -p /var/www/roboknow
sudo rsync -a --delete dist/ /var/www/roboknow/
sudo cp nginx.conf /etc/nginx/sites-available/roboknow
sudo ln -sf /etc/nginx/sites-available/roboknow /etc/nginx/sites-enabled/roboknow
sudo rm -f /etc/nginx/sites-enabled/default
if ! sudo nginx -t; then
  restore_previous_backend
  exit 1
fi
sudo systemctl reload nginx

echo "==> Deployment completed"
echo "Backend image: $ROBO_BACKEND_IMAGE"
sudo docker ps --filter name=roboknow-backend --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
