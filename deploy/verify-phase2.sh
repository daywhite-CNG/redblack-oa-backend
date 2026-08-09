#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE="docker compose --env-file $SCRIPT_DIR/.env -f $SCRIPT_DIR/docker-compose.yml"
BASE_URL=${BASE_URL:-http://127.0.0.1/api/v1}
KAFKA_PROBE_JAR=${KAFKA_PROBE_JAR:-$SCRIPT_DIR/KafkaRoundTripProbe.jar}
KAFKA_TOPIC=${KAFKA_TOPIC:-redblack.identity.integration.v1}
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

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

echo "[1/6] Container health"
$COMPOSE ps
for service in mysql redis kafka identity-service gateway-service nginx; do
  container_id=$($COMPOSE ps -q "$service")
  state=$(docker inspect --format '{{.State.Status}}' "$container_id")
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")
  if [ "$state" != "running" ] || { [ "$health" != "healthy" ] && [ "$health" != "none" ]; }; then
    echo "$service is not ready: state=$state health=$health"
    exit 1
  fi
done

echo "[2/6] MySQL Flyway and baseline data"
migrations=$($COMPOSE exec -T mysql sh -c \
  'mysql -N -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" redblack_identity -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;"')
users=$($COMPOSE exec -T mysql sh -c \
  'mysql -N -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" redblack_identity -e "SELECT COUNT(*) FROM sys_user WHERE username IN ('"'"'admin'"'"','"'"'leader'"'"','"'"'employee'"'"');"')
[ "$migrations" -eq 3 ] && [ "$users" -eq 3 ] || { echo "Unexpected migration/user count: $migrations/$users"; exit 1; }

echo "[3/6] Redis round trip"
$COMPOSE exec -T redis redis-cli SET redblack:identity:integration ready >/dev/null
redis_value=$($COMPOSE exec -T redis redis-cli GET redblack:identity:integration | tr -d '\r')
[ "$redis_value" = "ready" ] || { echo "Redis round trip failed"; exit 1; }

echo "[4/6] Kafka acknowledged produce/consume"
[ -r "$KAFKA_PROBE_JAR" ] || { echo "Kafka probe JAR is missing: $KAFKA_PROBE_JAR"; exit 1; }
identity_image=$($COMPOSE images -q identity-service)
[ -n "$identity_image" ] || { echo "Identity service image was not found"; exit 1; }
kafka_probe_output=$(docker run --rm --network redblack-oa_backend \
  -v "$KAFKA_PROBE_JAR:/probe/KafkaRoundTripProbe.jar:ro" \
  --entrypoint java "$identity_image" \
  -Dloader.path=/probe/KafkaRoundTripProbe.jar \
  -Dloader.main=KafkaRoundTripProbe \
  -cp /app/application.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  kafka:9092 "$KAFKA_TOPIC")
printf '%s\n' "$kafka_probe_output"
grep -q '^KAFKA_ROUND_TRIP_OK ' <<<"$kafka_probe_output" || {
  echo "Kafka round trip did not emit a success record"
  exit 1
}

echo "[5/6] Admin login and authenticated request through Nginx/Gateway"
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456","rememberMe":false}' "$BASE_URL/auth/login")
assert_status 200 "$status" "admin login"
token=$(python3 -c 'import json,sys; data=json.load(open(sys.argv[1], encoding="utf-8")); assert data["data"]["expiresIn"] == 28800; print(data["data"]["accessToken"])' "$TMP_DIR/response.json")
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE_URL/auth/me")
assert_status 200 "$status" "authenticated current user"

echo "[6/6] Employee permission denial and logout deny-list"
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"username":"employee","password":"123456","rememberMe":false}' "$BASE_URL/auth/login")
assert_status 200 "$status" "employee login"
employee_token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["data"]["accessToken"])' "$TMP_DIR/response.json")
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H "Authorization: Bearer $employee_token" "$BASE_URL/users?page=1&pageSize=10")
assert_status 403 "$status" "employee user-management denial"
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -X POST -H "Authorization: Bearer $token" "$BASE_URL/auth/logout")
assert_status 204 "$status" "admin logout"
status=$(curl -sS -o "$TMP_DIR/response.json" -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE_URL/auth/me")
assert_status 401 "$status" "logged-out token rejection"

echo "Phase 2 real-infrastructure smoke test passed."
