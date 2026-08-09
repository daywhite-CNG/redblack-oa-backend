#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE="docker compose --env-file $SCRIPT_DIR/.env -f $SCRIPT_DIR/docker-compose.yml"
BASE_URL=${BASE_URL:-http://127.0.0.1/api/v1}
KCAT_IMAGE=${KCAT_IMAGE:-edenhill/kcat:1.7.1}
TMP_DIR=$(mktemp -d)

cleanup() {
  $COMPOSE start kafka >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
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

mysql_query() {
  printf '%s\n' "$1" | $COMPOSE exec -T mysql sh -c \
    'MYSQL_PWD="$APPROVAL_DB_PASSWORD" mysql -N -u"$APPROVAL_DB_USERNAME" redblack_approval 2>/dev/null'
}

status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"username":"employee","password":"123456","rememberMe":false}' "$BASE_URL/auth/login")
assert_status 200 "$status" "employee login"
token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["accessToken"])' \
  "$TMP_DIR/response.json")

echo "[1/2] Redis authorization snapshot rebuild"
$COMPOSE exec -T redis redis-cli FLUSHDB >/dev/null
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' \
  -H "Authorization: Bearer $token" "$BASE_URL/leave-applications?scope=mine&page=1&pageSize=20")
assert_status 200 "$status" "approval read after Redis flush"
[ "$($COMPOSE exec -T redis redis-cli exists redblack:identity:authorization:10003 | tr -d '\r')" -eq 1 ] || {
  echo "Employee authorization snapshot was not rebuilt"
  exit 1
}

echo "[2/2] Approval Outbox survives Kafka outage and recovers"
docker image inspect "$KCAT_IMAGE" >/dev/null 2>&1 || {
  echo "Kafka verification image is missing: $KCAT_IMAGE"
  exit 1
}
$COMPOSE stop kafka >/dev/null
idempotency_key=$(cat /proc/sys/kernel/random/uuid)
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: $idempotency_key" \
  -d '{"leaveType":"PERSONAL","startTime":"2026-08-20T09:00:00+08:00","endTime":"2026-08-20T18:00:00+08:00","urgency":"NORMAL","reason":"Kafka中断审批联调申请","handoverUserId":"10002","contactPhone":"13800138000","attachmentIds":[]}' \
  "$BASE_URL/leave-applications")
assert_status 201 "$status" "create leave while Kafka is unavailable"
application_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["id"])' \
  "$TMP_DIR/response.json")
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["version"])' \
  "$TMP_DIR/response.json")
idempotency_key=$(cat /proc/sys/kernel/random/uuid)
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: $idempotency_key" -d "{\"version\":$version}" \
  "$BASE_URL/leave-applications/$application_id/submit")
assert_status 200 "$status" "submit leave while Kafka is unavailable"

event_row=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  event_row=$(mysql_query "SELECT CONCAT(event_id, '|', status) FROM outbox_event WHERE aggregate_id='$application_id' AND event_type='LEAVE_SUBMITTED' ORDER BY created_at DESC LIMIT 1;")
  [ "${event_row#*|}" = "PENDING" ] && break
  sleep 2
done
[ -n "$event_row" ] && [ "${event_row#*|}" = "PENDING" ] || {
  echo "Approval Outbox did not remain pending during Kafka outage: $event_row"
  exit 1
}
event_id=${event_row%%|*}

$COMPOSE start kafka >/dev/null
kafka_container=$($COMPOSE ps -q kafka)
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  kafka_health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "$kafka_container")
  [ "$kafka_health" = "healthy" ] && break
  sleep 2
done
[ "$kafka_health" = "healthy" ] || { echo "Kafka did not recover: $kafka_health"; exit 1; }

event_status=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  event_status=$(mysql_query "SELECT status FROM outbox_event WHERE event_id='$event_id';")
  [ "$event_status" = "SENT" ] && break
  sleep 2
done
[ "$event_status" = "SENT" ] || { echo "Approval Outbox did not become SENT"; exit 1; }
published=$(docker run --rm --network redblack-oa_backend "$KCAT_IMAGE" \
  -b kafka:9092 -t redblack.approval.events.v1 -C -o beginning -e -q 2>/dev/null \
  | grep -F "$event_id" | head -1 || true)
[ -n "$published" ] || { echo "Recovered approval event was not found in Kafka"; exit 1; }

version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["version"])' \
  "$TMP_DIR/response.json")
idempotency_key=$(cat /proc/sys/kernel/random/uuid)
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: $idempotency_key" \
  -d "{\"version\":$version,\"reason\":\"韧性验收完成后撤回\"}" \
  "$BASE_URL/leave-applications/$application_id/withdraw")
assert_status 200 "$status" "withdraw resilience leave"
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["version"])' \
  "$TMP_DIR/response.json")
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X DELETE \
  -H "Authorization: Bearer $token" "$BASE_URL/leave-applications/$application_id?version=$version")
assert_status 204 "$status" "delete resilience leave"

echo "PHASE3_RESILIENCE_OK application=$application_id event=$event_id"
