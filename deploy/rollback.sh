#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CURRENT_JAR="$APP_DIR/app.jar"
PREVIOUS_JAR="$APP_DIR/.previous_app.jar"

if [[ ! -f "$PREVIOUS_JAR" ]]; then
  echo "No previous JAR found: $PREVIOUS_JAR" >&2
  exit 1
fi

cp "$CURRENT_JAR" "$APP_DIR/.failed_app.jar" 2>/dev/null || true
cp "$PREVIOUS_JAR" "$CURRENT_JAR"
sudo systemctl restart paismart.service

for _ in $(seq 1 60); do
  code="$(curl -sS --connect-timeout 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/ || true)"
  if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then
    echo "Rollback completed."
    exit 0
  fi
  sleep 5
done

echo "Rollback JAR did not accept HTTP connections." >&2
sudo journalctl -u paismart.service -n 100 --no-pager || true
exit 1
