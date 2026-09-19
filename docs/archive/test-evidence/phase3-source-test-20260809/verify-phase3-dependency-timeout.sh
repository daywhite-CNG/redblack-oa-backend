#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL=${1:-http://127.0.0.1/api/v1}
IDENTITY_CONTAINER=redblack-oa-identity-service-1
TIMEOUT_SECONDS=8

cleanup() {
  docker unpause "$IDENTITY_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

token=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username":"employee","password":"123456","rememberMe":false}' \
  "$BASE_URL/auth/login" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])')

docker pause "$IDENTITY_CONTAINER" >/dev/null
started=$(date +%s%3N)
set +e
response=$(curl -sS --max-time "$TIMEOUT_SECONDS" \
  -H 'Accept: application/json' \
  -H 'X-Request-Id: phase3_dependency_timeout' \
  -H "Authorization: Bearer $token" \
  -w $'\n%{http_code}' \
  "$BASE_URL/leave-applications?scope=mine&page=1&pageSize=20")
curl_status=$?
set -e
elapsed=$(( $(date +%s%3N) - started ))
docker unpause "$IDENTITY_CONTAINER" >/dev/null
trap - EXIT

http_status=$(printf '%s\n' "$response" | tail -n 1)
body=$(printf '%s\n' "$response" | sed '$d')

if [ "$curl_status" -eq 0 ] && [ "$http_status" = "503" ]; then
  printf 'PHASE3_DEPENDENCY_TIMEOUT_OK http=%s elapsedMs=%s body=%s\n' "$http_status" "$elapsed" "$body"
  exit 0
fi

printf 'PHASE3_DEPENDENCY_TIMEOUT_FAILED curlStatus=%s http=%s elapsedMs=%s body=%s\n' \
  "$curl_status" "$http_status" "$elapsed" "$body"
exit 1
