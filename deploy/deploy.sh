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
  if curl -fsS -u elastic:PaiSmart2025 http://localhost:9200/_cluster/health >/dev/null 2>&1; then break; fi
  sleep 5
done

echo "==> Ensure MinIO bucket 'uploads' exists"
sudo docker run --rm --network pai_smart_default --entrypoint sh minio/mc -c \
  "mc alias set m http://minio:19000 admin PaiSmart2025 && mc mb -p m/uploads" || true

echo "==> Deploy backend via systemd"
sudo cp paismart.service /etc/systemd/system/paismart.service
sudo systemctl daemon-reload
sudo systemctl enable paismart >/dev/null 2>&1 || true
sudo systemctl restart paismart

echo "==> Deploy frontend via nginx"
sudo mkdir -p /var/www/paismart
sudo rsync -a --delete dist/ /var/www/paismart/
sudo cp nginx.conf /etc/nginx/sites-available/paismart
sudo ln -sf /etc/nginx/sites-available/paismart /etc/nginx/sites-enabled/paismart
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

echo "==> Deploy done. Backend status:"
sudo systemctl --no-pager status paismart | head -n 5 || true
