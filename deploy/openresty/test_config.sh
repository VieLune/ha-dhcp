#!/usr/bin/env bash
set -euo pipefail

if command -v openresty >/dev/null 2>&1; then
  openresty -t
elif command -v nginx >/dev/null 2>&1; then
  nginx -t
else
  echo "Neither openresty nor nginx command was found." >&2
  exit 1
fi
