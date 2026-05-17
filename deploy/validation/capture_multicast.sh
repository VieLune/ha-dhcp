#!/usr/bin/env bash
set -euo pipefail

IFACE="${1:-}"
OUT="${2:-multicast-$(date +%Y%m%d-%H%M%S).pcap}"

if [ -z "$IFACE" ]; then
  echo "Usage: $0 <interface> [output.pcap]" >&2
  exit 1
fi

exec tcpdump -i "$IFACE" -nn -vvv -s 0 -w "$OUT" 'multicast'
