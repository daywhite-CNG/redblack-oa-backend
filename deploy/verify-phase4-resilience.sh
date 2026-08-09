#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE="docker compose --env-file $SCRIPT_DIR/.env -f $SCRIPT_DIR/docker-compose.yml"
BASE_URL=${BASE_URL:-http://127.0.0.1/api/v1}
KCAT_IMAGE=${KCAT_IMAGE:-edenhill/kcat:1.7.1}
TMP_DIR=$(mktemp -d)

cleanup() {
  $COMPOSE start kafka >/dev/null 2>&1 || true
  case "$TMP_DIR" in /tmp/*) rm -rf -- "$TMP_DIR" ;; esac
}
trap cleanup EXIT

assert_status() {
  expected=$1
  actual=$2
  label=$3
  if [ "$actual" != "$expected" ]; then
    echo "$label failed: expected HTTP $expected, got $actual"
    cat "$TMP_DIR/response.json" 2>/dev/null || true
    exit 1
  fi
}

office_query() {
  printf '%s\n' "$1" | $COMPOSE exec -T mysql sh -c \
    'MYSQL_PWD="$OFFICE_DB_PASSWORD" mysql -N -u"$OFFICE_DB_USERNAME" redblack_office 2>/dev/null'
}

login() {
  username=$1
  target=$2
  status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"username\":\"$username\",\"password\":\"123456\",\"rememberMe\":false}" "$BASE_URL/auth/login")
  assert_status 200 "$status" "$username login"
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["accessToken"])' \
    "$TMP_DIR/response.json" >"$target"
  chmod 600 "$target"
}

login employee "$TMP_DIR/employee.token"
login admin "$TMP_DIR/admin.token"
employee_token=$(cat "$TMP_DIR/employee.token")
admin_token=$(cat "$TMP_DIR/admin.token")

echo "[1/2] Redis authorization and workbench caches rebuild"
$COMPOSE exec -T redis redis-cli DEL redblack:identity:authorization:10003 redblack:office:workbench:10003 >/dev/null
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' \
  -H "Authorization: Bearer $employee_token" "$BASE_URL/workbench")
assert_status 200 "$status" "workbench after Redis cache deletion"
identity_cache=$($COMPOSE exec -T redis redis-cli EXISTS redblack:identity:authorization:10003 | tr -d '\r')
office_cache=$($COMPOSE exec -T redis redis-cli EXISTS redblack:office:workbench:10003 | tr -d '\r')
[ "$identity_cache" = "1" ] || { echo "Identity authorization cache was not rebuilt"; exit 1; }
[ "$office_cache" = "1" ] || { echo "Office workbench cache was not rebuilt"; exit 1; }

echo "[2/2] Office Outbox survives Kafka outage and recovers"
docker image inspect "$KCAT_IMAGE" >/dev/null 2>&1 || { echo "Kafka verification image is missing: $KCAT_IMAGE"; exit 1; }
$COMPOSE stop kafka >/dev/null
suffix=$(cat /proc/sys/kernel/random/uuid | cut -c1-8)
idempotency_key=$(cat /proc/sys/kernel/random/uuid)
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $admin_token" \
  -H "Idempotency-Key: $idempotency_key" \
  -d "{\"title\":\"Kafka中断公告-$suffix\",\"summary\":\"第四阶段Outbox恢复验收\",\"content\":\"<p>Kafka中断期间提交</p>\",\"type\":\"URGENT\",\"scopeType\":\"ALL\",\"targetDepartmentIds\":[],\"isPinned\":false,\"scheduledPublishAt\":null,\"attachmentIds\":[]}" \
  "$BASE_URL/notices")
assert_status 201 "$status" "create notice while Kafka is unavailable"
notice_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["id"])' "$TMP_DIR/response.json")
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["version"])' "$TMP_DIR/response.json")
idempotency_key=$(cat /proc/sys/kernel/random/uuid)
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $admin_token" \
  -H "Idempotency-Key: $idempotency_key" -d "{\"version\":$version}" \
  "$BASE_URL/notices/$notice_id/publish")
assert_status 200 "$status" "publish notice while Kafka is unavailable"
published_version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["version"])' "$TMP_DIR/response.json")

event_row=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  event_row=$(office_query "SELECT CONCAT(event_id, '|', status) FROM office_outbox_event WHERE aggregate_id='$notice_id' AND event_type='NOTICE_PUBLISHED' ORDER BY created_at DESC LIMIT 1;")
  [ "${event_row#*|}" = "PENDING" ] && break
  sleep 2
done
[ -n "$event_row" ] && [ "${event_row#*|}" = "PENDING" ] || {
  echo "Office Outbox did not remain pending during Kafka outage: $event_row"
  exit 1
}
event_id=${event_row%%|*}

$COMPOSE start kafka >/dev/null
kafka_container=$($COMPOSE ps -q kafka)
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  kafka_health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$kafka_container")
  [ "$kafka_health" = "healthy" ] && break
  sleep 2
done
[ "$kafka_health" = "healthy" ] || { echo "Kafka did not recover: $kafka_health"; exit 1; }

event_status=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  event_status=$(office_query "SELECT status FROM office_outbox_event WHERE event_id='$event_id';")
  [ "$event_status" = "SENT" ] && break
  sleep 2
done
[ "$event_status" = "SENT" ] || { echo "Office Outbox did not become SENT"; exit 1; }

published=$(docker run --rm --network redblack-oa_backend "$KCAT_IMAGE" \
  -b kafka:9092 -t redblack.office.events.v1 -C -o beginning -e -q 2>/dev/null \
  | grep -F "$event_id" | head -1 || true)
[ -n "$published" ] || { echo "Recovered notice event was not found in Kafka"; exit 1; }

notification_found="0"
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' \
    -H "Authorization: Bearer $employee_token" "$BASE_URL/notifications?type=NOTICE_PUBLISHED&page=1&pageSize=100")
  assert_status 200 "$status" "notification after Kafka recovery"
  notification_found=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1],encoding="utf-8")); target=sys.argv[2]; print(int(any(x.get("businessId")==target for x in d["data"]["items"])))' "$TMP_DIR/response.json" "$notice_id")
  [ "$notification_found" = "1" ] && break
  sleep 2
done
[ "$notification_found" = "1" ] || { echo "Notice notification was not projected after Kafka recovery"; exit 1; }

idempotency_key=$(cat /proc/sys/kernel/random/uuid)
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $admin_token" \
  -H "Idempotency-Key: $idempotency_key" \
  -d "{\"version\":$published_version,\"reason\":\"韧性验收完成后撤回\"}" \
  "$BASE_URL/notices/$notice_id/withdraw")
assert_status 200 "$status" "withdraw resilience notice"

pending=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  pending=$(office_query "SELECT COUNT(*) FROM office_outbox_event WHERE status='PENDING';")
  [ "$pending" = "0" ] && break
  sleep 2
done
[ "$pending" = "0" ] || { echo "Office Outbox still has $pending pending events"; exit 1; }

echo "PHASE4_RESILIENCE_OK notice=$notice_id event=$event_id redis=rebuilt outbox=SENT kafka=roundtrip"
