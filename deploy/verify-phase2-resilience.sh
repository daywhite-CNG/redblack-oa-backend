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
    'mysql -N -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" redblack_identity 2>/dev/null'
}

echo "[1/2] Redis flush and authorization-cache rebuild"
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456","rememberMe":false}' "$BASE_URL/auth/login")
assert_status 200 "$status" "admin login before Redis flush"
token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["accessToken"])' \
  "$TMP_DIR/response.json")
$COMPOSE exec -T redis redis-cli FLUSHDB >/dev/null
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' \
  -H "Authorization: Bearer $token" "$BASE_URL/users?page=1&pageSize=10")
assert_status 200 "$status" "authorization fallback after Redis flush"
authorization_keys=$($COMPOSE exec -T redis sh -c \
  'redis-cli --scan --pattern "redblack:identity:authorization:*" | wc -l' | tr -d ' \r\n')
[ "$authorization_keys" -ge 1 ] || { echo "Authorization cache was not rebuilt"; exit 1; }
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $token" "$BASE_URL/auth/logout")
assert_status 204 "$status" "admin logout after Redis rebuild"

echo "[2/2] Kafka outage, pending Outbox, recovery and confirmed event"
docker image inspect "$KCAT_IMAGE" >/dev/null 2>&1 || {
  echo "Kafka verification image is missing: $KCAT_IMAGE"
  exit 1
}
$COMPOSE stop kafka >/dev/null
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' \
  -D "$TMP_DIR/response.headers" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456","rememberMe":false}' "$BASE_URL/auth/login")
assert_status 200 "$status" "admin login while Kafka is unavailable"
request_id=$(awk 'tolower($1) == "x-request-id:" {gsub("\\r", "", $2); value=$2} END {print value}' \
  "$TMP_DIR/response.headers")
[ -n "$request_id" ] || { echo "Login response did not include X-Request-Id"; exit 1; }
token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["accessToken"])' \
  "$TMP_DIR/response.json")

outbox_row=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  outbox_row=$(mysql_query \
    "SELECT CONCAT(event_id, '|', status, '|', attempts) FROM outbox_event WHERE payload LIKE '%$request_id%' ORDER BY created_at DESC LIMIT 1;")
  if [ -n "$outbox_row" ]; then
    outbox_status=${outbox_row#*|}
    outbox_status=${outbox_status%%|*}
    if [ "$outbox_status" = "PENDING" ]; then
      break
    fi
  fi
  sleep 2
done
[ -n "$outbox_row" ] || { echo "Kafka outage Outbox event was not persisted"; exit 1; }
[ "$outbox_status" = "PENDING" ] || {
  echo "Unexpected outage Outbox state: $outbox_row"
  exit 1
}
event_id=${outbox_row%%|*}

$COMPOSE start kafka >/dev/null
kafka_container=$($COMPOSE ps -q kafka)
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  kafka_health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "$kafka_container")
  [ "$kafka_health" = "healthy" ] && break
  sleep 2
done
[ "$kafka_health" = "healthy" ] || { echo "Kafka did not recover: $kafka_health"; exit 1; }

outbox_status=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  outbox_status=$(mysql_query "SELECT status FROM outbox_event WHERE event_id = '$event_id';")
  [ "$outbox_status" = "SENT" ] && break
  sleep 2
done
[ "$outbox_status" = "SENT" ] || { echo "Outbox event was not sent after Kafka recovery"; exit 1; }

published=$(docker run --rm --network redblack-oa_backend \
  "$KCAT_IMAGE" -b kafka:9092 -t redblack.audit.events.v1 -C -o beginning -e -q 2>/dev/null \
  | grep -F "$event_id" | head -1 || true)
[ -n "$published" ] || { echo "Recovered Outbox event was not found in Kafka"; exit 1; }
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $token" "$BASE_URL/auth/logout")
assert_status 204 "$status" "admin logout after Kafka recovery"

echo "Phase 2 resilience verification passed: Redis rebuilt and Outbox event $event_id recovered."
