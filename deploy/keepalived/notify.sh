#!/usr/bin/env bash
set -u

ROLE="${1:-UNKNOWN}"
ROLE_FILE="${ROLE_FILE:-/run/ac-ha-role}"
HA_NOTIFY_URL="${HA_NOTIFY_URL:-}"

case "$ROLE" in
  MASTER|BACKUP|FAULT|STOP)
    ;;
  *)
    logger -t ac-ha-notify "invalid role: $ROLE"
    exit 1
    ;;
esac

logger -t ac-ha-notify "keepalived role changed: $ROLE"

if [ -d "$(dirname "$ROLE_FILE")" ]; then
  echo "$ROLE" > "$ROLE_FILE" 2>/dev/null || true
fi

# Current validation stage does not require Spring Boot to expose this API.
# When the application implements a HA role endpoint, set for example:
# HA_NOTIFY_URL=http://127.0.0.1:8080/internal/ha/role
if [ -n "$HA_NOTIFY_URL" ]; then
  curl -fsS --max-time 2 -X POST "${HA_NOTIFY_URL}?role=${ROLE}" \
    || logger -t ac-ha-notify "failed to notify Spring Boot: role=$ROLE url=$HA_NOTIFY_URL"
fi

exit 0
