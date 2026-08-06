#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$APP_DIR/docker-compose.yaml"
ENV_FILE="$APP_DIR/backend.env"
CURRENT_JAR="$APP_DIR/app.jar"
NEW_JAR="$APP_DIR/app.jar.new"
PREVIOUS_JAR="$APP_DIR/.previous_app.jar"

cd "$APP_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Configure runtime secrets on EC2." >&2
  exit 1
fi
if [[ ! -f "$NEW_JAR" ]]; then
  echo "Missing $NEW_JAR." >&2
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

compose() {
  sudo docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

remove_legacy_infrastructure_containers() {
  local container
  # These exact names are the pre-Compose deployment containers identified in
  # CD evidence. Removing a container does not remove its named volume; this
  # is a one-time migration so current Compose labels and loopback bindings can
  # take ownership.
  for container in mysql minio redis kafka es; do
    if sudo docker inspect "$container" >/dev/null 2>&1; then
      echo "==> Replacing legacy infrastructure container: $container"
      sudo docker rm -f "$container" >/dev/null
    fi
  done
}

assert_loopback_port_bindings() {
  local container bindings
  for container in mysql redis kafka es minio ocr-service; do
    bindings="$(sudo docker inspect --format '{{range $port, $items := .HostConfig.PortBindings}}{{$port}} {{range $items}}{{.HostIp}}:{{.HostPort}} {{end}}{{end}}' "$container")"
    echo "==> $container published bindings: $bindings"
    if [[ "$bindings" == *'0.0.0.0:'* || "$bindings" == *'[::]:'* || "$bindings" == *':::'* ]]; then
      echo "Container $container exposes an infrastructure port beyond loopback." >&2
      return 1
    fi
  done
}

wait_for_container_health() {
  local container="$1"
  local attempts="${2:-60}"
  for _ in $(seq 1 "$attempts"); do
    status="$(sudo docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then return 0; fi
    sleep 5
  done
  echo "Container did not become healthy: $container" >&2
  sudo docker logs --tail 100 "$container" || true
  return 1
}

wait_for_backend_http() {
  for _ in $(seq 1 60); do
    code="$(curl -sS --connect-timeout 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/ || true)"
    if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then return 0; fi
    sleep 5
  done
  echo "Backend did not accept HTTP connections on 127.0.0.1:8081." >&2
  sudo journalctl -u paismart.service -n 100 --no-pager || true
  return 1
}

restore_previous_jar() {
  if [[ -f "$PREVIOUS_JAR" ]]; then
    echo "==> Restoring previous JAR"
    cp "$PREVIOUS_JAR" "$CURRENT_JAR"
    sudo systemctl restart paismart.service || true
  fi
}

echo "==> Bootstrap host prerequisites"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi
if ! command -v java >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y openjdk-17-jre-headless
fi
if ! command -v nginx >/dev/null 2>&1 || ! command -v rsync >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y nginx rsync
fi
if ! grep -q 'vm.max_map_count' /etc/sysctl.conf; then
  echo 'vm.max_map_count=262144' | sudo tee -a /etc/sysctl.conf >/dev/null
fi
sudo sysctl -w vm.max_map_count=262144 >/dev/null

echo "==> Start infrastructure containers"
# Reconcile every stateful service to the versioned Compose definition. This
# recreates containers but preserves named volumes, preventing old 0.0.0.0
# port bindings from surviving a successful deployment.
remove_legacy_infrastructure_containers
compose up -d --force-recreate mysql redis kafka es minio

# Rebuild OCR on every release so service-code and image changes take effect.
# The named ocr-data volume preserves downloaded PaddleOCR models across recreates.
compose up -d --build --force-recreate ocr-service

wait_for_container_health mysql 60
wait_for_container_health redis 60
wait_for_container_health kafka 60
wait_for_container_health es 60
# A first run may download PaddleOCR models before the HTTP health endpoint is ready.
wait_for_container_health ocr-service 180
assert_loopback_port_bindings

echo "==> Rotate and install backend JAR"
if [[ -f "$CURRENT_JAR" ]]; then cp "$CURRENT_JAR" "$PREVIOUS_JAR"; fi
mv "$NEW_JAR" "$CURRENT_JAR"
sudo cp paismart.service /etc/systemd/system/paismart.service
sudo systemctl daemon-reload
sudo systemctl enable paismart.service >/dev/null
sudo systemctl restart paismart.service

if ! wait_for_backend_http; then
  restore_previous_jar
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
# `paismart` is the previous deployment's enabled virtual-host link. Its
# server_name `_` wins request routing before RoboKnow and would make DAST scan
# the old/default site. Remove only the known enabled links, not the archived
# sites-available configuration.
sudo rm -f /etc/nginx/sites-enabled/default
sudo rm -f /etc/nginx/sites-enabled/paismart
sudo nginx -t
sudo systemctl reload nginx

echo "==> Verify public Nginx security headers on the deployed virtual host"
response_headers="$(curl -fsSI --connect-timeout 5 http://127.0.0.1/)"
printf '%s\n' "$response_headers"
for header in \
  'content-security-policy:' \
  'x-frame-options:' \
  'x-content-type-options:' \
  'permissions-policy:' \
  'cross-origin-opener-policy:' \
  'cross-origin-embedder-policy:' \
  'cross-origin-resource-policy:'; do
  if ! grep -qi "^$header" <<< "$response_headers"; then
    echo "Expected Nginx response header is missing: $header" >&2
    sudo nginx -T >&2 || true
    exit 1
  fi
done
if grep -qi '^server:.*nginx/' <<< "$response_headers"; then
  echo "Nginx version is still exposed in the Server header." >&2
  exit 1
fi

echo "==> Deployment completed"
sudo systemctl --no-pager --full status paismart.service | head -n 12
