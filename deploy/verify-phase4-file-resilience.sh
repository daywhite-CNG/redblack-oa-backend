#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE="docker compose --env-file $SCRIPT_DIR/.env -f $SCRIPT_DIR/docker-compose.yml"
BASE_URL=${BASE_URL:-http://127.0.0.1/api/v1}
TMP_DIR=$(mktemp -d)
PROBE_NAME="redblack-file-cleanup-probe-$$"
FILE_ID=""

cleanup() {
  docker rm -f "$PROBE_NAME" >/dev/null 2>&1 || true
  if [ -n "$FILE_ID" ]; then
    token=$(cat "$TMP_DIR/employee.token" 2>/dev/null || true)
    [ -n "$token" ] && curl -sS -o /dev/null -X DELETE \
      -H "Authorization: Bearer $token" "$BASE_URL/files/$FILE_ID" || true
  fi
  case "$TMP_DIR" in /tmp/*) rm -rf -- "$TMP_DIR" ;; esac
}
trap cleanup EXIT

office_query() {
  printf '%s\n' "$1" | $COMPOSE exec -T mysql sh -c \
    'MYSQL_PWD="$OFFICE_DB_PASSWORD" mysql -N -u"$OFFICE_DB_USERNAME" redblack_office 2>/dev/null'
}

wait_for_query() {
  sql=$1
  expected=$2
  for _ in $(seq 1 40); do
    actual=$(office_query "$sql")
    [ "$actual" = "$expected" ] && return 0
    sleep 1
  done
  echo "Timed out waiting for database state: expected $expected, got $actual" >&2
  return 1
}

status=$(curl -sS -o "$TMP_DIR/login.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"username":"employee","password":"123456","rememberMe":false}' \
  "$BASE_URL/auth/login")
[ "$status" = "200" ] || { echo "Employee login failed: HTTP $status" >&2; exit 1; }
python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8"))["data"]["accessToken"])' \
  "$TMP_DIR/login.json" > "$TMP_DIR/employee.token"
token=$(cat "$TMP_DIR/employee.token")

printf '\211PNG\r\n\032\nphase4-file-resilience' > "$TMP_DIR/probe.png"
status=$(curl -sS -o "$TMP_DIR/upload.json" -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -F "file=@$TMP_DIR/probe.png;type=image/png" "$BASE_URL/files")
[ "$status" = "201" ] || { echo "File upload failed: HTTP $status" >&2; cat "$TMP_DIR/upload.json"; exit 1; }
FILE_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8"))["data"]["id"])' \
  "$TMP_DIR/upload.json")

available=$(office_query "SELECT CONCAT(storage_status,'|',IF(etag IS NULL,0,1)) FROM office_file WHERE id=$FILE_ID;")
[ "$available" = "AVAILABLE|1" ] || { echo "Uploaded file is not AVAILABLE with ETag: $available" >&2; exit 1; }
office_query "UPDATE office_file SET created_at=UTC_TIMESTAMP(3)-INTERVAL 25 HOUR WHERE id=$FILE_ID;" >/dev/null

$COMPOSE run -d --name "$PROBE_NAME" --no-deps \
  -e OSS_ENDPOINT=http://127.0.0.1:9 -e FILE_CLEANUP_DELAY=1000 office-service >/dev/null
wait_for_query "SELECT CONCAT(storage_status,'|',cleanup_attempts) FROM office_file WHERE id=$FILE_ID;" \
  "DELETE_PENDING|1"
docker rm -f "$PROBE_NAME" >/dev/null

office_query "UPDATE office_file SET cleanup_next_attempt_at=UTC_TIMESTAMP(3) WHERE id=$FILE_ID;" >/dev/null
$COMPOSE run -d --name "$PROBE_NAME" --no-deps -e FILE_CLEANUP_DELAY=1000 office-service >/dev/null
wait_for_query "SELECT COUNT(*) FROM office_file WHERE id=$FILE_ID;" "0"
docker rm -f "$PROBE_NAME" >/dev/null
FILE_ID=""

pending=$(office_query "SELECT COUNT(*) FROM office_file WHERE storage_status IN ('PENDING','DELETE_PENDING') AND cleanup_next_attempt_at <= UTC_TIMESTAMP(3);")
[ "$pending" = "0" ] || { echo "Due file cleanup rows remain: $pending" >&2; exit 1; }

echo "PHASE4_FILE_RESILIENCE_OK failure=DELETE_PENDING:attempt1 recovery=deleted oss=real"
