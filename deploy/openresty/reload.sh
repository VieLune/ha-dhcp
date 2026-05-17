#!/usr/bin/env bash
set -euo pipefail

if command -v openresty >/dev/null 2>&1; then
  openresty -t
  if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files | grep -q '^openresty\.service'; then
    systemctl reload openresty
  else
    openresty -s reload
  fi
elif command -v nginx >/dev/null 2>&1; then
  nginx -t
  if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files | grep -q '^nginx\.service'; then
    systemctl reload nginx
  else
    nginx -s reload
  fi
else
  echo "Neither openresty nor nginx command was found." >&2
  exit 1
fi
