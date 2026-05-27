#!/usr/bin/env bash
set -u

ROLE="${1:-UNKNOWN}"
ROLE_FILE="${ROLE_FILE:-/run/ac-ha-role}"

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

# Historical Keepalived validation only writes the observed VRRP state locally.
# The current application does not accept external role updates.

exit 0
