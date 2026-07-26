#!/usr/bin/env bash
# Server-side deploy script. Run on the EC2 host by the CD workflow.
# Idempotent: installs missing prerequisites, then (re)deploys infra,
# backend and frontend. Safe to run repeatedly.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

echo "==> Bootstrap prerequisites (idempotent)"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER" || true
fi
if ! command -v java >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y openjdk-17-jre-headless
fi
if ! command -v nginx >/dev/null 2>&1; then
  sudo apt-get install -y nginx
fi
if ! command -v rsync >/dev/null 2>&1; then
  sudo apt-get install -y rsync
fi
if ! grep -q 'vm.max_map_count' /etc/sysctl.conf; then
  echo 'vm.max_map_count=262144' | sudo tee -a /etc/sysctl.conf >/dev/null
fi
sudo sysctl -w vm.max_map_count=262144 >/dev/null

echo "==> Start infrastructure (mysql, redis, kafka, es, minio)"
sudo docker compose -f docker-compose.yaml up -d

echo "==> Wait for MySQL"
for _ in $(seq 1 60); do
  if sudo docker exec mysql mysqladmin ping -h localhost --silent >/dev/null 2>&1; then break; fi
  sleep 5
done

echo "==> Wait for Elasticsearch"
for _ in $(seq 1 60); do
  if curl -fsS -u elastic:RoboKnow2025 http://localhost:9200/_cluster/health >/dev/null 2>&1; then break; fi
  sleep 5
done

echo "==> Ensure MinIO bucket 'uploads' exists"
sudo docker run --rm --network roboknow_default --entrypoint sh minio/mc -c \
  "mc alias set m http://minio:19000 admin RoboKnow2025 && mc mb -p m/uploads" || true

echo "==> Deploy backend via systemd"
sudo cp roboknow.service /etc/systemd/system/roboknow.service
sudo systemctl daemon-reload
sudo systemctl enable roboknow >/dev/null 2>&1 || true
sudo systemctl restart roboknow

echo "==> Deploy frontend via nginx"
sudo mkdir -p /var/www/roboknow
sudo rsync -a --delete dist/ /var/www/roboknow/
sudo cp nginx.conf /etc/nginx/sites-available/roboknow
sudo ln -sf /etc/nginx/sites-available/roboknow /etc/nginx/sites-enabled/roboknow
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

echo "==> Deploy done. Backend status:"
sudo systemctl --no-pager status roboknow | head -n 5 || true
