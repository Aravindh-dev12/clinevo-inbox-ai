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

if [[ -z "$chrome" ]]; then
  echo "::error::A Chromium-compatible browser is required to prove the Angular review UI renders"
  exit 1
fi

ui_url="http://127.0.0.1:4200/"
dom_file="$artifact_dir/ui/review-workbench.html"
chrome_log="$artifact_dir/ui/chrome.log"
screenshot="$artifact_dir/ui/review-workbench.png"
chrome_args=(
  --headless
  --no-sandbox
  --disable-gpu
  --disable-dev-shm-usage
  --hide-scrollbars
  --window-size=1920,3000
  --virtual-time-budget=10000
)

if ! timeout 45s "$chrome" "${chrome_args[@]}" --dump-dom "$ui_url" >"$dom_file" 2>"$chrome_log"; then
  cat "$chrome_log" || true
  echo "::error::Headless browser could not render the Angular application"
  exit 1
fi

if ! grep -q "Smart Inbox Review" "$dom_file" || ! grep -q "Incoming queue" "$dom_file"; then
  cat "$chrome_log" || true
  echo "::error::Angular shell did not bootstrap in the browser; refusing to publish blank UI evidence"
  exit 1
fi

if ! timeout 45s "$chrome" "${chrome_args[@]}" --screenshot="$screenshot" "$ui_url" >>"$chrome_log" 2>&1; then
  cat "$chrome_log" || true
  echo "::error::Could not capture the rendered review-workbench screenshot"
  exit 1
fi

if [[ ! -s "$screenshot" ]]; then
  echo "::error::Rendered UI screenshot is missing or empty"
  exit 1
fi

echo "[ok] browser-rendered Angular review UI verified and screenshot captured"
