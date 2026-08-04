#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$APP_DIR/docker-compose.yaml"
ENV_FILE="$APP_DIR/backend.env"
STATE_FILE="$APP_DIR/.previous_backend_image"

if [[ ! -f "$ENV_FILE" || ! -f "$STATE_FILE" ]]; then
  echo "No deploy environment or previous image state was found." >&2
  exit 1
fi

previous_image="$(tr -d '\r\n' < "$STATE_FILE")"
if [[ -z "$previous_image" ]]; then
  echo "Previous image state is empty." >&2
  exit 1
fi

echo "Rolling back backend to $previous_image"
sudo env ROBO_BACKEND_IMAGE="$previous_image" docker compose \
  --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull backend
sudo env ROBO_BACKEND_IMAGE="$previous_image" docker compose \
  --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d backend

for _ in $(seq 1 60); do
  code="$(curl -sS --connect-timeout 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/ || true)"
  if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then
    echo "Rollback completed."
    exit 0
  fi
  sleep 5
done

echo "Rollback image did not accept HTTP connections." >&2
sudo docker logs --tail 100 roboknow-backend || true
exit 1
