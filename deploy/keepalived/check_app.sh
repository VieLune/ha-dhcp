#!/usr/bin/env bash
set -euo pipefail

APP_HEALTH_URL="${APP_HEALTH_URL:-http://127.0.0.1:TODO_APP_PORT/actuator/health}"
HA_HEALTH_URL="${HA_HEALTH_URL:-http://127.0.0.1:TODO_APP_PORT/actuator/health/ha}"
ALLOW_BASIC_HEALTH_FALLBACK="${ALLOW_BASIC_HEALTH_FALLBACK:-true}"

check_up() {
  local url="$1"
  local body

  if ! body="$(curl -fsS --max-time 1 "$url" 2>/dev/null)"; then
    return 1
  fi

  echo "$body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
}

if check_up "$HA_HEALTH_URL"; then
  exit 0
fi

if [ "$ALLOW_BASIC_HEALTH_FALLBACK" = "true" ] && check_up "$APP_HEALTH_URL"; then
  exit 0
fi

logger -t ac-ha-check "Spring Boot health check failed: HA_HEALTH_URL=$HA_HEALTH_URL APP_HEALTH_URL=$APP_HEALTH_URL"
exit 1
