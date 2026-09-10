#!/usr/bin/env bash
set -Eeuo pipefail

compose=(docker compose -f docker-compose.yml -f docker-compose.smoke.yml)
artifact_dir="${ARTIFACT_DIR:-artifacts}"

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

mkdir -p "$artifact_dir/ui"
"${compose[@]}" ps --format json > "$artifact_dir/compose-status.json" || "${compose[@]}" ps > "$artifact_dir/compose-status.txt"

chrome=""
for candidate in google-chrome-stable google-chrome chromium chromium-browser; do
  if command -v "$candidate" >/dev/null 2>&1; then
    chrome="$candidate"
    break
  fi
done

if [[ -n "$chrome" ]]; then
  "$chrome" \
    --headless=new \
    --no-sandbox \
    --disable-gpu \
    --hide-scrollbars \
    --window-size=1920,4000 \
    --virtual-time-budget=6000 \
    --screenshot="$artifact_dir/ui/review-workbench.png" \
    http://127.0.0.1:4200/ >/dev/null 2>&1 || echo "::warning::Could not capture review-workbench screenshot"
else
  echo "::warning::No Chromium-compatible browser found; UI screenshot was not captured"
fi
