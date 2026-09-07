#!/usr/bin/env bash
set -Eeuo pipefail

compose=(docker compose -f docker-compose.yml -f docker-compose.smoke.yml)

cleanup() {
  local rc=$?
  trap - EXIT
  if (( rc != 0 )); then
    echo "::group::Docker Compose status"
    "${compose[@]}" ps || true
    echo "::endgroup::"
    echo "::group::Docker Compose logs"
    "${compose[@]}" logs --no-color --tail=300 || true
    echo "::endgroup::"
  fi
  "${compose[@]}" down -v --remove-orphans || true
  exit "$rc"
}
trap cleanup EXIT

"${compose[@]}" up -d --build --remove-orphans
python3 scripts/e2e_smoke.py
